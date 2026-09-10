#!/bin/bash
# Enroll a fingerprint on an emulator with a *known* finger id.
#
# The id that `mobile: fingerprint` has to send is the one the HAL saw during enrollment. Enrolling
# through the emulator UI's "Touch Sensor" button produces an arbitrary integer (45146572 in one
# observed case), and recovering it afterwards needs root -- unavailable on google_apis_playstore
# images. Driving the same wizard with `adb emu finger touch <id>` instead pins the id, so nothing
# has to be looked up later.
#
# Needs no root, so it also works as a prototype for automated emulator provisioning.
#
#   ./scripts/enroll-fingerprint.sh [emulator-serial] [finger-id] [pin]

set -uo pipefail

SERIAL="${1:-emulator-5554}"
FINGER_ID="${2:-1}"
PIN="${3:-1234}"

a() { adb -s "$SERIAL" "$@"; }
step() { printf '\n=== %s\n' "$1"; }

screen_texts() {
  a shell uiautomator dump /sdcard/mp-ui.xml >/dev/null 2>&1
  a shell cat /sdcard/mp-ui.xml 2>/dev/null
}

# Taps the first of the given labels that is on screen. Case-insensitive, exact match.
tap_any() {
  local xml; xml="$(screen_texts)"
  local label
  for label in "$@"; do
    local xy
    xy="$(printf '%s' "$xml" | python3 -c '
import sys, re
xml = sys.stdin.read(); want = sys.argv[1].upper()
for m in re.finditer(r"text=\"([^\"]*)\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", xml):
    if m.group(1).strip().upper() == want:
        print((int(m.group(2)) + int(m.group(4))) // 2, (int(m.group(3)) + int(m.group(5))) // 2)
        break
' "$label")"
    if [ -n "$xy" ]; then
      a shell input tap $xy
      echo "  tapped '$label'"
      return 0
    fi
  done
  return 1
}

has_text() {
  printf '%s' "$(screen_texts)" | grep -qiF "$1"
}

step "Clearing any existing credential (this also drops existing biometric enrollments)"
a shell locksettings clear --old "$PIN" >/dev/null 2>&1 || true
sleep 2
a shell dumpsys fingerprint | grep -o '"count":[0-9]*'

step "Setting the device credential"
a shell settings put global device_provisioned 1
a shell settings put secure user_setup_complete 1
# Keep the lock screen from appearing mid-run and swallowing taps.
a shell settings put system screen_off_timeout 1800000
a shell locksettings set-pin "$PIN"
# set-pin prints success even when it failed, so verify separately.
a shell locksettings verify --old "$PIN" || { echo "PIN was not actually set" >&2; exit 1; }

step "Dismissing the lock screen that setting a credential just introduced"
a shell input keyevent KEYCODE_WAKEUP
sleep 2
# A swipe just pulls the notification shade here; dismiss-keyguard is what brings up the PIN pad.
a shell wm dismiss-keyguard >/dev/null 2>&1
sleep 3
if has_text "Enter your PIN"; then
  a shell input text "$PIN"
  a shell input keyevent KEYCODE_ENTER
  sleep 3
fi

step "Opening the enrollment wizard"
sleep 1
a shell am start -a android.settings.FINGERPRINT_ENROLL >/dev/null 2>&1
sleep 4

step "Walking the wizard to the sensor screen"
for _ in $(seq 1 8); do
  if has_text "Touch the sensor"; then break; fi
  if has_text "PIN" && ! has_text "Set up"; then
    a shell input text "$PIN"
    a shell input keyevent KEYCODE_ENTER
    echo "  entered the PIN"
    sleep 3
    continue
  fi
  tap_any "MORE" "I AGREE" "AGREE" "NEXT" "START" "GET STARTED" || true
  sleep 2
done

if ! has_text "Touch the sensor"; then
  echo "Never reached the sensor screen. Current texts:" >&2
  printf '%s' "$(screen_texts)" | grep -oE 'text="[^"]+"' | head -10 >&2
  exit 1
fi

step "Sending touches as finger id $FINGER_ID"
for _ in $(seq 1 15); do
  a emu finger touch "$FINGER_ID" >/dev/null 2>&1
  sleep 1.2
  if has_text "Fingerprint added"; then break; fi
done
tap_any "DONE" "NEXT" || true

step "Result"
a shell dumpsys fingerprint | grep -o '"count":[0-9]*'
echo "If this device is rootable you can confirm the id:"
echo "  adb -s $SERIAL root && adb -s $SERIAL shell abx2xml /data/system/users/0/settings_fingerprint_0.xml -"
