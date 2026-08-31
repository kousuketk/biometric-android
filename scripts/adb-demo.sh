#!/bin/bash
# End-to-end check of the demo app without Appium.
#
# Enrolls a biometric on the emulator through the AOSP virtual *face* HAL -- which needs no UI at
# all, unlike the fingerprint path -- then authenticates and prints where it lands.
#
#   ./scripts/adb-demo.sh [emulator-serial]

set -euo pipefail

SERIAL="${1:-emulator-5560}"
PKG="com.magicpod.biometricdemo"
ACTIVITY="$PKG/.MainActivity"
PIN=1234
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

a() { adb -s "$SERIAL" "$@"; }
step() { printf '\n=== %s\n' "$1"; }

step "Checking which biometric HALs the emulator runs"
a shell ps -A | grep -iE 'face|finger' || true
a shell dumpsys biometric | grep 'ID(' || true

step "Enabling root and the virtual HAL"
a root >/dev/null 2>&1 || true
sleep 2
a shell settings put global device_provisioned 1
a shell settings put secure user_setup_complete 1
a shell settings put secure biometric_virtual_enabled 1
# The lock screen would otherwise appear part way through a run and swallow taps.
a shell settings put system screen_off_timeout 1800000

step "Setting a device credential (a prerequisite for any enrollment)"
a shell locksettings set-pin "$PIN"
# set-pin prints success even when it failed, so verify separately.
a shell locksettings verify --old "$PIN"

step "Enrolling a face with no UI"
a shell setprop persist.vendor.face.virtual.enrollments 1
a shell cmd face sync
a shell dumpsys face | grep -o '"count":[0-9]*'

step "Authenticating"
a shell am force-stop "$PKG"
# The HAL reads enrollment_hit when the operation starts, and re-reads it while running.
a shell setprop vendor.face.virtual.enrollment_hit 1
# confirmation=false drops the extra "Confirm" tap that a passive modality otherwise requires.
a shell am start -n "$ACTIVITY" --es auto_auth STRONG --ez confirmation false >/dev/null
sleep 7
a exec-out screencap -p > "$PROJECT_DIR/build/result-success.png"

step "Done"
echo "Screenshot: $PROJECT_DIR/build/result-success.png"
echo "Expected: result_status = SUCCESS, result_detail = mode=STRONG via=BIOMETRIC"
echo
echo "Other things to try:"
echo "  Non-matching scan  : setprop vendor.face.virtual.operation_authenticate_fails true"
echo "  Lockout            : setprop vendor.face.virtual.lockout true"
echo "  Un-enroll          : setprop persist.vendor.face.virtual.enrollments '' && cmd face sync"
echo "  CryptoObject case  : am start -n $ACTIVITY --es auto_auth STRONG_CRYPTO --ez create_key true"
echo "                       (expected to report cryptoFailed -- the virtual HAL issues no HAT)"
