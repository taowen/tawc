# Reproducible Builds

F-Droid rebuilds each `vN` tag from source, checks the result against
`tawc-vN.apk` on GitHub, and distributes *that* APK, signed with the
maintainer's key. First done for v3; fdroiddata CI confirmed the match
on its own runner.

Why: F-Droid never changes an app's signing key after it has shipped one.
Two signing lineages would force users to uninstall to switch channels,
and uninstalling deletes their distros. One lineage removes that trap.

Process: [release.md](release.md). Rig: [building.md](building.md)
("F-Droid buildserver rig").

## Approach: same environment, not path remapping

Release APKs are built inside F-Droid's own image
(`registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie`) at their
path (`/home/vagrant/build/me.phie.tawc`), driven by `fdroid build` on the
real recipe (`scripts/fdroid/prepare.sh release` + `run.sh`). Build paths
and the libhybris cross-GCC (the host distro's `aarch64-linux-gnu-gcc`)
then match by construction, with no `-ffile-prefix-map` /
`--remap-path-prefix` plumbing through ~25 autotools/meson/cargo builds.
Unstripped native libs are fine for the same reason.

Dev-machine builds are deliberately not reproducible. Before this,
an Arch build vs an F-Droid-image build (v2) differed in 39 of 1359
entries, all native: embedded build dirs (RUNPATHs, debug info, panic
paths, X11 `Compose` includes), tar metadata, and GCC 16 vs Debian's 14.
v1 and v2 can never reproduce.

The container build needs no keys, so the agent runs it; the maintainer
only signs. F-Droid's independent rebuild is the check that the signed
binary matches the tagged source.

## What makes it deterministic

- `scripts/lib/repro.sh` is the single source of `SOURCE_DATE_EPOCH`
  (HEAD's commit time, 0 outside git) and the tar flags (`repro_tar`:
  fixed order, owner, modes, mtime). Gradle exports the epoch onto every
  `Exec` task and uses the flags for `packDebootstrap`, `packLibhybris`
  and `packXwaylandShare`. Runtime extractors read only names, link
  targets and the exec bit, so normalising the rest is safe.
- `fdroid build` runs `git clean -dffx`, so every run builds the whole
  native stack from scratch.
- Checked 2026-09-18: cold caches, warm caches and 6 vs 24 cores gave
  byte-identical APKs. None of the usual suspects (`__DATE__`, hostname,
  parallel link order, AGP packaging order, Rust) showed up.
- `scripts/fdroid/compare-apks.py A B` compares entry by entry, ignoring
  the signing block. Run the spot-check before every tag.

## Signing

`scripts/build-release-apk.sh --apk <unsigned>` signs without
re-aligning and gates on `apksigcopier compare`. Two non-default
apksigner flags are needed to survive that comparison:

- `--v1-signing-enabled false`: apksigner sets the UTF-8 name flag on
  the three v1 META-INF entries and apksigcopier does not, which breaks
  the v3 digest. minSdk is 29, so v1 was dead weight.
- `--alignment-preserved true`: build-tools 35 apksigner re-aligns
  native libs to 16 KB; apksigcopier and AGP use 4 bytes / 4 KB pages.

Both apply to the plain signing path too. The certificate is unchanged
from v2, so updates install over it.

## Recipe

`fdroid/me.phie.tawc.yml` carries `Binaries:` (the `v%v`/`tawc-v%v.apk`
GitHub asset pattern) and `AllowedAPKSigningKeys:` (`0b2262d2…`, the
release cert's SHA-256). The asset name must match exactly, and a
published asset must never be replaced.

`scripts/fdroid/prepare.sh reproduce --apk <signed.apk>` serves the
signed APK inside the container and runs fdroidserver's real
download-and-verify path. `--unpinned-key` re-pins the recipe, only for
self-tests with a throwaway key. The server must be HTTPS with its cert
in the container trust store: fdroidserver's downloader has no plain
`http://` adapter ("No connection adapters were found").

## Rig gotchas

- Rootless podman ignores `--cpuset-cpus` unless systemd delegates the
  cpuset controller to the user slice, so `run.sh` applies the limit with
  `taskset` inside the container. `nproc` respects affinity.
- Podman records its storage path absolutely. If `build/fdroid/` moves,
  rewrite `StaticDir`/`GraphRoot`/`VolumeDir` in
  `build/fdroid/containers/storage/db.sql` rather than re-downloading.

## Risks

- **Image drift.** The rig `dist-upgrade`s like F-Droid's CI, so a
  trixie update between our build and theirs can change the output
  (GCC/binutils for libhybris; a JDK point release, which feeds javac,
  is the likeliest). Build right before publishing. Failure is benign:
  F-Droid skips that version and the fix is vN+1. So far libssl, libc6,
  libexpat and openjdk 21.0.11 → 21.0.12 bumps have all still matched.
- **CI image vs production buildserver.** Production builds run in a VM
  from the same base. Core count and cache warmth are tested; kernel,
  locale and umask differences only show after merge.
- **JDK.** The scanner deletes `gradle/gradle-daemon-jvm.properties`, so
  the build uses the buildserver's default JDK (21 today); building in
  their image tracks it.
- **Commitment.** Every release must reproduce or it does not reach
  F-Droid. Every new native dep is a determinism suspect.
