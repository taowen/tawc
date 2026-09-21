# No wake lock is ever held while a rootfs session runs

The app declares no `WAKE_LOCK` permission and never acquires one —
`AndroidManifest.xml` has zero `WAKE_LOCK` entries, and `dumpsys power`
shows no TAWC-owned wakelock (only transient
`NotificationManagerService:post:me.phie.tawc` ones owned by uid 1000).

The only thing keeping the device awake during terminal work is
`TerminalActivity`'s `keepScreenOn = true` (TerminalActivity.kt:146),
which applies solely while the terminal is the visible activity — i.e.
it stops exactly when the user leaves the app, which is when the
reported failures happen.

## Suspected consequence — NOT verified

With the screen off and no charger the SoC suspends and every guest
process stops mid-syscall, so long transfers die from server-side
timeouts even during windows where the Doze firewall
([rootfs-network-cut-when-app-backgrounded.md](rootfs-network-cut-when-app-backgrounded.md))
is not blocking them.

This could not be exercised on the wired test target: a USB-attached
adb session holds the device awake, so the kernel never suspends.
Verifying needs an unplugged run over wireless adb, or suspend counters
(`/sys/kernel/debug/suspend_stats`, `dumpsys power | grep -i suspend`)
sampled before and after a screen-off window.

## Fix sketch

Take a `PARTIAL_WAKE_LOCK` for the lifetime of a live rootfs session,
paired with the foreground service that issue proposes. It should
probably be user-visible and optional — holding the CPU awake for an
idle shell is a real battery cost, so scoping it to sessions with a
running foreground job, or to an explicit "keep running in background"
toggle, is worth considering.

Found 2026-08-11 investigating a user report of dropped connections
when backgrounding.

Plan: [plans/wakelock.md](../plans/wakelock.md).
