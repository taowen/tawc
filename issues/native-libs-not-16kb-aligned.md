# Native libs aren't 16 KB-page aligned

On a Pixel 9 Pro (Android 16) the first launch of a debug build shows
Android's "App Compatibility" dialog listing APK libs whose ELF
segments aren't 16 KB-aligned (libando.so, libwayland-server.so,
libxcb-xinput.so, …). 4 KB-page devices still run them; 16 KB-page
devices (Pixel dev option, future hardware) would fail to load them.

Not checked: which of our native builds (NDK cargo, meson/autotools
deps, ando) lack `-Wl,-z,max-page-size=16384`, and whether the release
APK shows the dialog too.
