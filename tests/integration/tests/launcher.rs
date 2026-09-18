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
/// An empty `icon` omits the `Icon=` key.
fn plant_desktop(
    subdir: &str,
    id: &str,
    name: &str,
    exec: &str,
    terminal: bool,
    icon: &str,
) {
    let path = format!("{}/{subdir}/{id}.desktop", rootfs());
    let term = if terminal { " 'Terminal=true'" } else { "" };
    let icon = if icon.is_empty() {
        String::new()
    } else {
        format!(" 'Icon={icon}'")
    };
    let plant = format!(
        "mkdir -p \"$(dirname '{path}')\" && printf '%s\\n' \
         '[Desktop Entry]' 'Type=Application' 'Name={name}' 'Exec={exec}'{term}{icon} \
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
        "",
    );
    plant_desktop(
        "usr/local/share/applications",
        LOCAL_ID,
        "TAWC Scan Local",
        "tawc-scan-local-exec",
        false,
        "",
    );
    plant_desktop(
        "root/.local/share/applications",
        DUP_ID,
        "TAWC Scan Dup User",
        "tawc-scan-dup-user-exec",
        false,
        "",
    );
    plant_desktop(
        "usr/share/applications",
        DUP_ID,
        "TAWC Scan Dup Packaged",
        "tawc-scan-dup-packaged-exec",
        false,
        "",
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

// ---- icon resolution --------------------------------------------------

/// Cache dir `launcher.rs` rasterizes SVG icons into — a sibling of the
/// rootfs, so it belongs to the install and goes away with it.
fn icon_cache_dir() -> String {
    format!(
        "/data/data/me.phie.tawc/distros/{}/icon-cache",
        tawc_integration::install_id()
    )
}

fn icons_dir() -> String {
    format!("{}/usr/share/icons", rootfs())
}

/// Run a host-side shell command as the app uid and return its stdout,
/// trimmed. Panics if the broker call fails; a non-zero command exit is
/// the caller's business (missing files are a normal answer here).
fn host_sh(cmd: &str) -> String {
    let out = adb::rootfs_host_exec(&["/system/bin/sh", "-c", cmd]).expect("host sh");
    String::from_utf8_lossy(&out.stdout).trim().to_string()
}

/// Same, but the command must succeed.
fn host_sh_ok(cmd: &str, what: &str) {
    assert_broker_ok(
        adb::rootfs_host_exec(&["/system/bin/sh", "-c", cmd]).expect(what),
        what,
    );
}

/// Value of a flat string field in a `launcher-list` object, with
/// org.json's `\/` path escaping undone.
fn json_field(obj: &str, key: &str) -> String {
    let needle = format!("\"{key}\":\"");
    let start = obj.find(&needle).map(|i| i + needle.len());
    let Some(start) = start else { return String::new() };
    let end = start + obj[start..].find('"').expect("unterminated JSON string");
    obj[start..end].replace("\\/", "/")
}

/// `iconPath` of the entry with this id. Panics if the entry is absent —
/// every icon test plants its entry first.
fn icon_path_of(id: &str) -> String {
    let list = adb::launcher_list(false).expect("launcher-list");
    let obj = entry_object(&list, id)
        .unwrap_or_else(|| panic!("entry {id} missing from launcher-list: {list}"));
    json_field(obj, "iconPath")
}

/// Write an SVG of `size`² into the rootfs at `path`.
fn plant_svg(path: &str, size: u32) {
    let svg = format!(
        "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"{size}\" height=\"{size}\">\
         <rect width=\"{size}\" height=\"{size}\" fill=\"#ff0000\"/></svg>"
    );
    host_sh_ok(
        &format!("mkdir -p \"$(dirname '{path}')\" && printf '%s' '{svg}' > '{path}'"),
        "plant svg",
    );
}

/// A 4×4 red PNG, so a PNG-vs-SVG preference test doesn't need a
/// renderer on the host side.
const FIXTURE_PNG_B64: &str = "iVBORw0KGgoAAAANSUhEUgAAAAQAAAAECAYAAACp8Z5+AAAAEklEQVR4nGP4z8DwHxk\
                               zkC4AADxAH+HggXe0AAAAAElFTkSuQmCC";

fn plant_png(path: &str) {
    host_sh_ok(
        &format!(
            "mkdir -p \"$(dirname '{path}')\" && printf '%s' '{FIXTURE_PNG_B64}' \
             | base64 -d > '{path}'"
        ),
        "plant png",
    );
}

const SVG_ICON_ID: &str = "tawc-icon-svg";
const SVG_ICON_NAME: &str = "tawc-icon-svg-name";

struct SvgIconCleanup;

impl Drop for SvgIconCleanup {
    fn drop(&mut self) {
        let _ = adb::rootfs_host_exec(&[
            "/system/bin/sh",
            "-c",
            &format!(
                "rm -f '{}/usr/share/applications/{SVG_ICON_ID}.desktop' \
                       '{}/hicolor/scalable/apps/{SVG_ICON_NAME}.svg'",
                rootfs(),
                icons_dir()
            ),
        ]);
    }
}

/// An SVG-only icon resolves to a PNG in the per-install cache, the
/// cache is a hit on the next scan, and rewriting the source rotates the
/// cached file and prunes the stale one.
#[test]
fn test_svg_icon_rasterized_and_cached() {
    tawc_integration::helpers::test_init();
    let _cleanup = SvgIconCleanup;

    let svg = format!("{}/hicolor/scalable/apps/{SVG_ICON_NAME}.svg", icons_dir());
    plant_svg(&svg, 32);
    plant_desktop(
        "usr/share/applications",
        SVG_ICON_ID,
        "TAWC Icon SVG",
        "tawc-icon-svg-exec",
        false,
        SVG_ICON_NAME,
    );

    let path = icon_path_of(SVG_ICON_ID);
    assert!(
        path.ends_with(".png") && path.starts_with(&icon_cache_dir()),
        "SVG icon did not resolve into the cache as a PNG: {path:?}"
    );
    // Decodable by BitmapFactory means: actually a PNG.
    let magic = host_sh(&format!("od -An -tx1 -N4 '{path}'"));
    assert_eq!(
        magic.split_whitespace().collect::<Vec<_>>(),
        vec!["89", "50", "4e", "47"],
        "cached icon is not a PNG: {path}"
    );

    // Second scan: same key, and the file was not rewritten.
    let mtime = host_sh(&format!("stat -c %Y '{path}'"));
    let again = icon_path_of(SVG_ICON_ID);
    assert_eq!(again, path, "cache key changed with no source change");
    assert_eq!(
        host_sh(&format!("stat -c %Y '{path}'")),
        mtime,
        "cached icon re-rendered on a cache hit: {path}"
    );

    // Source rewritten: new cache entry, old one pruned.
    plant_svg(&svg, 48);
    let rotated = icon_path_of(SVG_ICON_ID);
    assert_ne!(rotated, path, "cache key survived a source rewrite");
    assert_eq!(
        host_sh(&format!("test -e '{path}' && echo present || echo gone")),
        "gone",
        "stale cache entry not pruned: {path}"
    );
}

const BAD_ICON_ID: &str = "tawc-icon-bad";
const BAD_ICON_NAME: &str = "tawc-icon-bad-name";

struct BadIconCleanup;

impl Drop for BadIconCleanup {
    fn drop(&mut self) {
        let _ = adb::rootfs_host_exec(&[
            "/system/bin/sh",
            "-c",
            &format!(
                "rm -f '{}/usr/share/applications/{BAD_ICON_ID}.desktop' \
                       '{}/hicolor/scalable/apps/{BAD_ICON_NAME}.svg'",
                rootfs(),
                icons_dir()
            ),
        ]);
    }
}

/// A malformed SVG is not fatal: the entry keeps its place in the list
/// with an empty `iconPath` (Kotlin draws the fallback glyph), and a
/// `.fail` marker keeps the next scan from re-parsing it.
#[test]
fn test_malformed_svg_icon_is_not_fatal() {
    tawc_integration::helpers::test_init();
    let _cleanup = BadIconCleanup;

    let svg = format!("{}/hicolor/scalable/apps/{BAD_ICON_NAME}.svg", icons_dir());
    host_sh_ok(
        &format!(
            "mkdir -p \"$(dirname '{svg}')\" && printf '%s' '<svg not xml at all' > '{svg}'"
        ),
        "plant malformed svg",
    );
    plant_desktop(
        "usr/share/applications",
        BAD_ICON_ID,
        "TAWC Icon Bad",
        "tawc-icon-bad-exec",
        false,
        BAD_ICON_NAME,
    );

    let list = adb::launcher_list(false).expect("launcher-list");
    let obj = entry_object(&list, BAD_ICON_ID)
        .unwrap_or_else(|| panic!("entry with a bad icon dropped from the list: {list}"));
    assert_eq!(
        json_field(obj, "iconPath"),
        "",
        "malformed SVG resolved to a path: {obj}"
    );
    // The rest of the scan is unaffected.
    assert!(
        list.matches("\"id\":").count() > 1,
        "scan returned only the bad entry: {list}"
    );
    assert_ne!(
        host_sh(&format!("ls '{}' | grep -c '\\.fail$'", icon_cache_dir())),
        "0",
        "no .fail marker left for the malformed SVG"
    );
}

const THEME_ICON_ID: &str = "tawc-icon-theme";
const INHERIT_ICON_NAME: &str = "tawc-icon-inherit-name";
const BREEZE_ICON_ID: &str = "tawc-icon-breeze";
const BREEZE_ICON_NAME: &str = "tawc-icon-breeze-name";
const SYMBOLIC_ICON_ID: &str = "tawc-icon-symbolic";
const SYMBOLIC_ICON_NAME: &str = "tawc-icon-symbolic-name";
const PARENT_THEME: &str = "tawc-test-parent";

/// Seed theme to plant the fixture under. Must not already exist —
/// overwriting a distro's real theme is not something a test gets to do.
fn free_seed_theme() -> Option<&'static str> {
    ["Papirus", "breeze"].into_iter().find(|theme| {
        host_sh(&format!(
            "test -d '{}/{theme}' && echo present || echo free",
            icons_dir()
        )) == "free"
    })
}

struct ThemeCleanup(Option<&'static str>);

impl Drop for ThemeCleanup {
    fn drop(&mut self) {
        let seed = self.0.unwrap_or("");
        let _ = adb::rootfs_host_exec(&[
            "/system/bin/sh",
            "-c",
            &format!(
                "rm -rf '{icons}/{PARENT_THEME}' {seed_dir}; \
                 rm -f '{root}/usr/share/applications/{THEME_ICON_ID}.desktop' \
                       '{root}/usr/share/applications/{BREEZE_ICON_ID}.desktop' \
                       '{root}/usr/share/applications/{SYMBOLIC_ICON_ID}.desktop'",
                icons = icons_dir(),
                root = rootfs(),
                seed_dir = if seed.is_empty() {
                    String::new()
                } else {
                    format!("'{}/{seed}'", icons_dir())
                },
            ),
        ]);
    }
}

/// The theme walk follows `index.theme` `Inherits=` chains, handles the
/// `<context>/<size>` layout breeze uses (not just `<size>/<context>`),
/// and falls back to a tiled symbolic glyph when a name has nothing
/// else.
#[test]
fn test_theme_inherits_and_context_size_layout() {
    tawc_integration::helpers::test_init();

    let Some(seed) = free_seed_theme() else {
        eprintln!("skipping: every seed theme is already installed in this rootfs");
        return;
    };
    let _cleanup = ThemeCleanup(Some(seed));

    // The seed theme has no icons of its own but inherits the parent...
    host_sh_ok(
        &format!(
            "mkdir -p '{icons}/{seed}' && printf '%s\\n' '[Icon Theme]' \
             'Name={seed}' 'Inherits={PARENT_THEME},hicolor' \
             > '{icons}/{seed}/index.theme'",
            icons = icons_dir()
        ),
        "plant seed theme",
    );
    plant_png(&format!(
        "{}/{PARENT_THEME}/48x48/apps/{INHERIT_ICON_NAME}.png",
        icons_dir()
    ));
    plant_desktop(
        "usr/share/applications",
        THEME_ICON_ID,
        "TAWC Icon Inherited",
        "tawc-icon-theme-exec",
        false,
        INHERIT_ICON_NAME,
    );

    // ...and the seed itself carries a breeze-layout icon.
    plant_png(&format!(
        "{}/{seed}/apps/48/{BREEZE_ICON_NAME}.png",
        icons_dir()
    ));
    plant_desktop(
        "usr/share/applications",
        BREEZE_ICON_ID,
        "TAWC Icon Breeze Layout",
        "tawc-icon-breeze-exec",
        false,
        BREEZE_ICON_NAME,
    );

    // ...and a name that exists only as a symbolic glyph.
    plant_svg(
        &format!(
            "{}/{seed}/symbolic/apps/{SYMBOLIC_ICON_NAME}-symbolic.svg",
            icons_dir()
        ),
        16,
    );
    plant_desktop(
        "usr/share/applications",
        SYMBOLIC_ICON_ID,
        "TAWC Icon Symbolic",
        "tawc-icon-symbolic-exec",
        false,
        SYMBOLIC_ICON_NAME,
    );

    let inherited = icon_path_of(THEME_ICON_ID);
    assert!(
        inherited.ends_with(&format!("{PARENT_THEME}/48x48/apps/{INHERIT_ICON_NAME}.png")),
        "Inherits= chain not followed: {inherited:?}"
    );
    let breeze = icon_path_of(BREEZE_ICON_ID);
    assert!(
        breeze.ends_with(&format!("{seed}/apps/48/{BREEZE_ICON_NAME}.png")),
        "<context>/<size> layout not searched: {breeze:?}"
    );
    let symbolic = icon_path_of(SYMBOLIC_ICON_ID);
    assert!(
        symbolic.starts_with(&icon_cache_dir()) && symbolic.ends_with(".png"),
        "symbolic-only icon did not resolve to a tiled rendering: {symbolic:?}"
    );
}

const ESCAPE_ICON_ID: &str = "tawc-icon-escape";

struct EscapeCleanup;

impl Drop for EscapeCleanup {
    fn drop(&mut self) {
        let _ = adb::rootfs_host_exec(&[
            "/system/bin/sh",
            "-c",
            &format!(
                "rm -f '{}/usr/share/applications/{ESCAPE_ICON_ID}.desktop'",
                rootfs()
            ),
        ]);
    }
}

/// `Icon=` is guest-controlled and the resolver now *parses* what it
/// finds, so an absolute path that climbs out of the rootfs resolves to
/// nothing rather than to a host file.
#[test]
fn test_absolute_icon_path_cannot_escape_rootfs() {
    tawc_integration::helpers::test_init();
    let _cleanup = EscapeCleanup;

    plant_desktop(
        "usr/share/applications",
        ESCAPE_ICON_ID,
        "TAWC Icon Escape",
        "tawc-icon-escape-exec",
        false,
        "/../../../../system/etc/escape.png",
    );
    assert_eq!(
        icon_path_of(ESCAPE_ICON_ID),
        "",
        "absolute Icon= escaped the rootfs"
    );
}
