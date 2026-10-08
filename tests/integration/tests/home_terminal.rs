//! The home screen's terminal tabs (notes/terminal.md "Lifecycle"): what
//! happens when a terminal goes away, per the selection at the time
//! (the recents swipe is in lazy_compositor.rs). Typed into through the
//! real IME path (`input`), so MainActivity must be visible.

use std::time::Duration;

use tawc_integration::adb;
use tawc_integration::helpers::{
    show_home_apps, show_home_tab, show_home_terminal, terminal_run, test_init, wait_terminal_state,
};

/// New terminal tab, then wait for the prompt.
fn open_tab(want: &str) {
    adb::home_tab("new").expect("home-tab new");
    wait_terminal_state(want);
    std::thread::sleep(Duration::from_secs(1));
}

/// The last terminal exiting while selected closes the app; with others
/// left the right neighbour is selected, else the left one.
#[test]
fn test_terminal_exit_selects_neighbour_then_closes_app() {
    test_init();
    show_home_terminal();
    let reasons = adb::session_state().expect("session-state");
    assert!(reasons.iter().any(|r| r.starts_with("terminal ")), "terminal holds no session reason: {reasons:?}");
    open_tab("tabs:2 selected:1");
    open_tab("tabs:3 selected:2");

    // Middle tab exits: its right neighbour (now at its index) is selected.
    show_home_tab("1");
    wait_terminal_state("tabs:3 selected:1");
    terminal_run("exit");
    wait_terminal_state("tabs:2 selected:1");

    // Rightmost exits: the left neighbour.
    terminal_run("exit");
    wait_terminal_state("tabs:1 selected:0");

    // Last one: the app closes.
    terminal_run("exit");
    wait_terminal_state("tabs:0 selected:none");
    let reasons = adb::session_state().expect("session-state");
    assert!(!reasons.iter().any(|r| r.starts_with("terminal ")), "closed shell still holds: {reasons:?}");

    // Later tests expect a TAWC activity in front.
    show_home_apps();
}

/// A terminal exiting while the apps tab or another terminal is
/// selected only drops its tab; the selection stays and the app stays
/// open.
#[test]
fn test_unselected_terminal_exit_keeps_selection() {
    test_init();
    show_home_terminal();
    terminal_run("sleep%s4;exit");
    adb::home_tab("apps").expect("home-tab apps");
    wait_terminal_state("tabs:1 selected:apps");
    wait_terminal_state("tabs:0 selected:apps");

    // Tab 0 exits behind tab 1, which keeps the selection at its new index.
    show_home_terminal();
    terminal_run("sleep%s4;exit");
    open_tab("tabs:2 selected:1");
    wait_terminal_state("tabs:1 selected:0");
    terminal_run("exit");
    wait_terminal_state("tabs:0 selected:none");
    show_home_apps();
}
