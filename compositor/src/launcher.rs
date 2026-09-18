//! Desktop-entry scanner for the in-app launcher.
//!
//! Walks the standard XDG `applications/` directories *inside* a given
//! rootfs (e.g. `<appData>/distros/<id>/rootfs/usr/share/applications`)
//! and returns parsed `.desktop` entries. The list is consumed by Kotlin
//! via JNI in `lib.rs`; entries are JSON-encoded so the Java boundary
//! stays trivially small.
//!
//! Filtering matches what a normal Linux app menu shows: `Type=Application`,
//! not `NoDisplay`, not `Hidden`, has an `Exec`. The `OnlyShowIn` /
//! `NotShowIn` machinery is intentionally ignored — a desktop session inside
//! the chroot has no canonical name (we're "TAWC", not "GNOME"), and almost
//! every entry that uses these keys still works fine.
//!
//! Icon resolution: we map `Icon=` to an absolute on-device path inside
//! the rootfs. Only PNG is returned today — Android's `BitmapFactory`
//! can't decode SVG/XPM natively, so we'd just hand Kotlin a path it
//! couldn't load. Most real apps ship a hicolor PNG as well as their
//! SVG, so this loses surprisingly few icons; the modern-GNOME SVG-only
//! apps fall back to a placeholder. See `notes/launcher.md` for the
//! rationale + the SVG-rendering follow-ups.

use std::path::{Path, PathBuf};

use freedesktop_desktop_entry::{DesktopEntry, Iter};
use serde_json::json;

/// Directories under a rootfs that may contain `.desktop` files. Mirrors
/// `$XDG_DATA_HOME` + `$XDG_DATA_DIRS` for a standard glibc install
/// (the guest runs as fake root, so `$HOME` is `/root`) plus flatpak's
/// exports. Order is de-dup priority: earlier dirs win for a duplicated
/// id, so a user's `.desktop` in `/root/.local` shadows the packaged
/// copy — required for "hide the packaged entry behind my edited copy".
const APPS_SUBDIRS: &[&str] = &[
    "root/.local/share/applications",
    "usr/local/share/applications",
    "usr/share/applications",
    "var/lib/flatpak/exports/share/applications",
    "var/lib/snapd/desktop/applications",
];

/// Themes searched under `<rootfs>/usr/share/icons`. `hicolor` is the
/// universal fallback per the fdo Icon Theme Spec; the others are the
/// most common shipped themes. We don't parse `index.theme` to honour
/// `Inherits=` chains — overkill for MVP, and `hicolor` catches almost
/// everything in practice. Order matters: themed icons (Adwaita, …)
/// usually look nicer than hicolor's generic fallbacks, but apps that
/// install only into hicolor still need to be findable, so we try the
/// pretty themes first then hicolor.
const ICON_THEMES: &[&str] = &["Adwaita", "Papirus", "breeze", "hicolor"];

/// Pixel sizes to try, in preference order. Mid-size first because list
/// rows render at ~48dp (~144 px on a 3× density phone) — a 128 PNG
/// scales cleanly to that without burning the memory of a 256. The
/// smaller fallbacks keep us showing *something* when an app only ships
/// 48 / 64.
const ICON_SIZES: &[&str] = &["128x128", "96x96", "256x256", "64x64", "48x48"];

/// Image extensions recognised in `Icon=foo.png` style entries. Used
/// only to strip a known extension before doing the theme search; the
/// resolver itself only ever returns `.png` paths.
const KNOWN_ICON_EXTS: &[&str] = &["png", "svg", "xpm", "jpg", "jpeg"];

/// One launchable application, ready to ship to Kotlin.
pub struct Entry {
    /// Filename minus `.desktop` — the stable id Kotlin hide-state and
    /// shortcut pins reference; `resolve_metadata_for_app_id` matches
    /// window app_ids against it.
    pub id: String,
    pub name: String,
    pub comment: String,
    /// Raw `Exec=` line. Field codes (`%f`, `%u`, …) stripped because we
    /// don't pass URIs at launch time; everything else (quoting, env
    /// vars) is left for `bash -lc` to handle.
    pub exec: String,
    pub terminal: bool,
    /// Absolute path to the resolved icon file (always PNG), inside the
    /// rootfs. Empty string if no icon was findable. Kotlin loads this
    /// via `BitmapFactory.decodeFile`; the rootfs is app-uid-owned for
    /// proot/tawcroot installs so direct read works.
    pub icon_path: String,
    /// Absolute host path of the parsed `.desktop` file. Lets Kotlin
    /// decide whether an entry is user-editable (managed dir) without
    /// re-deriving the scan layout.
    pub path: String,
}

/// Scan [rootfs] for `.desktop` apps. Returns entries sorted by name
/// (case-insensitive), de-duplicated by id — the per-user dir wins over
/// `/usr/share` if both ship the same id, matching desktop-environment
/// convention. `launchable_only` additionally drops `NoDisplay` entries
/// (the launcher list); metadata resolution keeps them — a *running*
/// NoDisplay app still needs its icon/title resolved.
fn scan_entries(rootfs: &Path, launchable_only: bool) -> Vec<Entry> {
    let dirs: Vec<PathBuf> = APPS_SUBDIRS
        .iter()
        .map(|sub| rootfs.join(sub))
        .filter(|p| p.exists())
        .collect();

    let mut entries: Vec<Entry> = Vec::new();
    let locales = current_locales();
    for path in Iter::new(dirs.into_iter()) {
        let de = match DesktopEntry::from_path(path, Some(&locales)) {
            Ok(de) => de,
            Err(_) => continue,
        };
        if !is_relevant(&de, launchable_only) {
            continue;
        }
        let exec = match de.exec() {
            Some(e) if !e.trim().is_empty() => strip_field_codes(e),
            _ => continue,
        };
        let name = de
            .name(&locales)
            .map(|s| s.into_owned())
            .unwrap_or_else(|| de.appid.clone());
        let comment = de
            .comment(&locales)
            .map(|s| s.into_owned())
            .unwrap_or_default();
        let icon_path = de
            .icon()
            .and_then(|i| resolve_icon(rootfs, i))
            .map(|p| p.to_string_lossy().into_owned())
            .unwrap_or_default();
        entries.push(Entry {
            id: de.appid.clone(),
            name,
            comment,
            exec,
            terminal: de.terminal(),
            icon_path,
            path: de.path.to_string_lossy().into_owned(),
        });
    }

    // De-dup by id in walk order *before* sorting: Iter walks the dirs
    // in APPS_SUBDIRS order, which is user-first, so the first
    // occurrence is the highest-priority copy.
    let mut seen = std::collections::HashSet::new();
    entries.retain(|e| seen.insert(e.id.clone()));
    entries.sort_by(|a, b| {
        a.name
            .to_lowercase()
            .cmp(&b.name.to_lowercase())
            .then_with(|| a.id.cmp(&b.id))
    });
    entries
}

/// Desktop entry metadata useful outside the launcher.
#[derive(Clone, PartialEq, Eq)]
pub struct DesktopAppMetadata {
    pub desktop_id: String,
    pub name: String,
    pub icon_path: String,
}

/// Resolve a Wayland app_id / XWayland WM_CLASS against every installed
/// rootfs. Returns the first launchable desktop entry whose id is a
/// plausible match, with its already-resolved PNG icon path.
pub fn resolve_metadata_for_app_id(app_id: &str) -> Option<DesktopAppMetadata> {
    let query = app_id.trim();
    if query.is_empty() {
        return None;
    }
    for rootfs in installed_rootfs_dirs() {
        for entry in scan_entries(&rootfs, false) {
            if desktop_id_matches_app_id(&entry.id, query) {
                return Some(DesktopAppMetadata {
                    desktop_id: entry.id,
                    name: entry.name,
                    icon_path: entry.icon_path,
                });
            }
        }
    }
    None
}

fn installed_rootfs_dirs() -> Vec<PathBuf> {
    let distros = Path::new(&crate::app_paths::get().distros_dir);
    let mut out = Vec::new();
    let Ok(read_dir) = std::fs::read_dir(distros) else {
        return out;
    };
    for entry in read_dir.flatten() {
        let rootfs = entry.path().join("rootfs");
        if rootfs.is_dir() {
            out.push(rootfs);
        }
    }
    out.sort();
    out
}

fn desktop_id_matches_app_id(desktop_id: &str, app_id: &str) -> bool {
    let id = normalize_desktop_id(desktop_id);
    let app = normalize_desktop_id(app_id);
    if id == app {
        return true;
    }

    let id_tail = id.rsplit('.').next().unwrap_or(&id);
    let app_tail = app.rsplit('.').next().unwrap_or(&app);
    id_tail == app || id == app_tail || id_tail == app_tail
}

fn normalize_desktop_id(value: &str) -> String {
    value
        .trim()
        .trim_end_matches(".desktop")
        .to_ascii_lowercase()
}

/// JSON-encode the scan result for the JNI boundary. Each element is an
/// object: `{id, name, comment, exec, terminal, iconPath, path}`. Always
/// returns a valid JSON array (empty `[]` if the rootfs has no apps).
pub fn scan_json(rootfs: &Path) -> String {
    let entries = scan_entries(rootfs, true);
    let arr: Vec<_> = entries
        .iter()
        .map(|e| {
            json!({
                "id": e.id,
                "name": e.name,
                "comment": e.comment,
                "exec": e.exec,
                "terminal": e.terminal,
                "iconPath": e.icon_path,
                "path": e.path,
            })
        })
        .collect();
    serde_json::Value::Array(arr).to_string()
}

/// `Type=Application` and not `Hidden`; `launchable_only` (see
/// `scan_entries`) also requires not `NoDisplay`.
fn is_relevant(de: &DesktopEntry, launchable_only: bool) -> bool {
    de.type_() == Some("Application") && !de.hidden() && (!launchable_only || !de.no_display())
}

/// Find an absolute on-device path for [icon] inside [rootfs], or None.
///
/// Resolution rules:
///   1. Absolute `Icon=/foo/bar.png` → rooted at the rootfs prefix.
///   2. Bare name `Icon=firefox` → searched under
///      `usr/share/icons/<theme>/<size>/apps/<name>.png` and
///      `usr/share/pixmaps/<name>.png`. Themes and sizes are tried in
///      [ICON_THEMES] / [ICON_SIZES] order. Only PNG is returned —
///      Android can't decode SVG/XPM natively, so handing back a path
///      would just produce a broken row.
///   3. `Icon=name.<ext>` with a known image extension → stripped to
///      bare name, then rule 2.
fn resolve_icon(rootfs: &Path, icon: &str) -> Option<PathBuf> {
    let icon = icon.trim();
    if icon.is_empty() {
        return None;
    }
    if let Some(stripped) = icon.strip_prefix('/') {
        let p = rootfs.join(stripped);
        return is_png_file(&p).then_some(p);
    }
    let stem = strip_known_ext(icon);
    let icons_root = rootfs.join("usr/share/icons");
    for theme in ICON_THEMES {
        for size in ICON_SIZES {
            let p = icons_root
                .join(theme)
                .join(size)
                .join("apps")
                .join(format!("{}.png", stem));
            if p.is_file() {
                return Some(p);
            }
        }
    }
    let pixmap = rootfs
        .join("usr/share/pixmaps")
        .join(format!("{}.png", stem));
    if pixmap.is_file() {
        return Some(pixmap);
    }
    None
}

fn is_png_file(p: &Path) -> bool {
    p.is_file()
        && p.extension()
            .and_then(|e| e.to_str())
            .map(|e| e.eq_ignore_ascii_case("png"))
            .unwrap_or(false)
}

/// Strip a `.<known image ext>` suffix (case-insensitive) from [name].
/// `Icon=org.gnome.Files` keeps the dotted appid intact (no stripping —
/// `Files` isn't a known extension); `Icon=firefox.png` becomes `firefox`.
fn strip_known_ext(name: &str) -> &str {
    let lower = name.to_ascii_lowercase();
    for ext in KNOWN_ICON_EXTS {
        if let Some(stem) = lower.strip_suffix(ext).and_then(|s| s.strip_suffix('.')) {
            return &name[..stem.len()];
        }
    }
    name
}

/// Drop `%f`, `%u`, `%F`, `%U`, `%i`, `%c`, `%k`, `%d`, `%D`, `%n`, `%N`,
/// `%v`, `%m` from an Exec line. We don't substitute since the launcher
/// never has URIs / icons / file lists to pass. `%%` collapses to a
/// literal `%`. Trailing whitespace from a stripped trailing field code
/// is trimmed.
fn strip_field_codes(exec: &str) -> String {
    let mut out = String::with_capacity(exec.len());
    let mut chars = exec.chars().peekable();
    while let Some(c) = chars.next() {
        if c != '%' {
            out.push(c);
            continue;
        }
        match chars.next() {
            Some('%') => out.push('%'),
            Some(_) => {} // drop %X
            None => out.push('%'),
        }
    }
    out.split_whitespace().collect::<Vec<_>>().join(" ")
}

/// Locales we want translated `Name=` / `Comment=` for. Best-effort —
/// reads `LANG` / `LC_MESSAGES` from the host process env (the
/// CompositorService starts the JVM, so LANG is what Android set).
/// Empty list = use the default (untranslated) value only.
fn current_locales() -> Vec<String> {
    let mut out = Vec::new();
    for var in &["LC_ALL", "LC_MESSAGES", "LANG"] {
        if let Ok(v) = std::env::var(var) {
            // Strip ".UTF-8" suffix etc. — DesktopEntry::name handles
            // both `en_US` and `en` prefixes via its own fallback.
            let trimmed = v.split('.').next().unwrap_or("").to_string();
            if !trimmed.is_empty() && !out.contains(&trimmed) {
                out.push(trimmed);
            }
            break;
        }
    }
    out
}
