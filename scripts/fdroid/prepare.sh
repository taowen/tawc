#!/bin/bash
# Stage build/fdroid/ for one rig run: refresh the mirror of this repo and
# install the recipe under test into the fdroiddata checkout, rewritten to
# build the chosen commit from that mirror.
#
#   prepare.sh [verify|release] [--commit <ref>]
#   prepare.sh reproduce --apk <signed.apk> [--commit <ref>] [--unpinned-key]
#
# Bootstraps the checkouts on first run. See lib.sh for the modes.
set -euo pipefail
# shellcheck source=lib.sh
source "$(cd "$(dirname "$0")" && pwd)/lib.sh"

MODE=verify
COMMIT=HEAD
REFERENCE_APK=
UNPINNED_KEY=0
while [ $# -gt 0 ]; do
    case "$1" in
        verify|release|reproduce) MODE="$1"; shift ;;
        --commit) COMMIT="$2"; shift 2 ;;
        --apk) REFERENCE_APK="$2"; shift 2 ;;
        --unpinned-key) UNPINNED_KEY=1; shift ;;
        *) echo "usage: prepare.sh [verify|release|reproduce] [--commit <ref>]" >&2
           echo "                  [--apk <signed.apk>] [--unpinned-key]" >&2; exit 2 ;;
    esac
done

if [ "$MODE" = reproduce ] && [ -z "$REFERENCE_APK" ]; then
    echo "reproduce mode needs --apk <signed.apk>: the published binary" >&2
    echo "the rebuild is checked against" >&2
    exit 1
fi
[ -z "$REFERENCE_APK" ] || [ -f "$REFERENCE_APK" ] || {
    echo "no such APK: $REFERENCE_APK" >&2; exit 1; }

REPO="$FDROID_REPO_DIR"
STATE="$FDROID_STATE_DIR"
MIRROR="$STATE/tawc.git"
FDROIDDATA="$STATE/fdroiddata"
mkdir -p "$STATE"

if [ ! -d "$FDROIDDATA" ]; then
    echo "==> cloning fdroiddata (metadata/, config.yml and the output dirs"
    echo "    are all the rig uses, so shallow is fine)"
    git clone --depth 1 https://gitlab.com/fdroid/fdroiddata.git "$FDROIDDATA"
fi

# The container chowns the fdroiddata mount to its own vagrant user, which
# lands on a subuid the host user cannot write. Take it back.
if [ -n "$(find "$FDROIDDATA" -maxdepth 1 ! -user "$(id -u)" -print -quit)" ]; then
    podman unshare chown -R 0:0 "$FDROIDDATA"
fi

# A release build must be of exactly what gets tagged, so it comes from a
# mirror cloned fresh — no WIP snapshot refs, no stale objects.
if [ "$MODE" = release ]; then
    if [ -n "$(git -C "$REPO" status --porcelain)" ]; then
        echo "release mode needs a clean working tree (the tag has to build" >&2
        echo "the same source F-Droid will fetch)" >&2
        exit 1
    fi
    rm -rf "$MIRROR"
fi
if [ ! -d "$MIRROR" ]; then
    git clone --quiet --mirror "$REPO" "$MIRROR"
else
    git -C "$MIRROR" fetch --prune origin '+refs/*:refs/*'
fi

SHA="$(git -C "$REPO" rev-parse "$COMMIT")"

# verify mode tests what is in the working tree, not just what is
# committed: if the tree is dirty, snapshot it as a commit that exists only
# in the mirror. This writes objects into the working repo but touches no
# ref, index or branch there — nothing is committed on the user's behalf.
if [ "$MODE" = verify ] && [ "$COMMIT" = HEAD ] \
        && [ -n "$(git -C "$REPO" status --porcelain)" ]; then
    export GIT_INDEX_FILE="$STATE/wip-index"
    rm -f "$GIT_INDEX_FILE"
    git -C "$REPO" read-tree HEAD
    git -C "$REPO" add -A
    tree="$(git -C "$REPO" write-tree)"
    SHA="$(git -C "$REPO" commit-tree "$tree" -p HEAD -m 'fdroid rig: uncommitted working tree')"
    unset GIT_INDEX_FILE
    echo "working tree is dirty: testing snapshot $SHA"
fi

# The mirror only carries what origin has, and `fetch --prune` drops
# anything else — including a snapshot ref from an earlier run. Push the
# commit under test back whenever it is missing, so reproduce mode can
# re-test a snapshot the mirror has since forgotten.
if ! git -C "$MIRROR" cat-file -e "$SHA^{commit}" 2>/dev/null; then
    git -C "$REPO" push --quiet "$MIRROR" "+$SHA:refs/heads/fdroid-rig-wip"
fi

# The recipe in the repo builds a GitHub tag; point it at the mirror and
# the commit under test instead. Everything else is used verbatim.
RECIPE="$FDROIDDATA/metadata/me.phie.tawc.yml"
sed_args=(
    -e "s|^Repo: .*|Repo: /srv/tawc.git|"
    -e "s|^    commit: .*|    commit: $SHA|"
)
if [ "$MODE" = reproduce ]; then
    # Point `Binaries:` at a copy of the signed APK, served inside the
    # container (in-container.sh starts the server) so fdroidserver's own
    # download-and-verify path runs unchanged. This is the full check:
    # apksigcopier transplants the signature onto the rebuild and
    # apksigner must still verify it.
    VERSION="$(fdroid_version)"
    REFDIR="$STATE/reference"
    rm -rf "$REFDIR"; mkdir -p "$REFDIR"
    cp "$REFERENCE_APK" "$REFDIR/tawc-v$VERSION.apk"
    sed_args+=(-e "s|^Binaries: .*|Binaries: https://127.0.0.1:8099/tawc-v%v.apk|")
    if [ "$UNPINNED_KEY" = 1 ]; then
        # Self-test escape hatch: re-pin to whatever key signed the
        # reference APK, so the rig can be exercised with a throwaway
        # keystore. Never use this to check a real release.
        BT="$(ls -1 "${ANDROID_HOME:-$HOME/Android/Sdk}/build-tools" | sort -V | tail -1)"
        KEY="$("${ANDROID_HOME:-$HOME/Android/Sdk}/build-tools/$BT/apksigner" \
            verify --print-certs "$REFERENCE_APK" 2>/dev/null \
            | sed -n 's/.*certificate SHA-256 digest: //p' | head -1)"
        [ -n "$KEY" ] || { echo "could not read the reference APK's signing key" >&2; exit 1; }
        echo "WARNING: --unpinned-key: trusting the reference APK's own key $KEY"
        sed_args+=(-e "s|^AllowedAPKSigningKeys: .*|AllowedAPKSigningKeys: $KEY|")
    fi
else
    # No published binary matches an unreleased commit, so drop the
    # reference-binary check.
    sed_args+=(-e "/^Binaries: /d")
fi
sed "${sed_args[@]}" "$REPO/fdroid/me.phie.tawc.yml" > "$RECIPE"

fdroid_write_mode "$MODE"
grep -n '^Repo:\|commit:' "$RECIPE"
echo "staged $MODE build of $SHA as $(fdroid_app_id)"
