//! Launcher entry-management coverage via the debug `launcher-list` /
//! `set-entry-hidden` broker actions (notes/launcher.md, notes/
//! exec-broker.md). No screenshot scraping: plant a `.desktop` file in
//! the rootfs, flip hide state through the same locked metadata write
//! the launcher UI performs, and assert on the post-filter list the
//! launcher renders from.

use tawc_integration::adb;
use tawc_integration::helpers::assert_broker_ok;

const ENTRY_ID: &str = "tawc-hide-test";

fn rootfs() -> String {
    format!(
        "/data/data/me.phie.tawc/distros/{}/rootfs",
        tawc_integration::install_id()
    )
}

fn desktop_path() -> String {
    format!("{}/usr/share/applications/{ENTRY_ID}.desktop", rootfs())
}

/// Best-effort cleanup on both success and panic: unhide the id (the
/// metadata write is durable) and remove the planted `.desktop`.
struct Cleanup;

impl Drop for Cleanup {
    fn drop(&mut self) {
        let _ = adb::set_entry_hidden(ENTRY_ID, false);
        let rm = format!("rm -f '{}'", desktop_path());
        let _ = adb::rootfs_host_exec(&["/system/bin/sh", "-c", &rm]);
    }
}

/// Slice out the JSON object containing `"id":"<id>"`. Good enough for
/// the flat, nesting-free objects `launcher-list` emits and the planted
/// entry's brace-free values.
fn entry_object<'a>(list: &'a str, id: &str) -> Option<&'a str> {
    let needle = format!("\"id\":\"{id}\"");
    let key = list.find(&needle)?;
    let start = list[..key].rfind('{')?;
    let end = key + list[key..].find('}')?;
    Some(&list[start..=end])
}

/// Hide/unhide round-trip: a hidden entry disappears from the default
/// (launcher-visible) list, stays reachable with `showHidden` and is
/// flagged `hidden:true` there, and returns on unhide.
#[test]
fn test_hidden_entry_filtering() {
    tawc_integration::helpers::test_init();
    let _cleanup = Cleanup;

    let plant = format!(
        "mkdir -p \"$(dirname '{path}')\" && printf '%s\\n' \
         '[Desktop Entry]' 'Type=Application' 'Name=TAWC Hide Test' 'Exec=true' \
         > '{path}'",
        path = desktop_path()
    );
    assert_broker_ok(
        adb::rootfs_host_exec(&["/system/bin/sh", "-c", &plant]).expect("plant .desktop"),
        "plant .desktop",
    );
    // Clear any hide state a previously crashed run left behind.
    assert_broker_ok(
        adb::set_entry_hidden(ENTRY_ID, false).expect("set-entry-hidden"),
        "set-entry-hidden reset",
    );

    let list = adb::launcher_list(false).expect("launcher-list");
    let obj = entry_object(&list, ENTRY_ID)
        .unwrap_or_else(|| panic!("planted entry missing from launcher-list: {list}"));
    assert!(
        obj.contains("\"hidden\":false"),
        "visible entry not flagged hidden:false: {obj}"
    );
    assert!(
        obj.contains(&format!("{ENTRY_ID}.desktop")),
        "entry object missing .desktop source path: {obj}"
    );

    // Hide: gone from the launcher-visible list...
    assert_broker_ok(
        adb::set_entry_hidden(ENTRY_ID, true).expect("set-entry-hidden"),
        "set-entry-hidden hide",
    );
    let list = adb::launcher_list(false).expect("launcher-list");
    assert!(
        entry_object(&list, ENTRY_ID).is_none(),
        "hidden entry still in default launcher-list: {list}"
    );
    // ...but present and flagged with the show-hidden toggle.
    let list = adb::launcher_list(true).expect("launcher-list showHidden");
    let obj = entry_object(&list, ENTRY_ID)
        .unwrap_or_else(|| panic!("hidden entry missing from showHidden list: {list}"));
    assert!(
        obj.contains("\"hidden\":true"),
        "hidden entry not flagged hidden:true: {obj}"
    );

    // Unhide: back in the default list.
    assert_broker_ok(
        adb::set_entry_hidden(ENTRY_ID, false).expect("set-entry-hidden"),
        "set-entry-hidden unhide",
    );
    let list = adb::launcher_list(false).expect("launcher-list");
    assert!(
        entry_object(&list, ENTRY_ID).is_some(),
        "unhidden entry missing from launcher-list: {list}"
    );
}

// ---- scan directories + precedence ------------------------------------

const USER_ID: &str = "tawc-scan-user";
const LOCAL_ID: &str = "tawc-scan-local";
const DUP_ID: &str = "tawc-scan-dup";

/// Remove every fixture `test_scan_dirs_precedence_and_terminal` plants,
/// on success and panic alike.
struct ScanCleanup;

impl Drop for ScanCleanup {
    fn drop(&mut self) {
        let r = rootfs();
        let rm = format!(
            "rm -f '{r}/root/.local/share/applications/{USER_ID}.desktop' \
                   '{r}/usr/local/share/applications/{LOCAL_ID}.desktop' \
                   '{r}/root/.local/share/applications/{DUP_ID}.desktop' \
                   '{r}/usr/share/applications/{DUP_ID}.desktop'"
        );
        let _ = adb::rootfs_host_exec(&["/system/bin/sh", "-c", &rm]);
    }
}

/// Plant a minimal `.desktop` at `<rootfs>/<subdir>/<id>.desktop`.
fn plant_desktop(subdir: &str, id: &str, name: &str, exec: &str, terminal: bool) {
    let path = format!("{}/{subdir}/{id}.desktop", rootfs());
    let term = if terminal { " 'Terminal=true'" } else { "" };
    let plant = format!(
        "mkdir -p \"$(dirname '{path}')\" && printf '%s\\n' \
         '[Desktop Entry]' 'Type=Application' 'Name={name}' 'Exec={exec}'{term} \
         > '{path}'"
    );
    assert_broker_ok(
        adb::rootfs_host_exec(&["/system/bin/sh", "-c", &plant]).expect("plant .desktop"),
        "plant .desktop",
    );
}

/// The scanner walks the user-writable dirs and gives them precedence:
/// entries in `/root/.local/share/applications` (the guest's XDG
/// per-user dir) and `/usr/local/share/applications` both appear,
/// `Terminal=true` is reported, and a duplicated id resolves to the
/// user copy — its name/exec shadow the `/usr/share` one
/// (notes/launcher.md "Scan directories").
#[test]
fn test_scan_dirs_precedence_and_terminal() {
    tawc_integration::helpers::test_init();
    let _cleanup = ScanCleanup;

    plant_desktop(
        "root/.local/share/applications",
        USER_ID,
        "TAWC Scan User",
        "tawc-scan-user-exec",
        true,
    );
    plant_desktop(
        "usr/local/share/applications",
        LOCAL_ID,
        "TAWC Scan Local",
        "tawc-scan-local-exec",
        false,
    );
    plant_desktop(
        "root/.local/share/applications",
        DUP_ID,
        "TAWC Scan Dup User",
        "tawc-scan-dup-user-exec",
        false,
    );
    plant_desktop(
        "usr/share/applications",
        DUP_ID,
        "TAWC Scan Dup Packaged",
        "tawc-scan-dup-packaged-exec",
        false,
    );

    let list = adb::launcher_list(false).expect("launcher-list");

    let user = entry_object(&list, USER_ID)
        .unwrap_or_else(|| panic!("per-user dir entry missing from launcher-list: {list}"));
    assert!(
        user.contains("\"terminal\":true"),
        "Terminal=true not reported: {user}"
    );
    // org.json escapes `/` as `\/` in launcher-list output.
    assert!(
        user.contains("\\/root\\/.local\\/share\\/applications\\/"),
        "per-user entry path not in the managed dir: {user}"
    );

    assert!(
        entry_object(&list, LOCAL_ID).is_some(),
        "/usr/local entry missing from launcher-list: {list}"
    );

    let dup = entry_object(&list, DUP_ID)
        .unwrap_or_else(|| panic!("duplicated-id entry missing from launcher-list: {list}"));
    assert!(
        dup.contains("tawc-scan-dup-user-exec") && dup.contains("TAWC Scan Dup User"),
        "duplicated id did not resolve to the per-user copy: {dup}"
    );
}
