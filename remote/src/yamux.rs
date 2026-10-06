//! Minimal yamux (hashicorp/yamux spec) for the relay tunnel.
//!
//! Not libp2p's crate: that one opens outbound streams lazily (SYN rides
//! on the first data frame), but the relay speaks first on the control
//! stream, so the agent must announce the stream before writing. Here
//! opens and accepts are eager (a zero-delta WindowUpdate with SYN / ACK),
//! windows are fixed at the spec's 256 KiB, and a ping every 25 s both
//! keeps idle proxies from dropping the tunnel and detects a dead one.

use std::collections::HashMap;
use std::io;
use std::pin::Pin;
use std::sync::{Arc, Mutex};
use std::task::{Context, Poll, Waker};
use std::time::Duration;

use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, ReadBuf};
use tokio::sync::mpsc;
use tokio_util::sync::CancellationToken;

const T_DATA: u8 = 0;
const T_WINDOW: u8 = 1;
const T_PING: u8 = 2;
const T_GOAWAY: u8 = 3;

const F_SYN: u16 = 1;
const F_ACK: u16 = 2;
const F_FIN: u16 = 4;
const F_RST: u16 = 8;

pub const WINDOW: u32 = 256 * 1024;
const MAX_FRAME: usize = 64 * 1024;
const MAX_STREAMS: usize = 256;
const INBOUND_BACKLOG: usize = 64;
pub const KEEPALIVE: Duration = Duration::from_secs(25);
const WRITE_TIMEOUT: Duration = Duration::from_secs(20);

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Mode {
    Client,
    Server,
}

fn header(ty: u8, flags: u16, id: u32, len: u32) -> [u8; 12] {
    let mut h = [0u8; 12];
    h[1] = ty;
    h[2..4].copy_from_slice(&flags.to_be_bytes());
    h[4..8].copy_from_slice(&id.to_be_bytes());
    h[8..12].copy_from_slice(&len.to_be_bytes());
    h
}

#[derive(Default)]
struct StreamState {
    recv: std::collections::VecDeque<u8>,
    recv_window: u32,
    consumed: u32,
    send_window: u32,
    remote_fin: bool,
    local_fin: bool,
    reset: bool,
    dropped: bool,
    read_waker: Option<Waker>,
    write_waker: Option<Waker>,
}

impl StreamState {
    fn new() -> Self {
        StreamState { recv_window: WINDOW, send_window: WINDOW, ..Default::default() }
    }

    fn wake(&mut self) {
        if let Some(w) = self.read_waker.take() {
            w.wake();
        }
        if let Some(w) = self.write_waker.take() {
            w.wake();
        }
    }

    fn finished(&self) -> bool {
        self.dropped && (self.reset || (self.local_fin && self.remote_fin))
    }
}

struct State {
    streams: HashMap<u32, StreamState>,
    next_id: u32,
    closed: bool,
    ping_outstanding: bool,
    ping_seq: u32,
}

struct Inner {
    mode: Mode,
    state: Mutex<State>,
    out: mpsc::UnboundedSender<Vec<u8>>,
    dead: CancellationToken,
}

impl Inner {
    fn send(&self, frame: Vec<u8>) {
        let _ = self.out.send(frame);
    }

    fn send_hdr(&self, ty: u8, flags: u16, id: u32, len: u32) {
        self.send(header(ty, flags, id, len).to_vec());
    }

    /// Tear everything down: wake every stream, stop the tasks.
    fn shut(&self) {
        let mut st = self.state.lock().unwrap();
        if st.closed {
            return;
        }
        st.closed = true;
        for s in st.streams.values_mut() {
            s.reset = true;
            s.wake();
        }
        drop(st);
        self.dead.cancel();
    }
}

/// Handle to a yamux session. Cheap to clone.
#[derive(Clone)]
pub struct Control {
    inner: Arc<Inner>,
}

/// Streams opened by the peer.
pub struct Incoming {
    rx: mpsc::Receiver<Stream>,
}

impl Incoming {
    pub async fn accept(&mut self) -> Option<Stream> {
        self.rx.recv().await
    }
}

/// Run a session over `io`. Tasks are spawned on the current runtime and
/// end when the session does.
pub fn session<T>(io: T, mode: Mode) -> (Control, Incoming)
where
    T: AsyncRead + AsyncWrite + Send + 'static,
{
    let (out_tx, out_rx) = mpsc::unbounded_channel();
    let (in_tx, in_rx) = mpsc::channel(INBOUND_BACKLOG);
    let inner = Arc::new(Inner {
        mode,
        state: Mutex::new(State {
            streams: HashMap::new(),
            next_id: if mode == Mode::Client { 1 } else { 2 },
            closed: false,
            ping_outstanding: false,
            ping_seq: 0,
        }),
        out: out_tx,
        dead: CancellationToken::new(),
    });
    let (r, w) = tokio::io::split(io);
    let dead = inner.dead.clone();
    tokio::spawn(writer(w, out_rx, dead.clone()));
    let rd = inner.clone();
    tokio::spawn(async move {
        tokio::select! {
            _ = reader(r, rd.clone(), in_tx) => {}
            _ = rd.dead.cancelled() => {}
        }
        rd.shut();
    });
    let ka = inner.clone();
    tokio::spawn(async move {
        tokio::select! {
            _ = keepalive(ka.clone()) => {}
            _ = ka.dead.cancelled() => {}
        }
    });
    (Control { inner }, Incoming { rx: in_rx })
}

async fn writer<W: AsyncWrite>(
    w: W,
    mut rx: mpsc::UnboundedReceiver<Vec<u8>>,
    dead: CancellationToken,
) {
    tokio::pin!(w);
    let mut batch = Vec::new();
    loop {
        let first = tokio::select! {
            f = rx.recv() => f,
            _ = dead.cancelled() => None,
        };
        let Some(first) = first else { break };
        batch.clear();
        batch.extend_from_slice(&first);
        while batch.len() < 256 * 1024 {
            match rx.try_recv() {
                Ok(f) => batch.extend_from_slice(&f),
                Err(_) => break,
            }
        }
        let wrote = tokio::time::timeout(WRITE_TIMEOUT, async {
            w.write_all(&batch).await?;
            w.flush().await
        })
        .await;
        if !matches!(wrote, Ok(Ok(()))) {
            dead.cancel();
            break;
        }
    }
    // Flush whatever was queued before the close (e.g. a GoAway).
    let mut rest = Vec::new();
    while let Ok(f) = rx.try_recv() {
        rest.extend_from_slice(&f);
    }
    let _ = tokio::time::timeout(Duration::from_secs(2), async {
        if !rest.is_empty() {
            w.write_all(&rest).await?;
        }
        w.shutdown().await
    })
    .await;
}

async fn keepalive(inner: Arc<Inner>) {
    let mut tick = tokio::time::interval(KEEPALIVE);
    tick.tick().await;
    loop {
        tick.tick().await;
        let seq = {
            let mut st = inner.state.lock().unwrap();
            if st.ping_outstanding {
                // A whole interval without a pong: the tunnel is dead.
                drop(st);
                inner.shut();
                return;
            }
            st.ping_outstanding = true;
            st.ping_seq = st.ping_seq.wrapping_add(1);
            st.ping_seq
        };
        inner.send_hdr(T_PING, F_SYN, 0, seq);
    }
}

fn proto_err(msg: &str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, format!("yamux: {msg}"))
}

async fn reader<R: AsyncRead>(
    r: R,
    inner: Arc<Inner>,
    in_tx: mpsc::Sender<Stream>,
) -> io::Result<()> {
    tokio::pin!(r);
    let mut hdr = [0u8; 12];
    loop {
        r.read_exact(&mut hdr).await?;
        if hdr[0] != 0 {
            return Err(proto_err("bad version"));
        }
        let ty = hdr[1];
        let flags = u16::from_be_bytes([hdr[2], hdr[3]]);
        let id = u32::from_be_bytes([hdr[4], hdr[5], hdr[6], hdr[7]]);
        let len = u32::from_be_bytes([hdr[8], hdr[9], hdr[10], hdr[11]]);
        match ty {
            T_PING => {
                if flags & F_SYN != 0 {
                    inner.send_hdr(T_PING, F_ACK, 0, len);
                } else if flags & F_ACK != 0 {
                    let mut st = inner.state.lock().unwrap();
                    if len == st.ping_seq {
                        st.ping_outstanding = false;
                    }
                }
            }
            T_GOAWAY => return Ok(()),
            T_DATA | T_WINDOW => {
                let payload = if ty == T_DATA {
                    if len > WINDOW {
                        return Err(proto_err("frame exceeds window"));
                    }
                    let mut p = vec![0u8; len as usize];
                    r.read_exact(&mut p).await?;
                    p
                } else {
                    Vec::new()
                };
                on_stream_frame(&inner, &in_tx, ty, flags, id, len, payload)?;
            }
            _ => return Err(proto_err("unknown frame type")),
        }
    }
}

fn on_stream_frame(
    inner: &Arc<Inner>,
    in_tx: &mpsc::Sender<Stream>,
    ty: u8,
    flags: u16,
    id: u32,
    len: u32,
    payload: Vec<u8>,
) -> io::Result<()> {
    let mut st = inner.state.lock().unwrap();
    if flags & F_SYN != 0 {
        let theirs = if inner.mode == Mode::Client { id % 2 == 0 } else { id % 2 == 1 };
        if id == 0 || !theirs || st.streams.contains_key(&id) {
            return Err(proto_err("bad stream open"));
        }
        if st.streams.len() >= MAX_STREAMS {
            drop(st);
            inner.send_hdr(T_WINDOW, F_RST, id, 0);
            return Ok(());
        }
        st.streams.insert(id, StreamState::new());
        let stream = Stream { inner: inner.clone(), id };
        match in_tx.try_send(stream) {
            Ok(()) => inner.send_hdr(T_WINDOW, F_ACK, id, 0),
            Err(e) => {
                // Backlog full or nobody accepting: refuse. Dropping the
                // Stream would send a FIN; mark it reset first.
                let s = e.into_inner();
                if let Some(ss) = st.streams.get_mut(&s.id) {
                    ss.reset = true;
                }
                inner.send_hdr(T_WINDOW, F_RST, id, 0);
                drop(st);
                drop(s);
                return Ok(());
            }
        }
    }
    let Some(s) = st.streams.get_mut(&id) else {
        // A stream we already dropped: keep the peer's window open.
        if ty == T_DATA && len > 0 {
            drop(st);
            inner.send_hdr(T_WINDOW, 0, id, len);
        }
        return Ok(());
    };
    if ty == T_DATA {
        if len > s.recv_window {
            return Err(proto_err("receive window exceeded"));
        }
        s.recv_window -= len;
        if s.dropped {
            s.recv_window += len;
            let _ = inner.out.send(header(T_WINDOW, 0, id, len).to_vec());
        } else if !payload.is_empty() {
            s.recv.extend(payload);
            if let Some(w) = s.read_waker.take() {
                w.wake();
            }
        }
    } else if len > 0 {
        s.send_window = s.send_window.saturating_add(len);
        if let Some(w) = s.write_waker.take() {
            w.wake();
        }
    }
    if flags & F_FIN != 0 {
        s.remote_fin = true;
        s.wake();
    }
    if flags & F_RST != 0 {
        s.reset = true;
        s.wake();
    }
    if s.finished() {
        st.streams.remove(&id);
    }
    Ok(())
}

impl Control {
    /// Open a stream; the SYN goes out immediately.
    pub fn open(&self) -> io::Result<Stream> {
        let mut st = self.inner.state.lock().unwrap();
        if st.closed {
            return Err(io::ErrorKind::NotConnected.into());
        }
        if st.streams.len() >= MAX_STREAMS {
            return Err(io::Error::other("yamux: too many streams"));
        }
        let id = st.next_id;
        st.next_id = st.next_id.checked_add(2).ok_or_else(|| io::Error::other("yamux: ids exhausted"))?;
        st.streams.insert(id, StreamState::new());
        drop(st);
        self.inner.send_hdr(T_WINDOW, F_SYN, id, 0);
        Ok(Stream { inner: self.inner.clone(), id })
    }

    /// Send GoAway and tear the session down.
    pub fn close(&self) {
        if !self.inner.state.lock().unwrap().closed {
            self.inner.send_hdr(T_GOAWAY, 0, 0, 0);
        }
        self.inner.shut();
    }

    /// Resolves once the session is gone (closed, peer EOF, error, dead
    /// keepalive).
    pub async fn closed(&self) {
        self.inner.dead.cancelled().await
    }

    pub fn is_closed(&self) -> bool {
        self.inner.dead.is_cancelled()
    }
}

pub struct Stream {
    inner: Arc<Inner>,
    id: u32,
}

impl Stream {
    pub fn id(&self) -> u32 {
        self.id
    }

    fn with<T>(&self, f: impl FnOnce(&mut StreamState) -> T) -> Option<T> {
        let mut st = self.inner.state.lock().unwrap();
        st.streams.get_mut(&self.id).map(f)
    }

    /// Abort the stream (RST).
    pub fn reset(&self) {
        let sent = self.with(|s| {
            let was = s.reset;
            s.reset = true;
            s.wake();
            !was
        });
        if sent == Some(true) {
            self.inner.send_hdr(T_WINDOW, F_RST, self.id, 0);
        }
    }
}

impl AsyncRead for Stream {
    fn poll_read(self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        let mut update = 0;
        let res = self.with(|s| {
            if !s.recv.is_empty() {
                let n = buf.remaining().min(s.recv.len());
                let (a, b) = s.recv.as_slices();
                let na = n.min(a.len());
                buf.put_slice(&a[..na]);
                buf.put_slice(&b[..n - na]);
                s.recv.drain(..n);
                s.consumed += n as u32;
                if s.consumed >= WINDOW / 2 {
                    update = s.consumed;
                    s.recv_window += s.consumed;
                    s.consumed = 0;
                }
                return Poll::Ready(Ok(()));
            }
            if s.remote_fin {
                return Poll::Ready(Ok(()));
            }
            if s.reset {
                return Poll::Ready(Err(io::ErrorKind::ConnectionReset.into()));
            }
            s.read_waker = Some(cx.waker().clone());
            Poll::Pending
        });
        if update > 0 {
            self.inner.send_hdr(T_WINDOW, 0, self.id, update);
        }
        res.unwrap_or(Poll::Ready(Err(io::ErrorKind::ConnectionReset.into())))
    }
}

impl AsyncWrite for Stream {
    fn poll_write(self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &[u8]) -> Poll<io::Result<usize>> {
        if buf.is_empty() {
            return Poll::Ready(Ok(0));
        }
        let mut st = self.inner.state.lock().unwrap();
        let Some(s) = st.streams.get_mut(&self.id) else {
            return Poll::Ready(Err(io::ErrorKind::BrokenPipe.into()));
        };
        if s.reset || s.local_fin {
            return Poll::Ready(Err(io::ErrorKind::BrokenPipe.into()));
        }
        if s.send_window == 0 {
            s.write_waker = Some(cx.waker().clone());
            return Poll::Pending;
        }
        let n = buf.len().min(s.send_window as usize).min(MAX_FRAME);
        s.send_window -= n as u32;
        let mut frame = Vec::with_capacity(12 + n);
        frame.extend_from_slice(&header(T_DATA, 0, self.id, n as u32));
        frame.extend_from_slice(&buf[..n]);
        // Queue under the lock so frames of one stream keep their order.
        self.inner.send(frame);
        Poll::Ready(Ok(n))
    }

    fn poll_flush(self: Pin<&mut Self>, _cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Poll::Ready(Ok(()))
    }

    /// Half-close (FIN).
    fn poll_shutdown(self: Pin<&mut Self>, _cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        let mut st = self.inner.state.lock().unwrap();
        if let Some(s) = st.streams.get_mut(&self.id) {
            if !s.local_fin && !s.reset {
                s.local_fin = true;
                self.inner.send_hdr(T_WINDOW, F_FIN, self.id, 0);
            }
        }
        Poll::Ready(Ok(()))
    }
}

impl Drop for Stream {
    fn drop(&mut self) {
        let mut st = self.inner.state.lock().unwrap();
        let Some(s) = st.streams.get_mut(&self.id) else { return };
        s.dropped = true;
        if !s.local_fin && !s.reset {
            s.local_fin = true;
            self.inner.send_hdr(T_WINDOW, F_FIN, self.id, 0);
        }
        // Give back whatever the peer sent that nobody will read.
        let unread = s.recv.len() as u32 + s.consumed;
        s.recv.clear();
        s.consumed = 0;
        if unread > 0 && !s.reset && !s.remote_fin {
            s.recv_window += unread;
            self.inner.send_hdr(T_WINDOW, 0, self.id, unread);
        }
        if s.finished() {
            st.streams.remove(&self.id);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pair() -> ((Control, Incoming), (Control, Incoming)) {
        let (a, b) = tokio::io::duplex(4096);
        (session(a, Mode::Client), session(b, Mode::Server))
    }

    #[tokio::test]
    async fn open_is_eager_and_bidirectional() {
        let ((c, _ci), (_s, mut si)) = pair();
        let mut cs = c.open().unwrap();
        // The server sees the stream before the client writes anything,
        // and can speak first (the relay's challenge).
        let mut ss = si.accept().await.unwrap();
        assert_eq!(ss.id(), 1);
        ss.write_all(b"challenge\n").await.unwrap();
        let mut buf = [0u8; 10];
        cs.read_exact(&mut buf).await.unwrap();
        assert_eq!(&buf, b"challenge\n");
        cs.write_all(b"hi").await.unwrap();
        cs.shutdown().await.unwrap();
        let mut got = Vec::new();
        ss.read_to_end(&mut got).await.unwrap();
        assert_eq!(got, b"hi");
    }

    #[tokio::test]
    async fn bulk_transfer_respects_windows() {
        let ((c, mut ci), (s, _si)) = pair();
        let mut ss = s.open().unwrap();
        let mut cs = ci.accept().await.unwrap();
        assert_eq!(ss.id() % 2, 0);
        let data: Vec<u8> = (0..3 * WINDOW as usize + 12345).map(|i| (i * 7) as u8).collect();
        let d2 = data.clone();
        let w = tokio::spawn(async move {
            ss.write_all(&d2).await.unwrap();
            ss.shutdown().await.unwrap();
            ss
        });
        let mut got = Vec::new();
        cs.read_to_end(&mut got).await.unwrap();
        assert_eq!(got.len(), data.len());
        assert!(got == data);
        w.await.unwrap();
        drop(c);
    }

    #[tokio::test]
    async fn close_resets_streams() {
        let ((c, _ci), (_s, mut si)) = pair();
        let mut cs = c.open().unwrap();
        let _ss = si.accept().await.unwrap();
        c.close();
        c.closed().await;
        let mut b = [0u8; 1];
        assert!(cs.read(&mut b).await.is_err());
    }

    #[tokio::test]
    async fn peer_close_ends_session() {
        let ((c, _ci), (s, _si)) = pair();
        s.close();
        tokio::time::timeout(Duration::from_secs(5), c.closed()).await.unwrap();
    }
}
