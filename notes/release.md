# Release Process

Releases are signed APKs published as GitHub release assets. No app-store distribution yet.

Release APKs are **built in F-Droid's buildserver image, not on a dev
machine** — that is what lets F-Droid rebuild the tag, get a byte-identical
result, and distribute the maintainer-signed APK instead of one signed with
their own key. One signing lineage means nobody ever has to uninstall (which
would delete their distros) to switch channels. The container build needs no
keys, so the agent runs it; the maintainer only signs. See
[plans/reproducible-builds.md](../plans/reproducible-builds.md) and
[building.md](building.md) ("F-Droid buildserver rig").

## Versioning

- Plain release counter: versions are `1`, `2`, `3`, … No semver — the app has no breaking/non-breaking distinction for users; every release is expected to migrate existing installs forward.
- `versionName` in `app/build.gradle.kts` is the single source of truth; `versionCode = versionName.toInt()`, so Android's monotonic-versionCode upgrade requirement is satisfied automatically.
- Each release commit is tagged `vN` (annotated).

Everything else derives the version rather than repeating it: the app reads
it from `PackageManager` at runtime, and `build-release-apk.sh` reads it
back out of the built APK with aapt2. Only two things in the repo name a
version statically, both because their formats demand it:

| File | Why | On bump |
|------|-----|---------|
| `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` | F-Droid reads one changelog file per version code | add a new file; old ones stay |
| `fdroid/me.phie.tawc.yml` | fdroiddata recipe draft — needs a concrete `Builds` entry | re-sync its five version fields, until the recipe is merged upstream and the file is deleted |

`scripts/check-version-sync.sh` verifies both against
`app/build.gradle.kts`, so a bump cannot silently miss them.

## Prep steps (agent)

When asked to prep a release:

1. Bump `versionName` in `app/build.gradle.kts` to the next integer.
   Also check the shipped archive keyrings: none of the keys in
   `app/src/main/res/raw/debian_archive_keyring.asc` may be nearing
   expiry (currently 12/bookworm expires 2031, 13/trixie 2035), and if
   Debian has published a new suite key (14/forky…), add it — obtain
   from two independent origins and diff, per notes/installation.md
   "Bootstrap integrity". `ShippedPgpKeysTest` pins the expected
   fingerprints.
2. Re-run `scripts/gen-third-party-licenses.sh` and commit any change to
   `app/src/main/assets/licenses.json`. The APK is GPLv3 and
   carries permissive third-party notices, so the in-app attribution
   text must match what the release actually ships — see
   [licensing.md](licensing.md).
3. Draft release notes from `git log <last-tag>..` (first release: summarize the feature set instead). Keep them user-facing: features, fixes, known limitations.
4. Write the F-Droid changelog for this release:
   `fastlane/metadata/android/en-US/changelogs/<N>.txt`, a condensed
   version of the notes from step 3 (max 500 characters — F-Droid
   truncates past that). If `fdroid/me.phie.tawc.yml` still exists, re-sync
   its `versionName`, `versionCode`, `commit`, `CurrentVersion` and
   `CurrentVersionCode` to this release. Then run
   `scripts/check-version-sync.sh` and fix anything it reports.
5. Commit as `release: vN` (the prep request counts as the explicit ask to commit/tag; do not push).
6. Build the release APK in F-Droid's image, from that commit:

       scripts/fdroid/prepare.sh release
       scripts/fdroid/run.sh

   `release` mode refuses a dirty tree and re-clones the mirror, so what
   it builds is exactly what the tag will name. The unsigned APK lands in
   `app/build/outputs/apk/release/me.phie.tawc_N-unsigned.apk`. Build it
   close to publishing: the rig `dist-upgrade`s inside the container, so a
   trixie package update between this build and F-Droid's rebuild is the
   one thing that can break reproducibility.
7. Spot-check determinism: run it a second time with `--fresh-cache` (and
   optionally `--cpuset-cpus 0-5`) and compare:

       scripts/fdroid/compare-apks.py <first> <second>

   Anything other than "all entries identical" means a new
   non-determinism crept in — find it before tagging, not after F-Droid
   refuses to publish.
8. Tag `vN` (annotated) on the release commit. Do not push.
9. Hand off: print the human steps below with the concrete version filled
   in, the path to the unsigned APK, and the drafted notes (e.g. as a
   `--notes-file` in scratch or inline for copy/paste).

Signing cannot be run by the agent: the keystore lives in a different user account where Claude does not run.

## Publish steps (human, as the key-owning user)

1. Sign the container's APK — not a fresh local build, which would not
   reproduce:

       scripts/build-release-apk.sh --apk app/build/outputs/apk/release/me.phie.tawc_N-unsigned.apk

   It checks the APK is already aligned, signs it with v2/v3 only, and
   gates on `apksigcopier compare`, which is the same check F-Droid runs.
   The signed `tawc-vN.apk` lands **next to the APK you passed in** — so
   handing the unsigned APK over in a shared directory and signing it
   there works without a checkout path in the middle. Needs
   `apksigcopier` and build-tools 35+.
2. Optionally run F-Droid's own verification end to end before anything is
   public (no key needed, so the agent can do this given the signed APK):

       scripts/fdroid/prepare.sh reproduce --apk <tawc-vN.apk> --commit vN
       scripts/fdroid/run.sh

3. Smoke-test that exact APK on the physical phone: fresh install + launch + distro install + run an app (e.g. lxterminal); for later releases also install *over* the previous release to catch signing/versionCode upgrade breakage. The release build differs from the dev loop (no debug methods, production graphics set), so dev-loop testing does not cover it.
4. Push `main` and the tag.
5. `gh release create vN tawc-vN.apk --title "TAWC vN" --notes-file <notes>`.
   The asset name must be exactly `tawc-vN.apk` — the recipe's `Binaries:`
   URL pattern depends on it. **Never replace a published asset:** F-Droid
   pins the binary it verified, and swapping it out breaks that pin.
6. Close out any upstream issues the release fixes. Pending:
   [#12](https://github.com/wmww/tawc/issues/12) (`mkdir /test` →
   Permission denied under a 0555 `/`) — fixed by tawcroot's lazy
   CAP_DAC_OVERRIDE emulation; the interim workaround for anyone on an
   older build is `chmod 755 /` inside the guest.

## Debuggability over size

Release builds are deliberately NOT minified, obfuscated, or stripped (release block + `packaging.jniLibs.keepDebugSymbols` in `app/build.gradle.kts`): user-reported Java stack traces are readable as-is, and native tombstones come out of the device symbolized — no mapping.txt archiving, no unstripped-artifact hunting. This roughly doubles the APK (~29 vs ~13 MB R8-minified); anything under ~50 MB is an acceptable trade. `proguard-rules.pro` stays correct regardless, so minifying is a one-flag change if a size ceiling ever appears.

## Keystore

- The signing key is load-bearing: every release must be signed with the same key or users cannot upgrade without uninstalling — which deletes their app-private distro installs (real data loss).
- Keystore lives at `$KEYSTORE_PATH` (default `~/Android/keystore.jks`) in the key-owning user's account. Keep it and its password backed up somewhere durable.

## Release notes

Changelogs are the agent's job, not the maintainer's: the agent writes and
maintains every `changelogs/<N>.txt` (and any future changelog) without
being asked, as part of release prep. Descriptions and screenshots stay
maintainer-written.

No in-repo `CHANGELOG.md`; GitHub release notes are the changelog. Revisit if another distribution channel appears.
