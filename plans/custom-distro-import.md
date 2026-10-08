# Import custom (unsupported) distros from a tarball

Not started.

## Goal

"Import from tarball" also accepts an arbitrary Linux rootfs tarball
(`docker export`, `mmdebstrap`, a vendor or minirootfs image, a
hand-made one) and turns it into a working tawcroot distro labelled
**unsupported**. Same entry point, form, job, gate and state machine as
the current import (notes/installation.md "Export / import"); only the
classification, the layout mapping and a small post-extract setup step
are new. Best effort: handle the common shapes, warn about what probably
won't work, refuse only what can't work. Don't grow per-distro special
cases.

## Classification

`DistroImporter` sorts every archive into one of four kinds, and the
form says which one it found:

| kind | detected by | import |
|---|---|---|
| **TAWC export** | `tawc-export.json` first and valid, `metadata.json` second and valid | today's path. Unknown `(distro)` key is no longer refused: arch check only, plus an "unsupported distro" warning (newer app's distro, or an exported custom one). |
| **Damaged TAWC export** | any TAWC marker (`tawc-export.json`, `tawc-export-end.json`, or top-level `metadata.json` next to `rootfs/`) but not the above: bad/missing/too-new manifest, unparseable metadata, framing out of order (typical after the user unpacks, edits and re-tars an export) | warning banner listing the problems, then best effort: TAWC layout (`rootfs/` + `tawcroot/`), settings from `metadata.json` if it parses (else defaults), trailer not required (see below). Imported as its recorded distro if known, else custom. |
| **Plain rootfs** | no TAWC markers; a rootfs root found (below) | custom distro. |
| **Unrecognized** | no rootfs root found, or a known non-rootfs format (`docker save`/OCI layout: `manifest.json` + `*/layer.tar` or `blobs/`) | refused with a specific message (for OCI: "use `docker export` of a container instead"). |

**Trailer policy.** Only a well-framed TAWC export (manifest first,
trailer last) is held to the trailer: missing or mismatched → FAILED,
as now. Damaged exports and plain tarballs have no reliable end marker,
so a truncated one is caught only when the tar/zstd stream itself breaks
(mid-entry EOF, broken frame), which is the common case. The form says
so for those two kinds.

**Rootfs root detection** (plain rootfs): the shallowest dir prefix
that contains `etc/` plus `usr/` or `bin/`. This covers top-level trees,
`./`-prefixed trees (Alpine minirootfs, most `tar -C dir .`), and a
single wrapper dir (`root.x86_64/`, `rootfs/`, `<name>/`). The prefix is
stripped on extract (the extractor already supports `stripPrefix`; it
needs to accept `./`). Entries outside the prefix are skipped with a
count, not fatal.

## Scan (form time)

Format by magic bytes, not filename (SAF names are unreliable): zstd,
gzip, xz, bzip2 (commons-compress has it), plain tar (`ustar` at 257).

A TAWC export is still classified from the first two entries. For
anything else the form runs one headers-only pass over the whole archive
("Scanning archive…", cancellable; the content is decompressed but not
written) and collects:

- kind + rootfs prefix (above), entry count, uncompressed size (for the
  existing free-space warning);
- `etc/os-release` (and `usr/lib/os-release`) contents → default label
  (`PRETTY_NAME`, else `NAME`), shown as "Distro: <name> (unsupported)";
- **architecture**: `e_machine` from the first ELF regular file under
  `bin/` or `usr/bin/` (first 20 bytes). A mismatch with the phone's ABI
  is refused, since no foreign-arch emulation exists (see the x86 plans);
- **libc**: `ld-linux-*.so*` → glibc, `ld-musl-*.so*` → musl;
- **shell / env**: `bin/bash` or `usr/bin/bash` present;
  `usr/bin/env` a symlink to busybox (BusyBox env has no `-C`).

The broker `import` action can't rescan stdin. It classifies from the
leading entries (the TAWC framing is first, and a rootfs prefix almost
always shows within the first few entries); the post-extract checks
below are authoritative for both paths.

## Post-extract setup (custom only)

A light, idempotent stand-in for `Distro.configure`, run before the
usual `TawcInstaller` refresh:

- `/etc/resolv.conf`: write ours if missing or a dangling symlink
  (`docker export` of a systemd image points it into `/run`);
- `/tmp` (01777) and `/root` (0700) if missing;
- `/root/.bashrc` stub via `ShellDefaults` only if bash exists and the
  file doesn't;
- if `/etc/passwd` lacks a root line, log it (RootShell falls back).

Nothing package-manager-specific. Users fix the rest in the terminal.

## Runtime fallbacks (all distros, cheap)

- **`env -C` (BusyBox, old coreutils):** every spawn runs
  `/usr/bin/env -i -C /root …` (RootfsEnv). First try to make `-C`
  unnecessary for tawcroot by starting the tawcroot process with host
  cwd `<rootfs>/root` (TMPDIR stays `<rootfs>/tmp`; check nothing
  relies on the guest starting in `/tmp`). If that's not clean, record
  `envChdir=false` in metadata at import and wrap instead:
  `env -i K=V… /bin/sh -c 'cd /root && exec "$@"' sh <prog…>`.
- **No bash:** `RootShell.DEFAULT` becomes "`/bin/bash` if present else
  `/bin/sh`" per rootfs, so terminals and command/launcher spawns run.
  Command spawns keep `-lc`, which POSIX `sh` also accepts.
- **musl:** libhybris can't work (notes/distro-options.md). Warn on the
  form and on distro info ("GPU acceleration unavailable; use the CPU
  graphics backend"). A per-distro graphics-backend override would be
  the real fix, and belongs in its own change if wanted.

Anything else broken (no `/usr/share/applications`, an odd init, a
missing `/etc/passwd`) is just a degraded experience, not handled.

## Metadata

Custom slots: `distro = "custom"` (a new frozen key; add it to
notes/installation.md "Frozen identifiers"), `arch` = this phone's
ABI, `method = tawcroot`, `sourceUrl` = the picked file's display name,
`bootstrapFlavor = "imported"`, `installedAtAppVersionCode` = this app,
`importedAtMillis`, plus new optional `osName` / `osId` from os-release
for display. Additive, no schema bump. Exporting a custom distro then
produces a normal TAWC export that re-imports as custom.

`DistroRegistry.forInstallation` stays null for these; display already
falls back to the label/raw key. Uninstall and spawns don't consult the
registry.

## UI

- Import form: a kind banner — nothing for a TAWC export; amber
  "Damaged TAWC export: <problems>. It will be imported as-is." for a
  damaged one; "Unsupported distro: <name>. Some TAWC features may not
  work." for custom — plus specific warnings (musl/GPU, no bash, BusyBox
  env, no end-marker check). Refusals (unrecognized, wrong arch) replace
  the form with the reason, as unreadable files do today.
- Distro info: "Unsupported" next to the distro name for custom slots.
- No settings rows (unchanged).

## Tests

- JVM (`DistroArchiveTest` style, synthetic tars): classification of each
  kind (top-level, `./`, wrapper dir, re-tarred export, bad manifest,
  OCI layout refused, no rootfs root refused); prefix stripping; ELF
  arch and libc detection; os-release label; a valid export with an
  unknown distro key passes the arch-only check.
- Device (opt-in, `TAWC_EXPORT_TESTS=1` module): import an Alpine
  minirootfs (musl, BusyBox) and a Debian rootfs tarball fetched through
  the mirror proxy; run a command and a terminal-shaped spawn in each;
  check resolv.conf and `/tmp`; delete both. A re-tarred export imports
  with the damaged-export warning.

## Docs when done

notes/installation.md (classification, custom setup, frozen key),
notes/distro-options.md (custom imports: what works, glibc vs musl),
notes/exec-broker.md (`import` accepts plain tarballs).

## Open questions

- Is the headers-only scan fast enough on multi-GB tarballs, or should
  it stop early once kind, arch and libc are known (losing the size
  estimate)?
- Allow musl distros at all, or refuse them since GPU is the point of
  TAWC? This plan allows them with a warning.
