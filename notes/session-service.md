# Session service

**A notification exists exactly when there is something in a rootfs to
lose, and it says what.** While it exists the process is a foreground
service: not cached, so no trim/LMK-first kill (which takes every guest
with it — they live in the app's cgroup), no cached-apps freezer, no Doze
firewall cutting guest network. When the last reason goes, service and
notification go. TAWC's own UI with nothing running shows nothing;
installs keep their own `InstallationService` notifications.

Code: `app/src/main/java/me/phie/tawc/session/`.

## Holds

`SessionHolds.acquire(reason): Hold` — process-wide, any thread;
`Hold.release()` is idempotent, `Hold.update(reason)` swaps what it
reports. The registry itself never touches Android (JVM-unit-tested);
`SessionService.install` (from `TawcApplication.onCreate`) gives it the
`startForegroundService` starter.

| Reason | Acquired | Released |
|---|---|---|
| `Terminal(distroId)` | `TerminalSessions.add` | `remove` / `removeAll` |
| `Command(label)` | around the process in `UserRootfsSession.startInside` (launcher headless launch, `RunCommandOp`, broker `RUNINSIDE`) | a waiter thread on process exit |
| `Compositor(windowCount)` | `CompositorService`, when `nativeStartCompositor` spawned a thread | `onCompositorStopped` |
| `Stray(count)` | never acquired; service-internal | — |

Terminal holds live in `TerminalSessions`, not the activity — sessions
outlive it. Command holds follow the `Process`, so no caller cooperates.

## Service

`SessionService`: FGS type `specialUse` (subtype `linux_session`),
`START_NOT_STICKY` (after a process kill every guest is dead, and a
sticky restart would call `startForeground` from the background).
`onCreate` is trivial on purpose: `startForegroundService` allows ~5 s to
reach `startForeground`. `onStartCommand` calls `startForeground` again
every time — each `startForegroundService` must be answered.

Start/stop is atomic with the registry: the service stops only through
`SessionHolds.serviceStopIfIdle()`, and an `acquire` that finds no live
service calls the starter, so a release-then-acquire race either keeps
the service or starts a new one.

Every acquire site normally runs while a TAWC activity is visible. The
debug broker without `--foreground-app` is the exception: Android 12+
throws `ForegroundServiceStartNotAllowedException`; it is logged once and
the spawn carries on unprotected (the next acquire retries).

**Stray tail.** A `nohup`/`setsid` job outlives its tab and holds nothing.
When the last hold releases, the service runs `ProcessScanner.scan`
off-thread; if guests remain it stays up as "N background processes" and
re-scans every 15 s until none do. Only this tail state polls.

**Notification.** Channel `tawc_session` (the old `tawc_compositor`
channel is deleted), low importance, ongoing. Title "TAWC running", text
e.g. "2 terminals · 3 windows", "Running: htop", "3 background
processes". Tap opens `MainActivity`.

**Exit** (`SessionExit.killEverything`) kills everything: finishes every
terminal session (tabs/activities close through the normal
`onSessionFinished` path), stops the compositor, and
`ProcessScanner.killAllInRootfs` for every install — except installs that
are not `READY` or have a live `install:`/`uninstall:` operation, whose
processes belong to the installer. One notification stands for every
reason, so a partial exit would leave it up. Holds are not force-released;
each follows its own process down.

## Debug surfaces

Broker actions `session-state` (one line per held reason) and
`session-exit` (what the Exit button does). Covered by
`lazy_compositor::test_session_holds_*`.

## Start/stop must share the main thread

`startForegroundService` obliges the service to call `startForeground`;
if the service is stopped with such a start unanswered, Android throws
`ForegroundServiceDidNotStartInTimeException` and kills the process —
every guest with it, i.e. the exact failure this service exists to
prevent. A start issued from another thread can land between "nothing is
held" and `stopSelf()`. So the starter always runs on the main thread
(posting if needed), and the stop path has no suspension point between
`serviceStopIfIdle` and `stopSelf()`. `SessionHolds` tracks the live
instance by token so a late `onDestroy` of the old instance cannot
unregister its successor. This bit once: a full integration run died at
the first command that raced the compositor's auto-stop releasing its
hold (`lazy_compositor::test_session_service_survives_hold_churn` is the
best-effort guard; it is timing-dependent and did not reproduce the crash
on its own).

## Measured (OnePlus 9, Android 14, 2026-09-20)

Before: a terminal-only session had no service at all; HOME then a few
other apps → `procState=16`, adj 910, and `am kill me.phie.tawc` took
every guest with it. About a minute after screen-off, light Doze's
`fw_dozable` chain cut all guest network (guests run as the app uid):
DNS-shaped failures, curl exit 6, unreproducible while watching because
`TerminalActivity` keeps the screen on.

After, with a rootfs command running and the app behind three others:
`procState=4` (FGS), adj 50, `am kill` a no-op, a 1 s ticker unbroken. A
detached guest curl loop (held by the stray tail) through forced light
then deep idle, screen off: 48 requests, 0 failures, `procState=4`
throughout. Stray tail: "1 background process" while a `setsid sleep`
lived, service gone within 9 s of it exiting.

Emulator (API 36, where the cached-apps freezer is on): a backgrounded
ticker ran ~40 s with no gap over 2 s, `isFrozen=false`, `procState=4`.

Not measured: a `Terminal` hold specifically (needs the terminal UI; same
service either way), and swiping a `CompositorActivity` card with a
terminal alive.

An FGS is necessary but not always sufficient: a RESTRICTED standby
bucket, user "restrict battery usage", or an aggressive OEM ROM can still
cut the uid, and the app is not on the device-idle allowlist
(`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is the lever). It does nothing
for the phantom-process killer or CPU sleep — see
`issues/phantom-process-killer-kills-rootfs-processes.md` and
`plans/wakelock.md`.
