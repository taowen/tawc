#!/bin/bash
# Run the staged build in the buildserver container. Long: hours.
#
#   run.sh [--cpuset-cpus <spec>] [--fresh-cache]
#
# Run prepare.sh first; the mode it staged is what runs here. In release
# mode the unsigned APK is copied to app/build/outputs/apk/release/ at the
# end, ready for `scripts/build-release-apk.sh --apk`.
#
# --cpuset-cpus limits the build to those cores. Reproducibility must not
# depend on the core count (link order under parallel make is the usual
# culprit), so a determinism check builds the same commit twice with
# different values here. It is applied with taskset inside the container
# rather than podman's --cpuset-cpus, which rootless podman can only
# honour when systemd delegates the cpuset controller to the user slice
# — it usually delegates only cpu, memory and pids.
# --fresh-cache empties the Gradle/cargo/apt caches first, for the
# cold-vs-warm half of that same check.
set -euo pipefail
# shellcheck source=lib.sh
source "$(cd "$(dirname "$0")" && pwd)/lib.sh"

CPUSET=
FRESH_CACHE=0
while [ $# -gt 0 ]; do
    case "$1" in
        --cpuset-cpus) CPUSET="$2"; shift 2 ;;
        --fresh-cache) FRESH_CACHE=1; shift ;;
        *) echo "usage: run.sh [--cpuset-cpus <spec>] [--fresh-cache]" >&2; exit 2 ;;
    esac
done

STATE="$FDROID_STATE_DIR"
MODE="$(fdroid_read_mode)"
VERSION="$(fdroid_version)"

# Caches, so an iteration does not re-download the NDK, Gradle and crates
# each time. fdroiddata's CI caches .gradle across jobs too.
CACHES=(gradle cargo sdkmanager-root sdkmanager-vagrant apt)
if [ "$FRESH_CACHE" = 1 ]; then
    for c in "${CACHES[@]}"; do podman unshare rm -rf "$STATE/cache/$c"; done
fi
for c in "${CACHES[@]}"; do mkdir -p "$STATE/cache/$c"; done
mkdir -p "$STATE/logs"
# Only reproduce mode puts anything here, but the mount has to exist.
mkdir -p "$STATE/reference"

LOG="$STATE/logs/$MODE-$(date +%Y%m%d-%H%M%S).log"
podman_args=(
    run --rm --name tawc-fdroid-build
    -v "$STATE/fdroiddata:/builds/fdroiddata"
    -v "$STATE/tawc.git:/srv/tawc.git:ro"
    -v "$STATE/cache/gradle:/home/vagrant/.gradle"
    -v "$STATE/cache/cargo:/home/vagrant/.cargo"
    -v "$STATE/cache/sdkmanager-vagrant:/home/vagrant/.cache/sdkmanager"
    -v "$STATE/cache/sdkmanager-root:/root/.cache/sdkmanager"
    -v "$STATE/cache/apt:/var/cache/apt/archives"
    -v "$FDROID_SCRIPT_DIR/in-container.sh:/usr/local/bin/in-container.sh:ro"
    -v "$STATE/reference:/srv/reference:ro"
    -e "TAWC_APP=$(fdroid_app_id)"
    -e "TAWC_MODE=$MODE"
    -w /builds/fdroiddata
)
entry=(bash /usr/local/bin/in-container.sh)
[ -n "$CPUSET" ] && entry=(taskset -c "$CPUSET" "${entry[@]}")

echo "==> $MODE build of $(fdroid_app_id); log: ${LOG#"$FDROID_REPO_DIR"/}"
set +e
fdroid_podman "${podman_args[@]}" "$FDROID_IMAGE" "${entry[@]}" 2>&1 | tee "$LOG"
status="${PIPESTATUS[0]}"
set -e
[ "$status" = 0 ] || exit "$status"

[ "$MODE" = release ] || exit 0

# fdroid names the unsigned output by versionCode.
APK="$STATE/fdroiddata/unsigned/me.phie.tawc_$VERSION.apk"
[ -f "$APK" ] || { echo "no unsigned APK at $APK" >&2; exit 1; }
OUT_DIR="$FDROID_REPO_DIR/app/build/outputs/apk/release"
mkdir -p "$OUT_DIR"
cp "$APK" "$OUT_DIR/me.phie.tawc_$VERSION-unsigned.apk"
chmod u+w "$OUT_DIR/me.phie.tawc_$VERSION-unsigned.apk"
echo "==> unsigned APK: app/build/outputs/apk/release/me.phie.tawc_$VERSION-unsigned.apk"
echo "    sign it with: scripts/build-release-apk.sh --apk <that file>"
