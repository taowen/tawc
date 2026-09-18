# Usecase tests

One-shot, agent-executed manual test plans. Each file here describes a
realistic end-user usecase that is *expected to work* on the current build.
Each file is assigned to a separate autonomous agent and run without human
help. These are exploratory product tests, not scripted regression tests —
the goal is to catch snags a real user would hit, not to grow the automated
suite (though a test may *suggest* an automated test if it finds something
worth locking down).

## Procedure

1. Read this README fully, then your assigned test file.
2. Follow all repo rules (CLAUDE.md), especially device safety, cache-proxy
   rules, and the `/data/local/tmp/tawc-dev/` scratch policy.
3. Check `.tawctarget`. If your test's **Target** line is incompatible with
   the current target (e.g. physical-only test, target is `emulator` or
   `none`), stop and report — never substitute devices.
4. Assume you need exclusive device access. Do not run alongside other
   usecase-test agents or the integration suite.
5. Tests run against an **Arch Linux tawcroot install** by default; a test
   file may name a different distro when it matters. Confirm what is
   installed with `scripts/rootfs-run.sh 'cat /etc/os-release'` (set
   `TAWC_INSTALL_ID` when more than one distro is installed). If the needed
   distro is missing, install it via the `install` broker action with
   `--arg method=tawcroot` and the cache proxy (see CLAUDE.md and
   notes/exec-broker.md).
6. Package installs inside the rootfs: a proxied install hard-wires the
   mirror config to `http://127.0.0.1:8080` (notes/cache-proxy.md), reached
   from the device via `adb reverse` which the proxy script maintains.
   Before any `pacman -S`, check the proxy from the host:
   `curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/` must
   print `404`. Connection refused means the proxy is down — you are
   blocked (never start it yourself): report and stop.
7. Prefer existing scripts (`scripts/rootfs-run.sh`, `scripts/tawc-exec.sh`)
   over ad-hoc adb. The exec broker has no PTY, so curses/interactive
   programs will not render through `rootfs-run.sh`; drive them inside
   `tmux` or via the broker input actions (notes/exec-broker.md).
8. Where a plan calls for visual verification: screenshot to
   `/data/local/tmp/tawc-dev/`, pull to your host scratchpad, analyze with
   a sub-agent, then delete both copies.

## Outcomes

**Success** — everything behaved as the plan expects:

- Clean up (below).
- Delete the test file.
- Add a one-line entry to the "Completed" list at the bottom of this README.
- Commit (`usecase-tests: <name> passed`).

**Problems** — something broke, diverged from documented behavior, or was
clearly worse than a user would tolerate:

- Debug enough to characterize the failure and which layer owns it
  (distro packaging, tawcroot, compositor, app, docs).
- File a new issue in `issues/usecase_tests/` with details and a repro
  (or extend an existing issue if it is the same bug).
- Update your test file with what happened and the issue reference. Do
  **not** delete it and do **not** add it to the Completed list.
- Code changes are in scope only if simple and minor; otherwise stop at
  the issue.
- Commit.

Either way, commit only this directory, `issues/usecase_tests/`, and (rarely) minor
fixes or note corrections that fell out of the run.

## Cleanup

Leave the device as you found it:

- Remove files you created in the rootfs (`/root/usecase-*`, `/tmp`, …).
- Uninstall packages you installed for the test (`pacman -Rns …`) unless
  removal would destabilize the install; if you leave packages behind, say
  so in the commit message.
- Kill any background processes you started in the rootfs.
- Delete everything you put in `/data/local/tmp/tawc-dev/` and host copies.
- Revert Android-side state you changed (appops grants, ando toggles,
  settings, added binds).

## Completed

(one line per finished test; kept so future test authors don't re-cover
the same ground)

- android-serve-http-to-browser — passed on physical (Arch tawcroot; browser render, tap-through, adb-forward host access, clean shutdown); side finding: issues/usecase_tests/proc-cmdline-masking-breaks-pgrep-pkill-ps.md
- android-ando-broker — passed on physical (Arch tawcroot, re-run 2026-08-10). Disabled (default) → clean exit-127 refusal with enable instructions; `set-ando enabled=true` takes effect immediately; `ando getprop ro.build.version.release`=14; `ando /system/bin/sh -c 'id'` reports the real app uid 10250/`untrusted_app` vs fake `uid=0(root)` inside the rootfs; exit codes propagate (42); `ando getprop | grep`/`| wc -l` pipe like normal pipes (668 props, `head -5` truncation clean); disable restores the refusal. Step 5 (`ando am start …`) behaves as the documented platform limit: silent exit 255 for the app uid because `am`/`cmd <service>` binder shell transactions are root/shell-only (notes/ando.md "Semantics and known limits"), while rooted `ando -r am start -a VIEW -d https://example.com` returned 0 and Firefox came foreground on example.com (screenshot-verified). Unrooted fix tracked in plans/ando-am.md — rewrite step 5 when that ships. Cleanup: ando disabled, screenshots deleted, device returned to launcher.
- android-shared-storage-binds — passed on physical (Arch tawcroot; ManageBinds UI add/remove/suggestion/RO-dialog, both-direction RW round-trip, RO EROFS enforcement, revoked-grant fail-closed with actionable error + grant banner, live re-grant recovery; last-card-under-add-button issue not reproduced); side finding: password-autofill prompts over bind/run dialogs (fixed 2026-08-09: fields switched to textUri + autofill opt-out, verified on device; issue deleted)
- cli-background-daemon — passed on physical (Arch tawcroot; nohup daemon survives session exit and ~10 min app-backgrounded, host-side tawc-exec SIGKILL triggers broker descendant kill, kill-by-pid works); pkill/pgrep/ps-by-name still broken per issues/usecase_tests/proc-cmdline-masking-breaks-pgrep-pkill-ps.md (extended with confirmation)
- cli-c-toolchain — passed on physical (Arch tawcroot, 2026-07-13) after `pacman -S base-devel` — a fresh guest deliberately ships no toolchain (base set is just `inetutils`, see notes/installation.md PKG_INSTALL). gcc 16.1.1/make 4.4.1: `cc -O2` hello world, multi-file make project + clean, pthreads (total=800000), fork/execve/waitpid, GNU hello 2.12.1 autotools configure/make/run all clean; only benign systemd hook warnings during pacman ops. The plan's original prerequisite wrongly claimed base-devel was present at bootstrap — a test-spec mistake (docs corrected then), not a product bug; the issue filed for it was resolved and deleted.
- cli-git-workflow — passed on physical (Arch tawcroot; HTTPS clone of octocat/Hello-World, full local workflow with real merge-conflict resolve, diff, --graph log, annotated tag, gc; git fsck clean; git status over 1003 files returned in ~0.06s — no tawcroot syscall slowdown)
- cli-media-tools — passed on physical (Arch tawcroot, re-verified 2026-08-10). ImageMagick 7.1.2-26 (`pacman -S imagemagick` pulls only liblqr+libraqm): `gradient:` 800x600 PNG, `identify`, resize to 200x150, PNG→JPEG q90, and `montage` tiling to 420x160 all correct. ffmpeg n8.1.2 (pre-installed, left in place): 5 s `testsrc` H.264 in 0.5 s, VP9/webm transcode in 3.9 s (not pathological), 3 extracted PNG frames, `ffprobe` confirms h264/vp9 640x360 dur 5.0; a file-to-file `sine` audio stream encodes to AAC fine with no audio bridge. Upstream/distro caveat, NOT a TAWC bug: ImageMagick's default/fontconfig font lookup is broken in the Arch aarch64 package — bare `-annotate`, `label:`, `caption:` and `montage`'s auto-labels fail with ``unable to read font ` (invalid stream operation)'``, while explicit `-font /usr/share/fonts/...`, a `type.xml` name, and the `pango:` coder all render (so in-process fontconfig+freetype are healthy). Its issue file was dismissed and deleted.
- cli-node-http-server — passed on physical (Arch tawcroot; `pacman -S nodejs npm` v26.4.0/12.0.0 clean install, `npm init`/`npm install ms` via direct registry, `require("ms")("2 days")`=172800000; plain http server on 127.0.0.1:3000 served correct body, 50/50 request loop clean, 0.0.0.0 bind reachable via 127.0.0.1+localhost; no io_uring errors in logs, clean shutdown, no orphans; `nohup`+`setsid` background server survives across sessions per cli-background-daemon. Note: `$!` after `setsid nohup node &` returns the wrapper pid not node's (normal Unix) — track the real pid via a self-written pidfile or `/proc` comm scan; observed one transient read-after-unlink stale-pidfile read during a slow node cold start, not reproducible on demand and never affected serving, so no issue filed.)
- cli-python-pip-venv — passed on physical (Arch tawcroot; python 3.14.6 already present, `pacman -S python-pip` clean via proxy; `python -m venv` + activate, `pip install requests` reached PyPI directly over DNS 8.8.8.8 + TLS ca-certificates OK; script: HTTPS GET example.com=200, json dict roundtrip, `subprocess.run(["uname","-a"])`, file write/read all passed; no-PTY REPL `echo 'print(6*7)' | rootfs-run.sh python`=42. Cleanup: removed /root/usecase-py, `pacman -Rns python-pip` also removed 8 pulled-in deps, python retained. No issues filed.)
- cli-ssh-server — passed on physical (Arch tawcroot, re-run 2026-08-10 after tawcroot started fake-accepting guest seccomp installs). openssh-10.4p1 installs, `ssh-keygen -A`, `sshd -D -e -p 2222` listens, and key-authed sessions work both loopback and from the **host** over `adb forward tcp:2222` (`host-reached-openssh`, `uid=0(root)`); `ssh -tt` gets a real pty (`/dev/pts/0`, `TERM=xterm-256color`). sshd's `-e` log is clean — the old `ssh_sandbox_child: prctl(PR_SET_SECCOMP): Operation not permitted [preauth]` kex-reset failure is gone; only a benign `syslogin_perform_logout: logout() returned an error` on pty logout (utmp/wtmp, harmless). Port-22 bind fails `Permission denied` as expected (no `CAP_NET_BIND_SERVICE`). dropbear not needed this run (it passed 2026-07-13). Cleanup: sshd killed, forward removed, keys+host copy deleted, `pacman -Rns openssh`.
- cli-sqlite-wal — passed on physical (Arch tawcroot; sqlite 3.53.3 already present as a dependency, kept). WAL engages (`journal_mode` reports `wal`, not silent `delete`); 10k-row transaction count=10000/sum=50005000 ok. Concurrency: overlapping writer + reader loops both proceed, reader counts strictly non-decreasing, 0 SQLITE_BUSY/errors; `-wal`+mmap'd `-shm` sidecars appear during concurrent access. `integrity_check`=ok. Crash recovery: `kill -9` of a writer with `wal_autocheckpoint=0` left a 29MB `-wal` + 65KB `-shm`; reopen recovered committed frames (count 25200→32300), `integrity_check`/`quick_check`=ok, `wal_checkpoint(TRUNCATE)`=`0|0|0` cleared sidecars. DELETE mode reports `delete`, no sidecars, `-journal` rollback path works (rollback keeps count, commit +1). No issues filed. Harness note: SQLite's `PRAGMA busy_timeout=N` assignment form echoes N on stdout; and multi-line SQL/dot-commands with embedded newlines get mangled through the exec broker — use single-line `;`-separated SQL.
- cli-system-upgrade — dropped without running, don't re-author: a dev install is bootstrapped through the cache proxy's year-frozen repo dbs, so `pacman -Qu` is always 0 and `-Syu` is a no-op ("nothing to do"); exercising a real upgrade would need a user-driven db-cache wipe. The user runs `-Syu` on production installs regularly with no problems, so the usecase isn't worth keeping blocked.
- cli-tmux-curses — passed on physical (Arch tawcroot, re-run 2026-08-10 after the tawcroot SO_PEERCRED virtualization fix). tmux 3.7b: `new-session -d`, `ls`, `send-keys`/`capture-pane` round trips, `kill-server` all work — the old blanket `access not allowed` peer-cred rejection is gone. vim edit+`:wq` saved correct contents; htop drew a real process table and (with `H`) showed `tmux`/`-bash`/`htop`/`sleep` with truthful cmdlines, matching `ps -eo pid,stat,comm`; session survived 8.5 min and ~20 separate broker invocations; `split-window` gave two panes both rendering their own commands. Side finding: issues/usecase_tests/proc-uptime-loadavg-missing-breaks-uptime-and-htop-meters.md (`uptime` errors, htop shows all CPUs `offline` / `nan` load / unknown uptime — SELinux denies the app `/proc/{uptime,loadavg,stat}` and only `/proc/stat` is shadowed). Cleanup: `pacman -Rns tmux vim htop` (+3 deps).
- cli-unix-toolbox — passed on physical (Arch tawcroot, re-run 2026-08-10 after the tawcroot large-argv fix). 50 MB urandom sha256 round-trips through gzip/gunzip (1.9s) and xz/unxz (12s); tar preserves modes 600/750/4755 (setuid survives), symlink targets (rel+abs), and hardlink inode identity; 2000-file tree: `find|wc -l`=2000, `grep -rl`=2000, alpha/beta/gamma freq count correct; the previously-broken large-argv idioms now work — `cat many/dir*/*.txt`=6000 lines, `grep` over 2000 file args=2000 hits, `grep -q` exit 0, `ls`/`wc -l` over 2000 args correct; `mv`/`cp -a`/`rm -rf`/`df -h`/`du -sh` sane. No packages installed. Accepted limit (documented, loud not silent): >4096 argv entries gives `Argument list too long` even though `getconf ARG_MAX` reports 2 MB — verified at 4200 args; the old silent-destruction failure is gone.
- cli-weird-paths — passed on physical (Arch tawcroot). All hostile filenames (spaces, leading `-`, quotes, `äöü™日本語`, emoji, embedded newline, 255-byte, `...`) round-trip byte-exact via shell `ls -b` and independent python `os.listdir`; content/rename/delete all clean. Symlinks normal: a→b→c chain reads target, broken link is-symlink+ENOENT, self-loop gives ELOOP (no hang), absolute in-rootfs link works. Documented `..`-after-symlink lexical divergence confirmed (`dir/link/../x` with link→other resolves to `dir/x`, not the kernel target). Depth cap characterized: 256 total path components OK, 257 → clean `ENAMETOOLONG` (no crash/hang); getcwd from 200-deep correct; find over tree clean. No issues filed. Harness note: a filename with an embedded newline inflates `find | wc -l` by one line — a counting artifact, not a duplicate.
- gui-gtk3-app-launcher — passed on physical (Arch tawcroot). `pacman -S galculator` (2.1.4-10, no extra deps) appeared in the launcher immediately with no manual refresh; launched via the launcher UI (home card "Search apps" field → LauncherActivity → tap Galculator row). Rendered un-tinted on the GL/wlegl path (`surfaces_wlegl=1 surfaces_shm=0`, no magenta); injected touch (`adb input tap`) computed 7+5=12 with the display updating each step. GTK3 menus with the workaround as-found (enabled): galculator "View" menu and gtk3-demo `--run=menus` "bar" (non-leftmost) menu both opened anchored under the tapped item (NOT the leftmost fallback the workaround guards against) and dismissed cleanly. Plan-wording caveat, NOT a product bug: step 6's "close via Android Back" is wrong — Back is Escape by design (notes/input.md: dismiss-popup / exit-fullscreen / else ESC); windows close via recents-swipe or the app's own quit (notes/multi-activity.md). Verified real close: gtk3-demo's in-app "Close" decremented toplevels cleanly (3→2, compositor unaffected); full baseline reached via `am force-stop` (uid-wide kill removes all guest procs). Harness gotcha: every `rootfs-run.sh` call pops a LogScreenActivity that grabs foreground and eats taps aimed at compositor windows — dismiss it (Back) before tapping. Cleanup: `pacman -Rns galculator` clean (no deps), gone from launcher-list; screenshots deleted. No issue filed.
- gui-x11-apps — passed on physical (Arch tawcroot; xterm + xeyes via Xwayland). Both render magenta-tinted SHM; keyboard reaches xterm via broker `hardware-key` (`ls /` typed, real listing drew — note `adb shell input` keys are IME-intercepted); each X11 toplevel got its own Android task (`query-state` `hosts=2 x11_surfaces=2`, one shared Xwayland pid); clean shutdown drops `x11_surfaces`/`toplevels` to 0 and Xwayland idles out after its `-terminate 5` grace. Known-limitation, NOT a bug: pointer-*motion* X11 apps (xeyes pupils, hover tooltips) do not track touch — TAWC forwards `wl_touch` only and deliberately does not synthesize `wl_pointer` motion (notes/input.md); the original plan's step 4 asserted pupil-tracking, which contradicts the documented touch-first design, so it was dropped rather than filed. Cleanup: `pacman -Rns xterm xorg-xeyes`, screenshots deleted.
- gui-doom-game — dismissed 2026-08-10, don't re-author: root-caused to the known desktop-GL gap (plans/gl-on-gles-translator.md), which is not a bug to fix inside a usecase test. SDL3 defaults to a desktop-GL profile, so `SDL_CreateWindow(SDL_WINDOW_OPENGL)` asks `eglChooseConfig` for `EGL_RENDERABLE_TYPE=EGL_OPENGL_BIT`, and libhybris/Android EGL has no desktop GL (`EGL_BAD_ATTRIBUTE`, 0 configs; the ES bits give 48 on the same display). Everything downstream is healthy — with the ES profile set, an SDL GL window renders and swaps 60 frames on `OpenGL ES 3.2 / Adreno 660` and exits clean. Earlier blockers found by this test were fixed along the way (SDL haptic/udev netlink stub, `wl_output` from compositor start). Re-author a game test after the translator plan's EGL interposer lands. Its issue file was closed and deleted; the measurements moved into the translator plan.
- cli-man-and-docs — passed on physical (Arch tawcroot; matches slimming policy). Fresh state: no `man` binary, `/usr/share/man` and `/usr/share/doc` absent, `man bash` → `command not found`, `bash --help` fully functional. `pacman -S man-db man-pages` installs cleanly (only cosmetic journald/chroot hook warnings), but the `NoExtract = usr/share/man/*` rule blocks ALL pages including man-pages' own — `man bash`/`man 2 open`/`man pacman` all give graceful `No manual entry` (exit 16), no crash/corruption. Power-user recovery works: delete the `usr/share/man/*` NoExtract line + `pacman -S bash man-pages` repopulates `/usr/share/man` and `man bash`/`man 2 open` render real pages. No issue filed — failure modes are sane and documented.
