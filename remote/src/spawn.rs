//! Starting login processes: the [`Launcher`] says *what* to run (argv,
//! env, cwd); this module runs it on a pty or pipes, in its own session.

use std::fs::File;
use std::io;
use std::os::fd::{BorrowedFd, OwnedFd};
use std::os::unix::process::{CommandExt, ExitStatusExt};
use std::process::{Command, Stdio};

use rustix::fs::{fcntl_getfl, fcntl_setfl, OFlags};
use rustix::pty::{grantpt, openpt, ptsname, unlockpt, OpenptFlags};
use rustix::termios::{tcsetwinsize, Winsize};
use tokio::io::unix::AsyncFd;
use tokio::io::Interest;

/// What an SSH session asked to run.
#[derive(Debug, Clone)]
pub enum What {
    /// Interactive login shell.
    Shell,
    /// `exec` request: a command line for a shell.
    Exec(String),
    /// `subsystem` request (only `sftp` is passed on).
    Subsystem(String),
}

/// Builds the process for a session. The caller adds stdio, `setsid` and
/// the controlling terminal.
pub trait Launcher: Send + Sync {
    /// `env` is extra variables for the process (TERM, SSH_*, client env
    /// requests). `Ok(None)` for an unsupported subsystem.
    fn command(&self, what: &What, env: &[(String, String)]) -> io::Result<Option<Command>>;
}

/// Runs `/bin/sh` directly: host tests and the spike.
pub struct HostShell {
    pub shell: String,
}

impl Launcher for HostShell {
    fn command(&self, what: &What, env: &[(String, String)]) -> io::Result<Option<Command>> {
        let mut c = Command::new(&self.shell);
        match what {
            What::Shell => {
                c.arg0(format!("-{}", self.shell.rsplit('/').next().unwrap_or("sh")));
            }
            What::Exec(cmd) => {
                c.arg("-c").arg(cmd);
            }
            What::Subsystem(_) => return Ok(None),
        }
        c.envs(env.iter().map(|(k, v)| (k, v)));
        if let Ok(home) = std::env::var("HOME") {
            c.current_dir(home);
        }
        Ok(Some(c))
    }
}

/// The tawcroot spawn envelope from `TawcrootMethod.spawnEnvelope`:
/// `argv` ends with the guest env (`… env -i -C /root K=V …`), so extra
/// variables and then the program are appended. Shells are login
/// shells (`<shell> -l`, commands `/bin/bash -lc`, like every other
/// command spawn); sftp runs [`SFTP_SERVERS`]' first hit.
#[derive(Debug, Clone, serde::Deserialize)]
pub struct Envelope {
    pub argv: Vec<String>,
    pub shell: String,
    #[serde(default)]
    pub host_env: Vec<String>,
    pub cwd: String,
    /// Host path of the rootfs, to look for `sftp-server`.
    pub rootfs: String,
}

/// TAWC's own static sftp-server (SftpServerInstallProvider), then where
/// distros put OpenSSH's, for a rootfs not yet refreshed.
const SFTP_SERVERS: &[&str] = &[
    "/usr/lib/tawc/sftp-server",
    "/usr/lib/ssh/sftp-server",
    "/usr/lib/openssh/sftp-server",
    "/usr/libexec/sftp-server",
];

impl Launcher for Envelope {
    fn command(&self, what: &What, env: &[(String, String)]) -> io::Result<Option<Command>> {
        let (prog, rest) = self.argv.split_first().ok_or_else(|| io::Error::other("empty spawn envelope"))?;
        let mut c = Command::new(prog);
        c.args(rest);
        c.args(env.iter().map(|(k, v)| format!("{k}={v}")));
        match what {
            What::Shell => {
                c.args([self.shell.as_str(), "-l"]);
            }
            What::Exec(cmd) => {
                c.args(["/bin/bash", "-lc", cmd.as_str()]);
            }
            What::Subsystem(name) if name == "sftp" => {
                // symlink_metadata: an absolute symlink in the rootfs
                // points into the guest's view, not ours.
                let found = SFTP_SERVERS
                    .iter()
                    .find(|p| std::fs::symlink_metadata(format!("{}{p}", self.rootfs)).is_ok())
                    .ok_or_else(|| io::Error::other("no sftp-server in this rootfs"))?;
                c.arg(found);
            }
            What::Subsystem(_) => return Ok(None),
        }
        c.env_clear();
        for kv in &self.host_env {
            if let Some((k, v)) = kv.split_once('=') {
                c.env(k, v);
            }
        }
        c.current_dir(&self.cwd);
        Ok(Some(c))
    }
}

/// Is `name` a sane environment variable name to pass through?
pub fn valid_env_name(name: &str) -> bool {
    let mut b = name.bytes();
    matches!(b.next(), Some(c) if c.is_ascii_alphabetic() || c == b'_')
        && b.all(|c| c.is_ascii_alphanumeric() || c == b'_')
        && name.len() <= 256
}

#[derive(Debug, Clone, Copy, Default)]
pub struct PtySize {
    pub cols: u16,
    pub rows: u16,
    pub px_w: u16,
    pub px_h: u16,
}

fn winsize(s: PtySize) -> Winsize {
    Winsize { ws_row: s.rows, ws_col: s.cols, ws_xpixel: s.px_w, ws_ypixel: s.px_h }
}

/// A pty master registered with the reactor.
pub struct PtyMaster {
    fd: AsyncFd<OwnedFd>,
}

impl PtyMaster {
    pub fn resize(&self, s: PtySize) {
        let _ = tcsetwinsize(self.fd.get_ref(), winsize(s));
    }

    /// Read output; `Ok(0)` once the slave side is gone (EIO).
    pub async fn read(&self, buf: &mut [u8]) -> io::Result<usize> {
        loop {
            let r = self
                .fd
                .async_io(Interest::READABLE, |fd| rustix::io::read(fd, &mut *buf).map_err(io::Error::from))
                .await;
            return match r {
                Err(e) if e.raw_os_error() == Some(rustix::io::Errno::IO.raw_os_error()) => Ok(0),
                Err(e) if e.kind() == io::ErrorKind::Interrupted => continue,
                r => r,
            };
        }
    }

    pub async fn write_all(&self, mut buf: &[u8]) -> io::Result<()> {
        while !buf.is_empty() {
            let n = self
                .fd
                .async_io(Interest::WRITABLE, |fd| rustix::io::write(fd, buf).map_err(io::Error::from))
                .await?;
            buf = &buf[n..];
        }
        Ok(())
    }
}

pub struct Pipes {
    pub stdin: tokio::process::ChildStdin,
    pub stdout: tokio::process::ChildStdout,
    pub stderr: tokio::process::ChildStderr,
}

pub struct Spawned {
    pub pid: i32,
    pub pty: Option<PtyMaster>,
    pub pipes: Option<Pipes>,
    /// Exit status as SSH reports it (128+signal when killed).
    pub exit: tokio::task::JoinHandle<u32>,
}

impl Spawned {
    pub fn signal(&self, sig: rustix::process::Signal) {
        if let Some(pid) = rustix::process::Pid::from_raw(self.pid) {
            let _ = rustix::process::kill_process(pid, sig);
        }
    }
}

/// Highest open fd in this process, read before forking (the child may
/// only make async-signal-safe calls).
fn max_open_fd() -> i32 {
    std::fs::read_dir("/proc/self/fd")
        .map(|d| {
            d.filter_map(|e| e.ok()?.file_name().to_str()?.parse::<i32>().ok())
                .max()
                .unwrap_or(1024)
        })
        .unwrap_or(1024)
}

/// Start `cmd` in a new session, on a fresh pty when `pty` is given, on
/// pipes otherwise.
pub fn spawn(mut cmd: Command, pty: Option<PtySize>) -> io::Result<Spawned> {
    // Nothing of the host process may leak into the login: mark every fd
    // above stdio close-on-exec (Android apps have plenty that aren't).
    let max_fd = max_open_fd() + 64;
    let mut master = None;
    if let Some(size) = pty {
        let m = openpt(OpenptFlags::RDWR | OpenptFlags::NOCTTY | OpenptFlags::CLOEXEC)?;
        grantpt(&m)?;
        unlockpt(&m)?;
        let name = ptsname(&m, Vec::new())?;
        let slave: OwnedFd = rustix::fs::open(
            name.as_c_str(),
            OFlags::RDWR | OFlags::NOCTTY | OFlags::CLOEXEC,
            rustix::fs::Mode::empty(),
        )?;
        tcsetwinsize(&m, winsize(size))?;
        let slave = File::from(slave);
        cmd.stdin(Stdio::from(slave.try_clone()?))
            .stdout(Stdio::from(slave.try_clone()?))
            .stderr(Stdio::from(slave));
        master = Some(m);
    } else {
        cmd.stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(Stdio::piped());
    }
    let want_ctty = pty.is_some();
    unsafe {
        cmd.pre_exec(move || {
            rustix::process::setsid()?;
            if want_ctty {
                rustix::process::ioctl_tiocsctty(BorrowedFd::borrow_raw(0))?;
            }
            for fd in 3..max_fd {
                let _ = rustix::io::fcntl_setfd(BorrowedFd::borrow_raw(fd), rustix::io::FdFlags::CLOEXEC);
            }
            Ok(())
        });
    }
    let mut child = cmd.spawn()?;
    // The slave fds live in `cmd`; drop them so EIO arrives once the
    // login's side closes.
    drop(cmd);
    let pid = child.id() as i32;
    let pipes = if pty.is_none() {
        Some(Pipes {
            stdin: tokio::process::ChildStdin::from_std(child.stdin.take().unwrap())?,
            stdout: tokio::process::ChildStdout::from_std(child.stdout.take().unwrap())?,
            stderr: tokio::process::ChildStderr::from_std(child.stderr.take().unwrap())?,
        })
    } else {
        None
    };
    let pty = match master {
        Some(m) => {
            fcntl_setfl(&m, fcntl_getfl(&m)? | OFlags::NONBLOCK)?;
            Some(PtyMaster { fd: AsyncFd::new(m)? })
        }
        None => None,
    };
    // waitpid on this one pid, off the reactor. No SIGCHLD handler: the
    // host process (the Android app) owns that disposition.
    let exit = tokio::task::spawn_blocking(move || match child.wait() {
        Ok(st) => match (st.code(), st.signal()) {
            (Some(c), _) => c as u32,
            (None, Some(s)) => 128 + s as u32,
            _ => 255,
        },
        Err(_) => 255,
    });
    Ok(Spawned { pid, pty, pipes, exit })
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    #[tokio::test]
    async fn pty_is_controlling_terminal() {
        let mut c = Command::new("/bin/sh");
        c.arg("-c").arg("stty size; tty; ps -o sid= -p $$; echo pid=$$");
        let mut s = spawn(c, Some(PtySize { cols: 91, rows: 17, ..Default::default() })).unwrap();
        let m = s.pty.take().unwrap();
        let mut out = Vec::new();
        let mut buf = [0u8; 4096];
        loop {
            let n = m.read(&mut buf).await.unwrap();
            if n == 0 {
                break;
            }
            out.extend_from_slice(&buf[..n]);
        }
        let out = String::from_utf8_lossy(&out);
        assert!(out.contains("17 91"), "{out}");
        assert!(out.contains("/dev/pts/"), "{out}");
        // Session leader: sid == pid.
        let sid = out.lines().nth(2).unwrap().trim().to_string();
        assert!(out.contains(&format!("pid={sid}")), "{out}");
        assert_eq!(s.exit.await.unwrap(), 0);
    }

    #[tokio::test]
    async fn pipes_and_exit_status() {
        let mut c = Command::new("/bin/sh");
        c.arg("-c").arg("read x; echo got $x; echo err >&2; exit 7");
        let mut s = spawn(c, None).unwrap();
        let mut p = s.pipes.take().unwrap();
        p.stdin.write_all(b"hello\n").await.unwrap();
        drop(p.stdin);
        let mut out = String::new();
        p.stdout.read_to_string(&mut out).await.unwrap();
        let mut err = String::new();
        p.stderr.read_to_string(&mut err).await.unwrap();
        assert_eq!(out, "got hello\n");
        assert_eq!(err, "err\n");
        assert_eq!(s.exit.await.unwrap(), 7);
    }

    #[tokio::test]
    async fn killed_by_signal() {
        let mut c = Command::new("/bin/sh");
        c.arg("-c").arg("kill -TERM $$; sleep 5");
        let s = spawn(c, None).unwrap();
        assert_eq!(s.exit.await.unwrap(), 128 + 15);
    }

    #[test]
    fn envelope_argv() {
        let dir = std::env::temp_dir().join(format!("tawc-env-{}", std::process::id()));
        std::fs::create_dir_all(dir.join("usr/lib/openssh")).unwrap();
        std::fs::write(dir.join("usr/lib/openssh/sftp-server"), "").unwrap();
        let e = Envelope {
            argv: vec!["/t/tawcroot".into(), "-r".into(), "/r".into(), "--".into(), "/usr/bin/env".into(), "-i".into(), "A=1".into()],
            shell: "/usr/bin/zsh".into(),
            host_env: vec!["TMPDIR=/r/tmp".into()],
            cwd: "/tmp".into(),
            rootfs: dir.to_string_lossy().into(),
        };
        let env = vec![("TERM".to_string(), "xterm".to_string())];
        let args = |c: &Command| c.get_args().map(|a| a.to_string_lossy().into_owned()).collect::<Vec<_>>();
        let c = e.command(&What::Shell, &env).unwrap().unwrap();
        assert_eq!(c.get_program(), "/t/tawcroot");
        assert_eq!(args(&c)[5..], ["A=1", "TERM=xterm", "/usr/bin/zsh", "-l"]);
        assert_eq!(c.get_envs().collect::<Vec<_>>(), [(std::ffi::OsStr::new("TMPDIR"), Some(std::ffi::OsStr::new("/r/tmp")))]);
        let c = e.command(&What::Exec("echo 'a b'".into()), &[]).unwrap().unwrap();
        assert_eq!(args(&c)[6..], ["/bin/bash", "-lc", "echo 'a b'"]);
        let c = e.command(&What::Subsystem("sftp".into()), &[]).unwrap().unwrap();
        assert_eq!(args(&c).last().unwrap(), "/usr/lib/openssh/sftp-server");
        std::fs::remove_dir_all(&dir).unwrap();
        assert!(e.command(&What::Subsystem("sftp".into()), &[]).unwrap_err().to_string().contains("no sftp-server"));
    }

    #[test]
    fn env_names() {
        assert!(valid_env_name("LANG"));
        assert!(valid_env_name("_x1"));
        for bad in ["", "1A", "A=B", "A B", "-x"] {
            assert!(!valid_env_name(bad), "{bad}");
        }
    }
}
