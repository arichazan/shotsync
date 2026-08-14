# Building the Android app

## Requirements

- JDK 17+
- Android SDK: `platforms/android-36`, `build-tools/36.0.0` (installed
  through Android Studio or `sdkmanager`)
- A `android/local.properties` with `sdk.dir=/path/to/Android/sdk`
  (create this local, ignored file for your Android SDK installation)

## Build

```bash
./scripts/build_android.sh
# APK: android/app/build/outputs/apk/debug/app-debug.apk
```

or manually:

```bash
cd android
./gradlew :app:assembleDebug
```

## Install on a device

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

The APK is self-signed with the default debug key. For distribution or
long-term use, build a release APK and sign it with your own key:

```bash
cd android
./gradlew :app:assembleRelease
# configure signingConfigs in app/build.gradle.kts for your keystore
```

## Tests

```bash
cd android && ./gradlew :app:testDebugUnitTest
```

Covers screenshot path detection, manufacturer path variations, queue state
transitions, retry/backoff calculations, duplicate-observer handling,
reconciliation diffing, filename safety and pairing payload parsing.
