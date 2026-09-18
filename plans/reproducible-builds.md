# Reproducible Builds (F-Droid ships the maintainer's signature)

Goal: F-Droid rebuilds each `vN` from source, confirms the result matches
the APK published on GitHub, and distributes *that* APK — signed with the
maintainer's key, not F-Droid's.

Why, and why before the first F-Droid release: F-Droid will not change
an app's signing key after it has shipped one (the inclusion template
says so outright). With two signing lineages, moving between the GitHub
and F-Droid builds means uninstalling, and uninstalling TAWC deletes the
user's distros. One lineage removes that trap permanently. It has to be
decided before the fdroiddata MR (plans/f-droid.md step 3.3) is opened.

## Measured 2026-09-18 (v2, `ef2ae6b`)

F-Droid-image build (the rig, now `scripts/fdroid/`) vs the maintainer-signed
`tawc-v2.apk` built on Arch: 1320 of 1359 entries identical — all dex,
resources, manifest, `libtawcroot.so`, `libando.so`, zstd-jni. The 39
that differ are all native code our scripts build, from three causes:

| Cause | Entries | Evidence |
|---|---|---|
| Build dir embedded | ~35 Xwayland-stack libs (RUNPATH `…/install/lib`), `libtermux.so` + `libcompositor.so` (debug-info / panic paths — we ship unstripped on purpose), X11 `Compose` includes ([issue](../issues/xwayland-share-tar-embeds-build-paths.md)) | `/home/vagrant/build/me.phie.tawc/…` vs `/home/code/tawc/…` |
| Tar metadata | `assets/xwayland/share.tar`, `assets/libhybris/arm64-v8a.tar` | owner `vagrant` vs `code`, build-time mtimes, umask-dependent dir modes |
| Different compiler | everything in the libhybris tar | `GCC: (Debian 14.2.0-19)` vs Arch `gcc-16.1.0` — libhybris uses the host distro's `aarch64-linux-gnu-gcc` |

v2 itself can never reproduce; the first reproducible release is v3.

## Approach: same environment, not path remapping

Build the release APK inside F-Droid's own image
(`registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie`) at their
path (`/home/vagrant/build/me.phie.tawc`), driven by `fdroid build` from
the real recipe — i.e. what the rig already does. Paths and the
cross-GCC then match by construction, with no `-ffile-prefix-map` /
`--remap-path-prefix` plumbing through ~25 autotools/meson/cargo builds.
Dev-machine builds stay non-reproducible; only release builds need to be.

The maintainer's step shrinks to signing the container's unsigned APK.
The container build needs no keys, so the agent account can run it.
That is safe to sign: F-Droid's independent rebuild is exactly the check
that the binary corresponds to the tagged source.

## Steps

1. ~~**Deterministic asset tars.**~~ **Done.** `scripts/lib/repro.sh` is
   the single source of truth for `SOURCE_DATE_EPOCH` (HEAD's commit
   time, 0 outside git) and the tar flags; `app/build.gradle.kts` reads
   both back from it, exports the epoch onto every `Exec` task, and
   passes the flags to `packDebootstrap`, `packLibhybris` and
   `packXwaylandShare`; `build-mesa-gfxstream.sh` uses `repro_tar` for
   the zink tar. Re-packing now gives byte-identical tars. The runtime
   extractors read only entry names, link targets and the exec bit, so
   normalising owner/mode/mtime is safe.
2. ~~**Promote the rig to a release builder.**~~ **Done.**
   `scripts/fdroid/{lib,prepare,run,in-container,lint,clean}.sh`, state
   under `build/fdroid/`, version read from `versionName`. `prepare.sh
   [verify|release] [--commit <ref>]`, `run.sh [--cpuset-cpus <spec>]
   [--fresh-cache]`; release mode requires a clean tree, re-clones the
   mirror, drops `--test` and copies the unsigned APK to
   `app/build/outputs/apk/release/`. Documented in notes/building.md.
   `verify` and `lint` both exercised after the move; `release` mode has
   not run yet (it needs a clean tree).
3. ~~**Prove determinism before trusting it.**~~ **Done, clean.** Three
   `verify` builds of the same snapshot (2026-09-18, app version 2,
   `libhybris,cpu`): cold caches, warm caches, and six cores instead of
   24. All three came out byte-identical — same sha256, 1359 of 1359
   entries identical under `scripts/fdroid/compare-apks.py`. None of the
   suspected residuals (autotools `__DATE__`/hostname, link order under
   parallel make, AGP packaging order, Rust) showed up; `fdroid build`
   `git clean -dffx`s the checkout, so each run built the native stack
   from scratch. `libhybris` and `Xwayland` both rebuilt; Mesa does not
   ship in a release build. Re-run this before every tag.

   Rootless podman cannot honour `--cpuset-cpus` unless systemd
   delegates the cpuset controller to the user slice (it usually
   delegates only cpu, memory and pids), so `run.sh` applies the limit
   with `taskset` inside the container instead. `nproc` is
   affinity-aware, so the build scripts really did use `-j6`.

   Also: podman records its storage path absolutely, so moving the rig
   from `build/fdroid-test/` to `build/fdroid/` made it refuse to start.
   Rewriting `DBConfig`'s `StaticDir`/`GraphRoot`/`VolumeDir` in
   `build/fdroid/containers/storage/db.sql` fixes it without
   re-downloading the image; noted in `scripts/fdroid/lib.sh`.
4. ~~**Sign-only path.**~~ **Done.** `scripts/build-release-apk.sh --apk
   <unsigned>` skips Gradle, checks the APK is already aligned instead
   of re-aligning it, signs it, and gates on `apksigcopier compare
   <signed> --unsigned <that APK>`. Verified end to end with a throwaway
   keystore.

   Signing needed two non-default apksigner flags to survive that
   comparison, both found by trying it:
   - `--v1-signing-enabled false`. v1 adds three META-INF entries;
     apksigner sets the UTF-8 name flag on them and apksigcopier does
     not. Six bytes, and the v3 digest stops matching. minSdk is 29, so
     v1 was dead weight.
   - `--alignment-preserved true`. build-tools 35 apksigner re-aligns
     native libs to 16 KB by default and shifts every later entry;
     apksigcopier realigns the classic way (4 bytes / 4 KB pages), which
     is what AGP and zipalign already produced.

   Both apply to the plain path too, so there is one signing behaviour.
   That makes build-tools 35+ and `apksigcopier` release-time host deps.
   Note this changes the signing schemes v3 tags the APK with relative
   to v2's release (v1 entries gone); the certificate is unchanged, so
   updates still install over v2.
5. ~~**Recipe.**~~ **Done.** `fdroid/me.phie.tawc.yml` now carries
   `Binaries:` (the `v%v`/`tawc-v%v.apk` GitHub asset pattern) and
   `AllowedAPKSigningKeys:` (`0b2262d2…`, the release cert's SHA-256).
   Lint, rewritemeta and schema validation all still pass. The `Builds:`
   entry is still v2, which cannot reproduce — it has to become v3
   before submission, and the recipe says so.

   `scripts/fdroid/prepare.sh reproduce --apk <signed.apk>` is the third
   mode: it points `Binaries:` at a copy of the signed APK served inside
   the container, so `fdroid build` runs its real download-and-verify
   path. Exercised end to end against a container-built APK signed with
   a throwaway key (`--unpinned-key` re-pins the recipe for that
   self-test only): "compared built binary to supplied reference binary
   successfully", signature transplanted by fdroidserver's own
   apksigcopier and still verifying, allowed-signer check passed.

   The server has to be HTTPS with a cert added to the container trust
   store — fdroidserver's downloader mounts no plain `http://` adapter
   and fails with "No connection adapters were found".
6. ~~**Release process.**~~ **Done.** notes/release.md now runs: prep
   commit → container `release` build of that commit (agent) →
   determinism spot-check → tag → maintainer signs with `--apk` →
   `apksigcopier compare` → optional `reproduce` run → smoke test →
   push + GitHub release, with the exact asset name and the
   never-replace-a-published-asset rule spelled out. CLAUDE.md's release
   bullet points at the container build too.
7. **Cut v3 through the new process, then open the MR** with the
   "Enable Reproducible Builds" box ticked and v3 as the only Builds
   entry.

## Risks

- **Image drift.** The release is built against trixie's packages on
  day X; F-Droid rebuilds days later. A GCC/binutils point update in
  between changes libhybris. Trixie is stable so this is rare; the rig
  `dist-upgrade`s like their CI, so build right before publishing.
  Failure mode is benign: F-Droid does not publish that version, users
  stay on the previous one, fix is vN+1. Seen in practice already: a
  libssl 3.5.6 → 3.5.7 update landed between the determinism sweep and
  the step-5 verification an hour later, and the rebuild still matched —
  most trixie updates touch nothing this build links against.
- **CI image vs production buildserver.** fdroiddata CI and the rig use
  the container image; production builds run in a VM provisioned from
  the same base. Differences (core count, kernel, locale, umask) are
  what step 3's variations are for; the first real verification only
  happens after merge.
- **JDK.** The scanner deletes `gradle/gradle-daemon-jvm.properties`, so
  the build takes the buildserver's default JDK (21 today). Building in
  their image tracks that automatically.
- **Commitment.** Every future release must reproduce or it does not
  reach F-Droid. Any new native dep is a new determinism suspect; the
  compare script in the release flow is what catches it before tagging.

## Not in scope

- Reproducing on arbitrary dev machines (would need the path-remap
  plumbing this plan deliberately avoids).
- Stripping/minifying release builds. Unstripped debug info is fine
  when the build path is fixed; notes/release.md's debuggability
  trade-off stands.
