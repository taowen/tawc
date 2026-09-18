# SVG-only app icons fall back to the TAWC logo

`resolve_icon` (`compositor/src/launcher.rs`) only ever returns `.png`
paths — `BitmapFactory` can't decode SVG/XPM, so handing Kotlin an SVG
path would just produce a broken row. Apps that ship no PNG anywhere
therefore get an empty `iconPath`, and `LauncherActivity` renders the
`ic_tawc_logo` fallback. That is by design (notes/launcher.md "Icon
resolution"), but on Debian sid it now hits common apps: the launcher
shows several identical TAWC logos in a row, which reads as broken
rather than as "no icon shipped".

Observed on the emulator (sid, tawcroot) — Eye of GNOME, GTK Demo and
Konsole all show the app logo while Firefox and LibreOffice show real
icons:

| entry | `Icon=` | what exists in the rootfs |
| --- | --- | --- |
| `org.gnome.eog.desktop` | `org.gnome.eog` | `hicolor/scalable/apps/org.gnome.eog.svg` only |
| `org.gtk.Demo4.desktop` | `org.gtk.Demo4` | `hicolor/scalable/apps/org.gtk.Demo4.svg` only |
| `org.kde.konsole.desktop` | `utilities-terminal` | `Adwaita/symbolic/legacy/utilities-terminal-symbolic.svg` only |

Konsole is the worst case: it uses a generic theme name, and the only
thing in the rootfs that could satisfy it is a *symbolic* SVG under a
`legacy/` dir — wrong name, wrong directory, wrong format. Konsole's
own breeze icons aren't installed at all; `usr/share/icons` holds only
`Adwaita`, `default` and `hicolor`, so the `breeze` entry in
`ICON_THEMES` never matches on this distro.

The trend is against us: that rootfs has 368 PNGs vs 741 SVGs under
`usr/share/icons`, and GNOME/Adwaita keep dropping PNG sizes.

(Note `gtk3-demo.desktop` *does* ship hicolor PNGs, but it is
`NoDisplay=true` so it never reaches the launcher list. The visible
"GTK Demo" row is the GTK4 one.)

## Options, none tried

- Rasterize SVG in the scanner with `resvg` — correct and cacheable
  (write the PNG into an app-private icon cache keyed by source path +
  mtime), but a heavy new Rust dep for the compositor.
- Render in Kotlin via the AndroidSVG jar — smaller, but means
  `iconPath` stops being PNG-only, so `IconLoader.decode` and
  `EntryShortcuts`' pin-icon path both need a format switch.
- Cheap partial win, independent of the above: `resolve_icon` misses
  layouts it should already handle — the `<theme>/apps/<size>/` order
  breeze uses, the `default` theme, and `index.theme` `Inherits=`
  chains. Worth doing regardless, but it would not have helped any of
  the three entries above, since no PNG exists for them anywhere.

Whatever we pick, the fallback should probably also stop being the
TAWC logo — a neutral generic-application glyph would not imply the
app failed to load.

Found while investigating why eog and konsole showed no icon on the
emulator.
