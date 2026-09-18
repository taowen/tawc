#!/bin/bash
# Shared config for the F-Droid buildserver rig. Sourced by the other
# scripts in this dir; not useful on its own.
#
# The rig runs `fdroid build` on `fdroid/me.phie.tawc.yml` inside F-Droid's
# own buildserver image, as close as fdroiddata's CI gets. Two modes:
#
#   verify   throwaway check that the recipe still builds (`--test`).
#            Builds the local working tree, dirty or not.
#   release  build the APK we actually publish, from a clean tree, and
#            copy the unsigned result out. Its whole point is that the
#            build path and toolchain match what F-Droid will use when it
#            rebuilds the tag, so the two APKs are byte-identical —
#            see plans/reproducible-builds.md.
#   reproduce rebuild and check the result against a signed APK, the way
#            F-Droid will once the recipe carries `Binaries:`. Run it on
#            the signed release before publishing anything.
#
# All mutable state lives under build/fdroid/ (gitignored): the container
# storage, the fdroiddata checkout, the mirror of this repo, the caches
# and the logs. `clean.sh` removes the lot.

FDROID_IMAGE=registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie

FDROID_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FDROID_REPO_DIR="$(cd "$FDROID_SCRIPT_DIR/../.." && pwd)"
FDROID_STATE_DIR="$FDROID_REPO_DIR/build/fdroid"

# versionName in app/build.gradle.kts is the single source of the version
# (notes/release.md); versionCode is the same number, and that is what
# fdroid addresses a build entry by.
fdroid_version() {
    local v
    v="$(sed -n 's/^ *versionName *= *"\([^"]*\)".*/\1/p' \
        "$FDROID_REPO_DIR/app/build.gradle.kts" | head -1)"
    [ -n "$v" ] || { echo "no versionName in app/build.gradle.kts" >&2; return 1; }
    echo "$v"
}

# `me.phie.tawc:<versionCode>` — the build entry fdroid is asked for.
fdroid_app_id() { echo "me.phie.tawc:$(fdroid_version)"; }

# Rootless podman with the rig's own storage, so the rig's images never
# mix with anything else on the host. Note that podman records the
# absolute storage path in build/fdroid/containers/storage/db.sql, so
# moving or renaming the repo makes it refuse to start ("database
# configuration mismatch"); either rewrite DBConfig's StaticDir/
# GraphRoot/VolumeDir with sqlite3 or run clean.sh and re-download.
fdroid_podman() {
    podman --root "$FDROID_STATE_DIR/containers/storage" \
           --runroot "/run/user/$(id -u)/tawc-fdroid" "$@"
}

# prepare.sh writes the mode; run.sh reads it back, so the two cannot
# disagree about what is staged in fdroiddata/.
fdroid_write_mode() { printf '%s\n' "$1" > "$FDROID_STATE_DIR/mode"; }
fdroid_read_mode() {
    cat "$FDROID_STATE_DIR/mode" 2>/dev/null \
        || { echo "no mode staged; run scripts/fdroid/prepare.sh first" >&2; return 1; }
}
