# Vsync-driven frame clock

Replaces the free-running 16 ms frame timer in `compositor/src/event_loop.rs`
with ticks from Android's display vsync. Not started.

Context: [GH #25](https://github.com/wmww/tawc/issues/25) and
[PR #23](https://github.com/wmww/tawc/pull/23).

## Problem

The timer re-arms with `ToDuration(16ms)` *after* each tick, so each period is
16 ms plus render and `eglSwapBuffers` time. On the phone (60 Hz),
weston-simple-egl measured 54.7 fps. A grid of deadlines fixes the average
(measured 60.0 fps) but is still the wrong design:

- It free-runs against the panel. We render at arbitrary phase relative to
  vsync, so frames occasionally double or drop, and `eglSwapBuffers` blocks
  for an unpredictable part of the period.
- The rate is hard-coded (60 Hz), so 120 Hz panels are capped. #23 makes the
  period configurable but keeps the timer.
- It ticks forever, so there are 60 wakeups/s while nothing changes on screen.
- Frame callbacks and `wp_presentation` times are made up, not real vsync
  times.

Don't land the deadline fix or #23's timer change; this plan supersedes both.
#23's Android-side pieces (listed below) are still needed.

## Design

### Vsync source

A dedicated `tawc-vsync` thread owns an `ALooper` (`ALooper_prepare`) and
calls `AChoreographer_getInstance()` on it. It loops in `ALooper_pollOnce`.
`AChoreographer_postFrameCallback64` is API 29, which is our minSdk. Each
callback sends `(frame_time_nanos, period)` to the compositor through a
calloop channel or ping. `ALooper_wake` lets the compositor arm a callback
from its own thread.

Alternatives considered:
- Kotlin `Choreographer` on the main thread, relayed over JNI: adds a
  main-thread hop, and jank there would delay our frames. Reject.
- Polling the ALooper fd from calloop: there's no public fd to poll.

The choreographer follows the vsync of the app's default display. One
display is all we support today (see `notes/multi-activity.md`).

### Request frames on demand

The vsync thread posts a callback only while the compositor wants one. The
compositor arms it when:
- `needs_render` gets set (commits, toplevel changes, X11 associations,
  surface events), or
- a visible surface has pending `wl_surface.frame` callbacks. Note this in
  the commit handler.

Arm once per loop iteration, after dispatch (the `event_loop.dispatch`
while-loop or `run` callback), so every place that sets `needs_render`
doesn't have to remember to arm. Each vsync tick does what the timer did
for rendering: render the visible host, send frame callbacks, then flush.
When there's no work, don't re-arm, so idle means no wakeups. A screen-off
or backgrounded app gets no vsync, which is correct.

### Split housekeeping off the frame tick

The timer also runs non-frame work: `xwayland::service_pending`, dead
window/assignment pruning, popup and text-input cleanup, focus updates
on `toplevels_changed`, and `check_idle`. Move these to:
- the post-dispatch step, for event-driven work (toplevel changes, cleanup),
  and
- a slow timer (~250 ms to 1 s) for `check_idle` and XWayland polling.

Integration helpers (`wait_for_clean_state`, `wait_for_rendered_toplevels(0)`)
rely on cleanup running promptly; make sure they still pass and aren't
slower.

### Frame callbacks and presentation

- Send `wl_surface.frame` done with the vsync timestamp (ms of
  `CLOCK_MONOTONIC`), not `start_time.elapsed()`.
- Add `wp_presentation` (from #23): after the swap, report the vsync
  timestamp with flag `VSYNC` and the measured period as `refresh`. Later
  upgrade: `EGL_ANDROID_get_frame_timestamps` for real present times with
  the `HW_COMPLETION` flag.
- Callbacks still go only to the visible host's windows.

### Refresh rate

- From #23 (keep): the Activity requests a rate with
  `preferredRefreshRate` / `Surface.setFrameRate(FIXED_SOURCE)`, or
  `preferredDisplayModeId` on API 29. Without that, Android keeps us at
  60 Hz on a 120 Hz panel. Keep the Activity-side resolution and the
  persisted `outputRefreshMhz`, so a client's first `wl_output.mode` is
  already correct.
- `wl_output.mode.refresh` gets the current rate. Prefer
  `AChoreographer_registerRefreshRateCallback` (API 30) on the vsync
  thread, with the Activity-resolved value as the initial and API 29
  fallback. Re-send the mode when it changes.
- The cap setting UI from #23 is optional; decide separately.

### Pacing (follow-up)

v1 renders as soon as vsync arrives, which is about one frame of latency
and fine. Later: `AChoreographer_postVsyncCallback` (API 33) exposes frame
deadlines, so we could render as late as safely possible, and drop
`needs_render` frames that would miss the deadline.

## Steps

1. Add the vsync thread module: an ndk-sys choreographer/looper FFI and a
   calloop channel source. Expose `last_vsync_period` and the vsync tick
   count in `query-state`.
2. Move housekeeping out of the frame timer (behaviour-neutral; run the
   integration suite).
3. Replace the frame timer with armed vsync ticks. Remove the
   `Duration::from_millis(16)` timeout in `event_loop.dispatch` if nothing
   else needs it.
4. Real timestamps on frame callbacks, plus `wp_presentation`.
5. Bring over #23's Activity rate request and `wl_output` refresh, with
   output refresh from the choreographer.
6. Update `notes/rendering.md` and `notes/architecture.md` (event loop
   sources), then delete this plan.

## Verification

- On the phone (60 Hz): weston-simple-egl and vkcube run steady at about
  60.0 fps. Measure by sampling `frames` from `query-state`.
- On a 120 Hz device or mode: about 120 fps once the Activity requests it.
- Idle (no clients, compositor held, and a static window): no vsync ticks.
  Check the tick counter and that compositor-thread CPU is about 0.
- An integration test: an animating client reaches at least 0.95 × the
  reported refresh over 3 s.
- Full integration suite on the physical target, plus the emulator
  (gfxstream).
