# gfxstream vkcube test fails on Pixel 9 Pro

`gfxstream::test_vkcube_renders_via_ahb` fails on a Pixel 9 Pro
(caiman, Android 16): vkcube exits before rendering. Reproduces on
`main` (2026-10-07) and with the vsync frame clock, so it is not a
frame-clock regression. gfxstream is experimental on physical devices;
needs a look at vkcube's stderr under gfxstream there.
