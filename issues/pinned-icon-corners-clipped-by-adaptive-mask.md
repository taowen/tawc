# Pinned home-screen icons get their corners clipped

`EntryShortcuts.pinIconFit` scales an entry icon so its longer edge is
**2/3** of the 108 dp adaptive-icon canvas, centred. But a launcher masks
an adaptive icon to a shape *inscribed* in that central 72 dp region —
for the Pixel launcher, a 72 dp circle. A square icon at 72 dp therefore
has its corners cut: up to ~15 dp off each diagonal.

Icons with transparent margins (most of them) never notice. Full-bleed
artwork does. Observed on the emulator sid install, 2026-09-18: Eye of
GNOME pinned to the home screen shows its light rounded backplate
flat-topped where the circle mask crosses it.

This is pre-existing geometry, not new — it only became visible once
SVG-only icons started resolving at all (they previously fell back to
the TAWC app icon, which is drawn for the mask).

## Why it isn't just "shrink the icon"

Content that can *never* be clipped by a circle mask has to fit the
66 dp safe-zone circle, so a square may be at most ~47 dp — 0.43 of the
canvas against today's 0.67. Every well-behaved icon would get visibly
smaller to protect the few that are full-bleed.

Worth measuring before choosing: something like 0.58 probably shaves
much less off full-bleed art while staying close to the current size.
Whatever number wins, `EntryShortcutsTest` covers `pinIconFit`, so the
change is cheap to make safely.

Same geometry is used for the pin fallback glyphs, but those are drawn
with their own inset and are unaffected.
