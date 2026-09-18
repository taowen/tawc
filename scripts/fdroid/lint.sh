#!/bin/bash
# Lint the recipe exactly as it sits in the repo (GitHub Repo, tag commit),
# not the rewritten local-mirror copy the build modes use.
set -euo pipefail
# shellcheck source=lib.sh
source "$(cd "$(dirname "$0")" && pwd)/lib.sh"

RECIPE="$FDROID_REPO_DIR/fdroid/me.phie.tawc.yml"
FDROIDDATA="$FDROID_STATE_DIR/fdroiddata"
[ -d "$FDROIDDATA" ] || { echo "run prepare.sh first" >&2; exit 1; }

fdroid_podman run --rm \
    -v "$FDROIDDATA:/builds/fdroiddata" \
    -v "$RECIPE:/tmp/recipe.yml:ro" \
    -w /builds/fdroiddata \
    "$FDROID_IMAGE" bash -c '
set -e
source /etc/profile.d/bsenv.sh
rm -rf "$fdroidserver"; mkdir -p "$fdroidserver"
curl --silent https://gitlab.com/fdroid/fdroidserver/-/archive/master/fdroidserver-master.tar.gz \
    | tar -xz --directory="$fdroidserver" --strip-components=1
export PATH="$fdroidserver:$PATH"
export PYTHONPATH="$fdroidserver:$fdroidserver/examples"
export PYTHONUNBUFFERED=true serverwebroot=/tmp
cp /tmp/recipe.yml metadata/me.phie.tawc.yml
echo "=== fdroid lint ==="
fdroid lint me.phie.tawc && echo "(lint clean)"
echo "=== fdroid rewritemeta (diff means non-canonical formatting) ==="
cp metadata/me.phie.tawc.yml /tmp/before.yml
fdroid rewritemeta me.phie.tawc
diff -u /tmp/before.yml metadata/me.phie.tawc.yml && echo "(canonical)" || echo "(differs only in comments/field order — run rewritemeta before submitting)"
'

# The buildserver image has no pip, so validate against fdroiddata's JSON
# schema (a job of their CI) in a plain Debian container instead.
echo "=== metadata schema ==="
fdroid_podman run --rm \
    -v "$FDROIDDATA/schemas:/schemas:ro" \
    -v "$RECIPE:/tmp/recipe.yml:ro" \
    debian:trixie-slim bash -c '
set -e
export DEBIAN_FRONTEND=noninteractive
apt-get -qq update && apt-get -qq install -y python3-pip >/dev/null 2>&1
# check-jsonschema is the tool fdroiddata CI uses. It matters which: it
# reads YAML 1.2, where the bare `yes` in `gradle:` stays a string, while
# PyYAML (1.1) turns it into a boolean and reports a false failure.
pip install --quiet --break-system-packages check-jsonschema
check-jsonschema --schemafile /schemas/metadata.json /tmp/recipe.yml
'
