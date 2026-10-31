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
export SHOTSYNC_KEYSTORE_PATH=/secure/path/shotsync-upload.jks
export SHOTSYNC_KEYSTORE_PASSWORD=... SHOTSYNC_KEY_ALIAS=upload SHOTSYNC_KEY_PASSWORD=...
cd android && ./gradlew :app:bundleRelease   # AAB for Play; assembleRelease for an APK
```

Without these variables the release build is produced **unsigned**. CI
(`.github/workflows/android.yml`) reads the same values from the repository
secrets `SHOTSYNC_KEYSTORE_BASE64` (base64 of the .jks),
`SHOTSYNC_KEYSTORE_PASSWORD`, `SHOTSYNC_KEY_ALIAS` and `SHOTSYNC_KEY_PASSWORD`.

## Tests

```bash
cd android && ./gradlew :app:testDebugUnitTest
```

Covers screenshot path detection, manufacturer path variations, queue state
transitions, retry/backoff calculations, duplicate-observer handling,
reconciliation diffing, filename safety and pairing payload parsing.
