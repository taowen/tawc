#!/bin/bash
# Reproducible-build knobs, shared by the shell build scripts and by
# app/build.gradle.kts. Release APKs are built inside F-Droid's image so
# that paths and toolchain match theirs by construction; what this file
# fixes is the rest — the bits that vary even on one machine.
#
# Public API (after sourcing):
#   repro_epoch        -- echo SOURCE_DATE_EPOCH: HEAD's commit time, or
#                         0 outside a git checkout. Also exports it, so
#                         autotools/meson deps that stamp a build date
#                         pick it up.
#   repro_tar <args>   -- tar(1) with fixed entry order, owner, modes and
#                         mtime. Everything else is passed through.
#
# Or run directly as a tiny query tool:
#   scripts/lib/repro.sh --epoch       -- print the epoch
#   scripts/lib/repro.sh --tar-args    -- print the tar flags, one per line
# Gradle uses the query form so there is one source of truth for both.

repro_epoch() {
    if [ -z "${SOURCE_DATE_EPOCH:-}" ]; then
        SOURCE_DATE_EPOCH="$(git -C "$(dirname "${BASH_SOURCE[0]}")/../.." \
            log -1 --format=%ct 2>/dev/null)"
        : "${SOURCE_DATE_EPOCH:=0}"
        export SOURCE_DATE_EPOCH
    fi
    echo "$SOURCE_DATE_EPOCH"
}

# --format=ustar: no pax headers (they carry sub-second mtimes).
# --sort=name: readdir order is filesystem-dependent.
# --owner/--group/--numeric-owner: the builder's uid/name must not leak.
# --mode: drop the umask's fingerprint, keep only the exec bit, which is
#   the one permission the runtime extractors read back.
repro_tar_args() {
    printf '%s\n' \
        --format=ustar \
        --sort=name \
        --owner=0 --group=0 --numeric-owner \
        --mode=u+rwX,go+rX,go-w \
        "--mtime=@$(repro_epoch)"
}

repro_tar() {
    local args=()
    mapfile -t args < <(repro_tar_args)
    tar "${args[@]}" "$@"
}

# Direct invocation (not sourced).
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
    set -euo pipefail
    case "${1:-}" in
        --epoch) repro_epoch ;;
        --tar-args) repro_tar_args ;;
        *) echo "usage: repro.sh --epoch|--tar-args" >&2; exit 2 ;;
    esac
fi
