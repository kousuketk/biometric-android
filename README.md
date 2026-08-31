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

Note that an API 36 `google_apis_playstore` emulator showed **only** the fingerprint sensor, so the
face sensor is not guaranteed across device profiles / images. Check per image before relying on it.

### 2. Face enrollment needs no UI at all

This is the whole reason the face path matters. Enrollment, end to end:

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

`scripts/enroll-fingerprint.sh` does this end to end and needs no root:

```bash
./scripts/enroll-fingerprint.sh emulator-5554 1 1234
```

It clears the existing credential (which drops any existing enrollment), sets a PIN, walks the
enrollment wizard, and drives the touches with `adb emu finger touch 1`. Verified on
`android-34;google_apis;arm64-v8a`: the enrolled id comes out as

```
<fingerprint fingerId="1" name="Finger 1" groupId="0" deviceId="0" />
```

and `adb emu finger touch 1` then authenticates (`"accept":1,"reject":0`).

Two things that cost time while writing it, in case the wizard changes again:

- A swipe on the lock screen just pulls the notification shade down. `adb shell wm dismiss-keyguard`
  is what actually brings up the PIN pad.
- `locksettings clear --old <pin>` removes the biometric enrollments along with the credential, and
  needs no root -- handy for resetting between runs.

**Note for provisioning:** reading the id needs root, but *enrolling* does not — the wizard is
driven with `am start`, `input tap` and `adb emu finger touch`, all of which work on a user build.
So emulator provisioning can pin the id to 1 on any image, including `google_apis_playstore`, and
then no one has to look the id up at all.

## Next

- Decide whether emulator provisioning enrolls a face (cheap, no UI, no CryptoObject)
  or a fingerprint (UI automation needed, full CryptoObject support)
- Wire the steps up against `../biometric-ios` for the iOS half
