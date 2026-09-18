#!/bin/bash
# Remove the whole rig state, including files the container chowned to a
# subuid. The next prepare.sh re-clones and re-downloads everything (GBs).
set -euo pipefail
# shellcheck source=lib.sh
source "$(cd "$(dirname "$0")" && pwd)/lib.sh"

fdroid_podman system reset -f || true
podman unshare rm -rf "$FDROID_STATE_DIR"
