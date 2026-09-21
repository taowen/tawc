# Android's phantom process killer SIGKILLs rootfs processes past 32

A parallel workload in a rootfs (`make -j`, package-manager hooks, a
build) loses processes to an unexplained SIGKILL, or the whole terminal
session vanishes. Independent of the foreground-service problem in
[rootfs-network-cut-when-app-backgrounded.md](rootfs-network-cut-when-app-backgrounded.md).

## Cause

Android 12+ tracks processes forked by apps ("phantom processes") and
trims them down to `max_phantom_processes` — **32 on the target, and the
cap is global across all apps**. tawcroot guests are ordinary children
of the app process, so every guest process counts against it.

## Measured (physical target: OnePlus 9, Android 14 / SDK 34)

Spawning 45 CPU-active children in the arch rootfs produced 55
kills:

```
ActivityManager: Killing PhantomProcessRecord {…:31741:bash/u0a250}: Trimming phantom processes
```

The session was wiped entirely. Two details worth keeping:

- The **first** victim was pid 31819 — the bash of an *unrelated* open
  terminal tab. A heavy job in one tab kills sessions in another.
- It also killed a `su` process belonging to another app (u0a247),
  confirming the cap is system-wide, not per-app.

## Why it is intermittent

AMS only discovers phantom processes when it samples `/proc` via
`ProcessCpuTracker`, and only processes with measurable CPU are seen:
60 idle `sleep` children were never tracked at all, and in a later run
45 CPU-active children stayed untracked — and unkilled — for over a
minute with `dumpsys activity processes` reporting zero
`PhantomProcessRecord`s. So the sweep fires unpredictably, which matches
"the upgrade fails *sometimes*". The exact trigger was not pinned down;
`dumpsys cpuinfo` did not force it.

## Mitigation

There is no in-app fix — the app cannot raise its own cap. Options are
to document it, keep guest process counts modest, and detect the kills
(guest children dying on SIGKILL with no exit path) so the user gets a
real message instead of a silent failure.

The user-side escape hatches, for the docs:

```
adb shell settings put global settings_enable_monitor_phantom_procs false
adb shell device_config set_sync_disabled_for_tests persistent
adb shell device_config put activity_manager max_phantom_processes 2147483647
```

Which of these sticks varies by Android version, and the `device_config`
ones are reset by a config sync or reboot; neither was verified on the
target.

Found 2026-08-11 investigating a user report of dropped connections
when backgrounding.

An FGS does not help here; see [plans/session-service.md](../plans/session-service.md) for what it does fix.
