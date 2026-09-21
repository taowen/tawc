# Google Play Publication

Goal: ship TAWC on Google Play alongside F-Droid and GitHub releases,
from the same source and `vN` tags.

Steps marked **HUMAN** need the user: accounts, identity, payment, Console
forms, and anything sent to Google reviewers. Agents prepare inputs (build
variant, draft texts) only.

Play policy moves; re-verify the policy-dependent items below before acting.

## Verified 2026-09-18 (agent-checked, re-verify if stale)

- `targetSdk = 36`, `minSdk = 29`: meets Play's current target-API floor.
  The floor rises yearly (each August); a Play listing commits us to bumping
  `targetSdk` on that cadence.
- No `execve` of app-data files: tawcroot maps guest ELFs itself (userspace
  loader + `execveat` on memfds) and `libtawcroot.so` is exec'd from
  `nativeLibraryDir`. So the W^X restriction that pushed Termux off Play
  does not bite, and nothing depends on a low `targetSdk`.
- `-PtawcAllFilesAccess=false` already strips `MANAGE_EXTERNAL_STORAGE` and
  hides the binds UI (`AllFilesAccess.declared`).
- Release variant already drops proot/chroot and their binaries.
- Cleartext is off (`network_security_config.xml`), no GMS, no trackers, no
  ads, no analytics: the Data safety form should be "no data collected".
- `fastlane/metadata/android/en-US/` has title, short/full description, icon
  and phone screenshots, reusable for the Play listing.
- Size is fine: debug APK ~50 MB, release ~29 MB, against a 200 MB
  base-module limit. No asset packs needed.
- **Blocker:** native libs are not 16 KB aligned. NDK is r27
  (`27.2.12479018`), which still defaults to 4 KB; `readelf -lW` on the
  staged `jniLibs/arm64-v8a/*.so` shows `0x1000` LOAD alignment for
  everything except `libcompositor.so` / `libc++_shared.so` (`0x4000`) —
  `libtawcroot.so`, `libxwayland.so` and all its X/xcb deps,
  `libwayland-*`, `libdrm`, `libgfxstream_backend.so`. Play rejects uploads
  targeting Android 15+ that fail this. No build script passes
  `max-page-size`.
- No privacy policy exists anywhere in the repo or listing.
- `scripts/build-release-apk.sh` only produces a signed APK; Play needs an
  AAB signed with an upload key.

## Risks

1. **Downloaded-code policy (Device and Network Abuse).** Play forbids
   downloading executable code from outside Play. Precedent for
   user-initiated distro rootfs downloads exists (UserLAnd, Andronix,
   Termux's Play build), but review is inconsistent and a rejection or later
   takedown is possible. Mitigation: listing and review notes describe the
   rootfs as user-selected guest content in a sandbox, PGP-verified, from the
   distros' own mirrors; the app never updates its own code. Accept that this
   may end in an appeal or a "no".
2. **Signing split.** Play App Signing is mandatory for new apps: Google
   holds the app key, we hold an upload key. Play installs will not be
   upgrade-compatible with F-Droid/GitHub installs, and switching store means
   uninstall = losing distro installs (see notes/release.md "Keystore").
   Option: enrol the existing release key as the Play app signing key so
   GitHub and Play builds share a signature. This means uploading the
   private key to Google; decide in 3.2.
3. **Feature gap.** The Play build has no external-storage binds. Needs a
   line in the listing, and the in-app UI must not dangle references to it.
4. **specialUse FGS review.** Requires a written justification and a video;
   a reviewer may push for a standard type. None fits a compositor.

## Steps

### 1. 16 KB page size (agent)

Useful independent of Play: 16 KB devices exist.

1.1. Link every shipped native object with `-Wl,-z,max-page-size=16384`
   (or move to NDK r28+, which defaults to it, and update
   `notes/building.md` + `ndkVersion` together). Covers
   `build-xwayland.sh` and its deps, wayland, libdrm, libffi, gfxstream,
   proot, tawcroot, ando, and the host-side contents of the libhybris asset
   tar. Put the flag in one shared place in `scripts/lib/` rather than per
   script.
1.2. Audit tawcroot for hard-coded 4096 (loader `PT_LOAD` rounding, mmap
   emulation, shm, `AT_PAGESZ`); it must use the runtime page size.
1.3. Add a check script (`scripts/check-page-align.sh <apk|aab>`: `zipalign
   -c -P 16` plus a `readelf` LOAD-alignment pass) and call it from the
   release build script.
1.4. Test on a 16 KB emulator image. Needs `scripts/emulator.sh` support
   for a 16 KB system image; tawcroot host tests plus one install/launch
   integration test. x86_64 guest binaries are 4 KB-aligned, which loads
   fine on a 16 KB kernel only if segment boundaries cooperate — expect
   findings here; file issues rather than blocking Play on the emulator
   (arm64 distro binaries are 64 KB-aligned).

### 2. Play build variant (agent)

2.1. `scripts/build-release-aab.sh`: `bundleRelease` with
   `-PtawcAllFilesAccess=false -PtawcMethods=tawcroot
   -PtawcGraphics=libhybris,cpu -PtawcAbis=arm64-v8a`, signed with the upload
   key (`$PLAY_KEYSTORE_PATH`), output `tawc-vN.aab`. Share the
   keystore/alias/version plumbing with `build-release-apk.sh` instead of
   copying it. Prefer a Gradle property over a product flavor: flavors would
   double every variant-keyed task in `app/build.gradle.kts`.
2.2. Confirm native libs are still extracted to disk when installed from a
   bundle (`extractNativeLibs="true"` should force
   uncompressed-in-APK off; otherwise set
   `android.bundle.enableUncompressedNativeLibs=false`). Verify with
   `bundletool build-apks --connected-device` + install on the phone, then
   check `libtawcroot.so` exists under `nativeLibraryDir` and a distro
   launches.
2.3. x86_64: leave out unless the pre-launch report needs it. The x86_64
   path is emulator-only (gfxstream, experimental) and Play would offer it
   to Chromebooks.
2.4. Audit the no-binds build: no UI, docs link, or error text pointing at
   the binds feature; first launch with no rootfs installed must not crash
   (pre-launch report robots will do exactly that).
2.5. Keep release builds unminified (notes/release.md "Debuggability over
   size"); Play only warns about a missing mapping file.

### 3. Account and signing (**HUMAN**)

3.1. Create the Play developer account ($25, identity verification). A
   personal account must run a closed test with 12+ opted-in testers for 14
   consecutive days before production access — start this early, it is the
   critical path. An organisation account skips it but needs a D-U-N-S
   number. The developer's legal name is shown publicly.
3.2. Decide the signing model (Risk 2), generate the upload key, back it up
   next to the release keystore.
3.3. Create the app as `me.phie.tawc`. The package name is permanent.

### 4. Listing and declarations

4.1. (agent drafts, **HUMAN** publishes) Privacy policy at a stable public
   URL, e.g. `PRIVACY.md` in the GitHub repo: no data collected; network
   use is downloading distro packages from the mirrors the user selects;
   whatever Linux software the user installs is outside the app's control.
4.2. (**HUMAN**) Store listing from the fastlane metadata, plus a 1024x500
   feature graphic (new; derive from `app/icon.svg` via
   `scripts/gen-icon.sh` if wanted — do not hand-edit the SVG). State the
   no-shared-storage limitation and link GitHub/F-Droid for the full build.
4.3. (**HUMAN**, agent drafts text) Console declarations:
   - Foreground service `specialUse` (subtype `linux_session`):
     justification ("Keeps user-launched Linux programs alive while they
     run: terminal shells, command-line jobs, and the Wayland compositor
     hosting Linux GUI apps. They are child processes of the app, so
     Android killing the backgrounded app would kill them and lose the
     user's work; the service runs only while such a program exists and
     its notification has an Exit action") plus a short screen recording.
   - Foreground service `dataSync`: distro install/uninstall. Note the
     Android 15+ 6-hour cap; installs are far shorter.
   - Data safety: no data collected or shared.
   - Content rating questionnaire; target audience 18+ / not for children;
     no ads.
4.4. (**HUMAN**) Reviewer notes on the first submission explaining the
   rootfs download (Risk 1) up front rather than waiting for a rejection.

### 5. Test tracks and launch (**HUMAN**)

5.1. Internal testing upload; read the pre-launch report and fix crashes.
5.2. Closed test for the 14-day/12-tester requirement if on a personal
   account.
5.3. Apply for production access, then a staged rollout.

### 6. Fold into the release process (agent)

6.1. notes/release.md: add the AAB build + Play upload to the publish steps,
   the Play changelog (same 500-char text as
   `fastlane/.../changelogs/<N>.txt`), and the yearly `targetSdk` bump.
6.2. notes/building.md: upload keystore env vars, bundletool, page-align
   check, NDK change if 1.1 moves it.
6.3. Note that a rejected upload still burns its `versionCode`; with
   `versionCode == versionName` a Play-only re-upload means a new `vN` for
   every channel. If that becomes painful, revisit the versioning scheme
   rather than special-casing Play.
6.4. Delete this plan; move the durable parts (signing model, policy
   position, declarations text) into notes/release.md.

## Order

3.1 first (longest wait), then 1 and 2 in parallel with the F-Droid review,
then 4, then 5. Step 1 is worth doing even if Play is abandoned.
