# In-app `am` for `ando` (termux-am-style)

## Problem

`ando am start …` exits 255 silently on unrooted devices: `am` wraps
`cmd activity`, and ActivityManagerService accepts its binder
shell-command transaction only from uid 0/2000 (notes/ando.md
"Semantics and known limits"). The "launch apps/URLs from Linux" glue
usecase is therefore root-only today, and the failure mode is hostile.
Platform-sanctioned path for an app: call `Context.startActivity` (and
friends) from its own process — which is exactly what Termux does with
termux-am.

## Goal

`ando am <am-args…>` works on unrooted devices for the subcommands that
map to public app APIs. Scripts written against Termux's `am` (or real
`am`) port by prepending `ando ` — same argument syntax, same output
shape ("Starting: Intent { … }"), real error messages and exit codes
instead of a bare 255.

## Shape: broker-side interception

The ando broker, on receiving a request whose `ARGV[0]` is exactly the
bare string `am`, does not fork/exec. Instead it routes the argv to an
in-app am implementation (Kotlin) that parses am syntax and calls
`startActivity` / `sendBroadcast` / `startService`, writing output to
the already-received stdout/stderr fds and returning the exit code via
the normal `EXIT` message.

Why the broker and not the client or a new command:

- **Zero client change.** The client is a frozen static bionic binary
  and public CLI contract; interception needs no new flags and no wire
  protocol change — argv already arrives via `ARGV` lines, fds and exit
  plumbing already exist.
- **Gating for free.** Reached only through the per-distro ando socket,
  so `andoEnabled` (both layers: listener + bind) covers it with no new
  switches.
- **`ando -r am …` is untouched.** `-r` rewrites argv client-side to
  `["su","root","-c","am …"]`, so argv[0] is `su`, not `am` — rooted
  users keep the real, full-surface am automatically.
- **Escape hatch.** Only bare `am` is intercepted; `ando /system/bin/am …`
  (any argv[0] containing `/`) execs the real binary as today. Shells
  (`ando -s`, `ando sh -c 'am …'`) also resolve the real binary —
  interception applies to direct `ando am` only. Document both.

## Components

- **Broker hook** (`compositor/src/ando.rs`): after header parse, if
  argv[0] == "am", call into Kotlin instead of spawning. The connection
  thread is a plain `std::thread`, so it must attach to the JVM
  (`JavaVM::attach_current_thread`; the VM handle is available at
  `JNI_OnLoad` time — store it like other NativeBridge state). `SIG`
  messages are ignored for intercepted requests (the op is short and
  in-process; nothing to kill), and client EOF just abandons the wait.
- **Kotlin entry** — e.g. `AmRunner.run(argv: Array<String>, stdoutFd,
  stderrFd): Int`, called via JNI with dup'd fds; Kotlin wraps them in
  `FileOutputStream(FileDescriptor)` and closes its dups when done.
  Runs on the broker connection thread (blocking is fine — `am start -W`
  is allowed to block).
- **The am implementation.** Termux-compatible parser + dispatch,
  derived from AOSP's `Am.java` like termux-am is
  (termux/TermuxAm, Apache-2.0 — license-compatible; keep attribution).
  Decide at implementation time whether to vendor it via
  `deps/deps.list` as a Gradle module or port the needed classes into
  app source; porting is likely cleaner (the library drags in Termux
  packaging) but must keep the AOSP-derived argument semantics intact.
  Intent construction must add `FLAG_ACTIVITY_NEW_TASK` when starting
  from the non-Activity app context (Am.java assumes a shell identity
  that doesn't need it).
- **Subcommand coverage:** `start` (activity), `broadcast`,
  `startservice` / `start-foreground-service`, plus the pure helpers
  `to-uri` / `to-intent-uri` (no launch, deterministic output — ideal
  for tests). Anything else → one clear stderr line ("not available
  without root; use `ando -r am …` on a rooted device") and a distinct
  nonzero exit.

## Semantics and limits (document in notes/ando.md when built)

- **Background-activity-launch rules apply.** `startActivity` from the
  app process is allowed while TAWC is foreground (the terminal-user
  case) and blocked by Android 10+ BAL rules otherwise — and the block
  does not throw, it's silently dropped by the system. Best effort:
  check our own foreground/importance state first and print a warning
  ("TAWC is backgrounded; Android will likely block this launch") so
  the user isn't debugging a ghost.
- **App privilege envelope.** Broadcasts/services run as uid
  me.phie.tawc: permission-gated and protected broadcasts, other apps'
  non-exported components, etc. still fail — correctly, with the
  framework's exception message on stderr instead of silence.
- Real errors surface: `ActivityNotFoundException`, `SecurityException`
  → message + nonzero exit. Never a silent 255.

## CLI-contract note

This changes what `ando am …` does (today: exec `/system/bin/am`, which
always fails unrooted). That is the point — the current behavior is a
useless silent failure, so interception is strictly an improvement, and
the full-path spelling keeps the old behavior reachable. But the ando
surface is frozen post-release (notes/installation.md), so this should
land **before** the first release that freezes it, or be accepted
explicitly as a behavior change.

## Testing

- Integration (`tests/integration/tests/ando.rs`), unrooted-safe:
  - `ando am to-uri -a android.intent.action.VIEW -d https://example.com`
    → deterministic URI on stdout, exit 0 (proves interception + parser
    end-to-end without launching anything).
  - Unsupported subcommand (e.g. `ando am force-stop x`) → the
    root-hint error, distinct exit code.
  - `ando /system/bin/am start …` still reaches the real binary
    (expect the platform 255 — asserts the escape hatch).
  - Bad args → usage error on stderr, nonzero (not silent).
- Emulator/manual: `ando am start -a android.settings.SETTINGS` with
  TAWC foregrounded → Settings comes forward (screenshot).
- Update `plans/usecase_tests/android-ando-broker.md` step 5 to test
  the unrooted path once this ships.
