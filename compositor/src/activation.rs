//! Socket activation for the compositor: the Wayland and X11 listening
//! sockets outlive every compositor run, so a client can always connect.
//!
//! The holder binds `share/wayland-0` once and keeps the listener for the
//! life of the process; it also keeps Xwayland's prepared `:0` socket
//! whenever no compositor has borrowed it. While no compositor thread
//! exists, the holder thread polls both for a pending connection (without
//! accepting) and asks Kotlin to start the compositor; the connection
//! waits in the listen backlog meanwhile. A running compositor accepts on
//! its own clone of the Wayland listener and takes the X11 socket with
//! [`take_x11`], handing it back on exit with [`return_x11`]. Because the
//! holder re-polls as soon as the compositor thread is gone, a connection
//! that arrives while the compositor is stopping restarts it.
//!
//! See notes/architecture.md ("Compositor lifecycle").

use std::os::fd::{AsFd, AsRawFd, OwnedFd};
use std::os::unix::fs::PermissionsExt;
use std::os::unix::net::UnixListener;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Mutex, OnceLock};
use std::time::Duration;

use log::{error, info, warn};
use smithay::xwayland::{XWayland, XWaylandActivation};

struct Holder {
    wayland: UnixListener,
    x11: Mutex<Option<XWaylandActivation>>,
    /// Self-pipe: interrupts the idle poll when the X11 setting changes.
    wake_rx: OwnedFd,
    wake_tx: OwnedFd,
}

static HOLDER: OnceLock<Holder> = OnceLock::new();

/// The user's Xwayland setting. `:0` must refuse connections while off.
static X11_ENABLED: AtomicBool = AtomicBool::new(false);

/// Retry interval while the X11 socket is wanted but could not be
/// prepared. Otherwise the idle holder sleeps until something happens.
const X11_RETRY: Duration = Duration::from_millis(1000);
/// How long a requested start may take before the holder asks again.
const START_TIMEOUT: Duration = Duration::from_secs(10);

/// Bind the sockets and start the holder thread. Idempotent.
pub fn start(x11_enabled: bool) {
    if HOLDER.get().is_some() {
        set_x11_enabled(x11_enabled);
        return;
    }
    X11_ENABLED.store(x11_enabled, Ordering::SeqCst);
    let path = &crate::app_paths::get().wayland_socket_path;
    let wayland = match bind_wayland(path) {
        Ok(listener) => listener,
        Err(e) => {
            error!("activation: bind {} failed: {}", path, e);
            return;
        }
    };
    let (wake_rx, wake_tx) = match crate::clipboard::pipe() {
        Ok(fds) => fds,
        Err(e) => {
            error!("activation: pipe failed: {}", e);
            return;
        }
    };
    let holder = Holder { wayland, x11: Mutex::new(None), wake_rx, wake_tx };
    if HOLDER.set(holder).is_err() {
        return;
    }
    info!("activation: listening on {}", path);
    std::thread::Builder::new()
        .name("activation".into())
        .spawn(run)
        .expect("spawn activation thread");
}

pub fn set_x11_enabled(enabled: bool) {
    if X11_ENABLED.swap(enabled, Ordering::SeqCst) != enabled {
        if let Some(holder) = HOLDER.get() {
            let _ = rustix::io::write(&holder.wake_tx, &[0]);
        }
    }
}

/// A clone of the process-lifetime Wayland listener for one compositor
/// run. Non-blocking (the flag is shared with the holder's fd, which only
/// ever polls).
pub fn wayland_listener() -> std::io::Result<UnixListener> {
    let holder = HOLDER
        .get()
        .ok_or_else(|| std::io::Error::other("activation holder not started"))?;
    holder.wayland.try_clone()
}

/// Borrow the prepared X11 socket, if the holder has one.
pub fn take_x11() -> Option<XWaylandActivation> {
    HOLDER.get()?.x11.lock().unwrap().take()
}

/// Hand an unused X11 socket back when the compositor exits.
pub fn return_x11(activation: XWaylandActivation) {
    if let Some(holder) = HOLDER.get() {
        *holder.x11.lock().unwrap() = Some(activation);
    }
}

fn bind_wayland(path: &str) -> std::io::Result<UnixListener> {
    if let Some(parent) = std::path::Path::new(path).parent() {
        std::fs::create_dir_all(parent)?;
    }
    // Left over from a killed process (or a pre-activation compositor,
    // which also kept a lock file).
    let _ = std::fs::remove_file(path);
    let _ = std::fs::remove_file(format!("{path}.lock"));
    let listener = UnixListener::bind(path)?;
    // Clients run as any uid the rootfs method maps them to.
    std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o777))?;
    listener.set_nonblocking(true)?;
    Ok(listener)
}

fn run() {
    let holder = HOLDER.get().expect("holder set before thread start");
    let mut logged_start_failure = false;
    loop {
        crate::wait_until_idle();
        let (x11_fd, retry_x11) = sync_x11(holder);
        let readable = poll_readable(holder, x11_fd.as_ref(), retry_x11);
        // Our X11 fd is a dup: the original may have been borrowed (and
        // even consumed by Xwayland) while we slept.
        drop(x11_fd);
        if !readable || crate::compositor_running() {
            continue;
        }
        crate::call_native_bridge_void("onActivationRequested", "()V", &[]);
        if crate::wait_until_running(START_TIMEOUT) {
            logged_start_failure = false;
        } else {
            if !logged_start_failure {
                error!("activation: compositor did not start; pending clients keep waiting");
                logged_start_failure = true;
            }
            std::thread::sleep(X11_RETRY);
        }
    }
}

/// Keep the idle X11 socket in step with the setting. Returns a dup of
/// its fd to poll, and whether preparing it failed and should be retried.
fn sync_x11(holder: &Holder) -> (Option<OwnedFd>, bool) {
    let mut x11 = holder.x11.lock().unwrap();
    if !X11_ENABLED.load(Ordering::SeqCst) {
        *x11 = None;
        return (None, false);
    }
    let mut retry = false;
    if x11.is_none() && crate::xwayland::prepare_environment().is_some() {
        match XWayland::prepare_lazy(Some(0), false) {
            Ok(activation) => *x11 = Some(activation),
            // Usually the previous Xwayland's lock, gone within a poll.
            Err(e) => {
                warn!("activation: X11 socket not ready: {}", e);
                retry = true;
            }
        }
    }
    let fd = x11.as_ref().and_then(|a| a.poll_fd().try_clone_to_owned().ok());
    (fd, retry)
}

/// True when a client is waiting on either socket.
fn poll_readable(holder: &Holder, x11: Option<&OwnedFd>, retry_x11: bool) -> bool {
    let mut fds = [
        libc::pollfd { fd: holder.wayland.as_fd().as_raw_fd(), events: libc::POLLIN, revents: 0 },
        libc::pollfd {
            fd: x11.map_or(-1, |fd| fd.as_raw_fd()),
            events: libc::POLLIN,
            revents: 0,
        },
        libc::pollfd { fd: holder.wake_rx.as_raw_fd(), events: libc::POLLIN, revents: 0 },
    ];
    let timeout = if retry_x11 { X11_RETRY.as_millis() as i32 } else { -1 };
    let rc = unsafe { libc::poll(fds.as_mut_ptr(), fds.len() as _, timeout) };
    if rc <= 0 {
        return false;
    }
    if fds[2].revents & libc::POLLIN != 0 {
        let mut buf = [0u8; 16];
        let _ = rustix::io::read(&holder.wake_rx, &mut buf);
    }
    fds[..2].iter().any(|fd| fd.revents & libc::POLLIN != 0)
}
