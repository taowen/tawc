#!/bin/bash
# Build a signed release APK (zipaligned, v2/v3 signed).
#
# Keystore: $KEYSTORE_PATH (default: $HOME/Android/keystore.jks)
# Alias:    $KEY_ALIAS     (default: the keystore's sole PrivateKeyEntry,
#                                    or error if there are multiple)
# Prompts for the keystore password unless $KEYSTORE_PASS is set.
# Key password defaults to the keystore password; override with $KEY_PASS.
#
# Flags:
#   --no-build   reuse the existing app-release-unsigned.apk
#   --graphics=list
#              override production graphics backend set
#   --apk <path>
#              sign this exact unsigned APK instead of building one.
#              This is the release path: the APK comes from
#              scripts/fdroid/run.sh, built in F-Droid's own image, so
#              F-Droid's rebuild of the tag reproduces it and can ship
#              our signature. Signing must change nothing but the
#              signing block, so the file is verified already-aligned
#              rather than re-aligned, and `apksigcopier compare`
#              gates the result.
#
# Output: app/build/outputs/apk/release/tawc-v<version>.apk
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"

KEYSTORE_PATH="${KEYSTORE_PATH:-$HOME/Android/keystore.jks}"

DO_BUILD=1
PREBUILT_APK=
GRAPHICS="${TAWC_RELEASE_GRAPHICS:-libhybris,cpu}"
while [ $# -gt 0 ]; do
    case "$1" in
        --no-build) DO_BUILD=0; shift ;;
        --graphics=*) GRAPHICS="${1#--graphics=}"; shift ;;
        --apk) PREBUILT_APK="$2"; DO_BUILD=0; shift 2 ;;
        --apk=*) PREBUILT_APK="${1#--apk=}"; DO_BUILD=0; shift ;;
        -h|--help)
            sed -n '2,/^set -/p' "$0" | sed 's/^# \?//;$d'
            exit 0
            ;;
        *) echo "ERROR: unknown arg: $1" >&2; exit 2 ;;
    esac
done

if [ -n "$PREBUILT_APK" ]; then
    [ -f "$PREBUILT_APK" ] || { echo "ERROR: no such APK: $PREBUILT_APK" >&2; exit 1; }
    command -v apksigcopier >/dev/null || {
        echo "ERROR: --apk needs apksigcopier to verify that signing changed" >&2
        echo "       nothing else (Arch: pacman -S apksigcopier)" >&2
        exit 1
    }
fi

[ -f "$KEYSTORE_PATH" ] || { echo "ERROR: keystore not found at $KEYSTORE_PATH" >&2; exit 1; }

# build-tools 35+ — `apksigner --alignment-preserved` is newer than 34.
BUILD_TOOLS_VER="$(ls -1 "$ANDROID_HOME/build-tools" 2>/dev/null | sort -V | tail -1 || true)"
[ -n "$BUILD_TOOLS_VER" ] || { echo "ERROR: no build-tools under $ANDROID_HOME/build-tools" >&2; exit 1; }
ZIPALIGN="$ANDROID_HOME/build-tools/$BUILD_TOOLS_VER/zipalign"
APKSIGNER="$ANDROID_HOME/build-tools/$BUILD_TOOLS_VER/apksigner"
AAPT2="$ANDROID_HOME/build-tools/$BUILD_TOOLS_VER/aapt2"

if [ -z "${KEYSTORE_PASS+x}" ]; then
    read -rsp "Keystore password (if any): " KEYSTORE_PASS
    echo
fi
export KEYSTORE_PASS
export KEY_PASS="${KEY_PASS:-$KEYSTORE_PASS}"

# Verify the keystore password and pick an alias up front so we don't
# run the whole release build only to fail at the signing step.
if ! keystore_listing="$(keytool -list -keystore "$KEYSTORE_PATH" -storepass:env KEYSTORE_PASS 2>/dev/null)"; then
    echo "ERROR: keystore password rejected by keytool" >&2
    exit 1
fi
mapfile -t aliases < <(awk -F', ' '/PrivateKeyEntry/ {print $1}' <<<"$keystore_listing")
if [ -z "${KEY_ALIAS+x}" ]; then
    case "${#aliases[@]}" in
        0) echo "ERROR: no PrivateKeyEntry in $KEYSTORE_PATH" >&2; exit 1 ;;
        1) KEY_ALIAS="${aliases[0]}" ;;
        *) echo "ERROR: keystore has multiple aliases; set KEY_ALIAS to one of:" >&2
           printf '  %s\n' "${aliases[@]}" >&2
           exit 1 ;;
    esac
elif ! printf '%s\n' "${aliases[@]}" | grep -Fxq "$KEY_ALIAS"; then
    echo "ERROR: alias '$KEY_ALIAS' not found in keystore. Available:" >&2
    printf '  %s\n' "${aliases[@]}" >&2
    exit 1
fi
# -certreq actually unlocks the private key, which is what apksigner does
# and what -list does not. The stdout CSR is discarded.
verify_key_pass() {
    keytool -certreq -keystore "$KEYSTORE_PATH" -alias "$KEY_ALIAS" \
        -storepass:env KEYSTORE_PASS -keypass:env KEY_PASS >/dev/null 2>&1
}
if ! verify_key_pass; then
    read -rsp "Key password for alias '$KEY_ALIAS' (differs from keystore password): " KEY_PASS
    echo
    export KEY_PASS
    if ! verify_key_pass; then
        echo "ERROR: key password rejected by keytool" >&2
        exit 1
    fi
fi

UNSIGNED="${PREBUILT_APK:-$ROOT_DIR/app/build/outputs/apk/release/app-release-unsigned.apk}"
ALIGNED="$ROOT_DIR/app/build/outputs/apk/release/app-release-aligned.apk"
SIGNED="$ROOT_DIR/app/build/outputs/apk/release/app-release.apk"

if [ "$DO_BUILD" -eq 1 ]; then
    echo "=== Building release APK (graphics=$GRAPHICS) ==="
    ( cd "$ROOT_DIR" && ./gradlew "-PtawcGraphics=$GRAPHICS" assembleRelease --quiet )
fi

[ -f "$UNSIGNED" ] || { echo "ERROR: $UNSIGNED not found (drop --no-build to build it)" >&2; exit 1; }

echo "=== Checking for dev-only code ==="
"$SCRIPT_DIR/check-no-dev-code.sh" "$UNSIGNED"

if [ -n "$PREBUILT_APK" ]; then
    # Re-aligning would be a rewrite, and F-Droid's signature transplant
    # needs the signed APK to differ from their rebuild only by the
    # signing block. AGP's output is already aligned, so check and pass
    # it through untouched.
    echo "=== Checking alignment ==="
    "$ZIPALIGN" -c -p 4 "$UNSIGNED" || {
        echo "ERROR: $UNSIGNED is not zipaligned; signing it would not reproduce" >&2
        exit 1
    }
    ALIGNED="$UNSIGNED"
else
    echo "=== Zipaligning ==="
    "$ZIPALIGN" -p -f 4 "$UNSIGNED" "$ALIGNED"
fi

# Two non-default apksigner options, both required for apksigcopier (and
# so for F-Droid) to be able to reconstruct this APK from an unsigned
# rebuild plus the signing block:
#   --v1-signing-enabled false  v1 adds three META-INF entries, and
#       apksigner writes them with the UTF-8 name flag set while
#       apksigcopier writes them without — six bytes of difference, and
#       the v3 digest no longer matches. minSdk is 29, so v1 is dead
#       weight anyway.
#   --alignment-preserved true  build-tools 35 apksigner re-aligns native
#       libs to 16 KB by default, shifting every later entry.
#       apksigcopier realigns the classic way (4 bytes, 4 KB pages), i.e.
#       what zipalign and AGP already produced. Our .so files are
#       compressed (legacy packaging), so page alignment buys nothing
#       here; if that ever changes, this needs revisiting.
echo "=== Signing ==="
"$APKSIGNER" sign \
    --ks "$KEYSTORE_PATH" \
    --ks-key-alias "$KEY_ALIAS" \
    --ks-pass env:KEYSTORE_PASS \
    --key-pass env:KEY_PASS \
    --v1-signing-enabled false \
    --alignment-preserved true \
    --out "$SIGNED" \
    "$ALIGNED"
[ "$ALIGNED" = "$UNSIGNED" ] || rm -f "$ALIGNED"

echo "=== Verifying ==="
"$APKSIGNER" verify --verbose "$SIGNED"

if [ -n "$PREBUILT_APK" ]; then
    # The whole point of the prebuilt path: everything F-Droid rebuilds
    # must still be there, bit for bit.
    echo "=== Verifying the signature is the only change ==="
    # apksigcopier shells out to apksigner, which is not normally on PATH.
    PATH="$ANDROID_HOME/build-tools/$BUILD_TOOLS_VER:$PATH" \
        apksigcopier compare "$SIGNED" --unsigned "$UNSIGNED"
    echo "(signed APK differs from $UNSIGNED only by the signing block)"
fi

VERSION="$("$AAPT2" dump badging "$SIGNED" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1)"
[ -n "$VERSION" ] || { echo "ERROR: could not read versionName from $SIGNED" >&2; exit 1; }
FINAL="$(dirname "$SIGNED")/tawc-v$VERSION.apk"
mv "$SIGNED" "$FINAL"

echo
echo "Signed APK: $FINAL"
