# Socket-activated compositor (lazy start, auto-stop)

## Problem

The compositor only runs after something goes through
`UserRootfsSession.startInside` (launcher, `rootfs-run.sh`, RunCommandOp),
which calls `CompositorService.ensureRunning` + `waitForWaylandSocket`.
Terminal sessions deliberately skip that (notes/terminal.md), so a GUI
program or `wl-copy` typed into a terminal gets `ECONNREFUSED` on
`/usr/share/tawc/wayland-0` (stale socket file, no listener) unless the
user happened to launch a GUI app first. Once started it never stops by
itself: "TAWC running, 0 windows" persists until the notification Exit.
The user has to track and manage compositor state.

## Target behaviour

- While the app process is alive, `share/wayland-0` and the X11 `:0`
  socket always accept connections.
- First connection on either starts the compositor; the connection waits
  in the listen backlog meanwhile (measured on the emulator: ~7 ms from
  `nativeStartCompositor` to dispatch now that GL setup runs on a helper
  thread, plus service start; first run also pays asset extraction).
- When nothing is connected any more, the compositor stops (GLES context,
  foreground service, notification all go away). Next connection restarts
  it.
- Nothing starts the compositor explicitly. `UserRootfsSession` loses
  `ensureRunning`/`waitForWaylandSocket`; launcher, terminal and broker
  all become "just spawn the process".

Xwayland already works exactly like this inside the compositor
(`xwayland.rs::start_activation_socket` → `XWayland::prepare_lazy`, spawn
on first X11 connect, `-terminate 5` idle exit, re-arm in
`XwmHandler::disconnected`). This plan lifts the same pattern one level up.

## Design

### 1. Process-lifetime socket holder (Rust)

New module (e.g. `compositor/src/activation.rs`), started from
`TawcApplication.onCreate` via a new JNI call, independent of
`CompositorService`:

- Binds `share/wayland-0` (+ `.lock`, mode 0777) once and owns the
  `UnixListener` for the life of the process. Also owns the X11 `:0`
  prepared socket (`XWayland::prepare_lazy`; its `poll_fd` is usable
  without a running compositor).
- Idle state: a small thread `poll()`s both listener fds for readable,
  without accepting. On readable it reverse-JNIs "start compositor" and
  parks until the compositor hands the listeners back.
- Running state: the compositor thread borrows the listeners.
  `event_loop::run` stops using `ListeningSocket::bind_absolute`
  (wayland-server has no from-fd constructor) and instead registers a
  calloop `Generic` source on the shared `UnixListener`, doing
  `accept()` → `display_handle.insert_client(stream, ClientState)` — the
  same thing smithay's `ListeningSocketSource` does internally.
  `xwayland.rs` takes the prepared X socket from the holder instead of
  calling `prepare_lazy` itself, and returns it on stop.
- The socket file is never unlinked while the process lives, so the
  "bind last to avoid the accept gap" ordering in `lib.rs` (~1198) and the
  explicit `loop_handle.remove(listener_token)` lock-release dance go away.
  A stale file from a killed process is replaced at holder start.
- Make the kumquat listener process-lifetime in the same change (single
  thread reused across compositor runs). That resolves
  `issues/kumquat-listener-thread-leaks-per-compositor-start.md`, which
  frequent cycling would otherwise turn from benign into a real leak.

### 2. Start path

Holder → `NativeBridge` reverse-JNI → `CompositorService.ensureRunning`.
The existing `ensureCompositorRunning` sequence stays as is. Move the
one-time asset extraction (xkb, libhybris, Xwayland) to app start / a
cached "already extracted for this versionCode" check so it never sits in
the connect→accept gap.

### 3. Auto-stop

Decided on the compositor thread (frame timer or a dedicated timer):
stop when all hold for a grace period:

- `client_count == 0` (Wayland clients, excluding Xwayland),
- Xwayland not running (it exits by itself 5 s after its last X client),
- no debug hold (see Testing).

Then: compositor thread exits its loop, returns listeners to the holder,
reverse-JNIs the service to `stopForeground` + `stopSelf` (same teardown
as `exitFromNotification`, minus killing clients). If a connection is
already pending on a listener when the holder gets it back, it restarts
immediately — no connection is ever refused.

Grace period: 1 s (decided). Not literally immediate because app
startup commonly has short-lived helper connections before the real one
and scripts call `wl-copy`/`wl-paste` in bursts; each cycle costs a GLES
context + shader compile + FGS notification churn.

### 4. Clients that never leave

`wl-copy` forks a daemon that stays connected to serve the selection. A
paste does not end it (only with `--paste-once`); it exits on
`wl_data_source.cancelled`, i.e. when someone else takes the selection.
So our eager mirror read leaves it running and one `wl-copy` would pin
the compositor forever.

Fix: have the compositor take the selection over. When the only
remaining clients have no `wl_surface`, the current selection is owned
by one of them, and the Android mirror of it completed, install the
payloadless `SelectionUserData::Android` selection (what
`AndroidClipAvailable` already does when no live selection exists). The
daemon gets `cancelled`, exits by itself, `client_count` reaches 0 and
the normal 1 s rule stops the compositor. Nothing is lost: the text is
in the Android clipboard and pastes are served from there. If the mirror
did not complete (non-text, over cap, timeout) leave the owner alone and
let it pin the compositor — stopping would destroy the only copy.
Surfaceless clients that own no selection also pin (can't know what they
are for); revisit only if one shows up in practice.

### 5. UI consequences

- Notification Exit stays as the "kill everything" escape hatch (it kills
  clients; the compositor then stops by rule 3 anyway). The
  "running, nothing connected" state no longer exists, so any UI for it
  can go.
- `CompositorActivity.onCreate`'s `ensureRunning` stays harmless (the
  Activity only exists because a client mapped a window).

## Prerequisites / hazards (verify each; do not assume)

1. **Stop must be synchronous and leak-free.** Today
   `nativeStopCompositor` only flips `RUNNING`; `COMPOSITOR_RUNNING` is
   cleared by the thread up to a dispatch later, so a fast restart can hit
   the "already running" early return in `nativeStartCompositor`
   (`lib.rs` ~170) and end with no compositor. With the holder driving
   restarts this race goes from rare to routine. Restructure so restart is
   only triggered after the compositor thread has fully exited (the
   hand-back of listeners is the natural sync point; Kotlin `Lifecycle`
   should follow native state, not lead it).
2. **Per-cycle leaks.** notes/architecture.md documents the calloop
   `LoopHandle` Rc-cycle leak (Display, client fds, X11 lock). Add a
   stress test: N start/stop cycles, assert fd and thread counts in
   `/proc/<pid>` are flat, and EGL contexts are destroyed.
3. **Foreground-service start from the background.** The trigger is now a
   socket connect, which can happen while no TAWC Activity is visible
   (script in a backgrounded terminal). Android 12+ throws
   `ForegroundServiceStartNotAllowedException` there. Needs a decision and
   a test on API 31+: catch it and run the compositor un-promoted until an
   Activity appears (a windowed client will spawn one anyway; background
   Activity launch is an existing limitation, unchanged), or refuse and
   drop the pending connections. Check what `TerminalSessions` uses to
   keep the process alive, and whether that already exempts us.
4. **Connect→accept latency.** The `lib.rs` comment claims a GTK4 client
   "timed out its initial roundtrip" over an ~80 ms gap. libwayland has no
   roundtrip timeout, so that diagnosis is suspect — but something failed
   there. Reproduce with a cold start of `gtk4-widget-factory` and Firefox
   from the terminal on the slow emulator before relying on the backlog.
5. **Process lifetime.** "Always listening" only holds while the process
   is alive. A terminal-only session must already keep it alive for the
   shell to survive; confirm, and confirm the holder is up before the
   first terminal/launcher process can be spawned (Application.onCreate
   ordering).
6. X11-only programs (xterm from a terminal) connect only to `:0`. The
   holder must watch that fd too, otherwise lazy start works for Wayland
   and silently not for X11.

## Testing

- The integration harness currently force-stops, runs `true` in the
  rootfs to start the compositor, then waits for socket + `query-state`
  (`scripts/run-integration-tests.sh` ~330–356;
  `tests/integration/src/compositor.rs::assert_running`). Under this plan
  an idle compositor stops, so tests that poke an empty compositor need a
  debug-only broker action `compositor-hold on|off` (pins it running;
  starts it if stopped). `query-state` must answer "stopped" without
  starting anything.
- New tests: terminal-style spawn (no `UserRootfsSession` warmup) of a
  Wayland client and of an X11 client cold-starts the compositor;
  compositor stops within grace + ε after the last client exits; restart
  after stop works; connect during the stopping window is served;
  `wl-copy` then `wl-paste` across a stop/start keeps the text; cycle
  stress test from hazard 2. `cold_start.rs` gets simpler.

## Suggested order

1. Synchronous stop + leak fixes + kumquat process-lifetime thread
   (valuable on their own, no behaviour change).
2. Holder owns the sockets; compositor borrows them; start still explicit.
3. Lazy start from the holder; remove `ensureRunning`/`waitForWaylandSocket`
   from `UserRootfsSession`; `compositor-hold` for tests.
4. Auto-stop + selection-only-client rule + UI cleanup.
5. Update notes: architecture.md, multi-activity.md, launcher.md,
   terminal.md ("compositor is not started" paragraph), rootfs-sessions.md,
   xwayland.md, testing.md.

Steps 1–3 alone fix the reported `ECONNREFUSED`.
