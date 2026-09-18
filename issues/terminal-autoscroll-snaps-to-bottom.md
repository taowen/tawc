# In-app terminal snaps to the bottom on every new output line

Scrolling back through the transcript in the in-app terminal is
impossible while a program is still printing: each chunk of output
yanks the viewport back to the bottom. Repro: `for i in {0..200}; do
echo $i; sleep 1; done`, scroll up, wait a second. A desktop terminal
keeps the viewport on the same content and lets the new lines pile up
below.

Termux has the same behaviour (termux/termux-app#2535), and we
inherited it with the vendored widget.

## Cause

`TerminalView.onScreenUpdated(boolean)`
(`deps/termux-app/terminal-view/src/main/java/com/termux/view/TerminalView.java:457`)
unconditionally resets the scroll position:

```java
if (!skipScrolling && mTopRow != 0) {
    ...
    mTopRow = 0;
}
```

`mTopRow` is the viewport offset from the bottom (0 = pinned to the
live screen, negative = scrolled into the transcript). The two escapes
from that reset are text selection being active and
`mEmulator.isAutoScrollDisabled()`; both take the branch above it,
which does the *correct* thing — `mTopRow -= rowShift`, i.e. hold the
viewport on the same content and only clamp when the transcript ring
has scrolled that content away.

Nothing sets `isAutoScrollDisabled` for us. Upstream flips it only
from the optional `SCROLL` extra key
(`deps/termux-app/app/.../io/TermuxTerminalExtraKeys.java:99` →
`TerminalEmulator.toggleAutoScrollDisabled`,
`terminal-emulator/.../TerminalEmulator.java:2532`), and that key is
not in our `EXTRA_KEYS_CONFIG`
(`app/src/main/java/me/phie/tawc/terminal/TerminalActivity.kt:550`),
so in TAWC there is no way to stop the snap at all.

The caller is ours: `TerminalActivity.onTextChanged`
(`TerminalActivity.kt:461`) calls the no-arg `onScreenUpdated()`, which
is `onScreenUpdated(false)`. Same for `onResume` (`:229`, so leaving and
returning to the activity also snaps to the bottom) and `selectTab`
(`:329`).

Alt-screen programs (`less`, `vim`) are unaffected — `doScroll` sends
arrow keys there instead of moving `mTopRow`.

## Fix

`TerminalView` is `final` (`TerminalView.java:46`), so no subclass
override, and the widget is vendored unpatched (`notes/terminal.md`) —
patching it would mean starting a termux fork for a fix that does not
need private state. Everything required is public: `getTopRow()` /
`setTopRow()` (`TerminalView.java:1053`, `:1057`), `isSelectingText()`
(`:1374`), the `mEmulator` field (`:54`), and
`TerminalEmulator.getScrollCounter()` (`:2520`). So wrap the call in
`TerminalActivity` and route `onTextChanged`/`onResume` through it:

```kotlin
/** onScreenUpdated() that holds the viewport when scrolled back. */
private fun screenUpdated() {
    val emulator = terminalView.mEmulator
    val topRow = terminalView.topRow
    // Pinned to the bottom, or upstream already handles it (selection).
    if (emulator == null || topRow == 0 || terminalView.isSelectingText) {
        terminalView.onScreenUpdated()
        return
    }
    val shift = emulator.scrollCounter // cleared by onScreenUpdated
    terminalView.onScreenUpdated(true)
    terminalView.topRow =
        maxOf(-emulator.screen.activeTranscriptRows, topRow - shift)
    terminalView.invalidate()
}
```

Notes on the details:

- Read `scrollCounter` *before* `onScreenUpdated`, which clears it.
- Subtracting the shift is what keeps the same lines under the finger;
  clamping to `-activeTranscriptRows` is what happens once the user
  has been scrolled up longer than `TRANSCRIPT_ROWS` (4000) of output
  — the view then sits at the oldest surviving line.
- Delegate when `isSelectingText()`: upstream already applies the
  shift in that branch, and doing it here too would double it.
- No state to track. `topRow == 0` *is* "user is pinned to the
  bottom", so auto-scroll resumes by itself when they scroll back
  down, including mid-fling.
- During an active fling the hold is best-effort: the fling runnable
  recomputes `diff = newY - mTopRow` against the scroller's *absolute*
  trajectory (`TerminalView.java:222`), so each fling frame overwrites
  our shift and content slides under the fling until it settles.
  Upstream's autoscroll-disabled mode has the same limitation; ignore.
- `scrollCounter` over-counts one obscure case: `scrollDownOneLine`
  increments it even on the DECSLRM horizontal-margin path
  (`TerminalEmulator.java:2207`), where the ring buffer does *not*
  shift, so the viewport would drift up by one per such scroll.
  Upstream's selection branch has the identical bug; not worth
  handling. Plain vertical scroll regions (DECSTBM) are fine — the
  ring still advances (`TerminalBuffer.scrollDownOneLine`,
  `TerminalBuffer.java:395`), so subtracting the shift is correct
  there.
- Alt screen is safe by construction: its `activeTranscriptRows` is 0,
  so the `maxOf` clamps `topRow` to 0.

Second half of the change: scrolled-back input should snap to the
bottom, the way xterm's `scrollKey` does — otherwise typing while
scrolled up looks like a dead terminal. `TerminalView.onKeyDown`
(`:769`), `inputCodePoint` (`:846`) and `handleKeyCode` (`:912`) are
public, but they are called by the view/extra-keys internally, not
only by us; simplest is `setTopRow(0)` from `TerminalActivity`'s
`onKeyDown` (`TerminalActivity.kt:418`) and `onCodePoint` (`:450`)
client callbacks, plus `onPasteTextFromClipboard` (`:479`). The
extra-keys row is covered too: `TerminalExtraKeys` feeds buttons
through `TerminalView.onKeyDown`/`inputCodePoint`, which consult the
client callbacks. Two guards the snap needs:

- Skip system and bare-modifier keys in `onKeyDown`: the client
  callback fires *before* the `event.isSystem()` filter
  (`TerminalView.java:777` vs `:780`), so an unguarded snap would fire
  on Back/volume keys that never reach the terminal, and on a bare
  Ctrl/Alt press. `if (!e.isSystem && !KeyEvent.isModifierKey(keyCode))
  terminalView.topRow = 0`.
- `invalidate()` after `setTopRow(0)`: it only sets a field, so a key
  the program swallows without echoing would not visibly snap until
  the next output chunk.

## Related, out of scope

- `updateSize()` (`TerminalView.java:984`) also zeroes `mTopRow`, so a
  rotation or font-size change snaps to the bottom.
- `attachSession` zeroes it too, so per-tab scroll position is lost on
  tab switch. `TerminalSessions` would have to hold a per-session
  `topRow`.
- No test covers this; `TerminalSessionsTest` is registry-only and the
  scroll path needs the view. A unit test could drive a real
  `TerminalEmulator` and assert `topRow` bookkeeping, but the reset
  lives in the view, so it would be a manual repro for now.
