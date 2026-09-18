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

F-Droid-image build (`build/fdroid-test` rig) vs the maintainer-signed
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

1. **Deterministic asset tars.** `packLibhybris` and `packXwaylandShare`
   in `app/build.gradle.kts` (and the Mesa/zink packers, for
   consistency): add `--sort=name --owner=0 --group=0 --numeric-owner
   --mode=u+rwX,go+rX,go-w --mtime=@<epoch>`, epoch = commit time of
   HEAD (`git log -1 --format=%ct`; fall back to 0 outside git). Check
   the runtime extractors don't care about owner/mtime (the xwayland
   stamp is version+lastUpdateTime, not tar mtimes).
2. **Promote the rig to a release builder.** Move
   `build/fdroid-test/{prepare,run,in-container,lint,clean}.sh` into
   `scripts/fdroid/` (state stays under `build/fdroid/`), parameterised
   on the version instead of the hardcoded `me.phie.tawc:N`. Two modes:
   `verify` (today's behaviour: does the recipe build?) and `release`
   (build a given commit from a clean mirror clone, copy
   `unsigned/me.phie.tawc_N.apk` out to
   `app/build/outputs/apk/release/`). Document in notes/building.md
   (podman becomes a release-time host dep).
3. **Prove determinism before trusting it.** Build the same commit
   twice — once with cold `cache/`, once warm, and once with a different
   core count (`--cpuset-cpus`) — and diff entry-by-entry. Expect to
   chase a few residuals; suspects in order: autotools deps embedding
   `__DATE__`/hostname, link order under parallel make, zstd-jni/AGP
   packaging order, Rust (normally deterministic with a pinned
   toolchain and `Cargo.lock`). Fix each at its source; honour
   `SOURCE_DATE_EPOCH` (export it in the build scripts from the same
   commit time as step 1). Keep the comparison as a script
   (`scripts/fdroid/compare-apks.py`, the zip-CRC diff used for the v2
   measurement) so every release can re-run it.
4. **Sign-only path.** `scripts/build-release-apk.sh --apk <unsigned>`:
   skip Gradle, sign that exact file. The signed APK must differ from
   the unsigned one only by the signing block, or F-Droid's signature
   transplant fails — so verify whether the current zipalign pass
   rewrites an already-aligned AGP output, and drop it for this path if
   it does. Gate the script on `apksigcopier compare <signed>
   --unsigned <container apk>` succeeding.
5. **Recipe.** Add to `fdroid/me.phie.tawc.yml`:

       Binaries: https://github.com/wmww/tawc/releases/download/v%v/tawc-v%v.apk
       AllowedAPKSigningKeys: 0b2262d221a951b57d847b288324004ce3b2fefe1ef52a1140f2fbc1fbb990be

   (SHA-256 of the release cert, from `apksigner verify --print-certs`
   on v2.) Re-run lint, rewritemeta and schema validation. Add a rig
   mode that feeds the signed APK to `fdroid build` as the reference
   binary so the full verify path is exercised before anything is
   public.
6. **Release process.** Rewrite notes/release.md: prep commit →
   container `release` build of that commit (agent) → determinism
   spot-check → tag → maintainer signs with `--apk` → `apksigcopier
   compare` → smoke test → push + GitHub release. The asset name must
   match the `Binaries:` pattern exactly (`tawc-vN.apk`). Never replace
   a published asset: F-Droid pins what it verified.
7. **Cut v3 through the new process, then open the MR** with the
   "Enable Reproducible Builds" box ticked and v3 as the only Builds
   entry.

## Risks

- **Image drift.** The release is built against trixie's packages on
  day X; F-Droid rebuilds days later. A GCC/binutils point update in
  between changes libhybris. Trixie is stable so this is rare; the rig
  `dist-upgrade`s like their CI, so build right before publishing.
  Failure mode is benign: F-Droid does not publish that version, users
  stay on the previous one, fix is vN+1.
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
