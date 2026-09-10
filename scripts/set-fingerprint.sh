#!/bin/bash
# Register or delete a fingerprint enrollment on an Android emulator without touching any UI.
#
# The emulator's fingerprint HAL (android.hardware.biometrics.fingerprint-service.ranchu) keeps its
# enrollments in a single file, /data/vendor_de/<user>/fpdata/sensor<sensorId>.bin. Writing that
# file and then running `cmd fingerprint sync` -- which makes the framework re-enumerate the HAL and
# reconcile its own records -- is the whole register/delete operation. No enrollment wizard, no taps.
#
# Needs `adb root`, so:
#   - `google_apis` images work, `google_apis_playstore` ones do not (they are user builds)
#   - real devices are out of scope entirely
# Use ./enroll-fingerprint-via-ui.sh instead when root is unavailable.
#
# AOSP's generic Virtual Fingerprint HAL properties (persist.vendor.fingerprint.virtual.*) do NOT
# work here: the ranchu HAL ignores them and `sync` reports no enrollments.
#
#   ./scripts/set-fingerprint.sh register [serial] [finger-ids] [pin]
#   ./scripts/set-fingerprint.sh delete   [serial]
#   ./scripts/set-fingerprint.sh status    [serial]

set -uo pipefail

COMMAND="${1:-status}"
SERIAL="${2:-emulator-5554}"
FINGER_IDS="${3:-1}"   # comma separated, e.g. "1,2"
PIN="${4:-1234}"

ANDROID_USER=0
SENSOR_ID=0
FPDATA="/data/vendor_de/${ANDROID_USER}/fpdata/sensor${SENSOR_ID}.bin"
HAL_SERVICE="vendor.biometrics.fingerprint-service.ranchu"
# Arbitrary: the HAL replays it, and nothing compares it as long as the Keystore key is generated
# after the file is installed.
AUTHENTICATOR_ID="0xDEADBEEFCAFEBABE"

a() { adb -s "$SERIAL" "$@"; }
step() { printf '\n=== %s\n' "$1"; }
die() { echo "ERROR: $*" >&2; exit 1; }

enrollment_count() {
  a shell dumpsys fingerprint 2>/dev/null | grep -o '"count":[0-9]*' | head -1 | cut -d: -f2
}

require_root() {
  a root >/dev/null 2>&1
  sleep 3
  local whoami; whoami="$(a shell whoami 2>/dev/null | tr -d '\r')"
  if [ "$whoami" != "root" ]; then
    die "adb root failed (build type: $(a shell getprop ro.build.type | tr -d '\r')).
     This needs a google_apis image; google_apis_playstore images are user builds and cannot be rooted.
     Use ./enroll-fingerprint-via-ui.sh instead."
  fi
}

# `cmd fingerprint sync` is a silent no-op without this, and the setting does not survive a reboot.
enable_sync() {
  a shell settings put secure biometric_virtual_enabled 1
  # Keep the lock screen from appearing mid-run and swallowing input.
  a shell settings put system screen_off_timeout 1800000
}

reload_hal() {
  a shell stop "$HAL_SERVICE"
  sleep 1
  "$@"
  a shell start "$HAL_SERVICE"
  sleep 3
  a shell cmd fingerprint sync
  sleep 2
}

ensure_credential() {
  # The framework refuses to hold enrollments without a lock-screen credential, and its gatekeeper
  # SID is what Keystore checks during crypto-bound authentication.
  if a shell locksettings verify --old "$PIN" 2>/dev/null | grep -q "verified successfully"; then
    echo "  credential already set"
  else
    # Changing the credential drops the enrollment count to 0, so this has to happen first.
    a shell locksettings set-pin "$PIN" >/dev/null 2>&1
    # set-pin prints success even when it failed, so verify separately.
    a shell locksettings verify --old "$PIN" 2>/dev/null | grep -q "verified successfully" \
      || die "could not set the device credential"
    echo "  credential set to $PIN"
  fi
}

unlock_screen() {
  a shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
  sleep 1
  # A swipe only pulls the notification shade down; dismiss-keyguard is what shows the PIN pad.
  a shell wm dismiss-keyguard >/dev/null 2>&1
  sleep 2
  a shell input text "$PIN" >/dev/null 2>&1
  a shell input keyevent KEYCODE_ENTER >/dev/null 2>&1
  sleep 2
}

write_enrollment_file() {
  local sid; sid="$(a shell dumpsys lock_settings 2>/dev/null \
    | grep -oE 'SID: [0-9a-f]+' | head -1 | awk '{print $2}')"
  [ -n "$sid" ] || die "could not read the gatekeeper SID from dumpsys lock_settings"
  echo "  gatekeeper SID: $sid"

  # 25 bytes for one enrollment: magic | authenticatorId u64 | gatekeeper SID u64 | count u8 | ids u32*
  local tmp; tmp="$(mktemp -t sensor0)"
  python3 - "$tmp" "$AUTHENTICATOR_ID" "$sid" "$FINGER_IDS" <<'PY'
import struct, sys
path, auth_id, sid, ids = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
ids = [int(x) for x in ids.split(",") if x.strip()]
blob = b"arPF" + struct.pack("<QQ", int(auth_id, 16), int(sid, 16)) + bytes([len(ids)])
blob += b"".join(struct.pack("<I", i) for i in ids)
open(path, "wb").write(blob)
print(f"  {len(blob)} bytes, ids={ids}")
PY

  a push "$tmp" /data/local/tmp/sensor0.bin >/dev/null
  # restorecon matters: a wrong SELinux label leaves the HAL unable to read the file, which looks
  # exactly like "no enrollments".
  a shell "cp /data/local/tmp/sensor0.bin $FPDATA \
    && chown system:system $FPDATA \
    && chmod 600 $FPDATA \
    && restorecon $FPDATA" >/dev/null 2>&1 || die "could not install $FPDATA"
  rm -f "$tmp"
}

case "$COMMAND" in
  register)
    step "Preparing $SERIAL"
    require_root
    enable_sync
    ensure_credential
    unlock_screen

    step "Writing the enrollment file"
    reload_hal write_enrollment_file

    step "Result"
    count="$(enrollment_count)"
    echo "  enrolled: ${count:-?}"
    [ "${count:-0}" -ge 1 ] || die "the enrollment did not take"
    echo "  drive a scan with: adb -s $SERIAL emu finger touch ${FINGER_IDS%%,*}"
    ;;

  delete)
    step "Preparing $SERIAL"
    require_root
    enable_sync

    step "Removing the enrollment file"
    reload_hal a shell rm -f "$FPDATA"

    step "Result"
    count="$(enrollment_count)"
    echo "  enrolled: ${count:-?}"
    [ "${count:-1}" -eq 0 ] || die "the enrollment is still there"
    ;;

  status)
    a shell dumpsys fingerprint 2>/dev/null | sed -n '1,3p'
    a shell dumpsys biometric 2>/dev/null | grep 'ID(' || true
    ;;

  *)
    echo "usage: $0 {register|delete|status} [serial] [finger-ids] [pin]" >&2
    exit 2
    ;;
esac
