# Each Xwayland run leaks two sockets in the app process

Found 2026-09-20 on the physical target while checking the lazy
compositor for per-cycle leaks. With the compositor pinned
(`compositor-hold hold=on`), each "X11 client connects → Xwayland spawns →
client exits → Xwayland idle-exits after 5 s" cycle leaves
`/proc/<app pid>/fd` two `socket:` entries bigger (28 → 28 → 30 → 32 over
three `timeout 2 xclock` runs; the first cycle was flat). Nothing else
grows: no threads, no other fd types.

It is per *Xwayland* run, not per compositor run — Wayland-only compositor
start/stop cycles are flat (`lazy_compositor::test_compositor_cycles_do_not_leak`),
and the leak was already reachable before the compositor became
socket-activated, since Xwayland has cycled inside a long-lived compositor
all along. It does survive compositor stop, so lazy stop does not clean it
up either.

Unknown which sockets. Candidates: the `X11Wm` connection / x11rb event
thread's stream, the wl or WM socketpair halves created in
`XWayland::spawn_with_activation`, or the Xwayland `Client` staying in the
`Display`. The cleanup path also logs `calloop::loop_logic: Failed to
unregister source from the polling system: NotFound` on every Xwayland
exit, which may be the same object being torn down out of order.

Low priority: two fds per X11 session, bounded by the process lifetime.
An X11 variant of the cycle test should be added once fixed.
