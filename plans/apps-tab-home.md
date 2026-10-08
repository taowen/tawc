# Apps tab as home

Not started.

## Goal

Make the apps list the home screen. It becomes the first tab of the
terminal tab bar, so the app no longer has two persisted modes.

- **Apps tab.** Always the leftmost tab. It shows the `ic_apps` 3×3 grid
  icon instead of a label and has no ×.
- **Startup.** A cold start, a distro switch and a finished install all
  land on the apps tab.
- **Search.** The apps tab has an always-visible search bar at the top,
  replacing the 🔍 header button and its toggled search row.
- **FAB.** Shown only on the apps tab. It always opens a **new**
  terminal tab and never switches to an existing one; switching is the
  tab bar's job.
- **`+` button.** Shown in the tab bar only while at least one terminal
  tab exists.
- **Built-in entries** in the apps list:
  - **TAWC Term** opens a new terminal. It exists mainly so it can be
    pinned to the home screen.
  - **Update packages** runs the distro's upgrade command in a new
    terminal tab.
  - **Add entry** does what ⋮ → "Add entry…" does. It is hideable but
    not editable.
- **No pending terminal.** A terminal that has opened is a tab until it
  exits or is closed.
- **Keyboard.** Every time a terminal opens or its tab is selected, the
  soft keyboard pops up.

## Implementation rule: simplify as you go

This is a refactor as well as a UX change. Delete machinery the new
model makes pointless; don't keep it alive behind flags or
compatibility paths. In particular:

- **The pending state goes entirely.** That means:
  - `TerminalSessions.pending`, `promotePending`/`maybeDemote` and
    `promotedAt`;
  - the "first real key promotes" hooks in `onKeyDown`, `onCodePoint`
    and paste;
  - `setPendingLabel` and the "pending dies → tap/Enter respawns" path;
  - the `ShellIdle` parts that only serve demotion (`Anchor`,
    `screenIsFresh`, `aloneInSession`) and their tests. `ttyOf` stays
    if `TerminalPane` still uses it;
  - the "skip pending pids" special case in the `SessionService` stray
    scan.
- **The persisted pane state goes entirely.** That means:
  - `Settings.homePane`, the `HomePane` enum, `choosePane()` and
    `commandTerminalFor`;
  - the forced TERMINAL pane in `InstallActivity`;
  - the FAB's two-way toggle and the terminal ⋮ "Apps" item.

  Leave the stale prefs key unread; it needs no migration.
- **Selection is plain in-memory state.** The selected tab (apps or a
  session) lives on the activity. Rotation and recreation keep it; a
  cold start resets it to apps. Nothing about it is persisted.
- **`keyboardOnShow` goes too.** It is replaced by one rule: opening or
  selecting a terminal shows the keyboard (see Keyboard below).
- **Expect `MainActivity.refresh()` to shrink.** INTRO/INFO stay, but
  TERMINAL vs APPS stops being a pane choice. One "distro home" view
  holds the tab bar plus either the apps content or the terminal view.
  If `AppsPane` and `TerminalPane` end up sharing a host view, merge
  their header/menu plumbing instead of duplicating it.
- **Remove dead code, notes and tests in the same change.** Don't leave
  stubs.

## Tab bar

`[≡][⊞][term 1 ×][term 2 ×]…[+][⋮]`

- **⊞ (apps):** this tab's look and behaviour are listed under Goal. It
  must keep the same width and selection styling as the other tabs.
- **`+`:** visible iff ≥1 terminal tab exists. It opens a new shell tab
  and selects it.
- **The distro name** used to be in the apps header. Now it goes in the
  search hint ("Search Arch Linux ARM").
- **⋮ menu:**
  - Show the apps-only items ("Show hidden (N)", "Add entry…") when the
    apps tab is selected.
  - Show the terminal-only items ("Close all", "Keep awake") when a
    terminal tab is selected.
  - The shared items stay as they are.
- **Close all:** hangs up every terminal and selects the apps tab. It
  doesn't close the app.
- **Non-tawcroot installs** (proot/chroot are debug-only and have no
  terminal) get these changes:
  - the bar shows only ⊞;
  - there is no FAB;
  - the TAWC Term and Update packages entries are omitted.

## Terminal lifecycle

When a terminal goes away, whether its shell exits or the user taps its
×:

| What was selected | Other terminals left? | Result |
|---|---|---|
| This terminal | none | close the app (`finishAndRemoveTask`, as today) |
| This terminal | some | select the neighbouring terminal (prefer the right one, else the left) |
| Apps tab or another terminal | any | remove the tab; the selection stays |

- **Apps-tab case:** if the apps tab is selected and you close the last
  terminal, the app stays open.
- **Command tabs** keep `HOLD_OPEN_TRAILER`. Their shell only "exits"
  after the trailing keypress, which then follows the table above.
- **Unchanged:** shells keep running detached across activity
  destruction. A recents swipe still hangs up everything (`selfRemoving`
  is unchanged).

## Apps list

- **Search bar.** It sits at the top and never collapses. It reuses the
  current behaviour:
  - Enter/GO launches the top match;
  - Back clears a non-empty query first;
  - typing on a hardware keyboard with nothing focused types into it.
- **Focus.** The search bar must not take focus or pop the keyboard
  when the apps tab is shown; that happens only on tap or hardware
  typing.
- **Built-in entries.** `LauncherEntry` gains a built-in kind.
  - They are synthesized on the Kotlin side after `scan()` and are not
    `.desktop` files.
  - Their reserved ids (e.g. `tawc:term`, `tawc:update`,
    `tawc:add-entry`) can't collide with `.desktop` ids.
  - The search query matches them like any other entry.
  - Hide/unhide works through the existing `hiddenDesktopIds`.
  - Edit is always disabled.
  - Placement: TAWC Term and Update packages sort by name with
    everything else. Add entry goes last, regardless of the query, so it
    is easy to find.

| Entry | Action | Pin to home screen |
|---|---|---|
| TAWC Term | new shell tab | yes |
| Update packages | new command tab running the distro's upgrade command | yes |
| Add entry | open `DesktopFileEditorActivity` (new) | no |

- **Icons.** TAWC Term uses `ic_terminal`. The other two get small
  vector drawables.
- **Update command.** Add a `Distro.upgradeCommand` next to the existing
  bootstrap hooks.
  - Arch/Manjaro: `pacman -Syu`.
  - Debian: `apt update && apt full-upgrade`.
  - Void: `xbps-install -Su`.
  - It runs interactively, so prompts are answered in the terminal, and
    it uses the label "Update packages". Check whether the bootstrap
    code already holds these strings and share them where it reasonably
    can.
- **Pinned shortcuts for built-ins:**
  - Use the existing `<installId>/<desktopId>` id format with the
    reserved desktop id. That keeps the frozen wire format.
  - `ShortcutLaunchActivity` resolves built-ins before it rescans.
  - TAWC Term and Update packages go through the `.CommandLaunch` path
    (a null command means a plain shell).

## Keyboard

Any path that creates a terminal tab must show the soft keyboard once
the view is attached. That covers:

- the FAB;
- `+`;
- TAWC Term;
- a `Terminal=true` entry;
- Update packages;
- a home-screen shortcut on a cold start;
- a home-screen shortcut through `onNewIntent`, which today misses it.

Selecting an existing terminal tab shows the keyboard too. That
includes the neighbour selected after a terminal closes, from the
lifecycle table. Implement both in one place, the "select terminal tab"
function that opening a new tab also goes through, not as a flag
threaded through `refresh()`.

Selecting the apps tab hides the keyboard unless the search bar has
focus.

## Debug surfaces and tests

- **Broker actions.** Replace `home-pane` with something like
  `home-tab apps|<n>|new`. Make `terminal-state` report the tab list and
  the selection instead of pending/in-use. Update the `adb.rs`/
  `helpers.rs` callers.
- **Integration tests:**
  - Delete `home_terminal::test_terminal_returns_to_pending_when_idle`.
  - Add lifecycle tests that cover each row of the table: last selected
    terminal exits → app closes; exit with another terminal open →
    neighbour selected; close from the apps tab → app stays open.
  - Add a test that the built-in entries appear in `launcher-list` and
    that hiding one works.
  - Fix the `lazy_compositor.rs` uses of `home-pane`.
- **Unit tests.** Update `TerminalSessionsTest` for the registry without
  pending. Extend `LauncherEntryTest` with the built-ins and with Add
  entry staying last.
- **Manual check on the `.tawctarget` device:**
  - search bar layout at phone width;
  - keyboard pop on each open path and on tab switch;
  - pinning TAWC Term and launching it cold and warm.

## Notes to update

- `notes/terminal.md`: rewrite the pending/in-use section and the state
  table into the lifecycle table above.
- `notes/launcher.md`: add the built-ins and the search bar.
- `notes/android.md`, `notes/exec-broker.md`: update the pane and broker
  references.
- `notes/session-service.md`: remove the mention of pending pids.
