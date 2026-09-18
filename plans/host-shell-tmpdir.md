# Give the host-side shell a writable TMPDIR

## Problem

Install fails at Configure on many devices with

```
/system/bin/sh: <stdin>[92]: can't create temporary file /data/local/shfz21tg.tmp: Permission denied
```

(upstream https://github.com/wmww/tawc/issues/13 and
https://github.com/wmww/tawc/issues/16 — same bug, two reporters.)

`/system/bin/sh` is mksh, and mksh spills **every** here-document to a
temp file under `$TMPDIR`. An Android app process has no `TMPDIR` in
its environment (verified by dumping `/proc/<pid>/environ` for the app),
so mksh falls back to its compiled default `/data/local`, which is
`drwxr-x--x root:root` — not writable by the app uid.

Every distro's `configure()` writes its config files with heredocs
through `method.runOutside` → `Sh.run`:

- `distro/arch/ArchPacmanCommon.kt` (pacman.conf, mirrorlist, cache hook)
- `distro/apt/AptCommon.kt` (sources, apt.conf, dpkg cfg, profile.d)
- `distro/voidlinux/VoidCommon.kt` (xbps.d)
- `ShellDefaults.configureScript()`, appended by all three

`runOutside` prepends `set -eu`, so the first heredoc aborts the whole
script and the install lands in `failed`.

## Evidence

Reproduced in the app's own uid/domain on the x86_64 emulator via the
exec broker:

```
$ scripts/tawc-exec.sh /system/bin/sh -c "cat <<'EOF' … EOF"
/system/bin/sh: can't create temporary file /data/local/shw6meve.tmp: Permission denied

$ scripts/tawc-exec.sh --env TMPDIR=/data/user/0/me.phie.tawc/cache /system/bin/sh -c "cat <<'EOF' … EOF"
heredoc-with-tmpdir-ok
```

So it is not a vendor quirk. Loose end: the emulator's sid rootfs was
installed (tawcroot, heredocs already in the configure script) in July
and succeeded, which this analysis does not explain. Worth a look while
implementing, but it does not change the fix.

## Fix

Set `TMPDIR` once, in `Sh` itself, so no host-side shell we spawn can
land in an unwritable temp dir:

1. `Sh.run` injects `TMPDIR=<cacheDir>/sh` into the child environment,
   with the caller's `env` map able to override it.
2. `mkdirs()` the dir before spawning. mksh **silently** ignores
   `TMPDIR` unless it is absolute and an existing writable, searchable
   directory (`setspec` `V_TMPDIR`), so a missing dir falls straight
   back to `/data/local`.
3. Resolve the path without threading a `Context` into `Sh`: add
   `Sh.init(context)` called from `TawcApplication.onCreate`, mirroring
   the existing `Settings.init(this)`, and fall back to
   `System.getProperty("java.io.tmpdir")` when uninitialised (the
   framework points that at the app cache dir in every app process, and
   plain-JVM unit tests get a usable `/tmp`).

`Su.run` is left alone: it runs as root, which can write `/data/local`,
and chroot is debug-only.

This subsumes upstream PR https://github.com/wmww/tawc/pull/17, which
has the right diagnosis and mechanism (`Sh.run` already takes an `env`
map) but patches only `TawcrootMethod.runOutside` — leaving
`ProotMethod.runOutside` and `RootfsCleaner`'s three bare `Sh.run`
calls broken, and nothing to stop the next call site reintroducing it.
Credit pidjey in the commit message.

Rejected: replacing the heredocs with `printf` (pidjey's first
approach). A dozen call sites of literal config-file content, traded
for quoting hazards, and it papers over the real defect rather than
fixing it.

## Testing

- Integration test through the exec broker asserting a heredoc script
  runs in the app domain. `tests/integration/tests/tawcroot_prodenv.rs`
  is the closest home. This is the regression guard — a plain-JVM unit
  test cannot cover it (no Robolectric).
- Fresh install of Debian sid on the emulator through
  `scripts/tawc-exec.sh --foreground-app --action install`, with the
  dev mirror cache. Needs the cache proxy running.

## Follow-up, not in scope

issue #13 also reports that the failed install cannot be deleted
("rootfs delete failed"). `RootfsCleaner`'s scripts use no heredocs, so
`TMPDIR` will not fix it — tracked separately in
`issues/failed-install-cannot-be-deleted.md`.
