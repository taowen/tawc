# Launcher icons: rasterize SVG, walk themes properly, neutral fallback

Apps that ship no PNG icon show the TAWC logo in the launcher, the
recents card and home-screen pins. On Debian sid that is already common
apps, and the launcher reads as broken.

## Problem

`resolve_icon` (`compositor/src/launcher.rs`) returns `.png` paths only,
from a fixed `<theme>/<NxN>/apps/<name>.png` grid over
`Adwaita, Papirus, breeze, hicolor`, then `usr/share/pixmaps`. Anything
else yields an empty `iconPath`, and each consumer substitutes the app
logo.

Measured on the emulator (`sid`, tawcroot, 2026-09-18):

| entry | `Icon=` | what exists in the rootfs |
| --- | --- | --- |
| `org.gnome.eog.desktop` | `org.gnome.eog` | `hicolor/scalable/apps/org.gnome.eog.svg` (+ `symbolic/`) |
| `org.gtk.Demo4.desktop` | `org.gtk.Demo4` | `hicolor/scalable/apps/org.gtk.Demo4.svg` (+ `symbolic/`) |
| `org.kde.konsole.desktop` | `utilities-terminal` | `Adwaita/symbolic/legacy/utilities-terminal-symbolic.svg` only |

- `usr/share/icons` holds only `Adwaita`, `default`, `hicolor`; 368 PNG
  vs 741 SVG. Adwaita ships **no PNG above 16x16** (`16x16`, `scalable`,
  `symbolic` only), so the `Adwaita` row of the current grid can never
  match on sid.
- `Adwaita/index.theme` has `Inherits=AdwaitaLegacy,hicolor`;
  `default/index.theme` has `Inherits=Adwaita`. `AdwaitaLegacy` is not
  installed.
- No `.svgz` anywhere; two `.xpm` in pixmaps (python, `NoDisplay`). XPM
  stays out of scope.

Three consumers decode `iconPath`, all with `BitmapFactory`:
`IconLoader.decode` (launcher rows, and `EntryShortcuts.pinBitmap` via
the same function) and `CompositorActivity.decodeTaskIcon` (recents).

## Decision: rasterize in Rust, keep `iconPath` PNG-only

Use `resvg` in the scanner and hand Kotlin a cached PNG. Rejected:
AndroidSVG in Kotlin — it makes `iconPath` multi-format, so all three
decoders above need a format switch and their `inSampleSize` bounding
logic stops applying. With a PNG cache the Kotlin side does not change
at all, and the JSON contract (`iconPath` = decodable PNG or empty)
holds.

Cost is a new Rust dep tree. Keep it small:
`resvg = { version = "<current>", default-features = false }` — drops
`text`/`system-fonts` (fontdb, rustybuzz; app icons don't use `<text>`,
and there are no fonts to find on Android anyway) and `raster-images`.
Confirm PNG *encoding* still works with defaults off (tiny-skia's
`png-format`); if not, enable just that. Check the version's license
before adding (recent resvg is `MIT OR Apache-2.0`; old releases were
MPL-2.0).

**Gate:** record `libcompositor.so` size (arm64 release, stripped)
before and after. If growth is over ~1.5 MiB, stop and reconsider
before going further.

## Step 1 — make icon resolution lazy (prerequisite)

Today `scan_entries` resolves an icon for *every* entry, and
`resolve_metadata_for_app_id` calls it for every rootfs from the
compositor thread (`Compositor::resolve_cached_app_metadata`,
cached per app_id). That is already a stat storm on the compositor
thread; once resolution can rasterize, the first window map would
render every SVG-only icon in the rootfs there.

- `Entry` keeps the raw `Icon=` value (`icon: String`) instead of
  `icon_path`.
- `scan_json` resolves all entries (it runs on `Dispatchers.IO`).
- `resolve_metadata_for_app_id` matches by id first, then resolves the
  one winning entry's icon. One rasterize at most, a few ms, once per
  app_id.

## Step 2 — spec-shaped theme lookup

Replace the fixed grid with a small walker. Do **not** implement the
full fdo size-matching algorithm; we want "largest sensible raster,
else scalable".

- Theme order: start from `ICON_THEMES`, and for each theme that exists
  append its `index.theme` `Inherits=` (recursively, de-duplicated,
  missing themes skipped), always ending in `hicolor`. Add `default` to
  the front so a distro/user-selected theme wins. Parse `index.theme`
  with a minimal line scan for `Inherits=` — do not pull a theme crate.
  Memoize the resolved theme list per `scan_*` call.
- Per theme, candidate dirs for context `apps` in both layouts:
  `<size>/apps` (hicolor, Adwaita) and `apps/<size>` (breeze), where
  `<size>` ∈ `ICON_SIZES` plus `scalable`. Order: PNG sizes as today
  (`128, 96, 256, 64, 48`), then `scalable/*.svg`, then small PNGs
  (`32, 24, 22, 16`) — an SVG beats a 16 px PNG upscaled to a 56 dp
  row.
- Generic names (`utilities-terminal`, `system-file-manager`, …) live
  outside `apps` in some themes; after `apps`, try `legacy` and
  `categories` with the same size order.
- `usr/share/pixmaps/<name>.{png,svg}` last, PNG first.
- Absolute `Icon=/path`: accept `.png` directly and `.svg`/`.svgz` via
  step 3. Keep rooting at the rootfs, and reject paths that escape it
  after `..` normalization (the value is guest-controlled and we now
  *parse* the file, not just hand its path on).
- Symbolic icons are **not** a candidate here — see step 5.

Resolution returns an enum `{ Png(PathBuf), Svg(PathBuf) }`; only the
`Svg` arm goes through the cache.

## Step 3 — SVG → PNG cache

- Location: `<distros>/<id>/icon-cache/`, i.e. `rootfs.parent()` —
  a sibling of the existing `ando/` and `tawcroot/` dirs. Both call
  paths already hold the rootfs, so nothing new crosses JNI or
  `app_paths` (and `nativeLauncherScan` can run before
  `app_paths::init_from_env`, so it must not depend on it). Uninstall
  removes it with the install; keys can't collide across installs.
- Key: hash of `(rootfs-relative source path, mtime, len, RENDER_PX,
  CACHE_FORMAT_VERSION)` → `<hex>.png`. A package upgrade changes
  mtime/len and produces a new file.
- Render: `RENDER_PX = 192` square (covers the ~56 dp row at 3×, the
  recents icon and the 2/3-safe-zone pin bitmap), aspect-preserving,
  centred, transparent background. `usvg` handles `.svgz` itself.
- Write to `<hex>.png.tmp` then `rename` — two scans can race
  (launcher + shortcut trampoline).
- Guard rails, since the input is guest-controlled: skip sources over
  1 MiB, and wrap parse+render in `catch_unwind`. Any failure → `None`
  (fallback glyph), plus a zero-length `<hex>.fail` marker so a bad SVG
  is not re-parsed on every scan. No logging per icon; one `warn!` per
  scan with the failure count at most.
- Pruning: at the end of `scan_json` (not the metadata path), delete
  cache files not referenced by this scan. Skip when the scan returned
  no entries, so a transiently unreadable rootfs doesn't wipe it.
- chroot installs: the rootfs is uid-0-owned and the scan may not even
  enumerate it (notes/launcher.md "Access model"). Unchanged by this
  work; the cache dir itself is app-owned, so if a scan does work the
  cached PNGs are *more* readable than today's in-rootfs paths.

Cold-scan cost is the main risk: a full desktop install could have
tens of SVG-only entries. Measure the first `launcher-list` on sid with
`tg` (timegap skill). If the cold scan exceeds ~1 s, rasterize in
parallel with `std::thread::scope` over the `Svg` entries before
considering anything lazier.

## Step 4 — neutral fallback glyph

Independent of steps 1–3 and can land first.

- Add `ic_app_fallback.xml`: a generic application glyph (plain
  rounded-square "window" shape), tinted like `ic_terminal_fallback`.
- `LauncherActivity` (~line 444): non-terminal fallback becomes
  `ic_app_fallback` instead of `ic_tawc_logo`.
- `EntryShortcuts`: no/undecodable icon currently falls back to the
  TAWC app icon. Draw the fallback glyph through the same
  `pinIconFit` neutral-square path instead, so a pinned icon-less app
  is not visually identical to TAWC itself.
- Recents (`CompositorActivity.setTaskMetadata`): leave `null` → app
  icon. A TAWC-branded recents card is accurate, not misleading.
- Do not touch `app/icon.svg` or the generated mark.

## Step 5 — symbolic last resort (optional, do last)

Konsole on sid has *only* `utilities-terminal-symbolic.svg`. After
steps 2–3 it gets the neutral glyph, which already fixes "looks
broken". A real icon needs symbolic support:

- Last candidate in the walk: `<theme>/symbolic/{apps,legacy,
  categories}/<name>-symbolic.svg`.
- Symbolic SVGs are single-colour near-black and vanish on a dark
  background, and the cached PNG can't follow the app theme. Render
  them recoloured to a light foreground on a baked neutral rounded
  tile (same idea as the pin icon's neutral square), at 60 % glyph
  scale. Recolour by string-replacing the fill in the SVG source is
  fragile; prefer rendering to an alpha mask with resvg and compositing
  that mask in the foreground colour with tiny-skia.
- Cache key gains a `symbolic` bit.

Skip this step if it turns out fiddly; file the Konsole case as its own
issue instead. Installing `breeze-icon-theme` also fixes Konsole, which
is a packaging answer rather than ours.

## Tests

`launcher.rs` has no unit tests because the compositor crate only
builds for Android (`ndk-sys`). Don't fight that here; test through the
existing surfaces.

Integration (`tests/integration/tests/launcher.rs`, via `launcher-list`
and the existing `plant_desktop` helper — extend it with an `Icon=`
argument):

- Plant a `.desktop` + a tiny SVG under
  `usr/share/icons/hicolor/scalable/apps/`; assert `iconPath` is
  non-empty, ends in `.png`, lives under `icon-cache/`, and the file
  starts with the PNG magic.
- Second `launcher-list`: same `iconPath`, file mtime unchanged (cache
  hit).
- Rewrite the SVG (different size): `iconPath` changes and the old
  cache file is pruned.
- Malformed SVG: `iconPath` empty, scan still returns every other
  entry, `.fail` marker present.
- `Inherits=`: plant a theme `tawc-test-parent` with the icon and make
  a planted theme that inherits it reachable; assert it resolves. Also
  one `apps/<size>/` (breeze-layout) PNG case.
- Absolute `Icon=/../../escape.svg` → empty.
- Clean up planted themes/icons in the fixture's `Drop`, like the
  existing `.desktop` cleanup.

Kotlin unit tests: none needed unless step 4 changes `EntryShortcuts`
geometry — then extend `EntryShortcutsTest`.

## Verification (`.tawctarget`, currently the emulator sid install)

1. `scripts/run-integration-tests.sh launcher`.
2. Open the launcher: Eye of GNOME and GTK Demo show their real icons;
   Konsole shows the neutral glyph (or a tiled symbolic terminal after
   step 5); Firefox/LibreOffice unchanged. Screenshot to
   `/data/local/tmp/tawc-dev/`, review with a sub-agent, delete both
   copies.
3. Launch eog: recents card shows its icon.
4. Pin eog to the home screen: icon correct inside the adaptive mask.
5. Cold vs warm `launcher-list` timing recorded in the commit message.
6. If a phone with an Arch install is handy, spot-check that nothing
   regressed there (Arch ships more PNGs, so the PNG-first order
   matters).

## Docs and housekeeping in the same change

- `notes/launcher.md` "Icon resolution": rewrite for the new order,
  the cache, and the fallback; drop the "defer until users ask" and
  "Inherits isn't parsed" paragraphs. Update the module doc comment in
  `launcher.rs` and the `ICON_THEMES`/`Entry` comments to match.
- `notes/task-icons-window-index.md`: drop "SVG-only icons can stay
  out of scope".
- `notes/building.md`: mention the new crate only if it changes host
  requirements (it shouldn't — pure Rust).
- `notes/licensing.md` crate count, and re-run
  `scripts/gen-third-party-licenses.sh`; commit the regenerated
  `licenses.json`.
- Delete this plan when done.

## Suggested commit split

1. Neutral fallback glyph (step 4).
2. Lazy icon resolution (step 1) — no behaviour change.
3. Theme walker (step 2) — PNG-only still, fixes breeze layout/Inherits.
4. resvg + cache (step 3) + tests + notes + licenses.
5. Symbolic tiles (step 5), if done.
