# Wakelock toggle

Depends on [session-service.md](session-service.md). Background:
[issues/no-wakelock-for-rootfs-sessions.md](../issues/no-wakelock-for-rootfs-sessions.md).

## Problem

The session service keeps the process alive and un-Dozed, but it does not
keep the CPU awake. Screen off and unplugged, the SoC suspends and every
guest stops mid-syscall; long builds stall and transfers die on
server-side timeouts. The app has no `WAKE_LOCK` permission and takes no
lock. Only `TerminalActivity`'s `keepScreenOn` helps, and only while the
terminal is the visible activity.

**Not yet verified** — a USB-attached device never suspends. Step 1 below
is to confirm it before building anything.

## Design

A manual toggle, Termux-style. Off by default: holding the CPU awake for an
idle shell is a real battery cost, and only the user knows whether the job
matters. No automatic "hold while a foreground job runs" heuristic.

- Manifest: `android.permission.WAKE_LOCK`.
- `SessionService` owns a `PowerManager.PARTIAL_WAKE_LOCK`
  (`tawc:session`) and a `WifiManager` lock (`WIFI_MODE_FULL_LOW_LATENCY`
  on API 29+; `FULL_HIGH_PERF` is deprecated), both non-reference-counted.
- Notification gains a second action: "Keep awake" / "Release wakelock".
  While held the text says so, e.g. "2 terminals · awake", keeping the
  "notification explains why" rule from the session-service plan.
- Released on toggle-off, notification Exit, and service stop (last hold
  gone). Never outlives the service. State is not persisted: a new service
  lifetime starts released.
- Also expose the toggle in the terminal's UI (menu/extra-keys long-press —
  pick whatever fits `TerminalTabBar`), since notification actions are
  hard to discover.
- First enable: if `PowerManager.isIgnoringBatteryOptimizations` is false,
  offer (once, dismissible, remembered) to open
  `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. Needs
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; this is a Play-restricted
  permission — check [play-store.md](play-store.md) and, if it is a
  problem there, fall back to `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`
  (no permission needed) for the Play flavour or for all builds.

## Steps

1. Verify the problem: unplugged, wireless adb (or sample
   `/sys/kernel/debug/suspend_stats` / `dumpsys power` before and after),
   session service up, ticker running, screen off 10 min. Expect tick gaps.
   If there are none on the physical target, record that in the issue and
   stop — do not add a wakelock on speculation.
2. Permission, locks, notification action + text.
3. Terminal UI toggle, battery-optimization prompt.
4. Re-run step 1 with the lock held: no gaps; `dumpsys power` lists
   `tawc:session`; toggle-off and Exit both drop it.
5. Notes (`notes/session-service.md`), delete the wakelock issue.
