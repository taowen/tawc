# Session service: one foreground service for everything alive in a rootfs

## Problem

Background terminal programs die or stall unpredictably. Measured on the
physical target (OnePlus 9, Android 14) 2026-09-20, plus the 2026-08-11
measurements in the issues below:

- `TerminalActivity` starts no service. HOME → adj 700; three more apps
  opened → `procState=16` (cached), adj 910. A 1 s ticker kept running
  there, so nothing fails *at* the app switch — the process is simply
  killable from then on.
- `am kill me.phie.tawc` (what routine cached-process trimming and LMK do)
  succeeded and took every guest process with it: they live in the app's
  cgroup (`uid_N/pid_M`). `dumpsys activity exit-info` also showed a
  `REMOVE TASK` kill at importance 400: swiping any TAWC recents card while
  no FGS is up kills the whole process.
- Cached also means: Doze firewall cuts all guest network ~1 min after
  screen-off
  ([issues/rootfs-network-cut-when-app-backgrounded.md](../issues/rootfs-network-cut-when-app-backgrounded.md)),
  and on devices with the AOSP cached-apps freezer (off on the OnePlus:
  `use_freezer=false`; on for Pixels/recent AOSP) every guest is frozen
  ~10 s after the app goes cached.
- The only FGS is `CompositorService`, started by `CompositorActivity` and
  `UserRootfsSession` and never stopped except by notification Exit. So
  terminals are protected *by accident* after any GUI launch and lose it
  again on Exit. [lazy-compositor.md](lazy-compositor.md) makes the
  compositor transient, which removes even that.

Termux has none of this because it runs one FGS whenever any session
exists.

## Target behaviour

**A notification exists exactly when there is something in a rootfs to
lose, and it says what.** While it exists the process is a foreground
service (not cached: no trim/LMK-first, no freezer, no Doze firewall).
When the last reason goes, service and notification go. "TAWC running,
0 windows" no longer exists. TAWC's own UI with nothing running in a rootfs
shows no notification; installs keep their own `InstallationService` /
operations notifications.

## Design

### SessionService + holders

New `SessionService` (suggested package `me.phie.tawc.session`), FGS type
`specialUse`, plus a process-wide `SessionHolds` registry:

```kotlin
object SessionHolds {
    fun acquire(context: Context, reason: Reason): Hold   // Hold.release() is idempotent
    val reasons: StateFlow<List<Reason>>
}
```

- First `acquire` → `startForegroundService`; the service calls
  `startForeground` in `onCreate`. Last `release` → stray check (below) →
  `stopForeground(REMOVE)` + `stopSelf`.
- Every acquire site runs while a TAWC activity is visible (terminal,
  launcher, command runner, `CompositorActivity`), so the Android 12+
  background-FGS-start restriction is not hit. The debug broker is the
  exception; it already has `--foreground-app`. Catch
  `ForegroundServiceStartNotAllowedException`, log once, and carry on
  unprotected rather than failing the spawn.
- `START_NOT_STICKY`. After a process kill every guest is dead; there is
  nothing to restore, and a sticky restart would try `startForeground`
  from the background.

Reasons (each carries what the notification needs):

| Reason | Acquired | Released |
|---|---|---|
| `Terminal(distroId)` | `TerminalSessions.add` | `remove` / `removeAll` / session finished |
| `Command(label)` | around the process in `UserRootfsSession.startInside` (launcher headless launch, `RunCommandOp`, broker `RUNINSIDE`) | process exit |
| `Compositor(windowCount)` | compositor start | compositor stop |
| `Stray(count)` | internal, see below | internal |

Hook terminal holds in `TerminalSessions` itself, not the activity —
sessions outlive the activity. `UserRootfsSession.startInside` returns a
`Process`; wrap it (or watch `onExit()`) so the hold follows the process
without every caller cooperating.

### Compositor becomes a holder, not a foreground service

`CompositorService` stays as the bound service activities register with,
but loses `startForeground`, its notification channel/notification, the
Exit action and `START_STICKY`. `ensureRunning` becomes
`startService` + `SessionHolds.acquire(Compositor)`; with today's
explicit-start/never-stop compositor that hold simply lasts until Exit,
i.e. current behaviour. `toplevelCount` feeds the reason's window count.
Move the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` manifest property to
`SessionService` with a broader subtype (e.g. `linux_session`), and update
the justification text in [play-store.md](play-store.md) §4.3 to cover
terminals/CLI jobs, not only the compositor.

### Stray guest processes

A `nohup`/`setsid` job outlives its terminal tab and holds nothing. When
the last explicit hold releases, run `ProcessScanner.scan`; if any guest
process is alive, stay foreground with `Stray(n)` and re-scan on a slow
timer (~15 s; this state is rare and the scan is one `/proc` walk) until
none remain. Only this tail state scans; while explicit holds exist no
polling happens.

### Notification

One new channel `tawc_session` (delete the old `tawc_compositor` channel
on upgrade), one ongoing notification, low importance, no badge.

- Title: "TAWC running". Text lists reasons, e.g. "2 terminals · 3
  windows", "1 terminal", "Running: htop", "3 background processes".
  Strings go in resources (the old service hardcoded them).
- Tap: `MainActivity`, as today.
- **Exit** action: kill everything. `ProcessScanner.killAllInRootfs` for
  every install, finish terminal sessions and compositor activities, stop
  the compositor, release all holds. This reverses the current
  "Exit is scoped to the compositor" comment in `CompositorService` — with
  one notification standing for every reason, a partial Exit would leave
  the notification up and confuse. Matches Termux. No confirm dialog: as
  today, the tap is the decision.
- Android 13+ lets users deny/dismiss the notification; the FGS still
  protects, and the system's active-apps list shows TAWC with a Stop
  button. Nothing to do.

### Recents swipe

`TerminalActivity.onDestroy`'s swipe rule (kill that distro's shells)
stays. With the service held, swiping some *other* TAWC card no longer
kills the process. No other change.

## Hazards (verify; do not assume)

1. `startForegroundService` → `startForeground` has a ~5 s deadline. Keep
   `SessionService.onCreate` trivial; the asset extraction currently in
   `CompositorService.ensureCompositorRunning` must not move into it.
2. Release-then-acquire races (last tab closes as a command launches):
   serialise on the main thread and make stop re-check the registry after
   the stray scan, which runs off-thread.
3. Exit while an install/uninstall op is running must not touch the
   installer's processes. `killAllInRootfs` is per install; skip installs
   with a live operation (check how `RootfsCleaner` already avoids this).
4. Integration harness: `test-init`, `assert_running` and the
   force-stop/cold-start paths assume `CompositorService` is the FGS.
   Audit `tests/integration/src/compositor.rs` and
   `scripts/run-integration-tests.sh`.

## Testing

On the physical target, per `.tawctarget`:

- Terminal-only ticker (`while :; do date +%s >> f; sleep 1; done`), HOME,
  open ≥3 other apps: `dumpsys activity processes me.phie.tawc` shows
  `procState` FGS (4), adj ≤ 200, and `am kill me.phie.tawc` does nothing.
  Before this plan: `procState=16`, adj 910, `am kill` kills it.
- Screen off, light + deep Doze (`dumpsys deviceidle force-idle`): curl
  loop keeps succeeding (repeat of the network issue's measurement).
- Last tab closed → notification gone, `dumpsys activity services` empty.
- `nohup sleep 300 &`, close tab → "1 background process"; it exits →
  notification gone within one poll.
- Exit with a terminal + a GUI window + a stray: all gone, no service.
- Swipe a `CompositorActivity` card with a terminal alive: terminal
  survives.
- Emulator (API 36, freezer on): backgrounded ticker has no gaps and
  `isFrozen=false`.
- Unit tests for `SessionHolds` (acquire/release idempotence, reason
  aggregation, notification text).

## Order

1. `SessionHolds` + `SessionService` + notification; terminal and command
   holds. Fixes the reported bug.
2. Compositor holder; strip FGS/notification/Exit from `CompositorService`;
   new Exit semantics.
3. Stray tail state.
4. Notes: terminal.md ("No foreground service" paragraph), android.md,
   multi-activity.md, architecture.md, testing.md; new
   `notes/session-service.md`. Delete
   `issues/rootfs-network-cut-when-app-backgrounded.md` once the Doze test
   passes. Keep the phantom-process issue (an FGS does not help; no in-app
   fix) and the wakelock issue (see [wakelock.md](wakelock.md)).

[lazy-compositor.md](lazy-compositor.md) steps 3–4 depend on this plan.
