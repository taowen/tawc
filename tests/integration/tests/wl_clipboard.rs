//! `wl-copy` / `wl-paste` over data-control (notes/clipboard.md "CLI
//! clipboard"). wl-clipboard needs no window with data-control, and
//! data-control reads are gated on the reader's controlling tty: only
//! processes on the pty of the terminal tab the user is looking at may
//! read. Typed into the home terminal through `input`, so MainActivity
//! must be visible.

use std::time::{Duration, Instant};

use tawc_integration::helpers::{
    close_home_terminal, show_home_tab, show_home_terminal, start_wayland_debug_clipboard_copy,
    terminal_run, wait_for_rootfs_file, TIMEOUT,
};
use tawc_integration::{adb, compositor, GraphicsBackend};

const BACKEND: GraphicsBackend = GraphicsBackend::Cpu;

fn rootfs(cmd: &str) -> String {
    let out = adb::rootfs_run_with(BACKEND, cmd).expect("rootfs run");
    assert!(out.status.success(), "`{cmd}` failed: {out:?}");
    String::from_utf8_lossy(&out.stdout).into_owned()
}

fn wait_for_android_clipboard(expected: &str) {
    let deadline = Instant::now() + TIMEOUT;
    loop {
        let got = adb::clipboard_get_text().expect("get Android clipboard");
        if got == expected {
            return;
        }
        assert!(Instant::now() < deadline, "Android clipboard {got:?}, want {expected:?}");
        std::thread::sleep(Duration::from_millis(100));
    }
}

fn wait_for_file(path: &str) {
    wait_for_rootfs_file(BACKEND, path, Duration::from_secs(15));
}

/// `wl-copy` sets the selection through data-control: no window, and the
/// text is mirrored into Android.
#[test]
fn test_wl_copy_needs_no_window() {
    tawc_integration::helpers::test_init();
    let before = compositor::query_state(TIMEOUT).expect("query-state before");
    let text = "wl-copy to android";
    rootfs(&format!("wl-copy '{text}' </dev/null >/dev/null 2>&1"));
    wait_for_android_clipboard(text);
    let after = compositor::query_state(TIMEOUT).expect("query-state after");
    assert_eq!(
        (after.toplevels, after.hosts),
        (before.toplevels, before.hosts),
        "wl-copy mapped a window"
    );
}

/// Reads are allowed only from the focused terminal tab's pty: not from
/// the broker (no tty), a `setsid` child (no tty), or the terminal while
/// another window has focus — for Android clips and client-owned
/// selections alike.
#[test]
fn test_wl_paste_only_from_focused_terminal() {
    tawc_integration::helpers::test_init();
    rootfs("rm -f /tmp/tawc-wlp-*");
    let android_text = "android clip for wl-paste";
    adb::clipboard_set_text(android_text).expect("set Android clipboard");

    // Denied before the Android fetch: the counter doesn't move.
    let fetches_before = adb::clipboard_android_fetches_total().expect("clipboard state");
    let out = rootfs("wl-paste -n; true");
    assert_eq!(out, "", "broker (no tty) wl-paste read the clipboard");
    assert_eq!(adb::clipboard_android_fetches_total().expect("clipboard state"), fetches_before);

    show_home_terminal();
    terminal_run(
        "wl-paste%s-n>/tmp/tawc-wlp-a;setsid%s-w%swl-paste%s-n>/tmp/tawc-wlp-b;touch%s/tmp/tawc-wlp-1",
    );
    wait_for_file("/tmp/tawc-wlp-1");
    assert_eq!(rootfs("cat /tmp/tawc-wlp-a"), android_text, "focused terminal was denied");
    assert_eq!(rootfs("cat /tmp/tawc-wlp-b"), "", "setsid (no tty) child read the clipboard");
    assert!(
        adb::clipboard_android_fetches_total().expect("clipboard state") > fetches_before,
        "terminal paste did not go through the Android fetch"
    );

    // Same shell, but waits until a Wayland window has taken focus.
    terminal_run(
        "while%s[%s!%s-e%s/tmp/tawc-wlp-go%s];do%ssleep%s0.1;done;wl-paste%s-n>/tmp/tawc-wlp-c;touch%s/tmp/tawc-wlp-2",
    );
    let client_text = "client selection for wl-paste";
    let mut copy_app = start_wayland_debug_clipboard_copy(BACKEND, "", client_text);
    wait_for_android_clipboard(client_text);
    rootfs("touch /tmp/tawc-wlp-go");
    wait_for_file("/tmp/tawc-wlp-2");
    assert_eq!(rootfs("cat /tmp/tawc-wlp-c"), "", "unfocused terminal read the clipboard");

    // Focused again: the client-owned selection is readable.
    show_home_tab("0");
    terminal_run("wl-paste%s-n>/tmp/tawc-wlp-d;touch%s/tmp/tawc-wlp-3");
    wait_for_file("/tmp/tawc-wlp-3");
    assert_eq!(rootfs("cat /tmp/tawc-wlp-d"), client_text, "focused terminal was denied");

    copy_app.stop().expect("clipboard copy app failed to stop cleanly");
    rootfs("rm -f /tmp/tawc-wlp-*");
    close_home_terminal();
}
