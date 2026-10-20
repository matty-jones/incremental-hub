#!/usr/bin/env bash
# Build and run one instrumentation class on the Pixel Fold emulator only.
# Usage: run-instrumentation.sh <fully.qualified.Class[#method]>
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

class="${1:-com.example.hub.SharkOfflineGeometryTest}"
(cd "$ROOT" && ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon)

debug_apk="${ROOT}/app/build/outputs/apk/debug/app-debug.apk"
test_apk="${ROOT}/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
adb -s "$ANDROID_SERIAL" install -r -d "$debug_apk"
adb -s "$ANDROID_SERIAL" install -r -d "$test_apk"

adb -s "$ANDROID_SERIAL" shell am instrument -w \
  -e class "$class" \
  com.example.hub.test/androidx.test.runner.AndroidJUnitRunner
