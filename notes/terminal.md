# In-App Terminal

A per-distro terminal (home-screen "Terminal" button under "Manage")
giving an interactive shell into an installed rootfs without the
compositor/graphics stack.

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
termux's default double-row layout, inlined in `TerminalActivity`;
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
exists but dies on startup takes every new tab with it and leaves no
in-app way back; recovery is `scripts/rootfs-run.sh 'usermod -s
/bin/bash root'` from a dev box, or reinstalling the distro (`chsh`
itself PAM-prompts once root's current shell isn't in `/etc/shells`,
so it can't undo a `chsh` to a bogus path).

tawcroot-only: chroot spawns via `su` (no pty fd to hand over) and
proot is dev-only, so the button is gated on
`Installation.method == tawcroot` + state READY.

## Command sessions (launcher `Terminal=true` entries)

`EntryLauncher` routes `Terminal=true` launcher entries on tawcroot
installs here: `EXTRA_COMMAND` (the entry's Exec line) + `EXTRA_LABEL`
(the entry name) on the same per-distro document URI.
`ptyShellExec(command=…)` swaps `-l` for `-lc <command>` — still a
login shell so profile env fires, matching `startInside`. The command
gets a hold-open trailer (`; __c=$?; printf '\n[exited %d — press any
key]\n' "$__c"; read -rsn1`) so a short script's output doesn't vanish
with the tab: the session is still alive during `read`, so a keypress
ends the shell and the normal tab-removal flow runs — no
session-lifecycle changes. `onCreate` consumes the extras
(`removeExtra`) so recreation doesn't respawn the command; a repeat
launch while the task is alive lands in `onNewIntent`
(`intoExisting`) and opens a new tab running the command. The tab is
labelled with the entry name via `TerminalSession.mSessionName` until
an OSC title arrives. proot/chroot entries keep the headless launch
plus a logcat warn (debug-only methods). Verified on-device
2026-07-04.

## Session model

`TerminalSessions` is a process-wide registry of installation id → an
ordered list of `TerminalSession`s plus the selected index: multiple
shells per distro shown as tabs, reattached (sessions, labels, and
selection) on reopen/rotation (`TerminalActivity` uses the
CompositorActivity document trick — `documentLaunchMode="intoExisting"`
+ `tawc://terminal/<id>` URI — for one activity/recents card per
distro). The registry is dumb bookkeeping (`@Synchronized` order +
selection, JVM-unit-tested); tab policy lives in the activity. One
`TerminalView` shows the selected session via `attachSession()`
(termux-app's multi-session pattern: it resets emulator state and
`updateSize()`s, so background tabs keep a stale pty size until
selected). Last shell exiting (or its tab closed) finishes the
activity and drops the recents card; swiping the card kills all of the
distro's shells. Every registered session holds a `Terminal` reason in
`SessionHolds`, so the process is a foreground service while any shell
is alive ([session-service.md](session-service.md)); the hold lives in
the registry, not the activity, because sessions outlive it.

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
While the title is null/blank the label is a static "Terminal";
duplicate labels are fine (desktop terminals behave the same).
Verified on-device 2026-06-10. The compact
`TerminalTabBar` (fixed dark palette against the always-black terminal
surface) replaced the scaffold toolbar; system back still just
backgrounds the task.

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
