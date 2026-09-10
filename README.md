# BiometricDemo (Android)

A deliberately small app that exercises every Android biometric path worth automating, and
reports the outcome as a machine-readable string so a test can assert on it without parsing
localized system text. Counterpart of `../biometric-ios`, using the same outcome strings.

## Requirements

- JDK 17+ (verified on 21), Android SDK with platform 34
- Gradle 8.9 (no wrapper committed; see below)
- No third-party services

## Build & run

```bash
# Gradle is not on PATH here; the cached distribution works:
GRADLE=~/.gradle/wrapper/dists/gradle-8.9-bin/*/gradle-8.9/bin/gradle
$GRADLE assembleDebug

adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.magicpod.biometricdemo/.MainActivity
```

Application ID: `com.magicpod.biometricdemo` (same as the iOS bundle ID)
minSdk 23 / targetSdk 34

## What it covers

| Mode | Why it is in here |
|---|---|
| `STRONG` | Class 3 BiometricPrompt, no crypto. The plain gate. |
| `STRONG_CRYPTO` | Class 3 + a Keystore key with `setUserAuthenticationRequired(true)` handed over as a CryptoObject. **This is the banking-app shape and the case that separates a usable emulator from a decorative one.** |
| `WEAK` | Class 2. Cannot unlock Keystore keys by design. |
| `STRONG_OR_CREDENTIAL` | Biometrics with the device PIN as fallback. |
| Launch gate | The "mandatory security layer" case that blocks E2E automation. |

## Resource IDs

The result block is rendered first so a test can read it without scrolling. All ids are plain
`android:id` values on classic Views, so Appium sees them as `resource-id`.

| Resource id | Notes |
|---|---|
| `result_status` | **The value to assert on.** |
| `result_detail` | `mode=… via=… ERROR_…` |
| `attempt_count_value` | Number of *terminal* results so far |
| `failed_scan_count_value` | Number of non-matching scans (see finding 4) |
| `reset_button` | |
| `api_level_value` | |
| `can_authenticate_strong_value` / `can_authenticate_weak_value` / `can_authenticate_credential_value` | `OK` or `NG BIOMETRIC_ERROR_…` |
| `keystore_key_value` | `PRESENT` / `ABSENT` |
| `refresh_button` | |
| `auth_strong_button` / `auth_strong_crypto_button` / `auth_weak_button` / `auth_strong_or_credential_button` | |
| `keystore_create_button` / `keystore_delete_button` | |
| `gate_toggle` | |
| `gate_overlay` / `gate_title` / `gate_status` / `gate_detail` / `gate_unlock_button` / `gate_disable_button` | |

### `result_status` values

`IDLE` / `RUNNING` / `SUCCESS` / `CANCELED` / `NEGATIVE_BUTTON` / `NOT_ENROLLED` /
`NOT_AVAILABLE` / `LOCKED_OUT` / `LOCKED_OUT_PERMANENT` / `PASSCODE_NOT_SET` /
`KEY_INVALIDATED` / `ERROR`

There is deliberately no `FAILED`. See finding 4.

## Launch options

Intent extras, so a test can start from a known state without tapping anything first.

```bash
adb shell am start -n com.magicpod.biometricdemo/.MainActivity \
  --es auto_auth STRONG_CRYPTO --ez confirmation false --ez create_key true
```

| Extra | Effect |
|---|---|
| `--es auto_auth <MODE>` | Authenticate on launch. `STRONG`, `STRONG_CRYPTO`, `WEAK`, `STRONG_OR_CREDENTIAL` |
| `--ez gate true/false` | Force the launch gate for this run |
| `--ez create_key true` / `--ez delete_key true` | Manage the Keystore key on launch |
| `--ez confirmation false` | Drop the extra "Confirm" tap (see finding 3) |

## Verifying without Appium

```bash
./scripts/adb-demo.sh emulator-5560
```

## Findings

Measured on an `android-34;google_apis;arm64-v8a` emulator (pixel_6 profile), API 34.

### 1. The emulator ships two biometric sensors, and only one of them is virtual

```
$ adb shell ps -A | grep -iE 'face|finger'
android.hardware.biometrics.face-service.example      <- AOSP virtual HAL
android.hardware.biometrics.fingerprint-service.ranchu <- emulator-specific, driven by `emu finger touch`

$ adb shell dumpsys biometric | grep 'ID('
 ID(4), oemStrength: 15, updatedStrength: 15, modality 8   <- face,        Class 3
 ID(0), oemStrength: 15, updatedStrength: 15, modality 2   <- fingerprint, Class 3
```

Both are **Class 3 (`oemStrength: 15`)**. The fingerprint HAL is `ranchu`, so the
`persist.vendor.fingerprint.virtual.*` properties do nothing — but the **face** HAL is the AOSP
example one, and it ships pre-configured with `persist.vendor.face.virtual.strength=strong`.

**The face sensor disappeared somewhere between API 34 and API 36.** Measured with everything else
held constant (`google_apis`, arm64-v8a, freshly created AVDs):

| API | AVD device profile | face sensor |
|---|---|---|
| 34 | `pixel_6` | present |
| 34 | `pixel` | present |
| 36 | `pixel` | **absent** |

`pm list features` agrees: API 34 reports both `android.hardware.biometrics.face` and
`android.hardware.fingerprint`, API 36 only the latter. The device profile makes no difference, so
this is Google dropping the virtual face HAL from newer emulator images, not a configuration knob.
(API 35 is untested.)

Anything built on the face path therefore stops working on new platform versions, which rules it
out as the primary approach even though enrollment is far cheaper there.

### 2. Face enrollment needs no UI at all -- but only on images that still have the sensor

This is the one appealing thing about the face path, subject to finding 1. Enrollment, end to end:

```bash
adb root
adb shell settings put global device_provisioned 1
adb shell settings put secure user_setup_complete 1
adb shell settings put secure biometric_virtual_enabled 1
adb shell locksettings set-pin 1234
adb shell locksettings verify --old 1234      # set-pin reports success even when it failed
adb shell setprop persist.vendor.face.virtual.enrollments 1
adb shell cmd face sync
adb shell dumpsys face | grep -o '"count":[0-9]*'   # -> "count":1
```

After that the app reports `BIOMETRIC_STRONG: OK` — **no Settings wizard, no `emu finger touch`
loop**. The fingerprint path still requires walking the enrollment wizard.

Beware the side effect: setting a PIN makes the emulator start showing a lock screen, which will
swallow taps in unrelated tests. `settings put system screen_off_timeout 1800000` keeps it away
during a run.

### 3. Face requires an extra "Confirm" tap by default

`BiometricPrompt` defaults to `setConfirmationRequired(true)`, and passive modalities honour it:
after a successful face match the prompt stays up showing *"Tap Confirm to complete"* with
*Cancel* / *Confirm*. Fingerprint, being active, does not do this.

So a face-based test needs a tap on Confirm unless the app opts out. This app exposes
`--ez confirmation false` so both paths can be compared.

### 4. A non-matching scan is not an authentication failure

`onAuthenticationFailed()` fires but the prompt stays open and the authentication keeps running —
exactly like iOS. The app therefore counts those separately in `failed_scan_count_value` and keeps
`result_status` at `RUNNING`; only `onAuthenticationError()` produces a terminal value.

**This is why a "fail the biometric" test step should mean "send one non-matching scan", not
"make the authentication fail".** BrowserStack's `pass` / `fail` / `cancel` model does not
describe either platform accurately.

### 5. ⚠️ The virtual face HAL cannot unlock Keystore keys

The critical limitation. `STRONG_CRYPTO` reports:

```
SUCCESS
mode=STRONG_CRYPTO via=BIOMETRIC cryptoFailed=javax.crypto.IllegalBlockSizeException
```

and logcat explains it:

```
keystore2: No operation auth token received.
keystore2: Error::Km(r#KEY_USER_NOT_AUTHENTICATED)
```

The cause is in the HAL itself — AOSP's `FakeFaceEngine::authenticateImpl` ends with:

```cpp
cb->onAuthenticationSucceeded(id, {} /* hat */);
```

The Hardware Auth Token is **empty**, so Keystore refuses to release a key created with
`setUserAuthenticationRequired(true)`. BiometricPrompt reports success, but the crypto operation
that a real banking app depends on fails.

**Consequence for the POC:** the UI-less face path covers plain biometric gates, but *not* the
CryptoObject case. Covering that still needs the `ranchu` fingerprint HAL, and therefore real
fingerprint enrollment through the UI. iOS's `BiometricTestSession` carries the same caveat
("does not provide a valid HAT"), so this is a property of fake biometric HALs in general rather
than an Android quirk.

### 6. On a `google_apis_playstore` image there is no way to look up the enrolled finger id

`mobile: fingerprint` needs the id that was assigned at enrollment, and the emulator UI's
"Finger N" does not map to console id N
([appium/java-client#845](https://github.com/appium/java-client/issues/845)). Two ways to recover
that id, both dead ends on a Play Store image:

- **Read `/data/system/users/0/settings_fingerprint_0.xml`** — needs root, and these images are
  user builds (`ro.build.type=user`, `ro.debuggable=0`), so `adb root` answers
  *"adbd cannot run as root in production builds"*.
- **Probe ids by watching the accept/reject counters in `dumpsys fingerprint`** — Android locks
  biometrics out after 5 consecutive failures, and enough failures escalate to
  `ERROR_LOCKOUT_PERMANENT`, which only a device-credential authentication clears. Since the id
  can be an arbitrary integer, brute force cannot cover the space anyway. **Do not do this**; a
  probing run here bricked biometrics on the emulator until the PIN was re-entered.

So the id has to be *pinned at enrollment time* rather than discovered afterwards: drive the
enrollment wizard's touches with `adb emu finger touch 1` (not the emulator UI's Finger button)
and the enrolled id becomes 1, which is the sensible default for a test step. Verified end to end
on `android-34;google_apis;arm64-v8a`.

The workaround, when an emulator already has a fingerprint enrolled under an unknown id, is to
create a rootable one (`google_apis`, not `google_apis_playstore`), read the id there, and pass it
to the step as an explicit finger id. That is confirmed to work, but it only helps a hand-built
emulator.

### 7. The enrollment can be written straight into the HAL's data file (no UI at all)

Better than driving the wizard, when `adb root` is available. The ranchu HAL keeps its enrollments
in one file, `/data/vendor_de/<user>/fpdata/sensor<sensorId>.bin`. Writing that file and then
running `cmd fingerprint sync` -- which makes the framework re-enumerate the HAL and reconcile its
records -- *is* the register/delete operation.

`scripts/set-fingerprint.sh` implements it:

```bash
./scripts/set-fingerprint.sh register emulator-5554 1 1234
./scripts/set-fingerprint.sh delete   emulator-5554
./scripts/set-fingerprint.sh status   emulator-5554
```

25 bytes for a single enrollment:

| offset | size | field |
| --- | --- | --- |
| 0 | 4 | magic `arPF` |
| 4 | 8 | authenticator ID, u64 LE — arbitrary |
| 12 | 8 | **gatekeeper SID**, u64 LE — must be the target device's, from `dumpsys lock_settings` |
| 20 | 1 | enrollment count, u8 |
| 21 | 4 × count | enrollment IDs, u32 LE |

Verified on `android-34;google_apis;arm64-v8a`: registers (`"count":1`), authenticates
(`adb emu finger touch 1`), **passes crypto-bound authentication**
(`mode=STRONG_CRYPTO cryptoBytes=16`, `"acceptCrypto":1`), and deletes again (`"count":0`).
That last point is what the virtual face HAL cannot do (finding 5).

Constraints and traps:

- Needs `adb root`, so `google_apis` only — `google_apis_playstore` images are user builds.
  Fall back to `scripts/enroll-fingerprint-via-ui.sh` there.
- AOSP's generic `persist.vendor.fingerprint.virtual.*` properties do **not** work: the ranchu HAL
  ignores them and `sync` reports no enrollments. Only the file route works.
- `settings put secure biometric_virtual_enabled 1` is required by `cmd fingerprint sync`, and it
  does **not** survive a reboot. Without it `sync` is a silent no-op.
- Write the *target device's* gatekeeper SID. A file carrying someone else's SID still shows as
  enrolled and still unlocks the keyguard, but crypto-bound authentication fails with
  `KEY_USER_NOT_AUTHENTICATED`.
- Set the credential first: changing it afterwards drops the enrollment count to 0.
- `restorecon` matters — a wrong SELinux label looks exactly like "no enrollments".
- Generate the Keystore key *after* installing the file.

### 8. Wizard automation, as the no-root fallback

`scripts/enroll-fingerprint-via-ui.sh` walks the enrollment wizard instead, and needs no root, so
it also covers `google_apis_playstore`:

```bash
./scripts/enroll-fingerprint-via-ui.sh emulator-5554 1 1234
```

It clears the existing credential (which drops any existing enrollment), sets a PIN, walks the
wizard, and drives the touches with `adb emu finger touch 1` — which is what pins the id to 1.
Verified on `android-34;google_apis;arm64-v8a`.

Two things that cost time while writing it, in case the wizard changes again:

- A swipe on the lock screen just pulls the notification shade down. `adb shell wm dismiss-keyguard`
  is what actually brings up the PIN pad.
- `locksettings clear --old <pin>` removes the biometric enrollments along with the credential, and
  needs no root -- handy for resetting between runs.

### 9. Send the scan only once the prompt is actually up

A touch posted before `BiometricPrompt` has focus is dropped, and the attempt then ends as
`ERROR_USER_CANCELED(10)` with `"acquire":0` — no scan ever reached the HAL. Waiting for the window
first makes it deterministic:

```bash
until adb shell dumpsys window | grep -q "mCurrentFocus=Window{.*BiometricPrompt}"; do sleep 1; done
adb emu finger touch 1
```

Same shape as the iOS timing caveat: the match event is a sensor signal, not a request.

### 10. Whether crypto-bound authentication needs a screen lock depends on the Android version

Writing the enrollment file bypasses the framework's enrollment flow, so it also bypasses that
flow's precondition: normally Android forces a screen lock to be set before any biometric can be
enrolled. The result is a state a real device cannot be in -- a biometric enrolled with no
credential -- and platform versions disagree about what that means for Keystore.

Measured with `STRONG_CRYPTO` (a Keystore key created with `setUserAuthenticationRequired(true)`,
handed to `BiometricPrompt` as a `CryptoObject`):

| API | screen lock | result |
| --- | --- | --- |
| 34 | set | `SUCCESS ... cryptoBytes=16` |
| 34 | none | `ERROR ... java.security.ProviderException: Keystore key generation failed` |
| 36 | none | `SUCCESS ... cryptoBytes=16` |

On API 34 the key cannot even be generated without a credential; on API 36 it can. Plain
(non-crypto) `BiometricPrompt` succeeds in every one of those combinations, so the gap only shows
up in the banking-app shape -- which is exactly the case worth testing.

Practically: set a credential before enrolling, and mind the order, because changing the
credential drops the enrollment count back to 0.

```bash
adb shell locksettings set-pin 1234
adb shell locksettings verify --old 1234              # set-pin reports success even when it failed
adb shell settings put system screen_off_timeout 1800000   # keep the lock screen from eating taps
# only now enroll
./scripts/set-fingerprint.sh register <serial> 1 1234
```

### 11. The enrollment technique only works on the AIDL-era HAL (API 34+)

API 33 and older emulator images ship the HIDL fingerprint HAL, and none of the file-based approach
applies to it:

| API | init service | provider reported by `dumpsys fingerprint` | generation |
| --- | --- | --- | --- |
| 33 | `vendor.fps_hal` | `Fingerprint21` | HIDL |
| 34+ | `vendor.biometrics.fingerprint-service.ranchu` | `FingerprintProvider` | AIDL |

On API 33 the binary is `/vendor/bin/hw/android.hardware.biometrics.fingerprint@2.1-service` and
`/data/vendor_de/0/fpdata/` is empty, so both the service name and the storage layout differ.
Stopping the AIDL service name there fails with `Unable to stop service`, which says nothing about
the real cause -- so check `provider: FingerprintProvider` before touching anything.

## Next

- Fingerprint is the only viable path: face is gone from API 36 images, cannot unlock Keystore
  keys, and does not reflect how real Android users authenticate.
- On rootable images, provision with `scripts/set-fingerprint.sh` (finding 7) rather than the
  wizard; it also makes enroll/unenroll cheap enough to do inside a test.
- Either way, verify the result with `dumpsys fingerprint` instead of assuming it worked.
- Wire the steps up against `../biometric-ios` for the iOS half
