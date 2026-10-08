# Distro export / import

Not started.

## Goal

Move a working distro to another phone (e.g. across a factory reset) or
into another build of the app (a different application id, such as the
`TAWC_PACKAGE` side-by-side debug package). The result is one archive
file that the user stores wherever they like.

- **Export**: a button on distro info. It writes `<distros>/<id>/` to a
  file the user picks. An optional **Delete after export** checkbox,
  unchecked by default, uninstalls the distro once the export is
  verified.
- **Import**: an "Import distro" entry directly under every "Install
  new distro" entry point. It picks a file, shows a short form, and
  recreates the distro under a new id.

## Decisions (defaults the plan assumes)

- **Unit = `distros/<id>/`, never `rootfs/` alone.** Hardlinked data
  lives in `tawcroot/link/` (notes/tawcroot/link-emulation.md, "Backup/
  export invariant"). If you export only the rootfs, every emulated
  hardlink dangles.
- **tawcroot only.** That is the only release method. Chroot rootfses
  are root-owned (the app uid can't read them). proot is debug-only and
  isn't worth the extra matrix. Export on non-tawcroot installs is
  hidden. Import rejects `method != tawcroot`.
- **Format: `.tar.zst`**, a POSIX/PAX tar compressed with zstd. zstd-jni
  and commons-compress are already dependencies. It is a standard
  format, so users can inspect or repair an export on a PC with
  `tar --zstd`. Suggested filename: `<id>-<yyyymmdd>.tawc.tar.zst`.
- **Storage: SAF.** `ACTION_CREATE_DOCUMENT` for export and
  `ACTION_OPEN_DOCUMENT` for import. This works in every build (no
  `MANAGE_EXTERNAL_STORAGE` needed, so it's Play-safe). The user can
  pick Downloads, a USB drive, or a cloud provider. Both directions
  stream, so no local temp copy is made and peak extra disk use on
  export is zero.
- **Only READY installs export.** Export is a read-only snapshot and
  needs the distro quiesced (see "Quiesce").
- **Import is an install with a different pipeline.** It uses the same
  state machine (`INSTALLING → READY | FAILED`), the same "empty slot
  only" rule, and the same single-job gate in `InstallationService`.
  Failure leaves a FAILED slot that the normal Delete clears.
  `RootfsCleaner` stays the only deleter.
- **Imported archives are untrusted input**, even when the user made
  them. Extraction goes through the containment-checked Kotlin
  extractor. "Untrusted" covers the filesystem contents only.
- **Per-distro settings travel with the distro.** ando, external binds,
  hidden launcher entries and the label are restored as they were, so
  import needs no settings form.

## Archive layout

The archive mirrors the distro dir, with a header entry first and a
trailer entry last:

```
tawc-export.json          # manifest — always the first entry
metadata.json             # the source Installation record, verbatim
rootfs/...                # guest tree
tawcroot/version
tawcroot/link/...         # link objects + .cnt sidecars
tawc-export-end.json      # trailer — always the last entry
```

`tawc-export.json`:

```json
{
  "format": 1,
  "createdAtMillis": 0,
  "appVersionName": "N", "appVersionCode": 0,
  "sourcePackage": "me.phie.tawc",
  "id": "arch", "label": "Arch", "distro": "arch", "arch": "aarch64",
  "method": "tawcroot",
  "uncompressedBytes": 0,
  "entries": 0
}
```

`uncompressedBytes` and `entries` come from a pre-walk. The import form
uses them for display and for a free-space check. They are advisory.
The trailer is authoritative.

`tawc-export-end.json` holds `{ "entries": N, "sha256": "<hex>" }`,
where `sha256` covers the uncompressed tar bytes of every entry before
the trailer. Import is complete **only** if the trailer is present and
matches. This catches a truncated copy that happens to end at a clean
zstd frame or tar boundary.

Importers refuse `format` > supported, the same rule as
`schemaVersion`.

## What is exported / excluded

The exporter walks the **host** dir directly with `lstat`, never
follows symlinks, and never goes through the guest view. As a result,
bind sources (shared storage, `<filesDir>` GPU stacks, share dir) are
never included. Only their empty guest mountpoint dirs are, because
tawcroot binds are path rewrites. The export UI says so.

| Path | Handling |
|---|---|
| `rootfs/**` | Regular files, dirs, and symlinks with mode and mtime. uid/gid are written as 0/0 (`root`), because tawcroot lies about ownership and the on-disk owner is the app uid. |
| `rootfs/tmp/*` | Excluded. Only the dir itself is kept. It holds runtime sockets and agent state, and the tmp sweeper treats it as disposable. |
| sockets, FIFOs, device nodes anywhere | Skipped. A count is logged. |
| real hardlinks (`st_nlink > 1`, seen dev:ino) | Emitted as tar hardlink entries. They shouldn't exist under tawcroot (link(2) is denied), but it's cheap to handle. The extractor already falls back to a relative symlink. |
| `tawcroot/version`, `tawcroot/link/**` | Included verbatim. |
| `tawcroot/lock` | Excluded. It is recreated on demand (`O_CREAT` in `linkstore.c`). |
| `tawcroot/tmp/**` | Excluded (stray `O_TMPFILE` files). |
| `tawcroot/intent*`, `tawcroot/work/**` | Must be absent or empty after quiesce, or the export fails. The intent journal records **host-real paths** of the source app, so replaying it on another package or device would be wrong. |
| `metadata.json` | Included. It is also the source for the manifest fields. |
| `ando/`, `bootstrap-work/`, `metadata.json.tmp` | Excluded. |
| user xattrs | Phase 2. First check whether tawcroot guests leave any `user.*` xattrs on disk. If they do, write them as PAX `SCHILY.xattr.*` and restore them best-effort. |

## Quiesce (export)

A live guest can mutate the tree (pacman mid-transaction, link-store
writes) during a multi-minute export. Before walking:

1. The confirm dialog says: "Programs running in this distro will be
   stopped."
2. Mark the id **busy** in an in-memory `DistroBusy` set (process-wide,
   so it disappears if the app dies, which is correct because export
   is read-only). Every spawn path refuses a busy id with a clear
   error: `InstallationMethod.startInside` / `runInside`, terminal
   tabs, the launcher, broker RUNINSIDE, and ando/remote start. Do this
   in one spot. `TawcrootMethod` already resolves the id from the
   rootfs on every spawn, which makes it the natural choke point.
3. Stop the distro's remote session and close its terminal sessions.
   Then run `ProcessScanner.killAllInRootfs` with the same sweep loop
   as `RootfsCleaner` step 3 (factor it out rather than copy it).
4. Run one no-op tawcroot spawn (`/bin/true`) to trigger the link
   store's session-start crash recovery
   (`tawcroot_linkstore_recover_now`). Then assert that `intent` is
   absent and `work/` is empty. If not, fail ("link store has pending
   recovery") rather than export a store that only works on this
   device.

The busy mark is cleared in `finally`. Uninstall and install are
already excluded by the single-job gate.

## Export pipeline

New `install/DistroExporter.kt`, run as a new `JobKind.EXPORT` in
`InstallationService` (FGS `dataSync`, `MutableOperation`,
`LogScreenActivity`, cancellable, the same as install/uninstall):

1. Gate: state READY, method tawcroot, no other job running.
2. Quiesce (above).
3. Pre-walk: count entries and bytes for the manifest and for progress.
4. Open the SAF output stream (`contentResolver.openOutputStream(uri,
   "wt")`) → `ZstdOutputStream` (level 3, workers = cores/2; verify
   that the zstd-jni AAR is built multithreaded, and drop workers if
   not) → a digest tee → `TarArchiveOutputStream` (PAX for long
   names/big files).
5. Write the manifest, `metadata.json`, the walk (rootfs then
   tawcroot), and the trailer. Report byte-based progress.
6. Close and flush. A failure deletes the partial document
   (`DocumentsContract.deleteDocument`) so no half-written export is
   left looking valid.
7. **If Delete after export is checked**, chain into the existing
   `startUninstall(id)` once the trailer is written and the stream has
   closed without error. That path also retires pinned shortcuts. There
   is no read-back pass. Any write or close failure counts as an export
   failure, and the distro is kept.

Cancel: abort the stream, delete the partial document, and clear busy.
The distro is untouched.

SAF grants: `takePersistableUriPermission` when the job starts (the job
outlives the activity) and `releasePersistableUriPermission` when it
ends.

## Import pipeline

New `install/DistroImporter.kt` plus an `ImportActivity` form, run as
`JobKind.IMPORT`:

1. **Pick**: `ACTION_OPEN_DOCUMENT` (`*/*`). Take a persistable grant.
2. **Peek**: stream just the first two entries (manifest + metadata).
   Reject early, with a specific message, when:
   - the first entry isn't a valid manifest (not a TAWC export),
   - `format` or `metadata.schemaVersion` is too new,
   - `method != tawcroot`,
   - `(distro, arch)` doesn't resolve via `DistroRegistry`, or the
     arch isn't runnable on this host (e.g. an aarch64 export on the
     x86_64 emulator).
3. **Form** (`ImportActivity`), styled like the install form:
   - Read-only summary: distro, arch, original label, export date,
     exporting app version, uncompressed size.
   - **Label**, prefilled from the archive. Reuse InstallActivity's
     slug/collision validation by extracting it into a shared helper.
     The Import button is disabled on collision.
   - Settings carried from the archive (binds, ando) are listed
     read-only so the user can see what comes with the distro. They
     can be changed after import from the usual screens.
   - Bind warning: if a carried bind would currently fail closed at
     spawn time (host dir missing, or all-files access not granted),
     show a note that links to Manage binds or the all-files settings
     toggle. The bind is kept anyway, because the spawn path already
     reports it clearly.
   - Exception: in a build without all-files access in the manifest
     (`AllFilesAccess.declared == false`), shared-storage binds are
     dropped and the note says so. Otherwise the distro could never
     launch, and that build has no binds UI to fix it.
   - Free-space check: `StatFs(distrosDir)` against
     `uncompressedBytes` + 10% headroom. Shown as a warning; Import
     stays enabled because the number is advisory.
4. **Run**:
   1. Write `metadata.json` with state INSTALLING. The rewritten
      record is described below.
   2. Stream the archive and extract `rootfs/**` and `tawcroot/**`
      into `distros/<newId>/`, generalizing
      `ProotArchiveExtractor.extractStream` to take an allowlist of
      top-level prefixes. Keep its per-entry canonicalize-and-contain
      check, deferred dir modes, pre-delete, and hardlink endpoint
      checks. Any entry outside the allowlist, other than the
      manifest, metadata, and trailer, fails the import. So does any
      `tawcroot/intent*` or `tawcroot/work/*` entry.
   3. Verify the trailer (sha256 + count). If it is missing or
      mismatched, FAIL.
   4. Recreate empty `tawcroot/tmp/` and `rootfs/tmp/` if absent (mode
      01777 on the latter, as recorded by the original dir entry).
   5. `TawcInstaller.installInto`: the null stamp forces a refresh,
      which wipes the old manifest's dests and lays down this app's
      files.
   6. Set up ando as an install does when `andoEnabled` is set
      (`AndoInstallProvider` is part of the step 5 refresh, and the
      broker listener comes up through `AndoBrokers`, which reads the
      flag). Then state READY.
5. Cancel: `INSTALLING → FAILED` → auto-uninstall, exactly like
   cancelling an install. That's safe because the slot only holds
   what the import wrote.

Rewritten `Installation` for the new slot:

| field | value |
|---|---|
| `id`, `label` | from the form |
| `state` / `failure` | INSTALLING → READY / null |
| `distro`, `arch`, `method`, `sourceUrl`, `bootstrapFlavor`, `installedAtMillis` | kept |
| `installedAtAppVersionCode` | **kept**. It answers "what code wrote this rootfs", which is what a future `reconfigure` keys on. |
| `tawcStamp` | null, to force a TawcInstaller refresh |
| `tawcInstalls` | kept, so the refresh can wipe the old set by dest (dests are in-rootfs paths; srcs are only read on copy) |
| `externalBinds` | kept (minus shared-storage binds in a build without all-files access, see above) |
| `andoEnabled` | kept |
| `hiddenDesktopIds` | kept |
| new: `importedAtMillis`, `importedFromPackage` | additive optional fields, so no schema bump (notes/installation.md "Schema versioning") |

Pinned launcher shortcuts aren't carried. They belong to the old
package's launcher entries.

## Cross-package / cross-device concerns to verify

The design assumes the distro dir has no host-absolute paths baked in.
Check this before building:

- `grep -r` a real exported rootfs and store for the source data dir
  (`/data/data/<pkg>`, `/data/user/0/<pkg>`). Known safe:
  `TawcInstall` LINK targets are guest paths, the ando socket lives in
  `ando/` (excluded), and bind specs are built per spawn. Fix anything
  else found by moving it to a per-spawn bind or env.
- Link tokens are object inode numbers. After import the inodes are
  different, so tokens no longer equal inodes. The notes say
  allocation adds a `-<k>` suffix on collision after host copies.
  Confirm that `linkstore.c` NEW checks for an existing `link/<ino>`
  before using a bare token, and add a hosted test that copies a store
  to fresh inodes and then creates new links.
- The `tawcroot/version` gate: importing a store from a newer tawcroot
  degrades to DEGRADED mode (no data loss). Show a log line, but don't
  refuse.
- Android 15+ caps `dataSync` FGS time. A 20 GB export might hit the
  cap. Check that `onTimeout` cancels cleanly (deletes the partial
  document) and say so in the failure text.
- SAF providers may rename or append an extension based on the MIME
  type. Use `application/zstd` and check what Downloads and Files do
  with `.tawc.tar.zst`. Fall back to `application/octet-stream` if a
  provider mangles it.

## UI changes

- `DistroInfoView`: an **Export** button (tonal, non-destructive) above
  the red Delete. Only shown for READY tawcroot installs. Tapping it
  opens a dialog that says programs will be stopped and that bind
  contents aren't included, with a **Delete after export** checkbox
  (unchecked). Continue → `CREATE_DOCUMENT` → start the job → open
  `LogScreenActivity`. When the box is checked, the dialog's positive
  button turns red and reads "Export & delete".
- `MainActivity` drawer: an **Import distro** item (`ic_download` or
  similar) directly below "Install new distro".
- `MainActivity.buildIntro`: an **Import distro** text/tonal button
  under the orange Install button.
- No changes to `InstallActivity`.
- Strings in `res/values/strings.xml`. Keep them short.

## Broker / tests

The exec broker already multiplexes binary stdin/stdout frames, so
tests can stream archives without touching device storage:

- `export` action (`id`, `deleteAfter`): writes the archive to the
  action's stdout. `scripts/tawc-exec.sh --action export --arg id=x >
  out.tar.zst`.
- `import` action (`id`, `label`): reads the archive
  from stdin.
- Both register next to `install`/`uninstall` in `InstallActions`
  (debug only) and drive the same exporter/importer, with the
  `OutputStream`/`InputStream` passed in instead of a URI.

Integration tests (`tests/integration/tests/distro_export.rs`):

1. Install (mirror proxy), create a file, an emulated hardlink pair,
   and a symlink. Export, import as a new id, then check inside the
   new distro: contents, `st_nlink == 2` with a shared inode on the
   pair, the symlink target, mode bits, and that `pacman -Q` / `dpkg
   -l` works.
2. Round-trip into the `TAWC_PACKAGE` side-by-side debug package
   (export from one app, import into the other) to cover the
   cross-app-id case.
3. Truncated archive → import FAILED with a trailer error, then Delete
   clears it.
4. Hostile archives (hosted JVM unit tests on the extractor, not
   device tests): `../` entries, absolute paths,
   symlink-then-write-through, entries outside the allowlist, an
   `intent` entry, and a too-new `format`.
5. `deleteAfter=true` → the source slot is gone after a successful
   export. With a failing output stream (a test hook throws mid-write),
   the source survives.
6. Settings round-trip: export a distro with ando enabled, a custom
   bind and a hidden launcher entry. After import, all three are
   active (ando works from inside the guest).
7. Export while a guest is running → the guest is killed, the export
   succeeds, and a spawn during export is refused.

App unit tests: manifest/trailer encode and parse, metadata rewrite,
exclusion rules on a temp dir tree.

Run on the `.tawctarget` device before calling it done.

## Phases

1. **Core**: `DistroExporter` / `DistroImporter`, the format, the
   generalized extractor, quiesce + `DistroBusy`, the broker actions,
   integration tests 1, 3, 4, 6, 7. Also do the cross-package verification
   items (host-path grep, link-token collision test).
2. **UI**: the distro info Export dialog + SAF, the Import entries,
   `ImportActivity`, Delete after export (test 5), and the
   cross-package test 2.
3. **Polish**: xattrs if needed, an estimated-size line in the export dialog (reuse the
   size probe).

## Docs to update when done

- notes/installation.md: the state machine (import as an install
  variant), the code layout table, the busy gate, and a new
  "Export / import" section with the format, the exclusions, and the
  trust model.
- notes/tawcroot/link-emulation.md: point the "Backup/export
  invariant" at the implementation and note the excluded files.
- notes/exec-broker.md: the action table.
- notes/external-binds.md: binds are carried but their contents are
  not exported. Shared-storage binds are dropped on import into a
  build without all-files access.
- notes/ando.md: the ando setting is carried across export/import.

## Open questions

- Exclude `rootfs/tmp` contents (planned), or keep everything except
  sockets?
