# Xwayland share.tar ships Compose files that include a build-machine path

`assets/xwayland/share.tar` carries libX11's locale data, and the
per-locale `Compose` files that defer to en_US do it by absolute path
into the build tree:

    $ tar -xOf share.tar share/X11/locale/cs_CZ.UTF-8/Compose | sed -n 7p
    include "/home/<builder>/…/build/xwayland-aarch64/install/share/X11/locale/en_US.UTF-8/Compose"

Seen in the v2 release APK (am_ET, cs_CZ, fi_FI, ja_JP, km_KH, … —
every locale whose Compose is an `include` stub). libX11 generates these
from `--prefix`, which `scripts/build-xwayland.sh` sets to the host
`$OUT_DIR/install`.

That path never exists on a device, so any reader of those files gets a
failed include. Impact is unverified and probably nil: the bundled
libX11 only serves Xwayland and xkbcomp, which do not read Compose
tables; X clients run inside the rootfs with the distro's own libX11
and locale data. If that holds, `share/X11/locale` is dead weight in the
tar and the fix is to stop packing it (`packXwaylandShare` in
`app/build.gradle.kts` packs all of `share/X11`) — check what Xwayland
actually opens under `share/X11` before pruning (`share/X11/xkb` is
needed).

Either way the leak matters for reproducible builds: the file content
depends on where the tree was built. See
[plans/reproducible-builds.md](../plans/reproducible-builds.md).
