//! Embedded SSH server. Serves connections the tunnel hands it; never
//! listens.
//!
//! Replies to channel requests are decided in the [`Handler`] callbacks
//! (russh tracks `want_reply` per channel, so a reply sent later from
//! another task could answer the wrong request); the work happens in one
//! task per channel, fed by the handler over [`Ctl`].

pub mod auth;

use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, RwLock};
use std::time::Duration;

use russh::keys::PublicKey;
use russh::server::{Auth, ChannelOpenHandle, Msg, Session};
use russh::{Channel, ChannelId, ChannelMsg, ChannelOpenFailure, MethodKind, MethodSet, Pty, Sig};
use tokio::io::{AsyncRead, AsyncWrite, AsyncWriteExt};
use tokio::sync::mpsc;
use tokio_util::sync::CancellationToken;

use crate::event::Events;
use crate::hostkey::HostKey;
use crate::spawn::{self, Launcher, PtySize, What};
use auth::{Policy, Verdict};

pub const MAX_CONNECTIONS: usize = 64;
const HANDSHAKE_DEADLINE: Duration = Duration::from_secs(120);
const WRONG_GUESS_STALL: Duration = Duration::from_secs(1);

pub struct Server {
    version: String,
    config: RwLock<Arc<russh::server::Config>>,
    pub policy: Arc<Policy>,
    launcher: Arc<dyn Launcher>,
    events: Events,
    active: AtomicUsize,
    /// Authenticated connections; watched for the idle close.
    clients: tokio::sync::watch::Sender<usize>,
    throttle_note: std::sync::Mutex<Option<std::time::Instant>>,
}

fn ssh_config(key: &HostKey, version: &str) -> russh::server::Config {
    let v: String = version
        .chars()
        .map(|c| if c.is_ascii_graphic() && c != '-' { c } else { '_' })
        .collect();
    russh::server::Config {
        server_id: russh::SshId::Standard(format!("SSH-2.0-tawc_{v}").into()),
        methods: MethodSet::from(&[MethodKind::PublicKey][..]),
        // Stalls are ours: only a counted wrong secret waits.
        auth_rejection_time: Duration::ZERO,
        auth_rejection_time_initial: Some(Duration::ZERO),
        max_auth_attempts: 6,
        keys: vec![key.private.clone()],
        inactivity_timeout: None,
        keepalive_interval: Some(Duration::from_secs(60)),
        keepalive_max: 3,
        nodelay: true,
        ..Default::default()
    }
}

impl Server {
    pub fn new(
        key: &HostKey,
        version: &str,
        policy: Arc<Policy>,
        launcher: Arc<dyn Launcher>,
        events: Events,
    ) -> Self {
        Server {
            version: version.to_string(),
            config: RwLock::new(Arc::new(ssh_config(key, version))),
            policy,
            launcher,
            events,
            active: AtomicUsize::new(0),
            clients: tokio::sync::watch::Sender::new(0),
            throttle_note: Default::default(),
        }
    }

    /// New identity. Connections already up keep the old key.
    pub fn set_host_key(&self, key: &HostKey) {
        *self.config.write().unwrap() = Arc::new(ssh_config(key, &self.version));
    }

    /// Authenticated connections right now.
    pub fn clients(&self) -> usize {
        *self.clients.borrow()
    }

    pub fn watch_clients(&self) -> tokio::sync::watch::Receiver<usize> {
        self.clients.subscribe()
    }

    /// Serve one SSH connection to completion. `cancel` hangs up
    /// everything it started.
    pub async fn serve<S>(self: &Arc<Self>, io: S, from: String, cancel: CancellationToken)
    where
        S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
    {
        if self.active.fetch_add(1, Ordering::SeqCst) >= MAX_CONNECTIONS {
            self.active.fetch_sub(1, Ordering::SeqCst);
            self.events.emit("denied", &from, "too many connections");
            return;
        }
        let authed = Arc::new(AtomicBool::new(false));
        let attempts = Arc::new(AtomicUsize::new(0));
        let conn_cancel = cancel.child_token();
        let handler = Conn {
            srv: self.clone(),
            from: from.clone(),
            cancel: conn_cancel.clone(),
            authed: authed.clone(),
            attempts: attempts.clone(),
            chans: HashMap::new(),
            method: "",
            listeners: HashMap::new(),
        };
        let config = self.config.read().unwrap().clone();
        let deadline = tokio::time::sleep(HANDSHAKE_DEADLINE);
        tokio::pin!(deadline);
        let started = tokio::select! {
            r = russh::server::run_stream(config, io, handler) => r.ok(),
            _ = &mut deadline => None,
            _ = conn_cancel.cancelled() => None,
        };
        if let Some(mut running) = started {
            let handle = running.handle();
            let mut armed = true;
            loop {
                tokio::select! {
                    _ = &mut running => break,
                    _ = &mut deadline, if armed => {
                        armed = false;
                        if !authed.load(Ordering::SeqCst) {
                            let _ = handle.disconnect(russh::Disconnect::ByApplication, "login timeout".into(), String::new()).await;
                            break;
                        }
                    }
                    _ = conn_cancel.cancelled() => {
                        let _ = handle.disconnect(russh::Disconnect::ByApplication, "remote access stopped".into(), String::new()).await;
                        let _ = tokio::time::timeout(Duration::from_secs(2), &mut running).await;
                        break;
                    }
                }
            }
        }
        conn_cancel.cancel();
        if authed.load(Ordering::SeqCst) {
            self.clients.send_modify(|c| *c -= 1);
            self.events.emit("logout", &from, "disconnected");
            self.events.emit("status", "", "");
        } else if attempts.load(Ordering::SeqCst) > 0 {
            self.events.emit("denied", &from, "authentication failed");
        }
        self.active.fetch_sub(1, Ordering::SeqCst);
    }

    fn note_throttled(&self, from: &str) {
        let mut last = self.throttle_note.lock().unwrap();
        let now = std::time::Instant::now();
        if last.is_none_or(|t| now.duration_since(t) > Duration::from_secs(60)) {
            *last = Some(now);
            self.events.emit("warn", from, "too many wrong secrets; refusing secret logins for a bit");
        }
    }
}

enum Ctl {
    Start(What, Option<PtySize>, Vec<(String, String)>),
    Resize(PtySize),
    Signal(rustix::process::Signal),
}

struct ChanCtl {
    tx: mpsc::UnboundedSender<Ctl>,
    pty: Option<(String, PtySize)>,
    env: Vec<(String, String)>,
    started: bool,
}

struct Conn {
    srv: Arc<Server>,
    from: String,
    cancel: CancellationToken,
    authed: Arc<AtomicBool>,
    attempts: Arc<AtomicUsize>,
    chans: HashMap<ChannelId, ChanCtl>,
    /// How the login succeeded, for the event.
    method: &'static str,
    /// `-R` listeners by (requested address, bound port).
    listeners: HashMap<(String, u32), CancellationToken>,
}

fn pty_size(cols: u32, rows: u32, px_w: u32, px_h: u32) -> PtySize {
    let c = |v: u32| v.min(u16::MAX as u32) as u16;
    PtySize { cols: c(cols), rows: c(rows), px_w: c(px_w), px_h: c(px_h) }
}

fn signal(sig: &Sig) -> Option<rustix::process::Signal> {
    use rustix::process::Signal as S;
    Some(match sig {
        Sig::ABRT => S::ABORT,
        Sig::ALRM => S::ALARM,
        Sig::FPE => S::FPE,
        Sig::HUP => S::HUP,
        Sig::ILL => S::ILL,
        Sig::INT => S::INT,
        Sig::KILL => S::KILL,
        Sig::PIPE => S::PIPE,
        Sig::QUIT => S::QUIT,
        Sig::SEGV => S::SEGV,
        Sig::TERM => S::TERM,
        Sig::USR1 => S::USR1,
        Sig::Custom(s) if s == "USR2" => S::USR2,
        _ => return None,
    })
}

impl Conn {
    /// Start `what` on `channel` if nothing runs there yet.
    fn start(&mut self, channel: ChannelId, what: What, session: &mut Session) -> Result<(), russh::Error> {
        let Some(c) = self.chans.get_mut(&channel) else {
            return session.channel_failure(channel);
        };
        if c.started {
            return session.channel_failure(channel);
        }
        c.started = true;
        let mut env = std::mem::take(&mut c.env);
        let size = c.pty.as_ref().map(|(term, size)| {
            if !term.is_empty() {
                env.push(("TERM".into(), term.clone()));
            }
            *size
        });
        let (host, port) = split_addr(&self.from);
        env.push(("SSH_CLIENT".into(), format!("{host} {port} 22")));
        env.push(("SSH_CONNECTION".into(), format!("{host} {port} 127.0.0.1 22")));
        let _ = c.tx.send(Ctl::Start(what, size, env));
        session.channel_success(channel)
    }
}

/// Where a `-R` may listen: loopback only.
fn loopback_bind(address: &str) -> Option<std::net::IpAddr> {
    use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};
    match address {
        // OpenSSH sends "localhost" when no bind address is given; ""
        // (from `*` or an empty one) means every interface.
        "localhost" | "127.0.0.1" => Some(IpAddr::V4(Ipv4Addr::LOCALHOST)),
        "::1" | "[::1]" => Some(IpAddr::V6(Ipv6Addr::LOCALHOST)),
        _ => None,
    }
}

fn split_addr(from: &str) -> (&str, &str) {
    match from.rsplit_once(':') {
        Some((h, p)) if !h.is_empty() && p.bytes().all(|b| b.is_ascii_digit()) => {
            (h.trim_start_matches('[').trim_end_matches(']'), p)
        }
        _ => ("0.0.0.0", "0"),
    }
}

impl russh::server::Handler for Conn {
    type Error = russh::Error;

    async fn auth_none(&mut self, user: &str) -> Result<Auth, Self::Error> {
        self.attempts.fetch_add(1, Ordering::SeqCst);
        match self.srv.policy.try_secret(user) {
            Verdict::Ok => {
                self.method = "secret";
                return Ok(Auth::Accept);
            }
            Verdict::Wrong => tokio::time::sleep(WRONG_GUESS_STALL).await,
            Verdict::Disabled => {
                self.srv.events.emit(
                    "warn",
                    &self.from,
                    format!("{} wrong secrets: secret logins are off until a new secret", auth::LIFETIME_GUESSES),
                );
                self.srv.events.emit("status", "", "");
                tokio::time::sleep(WRONG_GUESS_STALL).await;
            }
            Verdict::Throttled => self.srv.note_throttled(&self.from),
            Verdict::Refused => {}
        }
        Ok(Auth::reject())
    }

    // Key mode: the authorized set, any username. Secret mode: every key
    // is refused without asking for a signature, so a wrong secret ends in
    // the familiar "Permission denied (publickey)".
    async fn auth_publickey_offered(&mut self, _user: &str, key: &PublicKey) -> Result<Auth, Self::Error> {
        self.attempts.fetch_add(1, Ordering::SeqCst);
        Ok(if self.srv.policy.key_allowed(key) { Auth::Accept } else { Auth::reject() })
    }

    async fn auth_publickey(&mut self, _user: &str, key: &PublicKey) -> Result<Auth, Self::Error> {
        if self.srv.policy.key_allowed(key) {
            self.method = "publickey";
            return Ok(Auth::Accept);
        }
        Ok(Auth::reject())
    }

    async fn auth_succeeded(&mut self, _session: &mut Session) -> Result<(), Self::Error> {
        self.authed.store(true, Ordering::SeqCst);
        self.srv.clients.send_modify(|c| *c += 1);
        self.srv.events.emit("login", &self.from, format!("authenticated ({})", self.method));
        self.srv.events.emit("status", "", "");
        Ok(())
    }

    async fn channel_open_session(
        &mut self,
        channel: Channel<Msg>,
        reply: ChannelOpenHandle,
        _session: &mut Session,
    ) -> Result<(), Self::Error> {
        let (tx, rx) = mpsc::unbounded_channel();
        self.chans.insert(channel.id(), ChanCtl { tx, pty: None, env: Vec::new(), started: false });
        reply.accept().await;
        tokio::spawn(run_channel(self.srv.clone(), channel, rx, self.from.clone(), self.cancel.clone()));
        Ok(())
    }

    #[allow(clippy::too_many_arguments)]
    async fn channel_open_direct_tcpip(
        &mut self,
        channel: Channel<Msg>,
        host: &str,
        port: u32,
        _orig_host: &str,
        _orig_port: u32,
        reply: ChannelOpenHandle,
        _session: &mut Session,
    ) -> Result<(), Self::Error> {
        if port == 0 || port > 65535 || host.is_empty() {
            reply.reject(ChannelOpenFailure::ConnectFailed).await;
            return Ok(());
        }
        let addr = if host.contains(':') { format!("[{host}]:{port}") } else { format!("{host}:{port}") };
        let (srv, from, cancel) = (self.srv.clone(), self.from.clone(), self.cancel.clone());
        tokio::spawn(async move {
            let tcp = match tokio::time::timeout(Duration::from_secs(10), tokio::net::TcpStream::connect(&addr)).await {
                Ok(Ok(t)) => t,
                _ => {
                    reply.reject(ChannelOpenFailure::ConnectFailed).await;
                    return;
                }
            };
            reply.accept().await;
            srv.events.emit("forward", &from, format!("forward {addr}"));
            let mut ch = channel.into_stream();
            let mut tcp = tcp;
            tokio::select! {
                _ = tokio::io::copy_bidirectional(&mut ch, &mut tcp) => {}
                _ = cancel.cancelled() => {}
            }
        });
        Ok(())
    }

    #[allow(clippy::too_many_arguments)]
    async fn pty_request(
        &mut self,
        channel: ChannelId,
        term: &str,
        cols: u32,
        rows: u32,
        px_w: u32,
        px_h: u32,
        _modes: &[(Pty, u32)],
        session: &mut Session,
    ) -> Result<(), Self::Error> {
        match self.chans.get_mut(&channel) {
            Some(c) if !c.started => {
                let term: String = term.chars().filter(|c| c.is_ascii_graphic()).take(64).collect();
                c.pty = Some((term, pty_size(cols, rows, px_w, px_h)));
                session.channel_success(channel)
            }
            _ => session.channel_failure(channel),
        }
    }

    async fn env_request(
        &mut self,
        channel: ChannelId,
        name: &str,
        value: &str,
        session: &mut Session,
    ) -> Result<(), Self::Error> {
        match self.chans.get_mut(&channel) {
            // The client is about to get a root shell anyway; take any
            // well-formed variable.
            Some(c) if !c.started && spawn::valid_env_name(name) && !value.contains('\0') && c.env.len() < 64 => {
                c.env.push((name.to_string(), value.to_string()));
                session.channel_success(channel)
            }
            _ => session.channel_failure(channel),
        }
    }

    async fn shell_request(&mut self, channel: ChannelId, session: &mut Session) -> Result<(), Self::Error> {
        self.start(channel, What::Shell, session)
    }

    async fn exec_request(&mut self, channel: ChannelId, data: &[u8], session: &mut Session) -> Result<(), Self::Error> {
        match std::str::from_utf8(data) {
            Ok(cmd) if !cmd.contains('\0') => self.start(channel, What::Exec(cmd.to_string()), session),
            _ => session.channel_failure(channel),
        }
    }

    async fn subsystem_request(&mut self, channel: ChannelId, name: &str, session: &mut Session) -> Result<(), Self::Error> {
        if name != "sftp" {
            return session.channel_failure(channel);
        }
        self.start(channel, What::Subsystem(name.to_string()), session)
    }

    async fn window_change_request(
        &mut self,
        channel: ChannelId,
        cols: u32,
        rows: u32,
        px_w: u32,
        px_h: u32,
        session: &mut Session,
    ) -> Result<(), Self::Error> {
        let size = pty_size(cols, rows, px_w, px_h);
        if let Some(c) = self.chans.get_mut(&channel) {
            if c.started {
                let _ = c.tx.send(Ctl::Resize(size));
            } else if let Some((_, s)) = c.pty.as_mut() {
                *s = size;
            }
        }
        session.channel_success(channel)
    }

    async fn signal(&mut self, channel: ChannelId, sig: Sig, _session: &mut Session) -> Result<(), Self::Error> {
        if let (Some(c), Some(s)) = (self.chans.get(&channel), signal(&sig)) {
            let _ = c.tx.send(Ctl::Signal(s));
        }
        Ok(())
    }

    /// `-R`: listen on loopback only (sshd's `GatewayPorts no` binds
    /// loopback whatever was asked; this refuses a wildcard or another
    /// interface outright, so the client sees it). The listener lives until
    /// cancelled or the connection (or the agent) ends.
    async fn tcpip_forward(&mut self, address: &str, port: &mut u32, session: &mut Session) -> Result<bool, Self::Error> {
        let Some(ip) = loopback_bind(address) else {
            self.srv.events.emit("denied", &self.from, format!("-R on {address} refused (loopback only)"));
            return Ok(false);
        };
        if *port > 65535 {
            return Ok(false);
        }
        let listener = match tokio::net::TcpListener::bind((ip, *port as u16)).await {
            Ok(l) => l,
            Err(_) => return Ok(false),
        };
        let bound = listener.local_addr().map(|a| a.port() as u32).unwrap_or(*port);
        *port = bound;
        let stop = self.cancel.child_token();
        self.listeners.insert((address.to_string(), bound), stop.clone());
        self.srv.events.emit("forward", &self.from, format!("listening on {ip}:{bound}"));
        let (handle, address) = (session.handle(), address.to_string());
        tokio::spawn(async move {
            loop {
                let (tcp, peer) = tokio::select! {
                    a = listener.accept() => match a {
                        Ok(a) => a,
                        Err(_) => continue,
                    },
                    _ = stop.cancelled() => return,
                };
                let (handle, address, stop) = (handle.clone(), address.clone(), stop.clone());
                tokio::spawn(async move {
                    let ch = handle
                        .channel_open_forwarded_tcpip(address, bound, peer.ip().to_string(), peer.port() as u32)
                        .await;
                    let Ok(ch) = ch else { return };
                    let (mut ch, mut tcp) = (ch.into_stream(), tcp);
                    tokio::select! {
                        _ = tokio::io::copy_bidirectional(&mut ch, &mut tcp) => {}
                        _ = stop.cancelled() => {}
                    }
                });
            }
        });
        Ok(true)
    }

    async fn cancel_tcpip_forward(&mut self, address: &str, port: u32, _session: &mut Session) -> Result<bool, Self::Error> {
        match self.listeners.remove(&(address.to_string(), port)) {
            Some(stop) => {
                stop.cancel();
                Ok(true)
            }
            None => Ok(false),
        }
    }

    async fn channel_close(&mut self, channel: ChannelId, _session: &mut Session) -> Result<(), Self::Error> {
        self.chans.remove(&channel);
        Ok(())
    }
}

/// One session channel: wait for shell/exec/subsystem, run it, report the
/// exit status. A closed channel, dropped connection or `cancel` hangs up
/// the login like closing a terminal: pty master closed, SIGHUP.
async fn run_channel(
    srv: Arc<Server>,
    channel: Channel<Msg>,
    mut ctl: mpsc::UnboundedReceiver<Ctl>,
    from: String,
    cancel: CancellationToken,
) {
    let (mut rd, wr) = channel.split();
    let mut pending = Vec::new();
    let mut client_eof = false;
    let (what, pty, env) = loop {
        tokio::select! {
            c = ctl.recv() => match c {
                Some(Ctl::Start(w, p, e)) => break (w, p, e),
                Some(_) => {}
                None => return,
            },
            m = rd.wait() => match m {
                Some(ChannelMsg::Data { data }) if pending.len() + data.len() <= 1 << 20 => pending.extend_from_slice(&data),
                Some(ChannelMsg::Eof) => client_eof = true,
                Some(ChannelMsg::Close) | None => return,
                _ => {}
            },
            _ = cancel.cancelled() => {
                let _ = wr.close().await;
                return;
            }
        }
    };

    let label = match &what {
        What::Shell => "shell".to_string(),
        What::Exec(c) => format!("exec {}", shorten(c, 60)),
        What::Subsystem(s) => s.clone(),
    };
    let spawned = srv.launcher.command(&what, &env).and_then(|cmd| match cmd {
        Some(cmd) => spawn::spawn(cmd, pty),
        None => Err(std::io::Error::other(format!("subsystem {label} is not available"))),
    });
    let mut sp = match spawned {
        Ok(s) => s,
        Err(e) => {
            let mut err = wr.make_writer_ext(Some(1));
            let _ = err.write_all(format!("tawc: {e}\r\n").as_bytes()).await;
            let _ = err.flush().await;
            let _ = wr.exit_status(127).await;
            let _ = wr.eof().await;
            let _ = wr.close().await;
            srv.events.emit("exit", &from, format!("{label}: failed to start"));
            return;
        }
    };
    srv.events.emit("run", &from, label.clone());

    let mut out_tasks = Vec::new();
    let master = sp.pty.take().map(Arc::new);
    let mut stdin = None;
    if let Some(m) = &master {
        let (m2, mut w) = (m.clone(), wr.make_writer());
        out_tasks.push(tokio::spawn(async move {
            let mut buf = vec![0u8; 16 * 1024];
            while let Ok(n) = m2.read(&mut buf).await {
                if n == 0 || w.write_all(&buf[..n]).await.is_err() {
                    break;
                }
            }
            let _ = w.flush().await;
        }));
        if !pending.is_empty() {
            let _ = m.write_all(&pending).await;
        }
    } else if let Some(p) = sp.pipes.take() {
        let (mut out, mut err) = (p.stdout, p.stderr);
        let (mut w, mut we) = (wr.make_writer(), wr.make_writer_ext(Some(1)));
        out_tasks.push(tokio::spawn(async move {
            let _ = tokio::io::copy(&mut out, &mut w).await;
            let _ = w.flush().await;
        }));
        out_tasks.push(tokio::spawn(async move {
            let _ = tokio::io::copy(&mut err, &mut we).await;
            let _ = we.flush().await;
        }));
        let mut si = p.stdin;
        if !pending.is_empty() {
            let _ = si.write_all(&pending).await;
        }
        if !client_eof {
            stdin = Some(si);
        }
    }

    let mut ctl_open = true;
    let exit = loop {
        tokio::select! {
            m = rd.wait() => match m {
                Some(ChannelMsg::Data { data }) => {
                    if let Some(m) = &master {
                        let _ = m.write_all(&data).await;
                    } else if let Some(si) = stdin.as_mut() {
                        if si.write_all(&data).await.is_err() {
                            stdin = None;
                        }
                    }
                }
                Some(ChannelMsg::Eof) => stdin = None,
                Some(ChannelMsg::Close) | None => break None,
                _ => {}
            },
            c = ctl.recv(), if ctl_open => match c {
                Some(Ctl::Resize(s)) => {
                    if let Some(m) = &master {
                        m.resize(s);
                    }
                }
                Some(Ctl::Signal(s)) => sp.signal(s),
                Some(Ctl::Start(..)) => {}
                None => ctl_open = false,
            },
            code = &mut sp.exit => break Some(code.unwrap_or(255)),
            _ = cancel.cancelled() => break None,
        }
    };
    drop(stdin);
    match exit {
        Some(code) => {
            // Let trailing output drain (EIO once the last slave fd
            // closes), then hang up on anything still holding it.
            let _ = tokio::time::timeout(Duration::from_secs(1), async {
                for t in out_tasks.iter_mut() {
                    let _ = t.await;
                }
            })
            .await;
            for t in &out_tasks {
                t.abort();
            }
            drop(master);
            let _ = wr.exit_status(code).await;
            let _ = wr.eof().await;
            let _ = wr.close().await;
            srv.events.emit("exit", &from, format!("{label}: exit {code}"));
        }
        None => {
            for t in &out_tasks {
                t.abort();
            }
            drop(master);
            sp.signal(rustix::process::Signal::HUP);
            let _ = wr.close().await;
        }
    }
}

fn shorten(s: &str, n: usize) -> String {
    let s = s.split_whitespace().collect::<Vec<_>>().join(" ");
    let s: String = s.chars().filter(|c| !c.is_control()).collect();
    if s.chars().count() > n {
        format!("{}…", s.chars().take(n - 1).collect::<String>())
    } else {
        s
    }
}
