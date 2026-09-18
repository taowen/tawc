#!/bin/bash
# Manage the TAWC AVDs: start one (windowed, with post-boot setup) or stop it.
# See notes/emulator.md for one-time setup (SDK, AVD, Magisk).
#
# Variants are passed positionally so new ones can be added (and the
# default rotated) without breaking callers that name what they want:
#   rooted    -> AVD 'tawc-rooted'    (Magisk-rooted; needed for chroot
#                                      install method, default for tests)
#   rootless  -> AVD 'tawc-rootless'  (stock; for testing tawcroot/proot
#                                      install methods on a non-rooted image)
#
# `start` with no variant refreshes the currently running TAWC AVD when
# exactly one is running. If none is running, it starts rootless.
# Override the AVD name with TAWC_AVD=<name>.
#
# Usage:
#   scripts/emulator.sh start [rooted|rootless] [--cold]
#   scripts/emulator.sh stop  [rooted|rootless]
#
# `start` is idempotent: if the AVD is already running it just (re-)applies
# the post-boot setup. Post-boot setup:
#   - rooted only: drops SELinux to permissive (rootAVD's Magisk has no
#     magiskpolicy, so the SHM type_transition can't be installed)
#   - rooted only, if the TAWC APK is installed: grants Magisk `su` to the
#     app's uid (so InstallationService can call `su` without a prompt)
#   - either: suppresses the immersive-mode education popup
#   - either: enables Gboard as the active IME and suppresses its stylus UI
#   - either, if the TAWC APK is installed: grants POST_NOTIFICATIONS
#     (so the install foreground-service notification displays).
# Per-app grants are no-ops when the APK isn't there yet — first install
# the APK with `scripts/app-build-install.sh`, then re-run `start` (or
# grant by hand; see notes/installation.md).
#
# `stop` with no variant stops every TAWC AVD that is running. `rooted` /
# `rootless` filter to only that one. (TAWC_AVD overrides to a single
# explicit name.)

set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
EMU="$ANDROID_HOME/emulator/emulator"
LOG="${TAWC_EMULATOR_LOG:-/tmp/emulator.log}"
ADB="$ANDROID_HOME/platform-tools/adb"
[ -x "$ADB" ] || ADB=adb

usage() {
    sed -n '2,36p' "$0" | sed 's/^# \{0,1\}//'
    exit "${1:-2}"
}

# Map a variant name to AVD_NAME + ROOTED. Recognised names: rooted, rootless.
# Add new variants here as they appear.
resolve_variant() {
    case "$1" in
        rooted)   AVD_NAME="${TAWC_AVD:-tawc-rooted}";   ROOTED=1 ;;
        rootless) AVD_NAME="${TAWC_AVD:-tawc-rootless}"; ROOTED=0 ;;
        *) echo "ERROR: unknown variant: $1 (expected: rooted, rootless)" >&2; usage 1 ;;
    esac
}

# Match the binary path rather than the args so unrelated shells that
# grep for "tawc" don't match. Accept x86_64 and aarch64 hosts.
running_pid() {
    local avd=$1
    pgrep -af '/qemu-system-(x86_64|aarch64) ' | awk -v avd="$avd" '
        index($0, "-avd " avd) {print $1; exit}
    '
}

# Force `hw.keyboard = yes` in the AVD's config.ini. avdmanager-created
# AVDs default to no, which silently swallows host keystrokes in the
# emulator window (the soft keyboard works, but typing on a physical
# keyboard does not). Preserves the surrounding whitespace style; runs
# every `start` so a wiped/recreated AVD picks the change back up.
# A change applies on the next emulator launch — already-running AVDs
# need a restart.
ensure_keyboard_enabled() {
    local config="$HOME/.android/avd/$1.avd/config.ini"
    [ -f "$config" ] || return 0
    if grep -qE '^hw\.keyboard[[:space:]]*=[[:space:]]*no[[:space:]]*$' "$config"; then
        sed -i -E 's/^(hw\.keyboard[[:space:]]*=[[:space:]]*)no[[:space:]]*$/\1yes/' "$config"
        echo "==> enabled hw.keyboard in $1 config (takes effect on next launch)"
    fi
}

configure_gboard_for_emulator() {
    local serial=$1

    "$ADB" -s "$serial" shell "su 0 sh -s" >/dev/null 2>&1 <<'EOF'
set -eu

pkg=com.google.android.inputmethod.latin
pref_dir=/data/user_de/0/$pkg/shared_prefs
pref=$pref_dir/${pkg}_preferences.xml
mkdir -p "$pref_dir"

if [ ! -f "$pref" ]; then
    printf "%s\n" \
        "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>" \
        "<map>" \
        "    <boolean name=\"enable_scribe\" value=\"true\" />" \
        "    <set name=\"show_vk_devices_names\">" \
        "        <string></string>" \
        "    </set>" \
        "    <boolean name=\"disable_stylus_toolbar\" value=\"true\" />" \
        "</map>" >"$pref"
fi

put_boolean_pref() {
    key=$1
    value=$2
    tmp=$pref.tmp.$$
    if grep -q "name=\"$key\"" "$pref"; then
        sed -E "s#<boolean name=\"$key\" value=\"[^\"]*\" />#<boolean name=\"$key\" value=\"$value\" />#" "$pref" >"$tmp"
    else
        awk -v line="    <boolean name=\"$key\" value=\"$value\" />" '
            /<\/map>/ { print line }
            { print }
        ' "$pref" >"$tmp"
    fi
    mv "$tmp" "$pref"
}

ensure_show_vk_for_current_device() {
    tmp=$pref.tmp.$$
    if grep -q 'name="show_vk_devices_names"' "$pref"; then
        awk '
            /<set name="show_vk_devices_names">/ {
                print "    <set name=\"show_vk_devices_names\">"
                print "        <string></string>"
                print "    </set>"
                in_set=1
                next
            }
            in_set && /<\/set>/ { in_set=0; next }
            in_set { next }
            { print }
        ' "$pref" >"$tmp"
    else
        awk '
            /<\/map>/ {
                print "    <set name=\"show_vk_devices_names\">"
                print "        <string></string>"
                print "    </set>"
            }
            { print }
        ' "$pref" >"$tmp"
    fi
    mv "$tmp" "$pref"
}

put_boolean_pref enable_scribe true
ensure_show_vk_for_current_device
put_boolean_pref disable_stylus_toolbar true

uid=$(stat -c %u /data/user_de/0/$pkg 2>/dev/null || stat -c %u /data/user/0/$pkg 2>/dev/null || true)
gid=$(stat -c %g /data/user_de/0/$pkg 2>/dev/null || stat -c %g /data/user/0/$pkg 2>/dev/null || true)
[ -n "$uid" ] && [ -n "$gid" ] && chown "$uid:$gid" "$pref" 2>/dev/null || true
chmod 660 "$pref" 2>/dev/null || true
EOF
}

# Walk `adb devices` and ask each emulator its AVD name; print the first
# emulator-NNNN serial whose AVD matches $1, or nothing if none does.
serial_for_avd() {
    local want=$1 serial got
    while read -r serial; do
        got=$("$ADB" -s "$serial" emu avd name 2>/dev/null | head -1 | tr -d '\r')
        [ "$got" = "$want" ] && { echo "$serial"; return; }
    done < <("$ADB" devices 2>/dev/null | awk 'NR>1 && $2=="device" && $1 ~ /^emulator-/ {print $1}')
}

cmd_start() {
    # First positional is an optional variant name. With no variant, use
    # the running TAWC AVD if there is exactly one; otherwise start the
    # rootless AVD, which matches tawcroot's normal no-root path.
    local variant=""
    if [ "$#" -ge 1 ]; then
        case "$1" in
            -*) ;;  # leave for option parsing below
            *)  variant=$1; shift ;;
        esac
    fi
    if [ -z "$variant" ]; then
        if [ -n "${TAWC_AVD:-}" ]; then
            AVD_NAME="$TAWC_AVD"
            ROOTED=0
        else
            local running=()
            local avd
            for avd in tawc-rooted tawc-rootless; do
                [ -n "$(running_pid "$avd")" ] && running+=("$avd")
            done
            case "${#running[@]}" in
                0) variant=rootless ;;
                1)
                    case "${running[0]}" in
                        tawc-rooted) variant=rooted ;;
                        tawc-rootless) variant=rootless ;;
                        *) AVD_NAME="${running[0]}"; ROOTED=0 ;;
                    esac
                    ;;
                *)
                    echo "ERROR: multiple TAWC AVDs are running; pass rooted or rootless explicitly." >&2
                    exit 1
                    ;;
            esac
        fi
    fi
    if [ -n "$variant" ]; then
        resolve_variant "$variant"
    fi

    [ -x "$EMU" ] || { echo "ERROR: emulator not found at $EMU (set ANDROID_HOME?)" >&2; exit 1; }
    [ -d "$HOME/.android/avd/$AVD_NAME.avd" ] || {
        echo "ERROR: AVD '$AVD_NAME' does not exist (~/.android/avd/$AVD_NAME.avd missing)" >&2
        echo "       See notes/emulator.md for one-time AVD creation." >&2
        exit 1
    }

    ensure_keyboard_enabled "$AVD_NAME"

    # -feature -QuickbootFileBacked: stops the emulator from mmap'ing guest
    # RAM onto snapshots/default_boot/ram.img. With it on (the default), every
    # guest memory write dirties a page of that 2 GB file, which the host
    # kernel then flushes to disk in 50–150 MB/s bursts — especially under
    # guest memory pressure where kswapd churns pages through zRAM. Trade-off:
    # snapshot save on exit must now copy 2 GB instead of relying on the file
    # mapping. Worth it.
    local args=(-avd "$AVD_NAME" -no-audio -no-boot-anim -feature -QuickbootFileBacked)
    for arg in "$@"; do
        case "$arg" in
            --cold) args+=(-no-snapshot-load) ;;
            *) echo "ERROR: unknown arg: $arg" >&2; usage 1 ;;
        esac
    done

    local already=0
    if [ -n "$(running_pid "$AVD_NAME")" ]; then
        echo "==> AVD '$AVD_NAME' already running; skipping launch, just refreshing setup"
        already=1
    fi

    # The emulator's bundled Qt only ships the xcb (X11) plugin — no wayland
    # plugin — so a real X (or Xwayland) socket is required. If DISPLAY is
    # unset or points at an unreachable :0, scan /tmp/.X11-unix/ for a socket
    # owned by the current uid (typically :1 under an Xwayland-on-Wayland
    # session) and export DISPLAY to that.
    if [ -z "${DISPLAY:-}" ] || ! command -v xset >/dev/null 2>&1 || ! xset q >/dev/null 2>&1; then
        for sock in /tmp/.X11-unix/X*; do
            [ -e "$sock" ] || continue
            [ "$(stat -c %u "$sock")" = "$(id -u)" ] || continue
            local n=${sock##*/X}
            if DISPLAY=":$n" xset q >/dev/null 2>&1; then
                export DISPLAY=":$n"
                echo "==> using DISPLAY=$DISPLAY"
                break
            fi
        done
    fi
    if ! xset q >/dev/null 2>&1; then
        echo "ERROR: no reachable X display; emulator needs one." >&2
        echo "       On Wayland, start an Xwayland-capable session as your user." >&2
        exit 1
    fi

    if [ "$already" = "0" ]; then
        echo "==> launching: $EMU ${args[*]}"
        echo "    log: $LOG"
        nohup "$EMU" "${args[@]}" >"$LOG" 2>&1 &
        local pid=$!
        echo "    pid: $pid"
        echo
        echo "==> waiting for boot (Ctrl+C to background; AVD keeps running)"
    fi

    # Wait for an adb serial to appear that maps to our AVD, then poll boot.
    local serial=""
    local emu_pid
    emu_pid=$(running_pid "$AVD_NAME")
    while [ -z "$serial" ]; do
        sleep 2
        if [ -n "$emu_pid" ] && ! kill -0 "$emu_pid" 2>/dev/null; then
            echo "ERROR: emulator died — see $LOG" >&2
            tail -20 "$LOG" >&2
            exit 1
        fi
        serial=$(serial_for_avd "$AVD_NAME")
    done
    until "$ADB" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | grep -q 1; do
        sleep 3
        if [ -n "$emu_pid" ] && ! kill -0 "$emu_pid" 2>/dev/null; then
            echo "ERROR: emulator died — see $LOG" >&2
            tail -20 "$LOG" >&2
            exit 1
        fi
    done
    [ "$already" = "0" ] && echo "==> booted: $serial"

    if [ "$ROOTED" = "1" ]; then
        # rootAVD's Magisk doesn't ship magiskpolicy (see notes/emulator.md),
        # so arch-chroot-run can't install the SELinux type_transition that
        # lets the compositor (untrusted_app) mmap memfds from chroot
        # clients. Drop to permissive instead — emulator-only, resets on
        # reboot. We already gave up isolation by Magisk-rooting the AVD.
        "$ADB" -s "$serial" shell 'su -c "setenforce 0"' >/dev/null 2>&1 || \
            echo "WARNING: failed to set SELinux permissive; SHM client surfaces will not render" >&2
    else
        echo "==> rootless AVD: skipping setenforce / Magisk-su grants"
    fi

    # Suppress the one-shot "swipe down to exit fullscreen" education popup
    # on fresh AVDs. It otherwise eats the first taps tests send.
    "$ADB" -s "$serial" shell 'settings put secure immersive_mode_confirmations confirmed' >/dev/null 2>&1 || \
        echo "WARNING: failed to suppress immersive-mode confirmation popup" >&2

    # Gboard used to be disabled here because its stylus education dialog
    # ate stylus-tool-type taps. Keep the IME enabled for text-input work,
    # but force the stylus path to show the normal keyboard and disable the
    # stylus toolbar. Re-enable every start so older AVDs recover from the
    # historical disable-user state.
    local gboard_pkg=com.google.android.inputmethod.latin
    local gboard_ime="$gboard_pkg/com.android.inputmethod.latin.LatinIME"
    "$ADB" -s "$serial" shell "pm enable --user 0 $gboard_pkg" >/dev/null 2>&1 || \
        echo "WARNING: failed to enable Gboard package" >&2
    "$ADB" -s "$serial" shell "am force-stop $gboard_pkg" >/dev/null 2>&1 || true
    for _ in $(seq 1 10); do
        "$ADB" -s "$serial" shell ime list -s 2>/dev/null | tr -d '\r' | grep -qx "$gboard_ime" && break
        sleep 0.5
    done
    configure_gboard_for_emulator "$serial" || \
        echo "WARNING: failed to configure Gboard stylus preferences; stylus UI may interfere with tests" >&2
    "$ADB" -s "$serial" shell 'settings put secure stylus_handwriting_enabled 0' >/dev/null 2>&1 || true
    "$ADB" -s "$serial" shell "ime enable $gboard_ime" >/dev/null 2>&1 || \
        echo "WARNING: failed to enable Gboard IME" >&2
    "$ADB" -s "$serial" shell "ime set $gboard_ime" >/dev/null 2>&1 || \
        echo "WARNING: failed to select Gboard IME" >&2
    "$ADB" -s "$serial" shell 'settings put secure show_ime_with_hard_keyboard 1' >/dev/null 2>&1 || \
        echo "WARNING: failed to show IME with hardware keyboard" >&2

    # TAWC-app-specific runtime setup (no-op if APK isn't installed yet).
    # Grants reset on emulator wipe; setenforce 0 (rooted) resets every boot
    # — re-run `start` after `adb install` to refresh them all.
    local pkg=me.phie.tawc
    local uid
    uid=$("$ADB" -s "$serial" shell "pm list packages -U $pkg" 2>/dev/null | awk -F: '/uid:/ {print $3}' | tr -d '\r')
    if [ -n "$uid" ]; then
        if [ "$ROOTED" = "1" ]; then
            echo "==> granting Magisk su + POST_NOTIFICATIONS to $pkg (uid=$uid)"
            "$ADB" -s "$serial" shell "su -c 'magisk --sqlite \"INSERT OR REPLACE INTO policies (uid,policy,until,logging,notification) VALUES($uid,2,0,1,0);\"'" >/dev/null 2>&1 || \
                echo "WARNING: failed to grant Magisk su to $pkg (uid=$uid)" >&2
        else
            echo "==> granting POST_NOTIFICATIONS to $pkg (uid=$uid)"
        fi
        "$ADB" -s "$serial" shell "pm grant $pkg android.permission.POST_NOTIFICATIONS" >/dev/null 2>&1 || \
            echo "WARNING: failed to grant POST_NOTIFICATIONS to $pkg" >&2
    else
        echo "==> $pkg not installed yet; skipping app-specific grants"
        echo "    (install the APK then re-run \`start\` to set them up)"
    fi
}

stop_one() {
    local avd=$1 pid serial
    pid=$(running_pid "$avd")
    [ -n "$pid" ] || return 1
    echo "==> shutting down AVD '$avd' (pid $pid)"
    # Prefer `adb emu kill` against the AVD's actual serial — that lets the
    # emulator save its quickboot snapshot on the way out. Fall back to
    # SIGTERM if the serial can't be found or adb refuses.
    serial=$(serial_for_avd "$avd")
    if [ -n "$serial" ] && "$ADB" -s "$serial" emu kill >/dev/null 2>&1; then
        :
    else
        echo "    adb emu kill unavailable; sending SIGTERM"
        kill "$pid" 2>/dev/null || true
    fi
    for _ in $(seq 1 30); do
        kill -0 "$pid" 2>/dev/null || { echo "==> stopped '$avd'"; return 0; }
        sleep 1
    done
    echo "    still alive after 30s; sending SIGKILL"
    kill -9 "$pid" 2>/dev/null || true
    sleep 1
    kill -0 "$pid" 2>/dev/null && { echo "ERROR: pid $pid did not die" >&2; return 1; }
    echo "==> stopped '$avd'"
}

cmd_stop() {
    # stop takes an optional variant: with one, stop only that AVD; with
    # none, stop every tawc-* AVD that is running. TAWC_AVD overrides the
    # candidate list to that single name.
    local candidates=()
    if [ "$#" -ge 1 ]; then
        resolve_variant "$1"
        candidates=("$AVD_NAME")
        shift
        [ "$#" -eq 0 ] || { echo "ERROR: stop takes at most one variant arg" >&2; usage 1; }
    elif [ -n "${TAWC_AVD:-}" ]; then
        candidates=("$TAWC_AVD")
    else
        candidates=(tawc-rooted tawc-rootless)
    fi

    local stopped=0
    for avd in "${candidates[@]}"; do
        if stop_one "$avd"; then
            stopped=1
        fi
    done
    if [ "$stopped" = "0" ]; then
        if [ "${#candidates[@]}" -eq 1 ]; then
            echo "==> no AVD '${candidates[0]}' running"
        else
            echo "==> no TAWC AVDs running"
        fi
    fi
}

[ "$#" -ge 1 ] || usage
sub=$1; shift
case "$sub" in
    start) cmd_start "$@" ;;
    stop)  cmd_stop "$@" ;;
    -h|--help|help) usage 0 ;;
    *) echo "ERROR: unknown subcommand: $sub" >&2; usage 1 ;;
esac
