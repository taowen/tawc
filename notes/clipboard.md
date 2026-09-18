# Clipboard bridge

Text-only bridge between Android's ClipboardManager and the Wayland/X11
clipboard selection. Kotlin side: `ClipboardBridge.kt`; native side:
`compositor/src/clipboard.rs` plus the policy arm in `event_loop.rs`.

## Android → Wayland: announce-only, fetch at paste

Android 12+ toasts "app pasted from your clipboard" on
`getPrimaryClip()` reads of foreign clips, and some OEM builds toast on
*every* read. So the Android→Wayland direction never reads clip content
eagerly:

- All sync points (clip-changed listener, window-focus regain with a
  short retry while reads are still denied, service start) read only the
  `ClipDescription` — which never toasts — and, for clips advertising
  text/plain or text/html, call
  `nativeOnAndroidClipAvailable(timestampMs, ownWrite)`. `ownWrite` means
  the clip label is `"tawc"`, i.e. our own Wayland→Android mirror write.
  text/html covers Firefox/Gecko web copies (HTML clips advertising only
  that MIME). Deliberately not a `text/*` wildcard: URI/Intent clips
  advertise text/uri-list / text/vnd.android.intent with no item text,
  and announcing them would clobber a live client selection with an
  unpasteable one. Clips whose text hides in an item without a text MIME
  in the description are not caught (acceptable, rare).
- The event loop (`ClipboardEvent::AndroidClipAvailable`) skips the
  announce only when a live selection exists AND (it's our own write or
  the timestamp matches the last announced clip). Otherwise it installs
  a payloadless `SelectionUserData::Android` selection and notifies xwm.
  `ts == 0` (OEMs that don't stamp clips) always re-announces. Mostly
  idempotent, but on those devices every focus gain over a foreign clip
  re-announces, which replaces a client selection whose mirror never
  completed (non-text, over cap, timeout) — completed mirrors are
  protected by the own-write label. The "only if a selection is live"
  clause means the compositor takes ownership when a mirrored owner
  dies, so paste keeps working. `last_announced_android_clip_ts` lives
  in `TawcState`.
- Content is fetched when a client actually pastes: both
  `send_selection` paths (Wayland `SelectionHandler` in compositor.rs,
  X11 `XwmHandler` in xwayland.rs) spawn a `clipboard-fetch-android`
  thread that reverse-JNIs `NativeBridge.fetchClipboardText()` →
  `ClipboardBridge.getTextForPaste()` — the real `getPrimaryClip()`
  read, so the OS toast fires exactly once per actual paste. A null
  fetch (read denied because TAWC isn't focused, non-text clip, over the
  1MB cap) drops the fd: the client sees EOF / empty paste. Known
  regression, accepted: pasting while TAWC has no Android input focus
  yields an empty paste instead of stale mirrored text.
- The fetch thread writes into the client pipe with a 5s deadline
  (nonblocking + poll), so a client that pastes and never reads can't
  pin the thread/fd. The reverse-JNI ClipboardManager binder call has
  no deadline of its own; bounding it is harder and hasn't mattered.
- `getTextForPaste()` keeps a one-entry `(timestamp → text)` cache,
  checked via the toast-free description, so repeat pastes of an
  unchanged clip don't re-read — kills repeat toasts on per-read-toast
  OEMs. Disabled when the timestamp is 0.

## A backgrounded app must not be able to paste

Android's own foreground rule bounds clipboard reads to "while some TAWC
window is focused". Two compositor-side gates narrow that to the app the
user is actually looking at. Both refuse by closing the client's pipe /
answering with no property, which reads as an empty paste.

**Wayland — offer serials.** Smithay hands `wl_data_device.selection`
only to the keyboard focus, but it never takes an offer back: a client
that loses focus isn't sent a null selection, its `wl_data_offer` object
stays alive, and `wl_data_offer.receive` checks neither focus nor
staleness (`selection/offer.rs`) before calling
`SelectionHandler::send_selection` with the offer's *cloned* user data.
So a backgrounded client could keep pasting through an offer it held on
to — verified, it pasted indefinitely. `SelectionUserData::Android`
therefore carries a serial: `refresh_android_selection` re-announces the
selection under a fresh one at the end of every `set_input_focus`, and
`send_selection` serves only offers whose serial is current. Only the
focused client is ever handed an offer, so "current serial" means "the
client focus went to"; focus leaving for another app — or for nothing,
when TAWC is backgrounded — invalidates the lot. Refocusing hands out a
current offer again, so nothing is permanently poisoned. Cooperative
toolkits drop stale offers by themselves and never notice.

**X11 — `allow_selection_access`.** X11 has no offer objects to age out;
clients convert the selection live, so xwayland.rs just refuses unless an
X11 window holds focus. That covers the case that matters — a guest can't
read behind a Wayland window or behind no TAWC window at all — but it is
deliberately coarse: smithay reports *that* a selection was requested,
not by which X client, so any X11 client can read while any X11 window is
focused. One Xwayland serves every distro, so that includes across
installs. Closing it needs a smithay patch forwarding the requesting
window; judged not worth it. Xdnd is unaffected (its selection has no
`SelectionTarget`, so smithay skips the callback), and an X11 client that
owns the selection itself is never routed through the WM.

Per-distro isolation is a separate, unaddressed boundary: the clipboard,
the Wayland socket, and the X display are all shared across installs by
design.

Smithay has no "does any selection exist" query and silently clears a
dead client source's selection without calling the handler, so
`clipboard::selection_exists` probes with
`request_data_device_client_selection` using a MIME nobody offers
(`x-tawc/selection-probe`): `NoSelection` means no, anything else means
yes, with no transfer (worst case, a client that actually offers the
probe MIME gets one spurious `send` into /dev/null). It's policy on an
error-enum side channel; the cleaner long-term fix is a tiny
`selection_exists` accessor in the smithay fork if this ever grows.

## Wayland/X11 → Android: eager mirror

Client-owned selections are pulled eagerly ("latest selection wins", one
in-flight pull owned by the event loop, 5s timeout, 1MB cap) and written
into Android via `onSetAndroidClipboardText` with clip label `"tawc"` so
the resulting announce is recognized as our own echo. GTK3 apps set the
selection twice per copy (`SAVE_TARGETS`); the pull machinery survives
bursts. See the module doc in clipboard.rs.

## Dev/test surface

- Broker actions: `clipboard-set-text` (label `"tawc-dev"`, so it
  announces like a foreign app; `--arg html=1` mimics Gecko HTML clips),
  `clipboard-get-text` (debug-only full read), `clipboard-debug-state`.
- `nativeClipboardDebugState` counters:
  `clipboard_pull_timeouts_total`, `clipboard_android_fetches_total`
  (paste requests routed through the lazy fetch path, cache hits
  included — not raw `getPrimaryClip()` reads),
  `clipboard_write_timeouts_total` (paste pipe drains that hit the 5s
  write deadline); accessors in `tests/integration/src/adb.rs`.
- Coverage: clipboard cases in `tests/integration/tests/text_input.rs`
  and `xwayland.rs` (filter `clipboard`), plus
  `apps.rs::test_gtk4_widget_factory_copy_paste_and_text_input`. The
  background gates are
  `text_input::test_retained_clipboard_offer_denied_when_backgrounded`
  (wayland-debug-app `clipboard-paste-retained` keeps pasting through an
  offer it never drops, while another app takes focus and gives it back)
  and `xwayland::test_x11_clipboard_denied_behind_wayland_window`
  (x11-debug-app `paste-loop` behind a Wayland window). Both clients
  report every attempt as `CLIPBOARD_TRY:ok=<text>` vs
  `CLIPBOARD_TRY:empty` / `:denied`.
- Toast behavior (none on screen open, one per paste) can only be
  judged manually on a per-read-toast OEM device; AOSP/emulator dedupes
  per app+clip.
