# Entry editor: icon picker, import, preview

Not started.

## Goal

The Icon field in `DesktopFileEditorActivity` today is a bare text box:
no preview, no way to discover names. Make it:

```
Icon
[preview]  [ text field ......................... ✕ ]
           [ Select ]  [ Load ]
```

- **Preview** shows what the launcher grid will draw for the current
  value. When the value is empty or can't be resolved, it shows the same
  fallback glyph as the grid (`ic_terminal_fallback` if Terminal is
  checked, `ic_app_fallback` otherwise). It updates on every edit
  (debounced) and when the Terminal checkbox changes.
- **✕** clears the field. It's shown only when the field has text. Use
  Material `TextInputLayout` with `END_ICON_CLEAR_TEXT` on this one field
  (Material is already a dependency).
- **Select** opens a searchable grid of every icon name in the distro.
  Tapping one writes its name into the field.
- **Load** opens the Android image picker and copies the chosen image
  into the distro as a TAWC-owned icon. Its name goes into the field,
  and it appears in Select afterwards.
- Typing a name or an in-distro absolute path keeps working, as it does
  today. There is no in-distro file browser.

## Step 0: find out why a bare name failed

The user reported that typing a name didn't work. Reproduce this first
to confirm the resolver changes below cover it. These
are the candidate causes in `launcher.rs`; each name is valid for guest
toolkits but missed by our walk:

- The icon is in a context we don't search. We only search
  `apps`/`legacy`/`categories`; `places`, `devices`, `status`,
  `mimetypes` and `actions` are skipped.
- The icon is in a size dir that isn't in `ICON_SIZES`: `512x512`,
  `1024x1024`, `36`, `72`, `192`, `@2` dirs. Electron and flatpak apps
  often ship only 512 px.
- The icon is in a theme outside the seed list and its `Inherits=`
  chain.
- The icon is in `~/.local/share/icons`, which isn't searched.
- The source is XPM only.

Without a preview there was no feedback either way, which is the other
half of the problem.

## Resolver changes (Rust, `launcher.rs`)

1. **User icon base dir.** Search `root/.local/share/icons` before
   `usr/share/icons`, as the spec's `$XDG_DATA_HOME/icons` requires. A
   theme can span base dirs, so `ThemeDir` holds a list of roots in base
   order (user, then system), with subdir names merged. Theme order
   resolution reads `index.theme` from whichever base has it.
2. **Unlisted sizes as a last tier.** After `ICON_SIZES`, try any other
   numeric `NxN`/`N` size dirs the theme has, largest first, before the
   symbolic fallback. This keeps the current preference order and stops
   512-only icons from disappearing.
3. **All contexts.** Select shows every icon in the distro, so the
   resolver must find all of them too. Keep `apps`/`legacy`/`categories`
   as the preferred tier, then search every other context the theme
   has (`places`, `devices`, `status`, `mimetypes`, `actions`, `emotes`,
   … — whatever subdirs exist, not a fixed list) before falling back to
   the next theme. An app's own icon still wins over a same-named
   generic one, since the preferred tier comes first.
4. **One walk, two consumers.** Split the walk into a "visit candidate
   sources in resolver order" iterator. `resolve` takes the first hit;
   the listing collects every name. Then everything Select lists is
   guaranteed to resolve to the same file the launcher would use.
   Integration tests check this invariant.

New JNI on `NativeBridge`, both run on `Dispatchers.IO`:

- `nativeResolveIcon(rootfs, value): String` returns `resolve_string`
  for one value: a PNG path, or empty. Used for the preview and for each
  picker cell. It goes through the same SVG cache as the launcher.
- `nativeListIcons(rootfs): String` returns a JSON array of
  `{name, user}`, sorted, with **one entry per name**: the same name in
  several themes, sizes, contexts or formats is one cell, showing
  whichever file the resolver would pick. Sources are themes
  (both bases), `pixmaps` and symbolic-only names (with the `-symbolic`
  suffix stripped, matching how `resolve` would find them). `user` marks
  names from the user base, which the grid shows first. The list holds
  names only: Papirus alone has thousands of icons, and rasterizing them
  all up front would take seconds. Cells resolve lazily.

**Cache.** Picker cells use the shared `icon-cache/`. The next launcher
scan's `prune` removes renders it didn't reference, so reopening the
picker re-rasterizes the visible cells (~8 ms each, off the main
thread). Nothing races: `AppsPane` scans on resume, and it isn't resumed
while the editor or picker is in front. If that churn is ever
noticeable, the fix is a `picker/` subdir, which `prune` already skips
because it only deletes files.

## Select: `IconPickerActivity`

Started for a result from the editor with `EXTRA_ID`, and returns the
chosen name.

- Child-screen scaffold, with a search field pinned at the top, the same
  pill idiom as `AppsPane`.
- A `RecyclerView` grid with `AppsPane`'s column math. Each cell shows
  the icon and its name (one line, end-ellipsized). Each cell calls
  `nativeResolveIcon(name)` on IO, then `IconLoader` decodes the PNG
  through its LRU cache. Bind/recycle must cancel or ignore stale loads.
- Filtering is a case-insensitive substring match on the name. Imported
  (`user`) icons sort first when there's no query.
- Loading text while `nativeListIcons` runs, and an empty state when
  nothing matches.

## Load: import an image

`ActivityResultContracts.OpenDocument` with `image/*`, then:

- **Raster** (PNG, JPEG, WebP, GIF, HEIF, anything `ImageDecoder`
  takes): decode, scale down to fit 256×256 keeping the aspect ratio
  (never upscale), and write a PNG to
  `root/.local/share/icons/hicolor/256x256/apps/tawc-<slug>.png`.
  Re-encoding matters, because the resolver only returns PNGs. Writing
  into the `256x256` dir means a smaller image is filed under the wrong
  size. Hicolor tolerates that, and our resolver ignores sizes beyond
  directory order.
- **SVG** (`image/svg+xml`, or a `.svg` display name): copy the bytes
  unchanged to `…/hicolor/scalable/apps/tawc-<slug>.svg`. Refuse files
  over 1 MiB, the same cap as `icon_cache::MAX_SOURCE_BYTES`, since a
  larger one would never render.
- **Name**: the `tawc-` prefix keeps imports clear of anything a package
  installs. The slug comes from the document's display name minus its
  extension (`slugifyLabel`-style). On collision, add a `-2`/`-3` suffix
  across both extensions. If the bytes are identical to an existing
  import, reuse that name instead of adding a copy.
- The field gets `tawc-<slug>`, a plain theme name, so guest desktops
  that read the same `.desktop` file find it too. The import lives in
  the rootfs, so it travels with exports, like the entries do.
- Write atomically (`atomicWriteText`'s tmp+rename pattern, in a byte
  version). If decoding fails, show a toast and leave the field
  untouched.
- Nothing ever deletes an import. Pruning unused `tawc-*` icons is out
  of scope; they're small.

## Code layout

- `launcher/IconPickerActivity.kt` (new) and its manifest entry.
- `launcher/IconImport.kt` (new): slug, collision and dedupe logic, pure
  and JVM-unit-tested, plus the decode/scale/write wrapper.
- `DesktopFileEditorActivity`: the icon row (preview, `TextInputLayout`,
  buttons), two result launchers, and a debounced preview resolve.
- `launcher.rs`: base dirs, size tier, candidate iterator,
  `list_icons_json`; JNI in `lib.rs`.
- Debug broker actions, as the query surface for tests:
  `launcher-icons` (the list) and `launcher-resolve-icon` (one value →
  path).
- Update notes/launcher.md ("Managed dir + .desktop editor" and "Icon
  resolution") and notes/exec-broker.md.

## Tests

- JVM: slug/collision/dedupe (`IconImportTest`).
- Integration (`tests/integration/tests/launcher.rs`), using fixtures
  written into the rootfs:
  - A user-base hicolor PNG resolves, and wins over the system base.
  - A 512-only icon resolves.
  - An icon only in a non-app context (say `places`) resolves and is
    listed once, and an app icon beats a same-named `mimetypes` one.
  - A name in two themes is listed once.
  - An absolute path resolves.
  - Every name `launcher-icons` returns resolves to a non-empty PNG
    through `launcher-resolve-icon`. Sample this on a real sid install
    rather than checking thousands.
- Manual on `.tawctarget`: Select, then search, then tap, then the
  preview updates. Load a PNG, a JPEG and an SVG, and confirm each shows
  in the preview, in Select, and in the launcher grid after saving.
  Clear brings back the fallback glyph, and toggling Terminal swaps it.

## Open questions

- Group the picker into "Imported" and "Distro" sections, or just sort
  imports first? Start with sorting.
