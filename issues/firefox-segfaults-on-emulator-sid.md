# Firefox segfaults at startup in the emulator's Debian sid rootfs

Seen 2026-09-20 on the x86_64 emulator (tawcroot, install id `sid`):
`firefox --no-remote --version` and `/usr/lib/firefox/firefox-bin --version`
both exit 139 (SIGSEGV) before touching any display, so
`apps::test_firefox_launches` fails in ~0.3 s with "Firefox crashed/exited
before first paint". The other 125 integration tests pass.

Not compositor-related (`--version` never connects to Wayland). Likely a
sid package update or a tawcroot syscall/seccomp interaction during early
startup. Not checked: Arch rootfs, physical phone, proot/chroot, a core
dump or `strace`.
