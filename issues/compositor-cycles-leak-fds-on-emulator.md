# Compositor start/stop cycles leak fds on the emulator

Found 2026-10-07 on the x86_64 emulator (fresh Arch x86_64 install, CPU
backend): `lazy_compositor::test_compositor_cycles_do_not_leak` fails
deterministically with `fds grew over 8 compositor cycles: 157 -> 171`.
Same numbers on 6546dbf (before the apps-tab-home change), so it is not
from that work. Threads stay flat.

Not yet looked at which fd types grow (`ls -l /proc/<app pid>/fd`
before/after a few cycles). Compare with the physical target, where the
Wayland-only cycle was flat as of
[xwayland-cycle-leaks-two-sockets.md](xwayland-cycle-leaks-two-sockets.md);
the emulator-only GL path may be involved.
