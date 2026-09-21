//! Socket-activated compositor lifecycle and the session holds around it.
//!
//! The suite runs with the compositor pinned (`compositor-hold`); each
//! test here drops the pin for its duration so the production rule —
//! start on first connection, stop a second after the last client — is
//! what runs.

use std::time::{Duration, Instant};

use tawc_integration::helpers::{ensure_wayland_debug_app, has_shm_surface, TIMEOUT};
use tawc_integration::rootfs_process::RootfsProcess;
use tawc_integration::{adb, compositor, GraphicsBackend};

const BACKEND: GraphicsBackend = GraphicsBackend::Cpu;

/// Idle grace (1 s) plus slack for teardown and a slow device.
const STOP_TIMEOUT: Duration = Duration::from_secs(6);
/// Xwayland lingers 5 s after its last client before the rule can apply.
const X11_STOP_TIMEOUT: Duration = Duration::from_secs(20);
const START_TIMEOUT: Duration = Duration::from_secs(15);

/// Unpinned for the test, re-pinned (and so restarted) afterwards even
/// on panic, so later tests find the compositor they expect.
struct Unpinned;

impl Unpinned {
    fn new() -> Self {
        tawc_integration::helpers::test_init();
        let guard = Unpinned;
        adb::compositor_hold(false).expect("compositor-hold off");
        compositor::wait_for_stopped(X11_STOP_TIMEOUT).expect("idle compositor should stop");
        guard
    }
}

impl Drop for Unpinned {
    fn drop(&mut self) {
        let _ = adb::compositor_hold(true);
    }
}

fn wait_for_clients(min: u32, timeout: Duration) {
    let deadline = Instant::now() + timeout;
    loop {
        if let Ok(state) = compositor::query_state_once() {
            if state.clients >= min {
                return;
            }
        }
        assert!(Instant::now() < deadline, "no client reached the compositor in {timeout:?}");
        std::thread::sleep(Duration::from_millis(50));
    }
}

/// `(fds, threads)` of the app process, read by a child of it.
fn app_fds_and_threads() -> (u32, u32) {
    let out = adb::host_sh("ls /proc/$PPID/fd | wc -l; ls /proc/$PPID/task | wc -l")
        .expect("host-sh fd/thread count");
    let nums: Vec<u32> = String::from_utf8_lossy(&out.stdout)
        .split_whitespace()
        .filter_map(|n| n.parse().ok())
        .collect();
    assert_eq!(nums.len(), 2, "unexpected fd/thread count output: {out:?}");
    (nums[0], nums[1])
}

/// A terminal-style spawn — nothing warms the compositor up — of a
/// Wayland client starts it; it stops after the client leaves; and a
/// second client starts it again.
#[test]
fn test_wayland_client_starts_and_idle_stops_compositor() {
    let binary = ensure_wayland_debug_app();
    let _unpinned = Unpinned::new();

    for round in 0..2 {
        let mut app = RootfsProcess::spawn_with(BACKEND, &format!("{binary} scale"))
            .expect("spawn debug app");
        wait_for_clients(1, START_TIMEOUT);
        let deadline = Instant::now() + START_TIMEOUT;
        while !has_shm_surface() {
            assert!(app.is_running(), "debug app exited before rendering (round {round})");
            assert!(Instant::now() < deadline, "debug app never rendered (round {round})");
            std::thread::sleep(Duration::from_millis(100));
        }
        let reasons = adb::session_state().expect("session-state");
        assert!(
            reasons.iter().any(|r| r.starts_with("compositor ")),
            "running compositor should hold a session reason, got {reasons:?}"
        );
        app.stop().expect("debug app failed to stop cleanly");
        compositor::wait_for_stopped(STOP_TIMEOUT)
            .unwrap_or_else(|e| panic!("round {round}: {e}"));
        let reasons = adb::session_state().expect("session-state");
        assert!(
            !reasons.iter().any(|r| r.starts_with("compositor ")),
            "stopped compositor should release its session reason, got {reasons:?}"
        );
    }
}

/// An X11-only client connects to `:0` and nothing else; the holder must
/// watch that socket too.
#[test]
#[cfg_attr(tawc_skip_libhybris_on_target, ignore = "xwayland skipped on x86 device")]
fn test_x11_client_starts_and_idle_stops_compositor() {
    let _unpinned = Unpinned::new();

    let mut app = RootfsProcess::spawn_with(
        BACKEND,
        "env -u WAYLAND_DISPLAY DISPLAY=:0 xclock -update 1",
    )
    .expect("spawn xclock");
    let deadline = Instant::now() + START_TIMEOUT;
    while !has_shm_surface() {
        assert!(app.is_running(), "xclock exited before rendering");
        assert!(Instant::now() < deadline, "xclock never rendered via a lazily started compositor");
        std::thread::sleep(Duration::from_millis(100));
    }
    app.stop().expect("xclock failed to stop cleanly");
    compositor::wait_for_stopped(X11_STOP_TIMEOUT).expect("compositor should stop after Xwayland");

    // `:0` is listening again: a second X11 client restarts everything.
    let mut again = RootfsProcess::spawn_with(
        BACKEND,
        "env -u WAYLAND_DISPLAY DISPLAY=:0 xclock -update 1",
    )
    .expect("spawn second xclock");
    let deadline = Instant::now() + START_TIMEOUT;
    while !has_shm_surface() {
        assert!(again.is_running(), "second xclock exited before rendering");
        assert!(Instant::now() < deadline, "second xclock never rendered");
        std::thread::sleep(Duration::from_millis(100));
    }
    again.stop().expect("second xclock failed to stop cleanly");
}

/// Start/stop cycles must not leak: every run's Display, client fds, GL
/// context and helper threads go away with it.
#[test]
fn test_compositor_cycles_do_not_leak() {
    let _unpinned = Unpinned::new();
    const CONNECT: &str = "python3 -c 'import socket; s = socket.socket(socket.AF_UNIX); \
        s.connect(\"/usr/share/tawc/wayland-0\"); s.close()'";

    let cycle = || {
        let out = adb::rootfs_run_with(BACKEND, CONNECT).expect("connect to wayland-0");
        assert!(
            out.status.success(),
            "connection refused by an idle compositor socket: {}",
            String::from_utf8_lossy(&out.stderr)
        );
        compositor::wait_for_stopped(STOP_TIMEOUT).expect("compositor should stop after cycle");
    };
    // Warm-up: one-time lazy initialisation is not a leak.
    cycle();
    cycle();
    let (fds_before, threads_before) = app_fds_and_threads();
    for _ in 0..8 {
        cycle();
    }
    let (fds_after, threads_after) = app_fds_and_threads();
    assert!(
        fds_after <= fds_before + 2,
        "fds grew over 8 compositor cycles: {fds_before} -> {fds_after}"
    );
    assert!(
        threads_after <= threads_before + 2,
        "threads grew over 8 compositor cycles: {threads_before} -> {threads_after}"
    );
}

/// Holds released and re-acquired back to back: the session service's
/// stop must never swallow a pending `startForegroundService`. Android
/// answers that with `ForegroundServiceDidNotStartInTimeException`, which
/// kills the app process and every guest with it. Each short command is
/// one acquire/release, and each compositor cycle releases the
/// compositor hold right as the next command acquires.
#[test]
fn test_session_service_survives_hold_churn() {
    let _unpinned = Unpinned::new();
    let app_pid = || {
        let out = adb::host_sh("echo $PPID").expect("host-sh pid");
        String::from_utf8_lossy(&out.stdout).trim().to_string()
    };
    let pid_before = app_pid();
    assert!(!pid_before.is_empty(), "could not read app pid");

    const CONNECT: &str = "python3 -c 'import socket; s = socket.socket(socket.AF_UNIX); \
        s.connect(\"/usr/share/tawc/wayland-0\"); s.close()'";
    for i in 0..12 {
        for _ in 0..3 {
            let out = adb::rootfs_run_with(BACKEND, "true").expect("run true");
            assert!(out.status.success(), "iteration {i}: `true` failed — app died?");
        }
        let out = adb::rootfs_run_with(BACKEND, CONNECT).expect("connect");
        assert!(out.status.success(), "iteration {i}: connect failed — app died?");
        // Land the next acquire right around the compositor's auto-stop.
        std::thread::sleep(Duration::from_millis(900 + (i * 25) as u64));
    }
    assert_eq!(pid_before, app_pid(), "app process was restarted during hold churn");
}

/// A rootfs command holds a session reason exactly while it runs, and
/// Exit kills it — and a detached child that holds nothing — outright.
#[test]
fn test_session_holds_follow_commands_and_exit_kills_everything() {
    let _unpinned = Unpinned::new();

    let mut cmd = RootfsProcess::spawn_with(
        BACKEND,
        "setsid sleep 3917 >/dev/null 2>&1 & exec sleep 3918",
    )
    .expect("spawn sleeper");
    let deadline = Instant::now() + TIMEOUT;
    loop {
        let reasons = adb::session_state().expect("session-state");
        if reasons.iter().any(|r| r.starts_with("command ")) {
            break;
        }
        assert!(Instant::now() < deadline, "command never held a session reason: {reasons:?}");
        std::thread::sleep(Duration::from_millis(50));
    }

    // Both must really be running first, or "gone after Exit" proves
    // nothing: a shell that exits at once can beat `setsid` to its fork.
    let sleepers = || {
        let ps = adb::host_sh("ps -A -o ARGS | grep -E 'sleep 391[78]' | grep -v grep; true")
            .expect("ps");
        String::from_utf8_lossy(&ps.stdout).trim().to_string()
    };
    let deadline = Instant::now() + TIMEOUT;
    loop {
        let alive = sleepers();
        if alive.contains("sleep 3917") && alive.contains("sleep 3918") {
            break;
        }
        assert!(Instant::now() < deadline, "sleepers never both started: {alive:?}");
        std::thread::sleep(Duration::from_millis(50));
    }

    adb::session_exit().expect("session-exit");
    let deadline = Instant::now() + Duration::from_secs(10);
    loop {
        let alive = sleepers();
        let reasons = adb::session_state().expect("session-state");
        if alive.is_empty() && reasons.is_empty() {
            break;
        }
        assert!(
            Instant::now() < deadline,
            "exit left things alive: processes={alive:?} reasons={reasons:?}"
        );
        std::thread::sleep(Duration::from_millis(100));
    }
    let _ = cmd.stop();
}
