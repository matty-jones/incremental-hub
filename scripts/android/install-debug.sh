#!/usr/bin/env bash
# Install the debug APK onto the Pixel Fold emulator only.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

apk="${1:-${ROOT}/app/build/outputs/apk/debug/app-debug.apk}"
if [[ ! -f "$apk" ]]; then
  (cd "$ROOT" && ./gradlew :app:assembleDebug --no-daemon)
fi

adb -s "$ANDROID_SERIAL" install -r -d "$apk"
echo "Installed ${apk} on ${ANDROID_SERIAL}"
