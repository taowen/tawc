# shellcheck shell=bash
# Sourced. Sets and exports TAWC_PACKAGE, the app ID host tooling builds
# and targets. Debug builds default to me.phie.tawc.dev so they install
# beside a release-signed me.phie.tawc; TAWC_PACKAGE=me.phie.tawc opts a
# debug build back into the plain ID. See notes/building.md.
TAWC_PACKAGE="${TAWC_PACKAGE:-me.phie.tawc.dev}"
case "$TAWC_PACKAGE" in
    me.phie.tawc|me.phie.tawc.*) ;;
    *) echo "ERROR: TAWC_PACKAGE must be me.phie.tawc[.<suffix>]" >&2; exit 2 ;;
esac
export TAWC_PACKAGE
