# Make wl-copy / wl-paste work

## Root cause (verified on the emulator, wl-clipboard 2.3.0, Debian sid)

With the compositor running, `wl-copy foo` dies with
`xdg_surface: error 3: must ack the initial configure before attaching
buffer`. `WAYLAND_DEBUG=1` trace:

```
-> xdg_surface.get_toplevel, set_title, set_app_id
-> wl_surface.commit            # initial commit
-> wl_display.sync / <- done    # one roundtrip; NO xdg_surface.configure arrived
-> wl_surface.attach(1x1 shm) + commit
<- wl_display.error(UnconfiguredBuffer)
```

logcat at the same instant: `Deferring initial configure for … until host
a-…-2 registers`.

We advertise no data-control protocol, so wl-clipboard falls back to its
"popup surface" trick (`src/types/popup-surface.c`): map a 1×1 transparent
toplevel to obtain keyboard focus and an input serial. It commits, does
exactly one roundtrip, assumes the configure arrived inside it, then
attaches a buffer. Every desktop compositor answers the initial commit
immediately, so this works everywhere else. TAWC's `new_toplevel`
(`compositor.rs` ~965) sends the first configure only if the assigned
host already has a size; a new toplevel gets a *new* Activity, so the
configure waits for `nativeRegisterActivitySurface` (~50–500 ms later;
flushed by `reconfigure_all_toplevels` on "Host registered"). That
deferral is deliberate (`lib.rs` ~1168: avoids configure(0,0) and
size guesses). wl-clipboard is technically the sloppy party, but it is
the de-facto CLI clipboard tool and we should work with it.

Even with the protocol error fixed, the fallback is a bad fit here: each
`wl-copy`/`wl-paste` would spawn a full-screen Android Activity over the
terminal, wait for it to gain focus, then finish it — a visible flash
and a recents entry per invocation, and it cannot work at all while TAWC
is not in the foreground.

## Fix A (primary): implement data-control

Advertise `ext_data_control_v1` and `zwlr_data_control_v1`. Both exist in
the smithay fork (`wayland/selection/{ext,wlr}_data_control`), including
a per-client global filter. wl-clipboard prefers them and then never
creates a surface: no Activity, no focus needed, no configure race.
Other tools (cliphist, clipman, wayvnc…) get the same benefit.

Work:
- Create `DataControlState`s in `TawcState` setup, implement the
  handler traits, `delegate_*!`.
- wl-copy → Android: a data-control `set_selection` should reach the
  existing `SelectionHandler::new_selection` path and thus the eager
  mirror (clipboard.rs); verify, since the mirror is what makes the copy
  visible to Android apps and to the terminal's own paste.
- wl-paste of an Android clip: goes through
  `SelectionHandler::send_selection` with `SelectionUserData::Android`
  → `clipboard-fetch-android` thread. Verify the serial gate: data-control
  devices are re-offered on every `set_data_device_selection`, so they
  always hold the current serial.

**Read policy (decided): wl-paste works only from the foreground
terminal.** notes/clipboard.md ("A backgrounded app must not be able to
paste") narrows Android-clip reads to the focused Wayland client via
offer serials. Data-control lets any client read at any time, and the
terminal is a Kotlin Activity, not a Wayland client, so the serial gate
cannot express "the shell that ran wl-paste". Gate data-control reads on
the requesting process's controlling tty instead:

- Smithay fork patch: tell `SelectionHandler::send_selection` which
  `Client` asked and whether it came through data-control (today it gets
  only type/mime/fd/seat/user-data). Same patch would let the X11 gate in
  clipboard.md get finer later.
- Client → pid via `Client::get_credentials` (SO_PEERCRED). Read field 7
  (`tty_nr`) of `/proc/<pid>/stat`. Verified on the emulator: tawcroot
  guests are ordinary same-uid processes, their `/proc` entries are
  readable by the app, and a terminal tab's processes carry its pts
  (`tty_nr` 34818 = pts/2; broker/launcher-spawned ones have 0).
- Kotlin pushes down the pts number of the terminal tab that currently
  has Android window focus (none when no `TerminalActivity` is focused);
  `TerminalSessions` owns the ptys. Allow the read iff `tty_nr` matches.
  Otherwise close the fd (empty paste), like the existing gates.
- Applies to every data-control read, client-owned selections included,
  not just Android clips — otherwise a background guest could read what
  another Linux app copied. Writes (`wl-copy`) are never gated.
- Core-protocol reads keep the serial gate unchanged.
- Floor that holds regardless: Android itself denies
  `getPrimaryClip()` unless some TAWC window has focus, so Android clips
  can never leak while TAWC is backgrounded even if this gate regresses.
- Limits, accepted: a process sharing the foreground tab's tty (a job
  backgrounded from that shell) can read; SO_PEERCRED names the process
  that connected, so an fd passed to another process inherits the
  verdict. proot/chroot (debug-only) guests may run as another uid with
  unreadable `/proc` → denied; note it, don't solve it.
- Consequence: clipboard managers (cliphist etc.) that read from the
  background won't work. Fine for now.

## Fix B (secondary, optional): answer the initial commit promptly

Protects against other clients with the same one-roundtrip assumption
and removes a spec-grey behaviour (configure is sent from
`new_toplevel`, i.e. at `get_toplevel`, not in response to the initial
commit, and may be arbitrarily late).

- When the assigned host has no size yet, send a provisional initial
  configure using the most recent registered host's logical size (hosts
  are near-always identical full-screen Activities; keep the last size
  in `TawcState`, surviving host teardown). If no host has ever
  registered, keep deferring as today. When the real host registers,
  the existing `reconfigure_all_toplevels` corrects it if it differs.
- Cost: a well-behaved client may render one frame at the provisional
  size before its Activity exists — invisible unless the size differs
  (split-screen / freeform), where it costs one extra resize. This is
  the "service-side guess" the `lib.rs` comment rejected, so only do it
  if a second affected client turns up; with Fix A wl-clipboard no
  longer needs it.
- Not sufficient alone for wl-clipboard: see the Activity-flash problem
  above.

## Interaction with the lazy compositor (done)

See notes/architecture.md, "Compositor lifecycle".

- `wl-copy` leaves a daemon connected to serve the selection. The
  compositor's idle rule already handles it: once the mirror into Android
  completed and no client has a `wl_surface`, it installs the Android
  selection, the daemon gets `cancelled` and exits, and the compositor
  stops. That path has never run end to end, because `wl-copy` dies on the
  configure bug above first — verify it here.
- With lazy start, `wl-copy` from a cold terminal starts the compositor,
  the mirror writes the text to Android, and the compositor stops again;
  the text survives in the Android clipboard. Add that as an integration
  test (`lazy_compositor`, unpinned): `wl-copy` then `wl-paste` across a
  stop/start keeps the text.

## Tests

- `wl-copy foo` exits 0, `clipboard-get-text` returns `foo`; no
  Activity spawned (`query-state` toplevels/hosts unchanged).
- `clipboard-set-text bar` then `wl-paste` from the focused terminal tab
  prints `bar`, with `clipboard_android_fetches_total` incremented.
- `wl-paste` via the broker (no tty), and from a terminal tab that is not
  focused, yields empty — for Android clips and client-owned ones.
- If Fix B: a debug-app mode that attaches right after one roundtrip.
- wl-clipboard must be added to the test rootfs package set.
