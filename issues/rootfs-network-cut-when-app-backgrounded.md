# Rootfs loses all network ~1 min after the app is backgrounded

A long network job started from the in-app terminal (`pacman -Syu`,
`apt upgrade`, `git clone`, plain `curl`) dies if the user leaves the
app. Errors are DNS-shaped — `could not resolve host`, curl exit 6.
It does not reproduce while watching the terminal.

## Cause

`TerminalActivity` runs with **no foreground service** (a deliberate
choice recorded in notes/terminal.md; the compositor's FGS is only
started by `CompositorActivity` and `UserRootfsSession`). Backgrounding
the app therefore drops the uid to a cached procstate, and ~1 minute
after screen-off the device enters *light* Doze, where netd's
`fw_dozable` chain blocks all traffic for non-allowlisted uids. Guest
processes run as the app uid, so the whole rootfs loses network at once.

## Measured (physical target: OnePlus 9, Android 14 / SDK 34)

- Terminal open with a job running: `dumpsys activity services
  me.phie.tawc` lists **no services at all**.
- HOME → `procState=LAST`, `/proc/<app>/oom_score_adj` = **700**.
- Light doze, screen off: the first curl failure lands in the same
  second as `Firewall chain dozable state: true`, and `dumpsys
  netpolicy` reports
  `blocked=DOZE|RESTRICTED_MODE, allowed=RESTRICTED_MODE_PERMISSIONS, effective=DOZE`.
- Identical run with the compositor FGS up: `procState=FGS`,
  `allowed=FOREGROUND…`, `effective=NONE` — traffic survives both light
  and deep Doze. The FGS is the whole difference.

## Why `ping` looks fine

ICMP is blocked too (`ping: sendmsg: Operation not permitted`, 109
blocked sends in one 35 s deep-idle window), but ping *resumes silently*
when you come back — `icmp_seq` just continues, and the error lines
scroll away. Only jobs that abort on failure (package downloads, TLS
sessions) leave visible damage. On top of that `TerminalActivity` sets
`keepScreenOn = true`, so while the terminal is the visible activity the
screen never sleeps and Doze never starts: the bug is *unreproducible
while you watch it*.

## Same root cause, second symptom

At adj 700 the app is a prime LMK target, and the entire guest tree
lives in the app's process cgroup — an LMK kill, a force-stop, or a
swipe from recents takes every guest process with it. Confirmed: `am
force-stop` killed a `setsid`'d, init-reparented rootfs process, so
neither detaching nor a new session escapes it.

## Fix sketch

Keep a foreground service alive whenever *any* rootfs session exists,
not only when the compositor runs — either have `TerminalSessions`
start/stop a session FGS around live sessions, or add a generic
rootfs-session service that any live guest process anchors to.
notes/terminal.md says "promote to a service only if that becomes a
real complaint"; this is that complaint. Prefer the `specialUse` type
already used by the compositor — `dataSync` foreground services are
subject to a daily time limit on recent Android versions.

Note an FGS is necessary but not always sufficient: a RESTRICTED
standby bucket, user "restrict battery usage", or an aggressive OEM ROM
can still cut the uid. Worth also pointing users at
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — this app is not on the
device-idle allowlist.

Found 2026-08-11 investigating a user report of dropped connections
when backgrounding.

Plan: [plans/session-service.md](../plans/session-service.md).
