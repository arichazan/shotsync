#!/usr/bin/env bash
# Builds the Android debug APK. Requires a local Android SDK (see docs/build-android.md).
set -euo pipefail
cd "$(dirname "$0")/../android"

./gradlew :app:assembleDebug
echo "APK: android/app/build/outputs/apk/debug/app-debug.apk"
