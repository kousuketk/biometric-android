# BiometricDemo (Android)

A small app for exercising the Android biometric authentication paths on an
emulator.

## Build

```bash
./gradlew assembleDebug
```

Requires JDK 17 or later and an Android SDK with platform 34. The Gradle
wrapper downloads Gradle itself, and there are no third-party services.

## Build output

```
app/build/outputs/apk/debug/app-debug.apk
```

Install and launch it with:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.magicpod.biometricdemo/.MainActivity
```
