# In-App Terminal

The home screen's terminal pane (`terminal/TerminalPane`, hosted by
`MainActivity`; see android.md "Home screen"): an interactive shell into
the open distro's rootfs, without the compositor/graphics stack.
Opening the app lands in it, termux-style.

## Termux terminal modules

The UI is termux's terminal widget, vendored as `deps/termux-app`
(pinned in `deps/deps.list`) and wired in unpatched as Gradle included
projects `:terminal-emulator` and `:terminal-view`
(`settings.gradle.kts` `projectDir` redirects; the dep is ensured at
settings-evaluation time because included projects must exist before
configuration):

- `terminal-emulator` — VT emulation plus a small JNI
  (`libtermux.so`, built by ndk-build during the app build) that opens
  the pty pair, forks, `setsid()`s, wires the slave to stdio, sets a
  caller-supplied env, and execs an arbitrary argv.
- `terminal-view` — `TerminalView`, a plain `android.view.View` with
  IME/scroll/selection/mouse-reporting handling.

Scrollback: upstream `onScreenUpdated()` snaps to the bottom on every
output chunk. `TerminalPane.screenUpdated()` wraps it through public
API to hold the viewport when scrolled back (`topRow -= scrollCounter`,
clamped to the transcript); input/paste snaps back to the bottom.
Rotation, font size and tab switches still reset to the bottom.

Font: bundled Hack v3.003 (`res/font/hack_regular.ttf`, from the upstream release zip) rather than
`Typeface.MONOSPACE`, whose OEM mapping may not be monospace (termux's
renderer then stretches mismatched glyphs per cell). Size is 13sp (so it
follows system font size) times the "Terminal scale" setting, reapplied
on resume. Pinch-zoom is disabled.

Both modules are **Apache-2.0** (the explicit exception in termux-app's
`LICENSE.md`; they descend from jackpal's Android-Terminal-Emulator).
Termux packages/bootstrap are not involved at all; the shell is one
the distro itself ships (see "Spawn path").

The extra-keys row (ESC/TAB/CTRL/arrows above the IME) is termux's
`ExtraKeysView` + `TerminalExtraKeys`, cherry-picked from the
`termux-shared` module by the in-repo `:termux-extrakeys` shim
(`termux-extrakeys/build.gradle.kts`): it compiles just those classes
straight out of the vendored checkout (an include-filtered `srcDir`),
plus a local trimmed `ThemeUtils` stand-in so the rest of
termux-shared (Logger → guava, markwon, NDK code, ...) stays out of
the build. **License**: those classes are GPLv3-only, a deliberate
exception to the otherwise-MIT app (decided 2026-06; the repo sources
stay MIT, but distributed APKs are subject to GPLv3). Config is
termux's default double-row layout, inlined in `TerminalPane`;
held CTRL/ALT/SHIFT/FN flow through the `read*Key()`
`TerminalViewClient` callbacks, same as termux.

The rest of `termux-shared` and the GPLv3 `app` module are still not
built or shipped.

The modules read `minSdkVersion`/`targetSdkVersion`/`compileSdkVersion`/
`ndkVersion` from root `gradle.properties` (keys added there; keep in
sync with `app/build.gradle.kts`).

Known wart: `TerminalSession.wrapFileDescriptor` reflects on the
private `FileDescriptor.descriptor` field (greylisted hidden API).
Works on current Android; termux and UserLAnd ship the same code.

## Spawn path

`TawcrootMethod.ptyShellExec` builds the same tawcroot envelope as
`startInside` (binds, `env -i` rootfs env, `<shell> -l`) minus the
`setsid` prefix — the termux JNI setsid()s the child itself, which
keeps the rootfs-session invariant (rootfs-sessions.md) and makes the
shell the session leader of the pty, so job control/readline/curses
work (unlike the pipe-fed `RunCommandOp`/exec-broker paths). `TERM`/
`COLORTERM` are appended after the shared `RootfsEnv` map, which the
non-tty paths don't want.

Which shell: `RootShell.resolve` reads field 7 of the first `root`
line of `<rootfs>/etc/passwd`, so `chsh -s /usr/bin/zsh` inside the
rootfs is honoured (wmww/tawc#7). The rootfs is app-uid-owned under
tawcroot, so that's a plain host-side read; symlinks are followed
*within* the rootfs (an absolute `/usr/bin/zsh -> /usr/bin/zsh-5.9`
must not be read against the host's `/`). It falls back to `/bin/bash`
whenever the answer isn't usable — no passwd file or `root` line,
empty field, or a shell that isn't an existing executable in the
rootfs (`chsh` to a since-uninstalled shell). `-l` is accepted by
bash, zsh, fish, dash and ksh alike. There is deliberately no app-side
shell setting: `chsh` already expresses it.

Only interactive tabs switch shells. Every command spawn — command
sessions below, launcher Exec lines, install steps, `RunCommandOp`,
the exec broker, `rootfs-run.sh` — stays on `/bin/bash -lc`, because
Exec lines, the hold-open trailer and the install scripts all assume
POSIX-or-better syntax that fish doesn't speak. `SHELL` in the
`RootfsEnv` map *is* the resolved shell on every tawcroot spawn, so
scripts and GUI terminals launched from the desktop open the same one.

`ShellDefaults`' prompt and cwd tab title are bash-only (they live in
`/root/.bashrc` + `/usr/lib/tawc/bashrc`), so a zsh/fish tab gets that
distro's own prompt and — with no OSC title arriving — a `Term <n>`
label. Configuring a non-bash prompt is the user's job. A shell that
exists but dies on startup kills the pending shell before anyone
types; the pane keeps its transcript (termux's exit line) instead of
closing the app, and a tap or Enter respawns. Every new shell dies the
same way, so the fix is outside the terminal: `scripts/rootfs-run.sh
'usermod -s /bin/bash root'` from a dev box, or reinstalling the
distro (`chsh` itself PAM-prompts once root's current shell isn't in
`/etc/shells`, so it can't undo a `chsh` to a bogus path).

tawcroot-only: chroot spawns via `su` (no pty fd to hand over) and
proot is dev-only, so the pane (and its FAB / ⋮ entry) is gated on
`Installation.method == tawcroot` + state READY; other installs get the
apps pane.

## Command sessions (launcher `Terminal=true` entries)

`EntryLauncher` routes `Terminal=true` launcher entries on tawcroot
installs here: `MainActivity` with `EXTRA_DISTRO`, `EXTRA_COMMAND`
(the entry's Exec line) and `EXTRA_LABEL` (the entry name). The home
screen opens that distro and shows the terminal pane (without writing
`Settings.homePane`); the command tab is in use from birth and replaces
a pending shell.
`ptyShellExec(command=…)` swaps `-l` for `-lc <command>` — still a
login shell so profile env fires, matching `startInside`. The command
gets a hold-open trailer (`; __c=$?; printf '\n[exited %d — press any
key]\n' "$__c"; read -rsn1`) so a short script's output doesn't vanish
with the tab: the session is still alive during `read`, so a keypress
ends the shell and the normal tab-removal flow runs — no
session-lifecycle changes. `onCreate` consumes the extras
(`removeExtra`; `savedInstanceState` means restore) so recreation
doesn't respawn the command; a launch while the activity is alive
lands in `onNewIntent` (`singleTask`) and opens a new tab. The tab is
labelled with the entry name via `TerminalSession.mSessionName` until
an OSC title arrives. proot/chroot entries keep the headless launch
plus a logcat warn (debug-only methods). Verified on-device
2026-07-04.

## Session model

`TerminalSessions` is a process-wide registry of installation id → an
ordered list of in-use `TerminalSession`s plus the selected index, and
at most one *pending* session. Multiple shells per distro show as
tabs, reattached (sessions, labels, selection) after recreation or a
distro switch. The registry is dumb bookkeeping (`@Synchronized`,
JVM-unit-tested); tab policy lives in `TerminalPane`. One
`TerminalView` shows the selected session via `attachSession()`
(termux-app's multi-session pattern: it resets emulator state and
`updateSize()`s, so background tabs keep a stale pty size until
selected).

**Pending vs in use.** With no in-use tabs, showing the pane spawns a
pending shell. It becomes in use (`promote`, appended as the last tab)
on the first input that reaches it: a non-system, non-modifier
`onKeyDown` or an `onCodePoint` client callback (together these gate
every `TerminalView` write path except autofill; the extra keys route
through them) or a paste. Output alone (bashrc, the prompt) doesn't
count. `+` and command tabs are in use from birth.

**Back to pending.** The promoted shell is demoted
(`TerminalSessions.demote`, hold released) when its first input is
erased with nothing entered and nothing else runs in its session —
checked on each output chunk and bell (`ShellIdle`, unit-tested
against a real emulator):

- *Screen:* not the alternate screen, the cursor back where the
  pending shell got its first input (same size), nothing at or after
  it. Enter, a paste or a tab switch forfeits it.
- *Session:* the shell is the only process whose session id is its pid
  (`/proc/*/stat`), in case a key binding started something.

Once anything was entered the tab stays in use until it closes.
⋮ **Close all** (above Apps, in-use only) hangs up every tab
(`TerminalSessions.hangUp`, as the swipe below) and leaves one fresh
pending shell; closing the last tab by `exit`/× closes the app
instead.
`home_terminal::test_terminal_returns_to_pending_when_idle` drives the
real shell.

| | Pending | In use |
|---|---|---|
| `SessionHolds` | none — no service, no notification | `Reason.Terminal` |
| Tab strip | hidden, distro label instead, no `+` | tabs, `+` after the last |
| FAB | Apps | none (⋮ → Apps) |
| `keepScreenOn` | off | on |
| Pane / distro switch | killed | keep running, pane detaches |
| `MainActivity.onDestroy` | killed (recreation reattaches) | untouched |
| Recents swipe of the home task | killed | SIGHUP (below) |
| Notification Exit | killed | killed |
| Uninstall started | killed (`InstallationService.startUninstall`) | swept by the uninstall |
| First input erased (above) | — | back to pending |
| Shell exits / × on last tab | transcript stays, tap/Enter respawns | tab closes; last one → `finishAndRemoveTask` |
| ⋮ Close all | — | hung up; one new pending shell |

**Swipe = closing the windows.** Only an explicit recents swipe of the
home task closes in-use shells, like closing desktop terminal windows:
`SessionService.onTaskRemoved` (never called for system kills; the
service is up while any tab is in use) checks the root activity's
affinity is the default one (not a compositor window) and runs
`TerminalSessions.hangUpAll`: SIGHUP to each shell (the pid is the
shell — tawcroot is in-process), which hangs up its jobs as bash/zsh
do on a pty hangup (this needs tawcroot's top-level SIGHUP reset:
Android apps inherit it ignored); shells still up after 3 s are killed. `nohup`,
`disown` and `setsid` children survive and keep the service up as the
stray tail ([session-service.md](session-service.md)).
`onTaskRemoved` also fires for the app's own `finishAndRemoveTask`
(last tab closed), which must not hang up other distros' tabs:
`TerminalSessions.selfRemoving` marks that one (reset by
`MainActivity.onCreate` in case the callback never comes). Otherwise
in-use shells outlive the activity; the notification brings them
back. Detached sessions get a
`DetachedTerminalClient`, which drops their registry entry (tab or
pending slot) if they exit meanwhile. `SessionService`'s stray scan
skips pending pids ([session-service.md](session-service.md)).

Never `finishIfRunning()` a session no view has sized: its pid is 0
but it counts as running, and `kill(0, SIGKILL)` takes down the app's
own process group. `TerminalSession.kill()` guards that.

Tab labels are the session's xterm window title (OSC 0/2, parsed by
the vendored emulator, surfaced via `TerminalSession.getTitle()` /
`onTitleChanged`), and apps that set their own title (vim, htop, ssh)
show through while running. TAWC's shipped shell defaults
(`ShellDefaults`) set a cwd-only title (user@host carries no info —
always root@localhost) by embedding the escape in PS1, which is
emitted after any distro PROMPT_COMMAND title each prompt, so it wins
by default yet stays user-overridable like the rest of the defaults
file. On rootfses without the defaults (pre-ShellDefaults installs,
or sourcing opted out) the label is whatever the distro sets: Arch's
`/etc/bash.bashrc` PROMPT_COMMAND gives `root@localhost:~` (needs
`USER`, which `RootfsEnv` sets — login(1) never runs to set it),
Debian-root sets nothing (its escape lives only in
`/etc/skel/.bashrc`). A title of exactly `~` (the cwd default at
home, i.e. every fresh tab) is shown as `Term <n>` by tab position —
app-side, since the number must follow the index as tabs close.
Duplicate labels are fine (desktop terminals behave the same).
Verified on-device 2026-06-10. The `TerminalTabBar` (`[≡][tabs… +][⋮]`,
fixed dark palette against the always-black terminal surface) is the
pane's top row. The selected tab has a faint fill and a 2 dp accent
strip along its top. Title changes are applied after 150 ms of quiet:
Arch's `PROMPT_COMMAND` title (`root@localhost:~`) and ShellDefaults'
`~` can arrive in separate output chunks, and relabelling on each
flashed the long one on every new tab.

The compositor is *not* started or waited for. The Wayland/X11 env
vars are still set, so GUI apps launched from the terminal connect
only if a compositor session is already up; CLI work needs nothing.

## Running Android commands

`ando <cmd>` (installed in every rootfs at `/usr/local/bin/ando`) runs
a command as a plain Android process — useful from a terminal session
for `getprop`, `am`/`pm`, copying into shared storage, or `ando su -c
'…'` on rooted devices. Since the terminal child holds the real pty,
ando children inherit working tty semantics (no job control — the
rootfs shell owns the pty session). See [ando.md](ando.md).
