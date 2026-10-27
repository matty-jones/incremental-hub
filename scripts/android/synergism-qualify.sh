#!/usr/bin/env bash
# Synergism qualification on the Pixel Fold emulator. Does not touch the Pixel 6.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
log="$LOG_DIR/synergism-qualify.log"
exec > >(tee "$log") 2>&1

"$(dirname "${BASH_SOURCE[0]}")/check-webview.sh"
"$(dirname "${BASH_SOURCE[0]}")/run-instrumentation.sh" com.example.hub.SynergismQualificationTest#liveSession || true

adb -s "$ANDROID_SERIAL" shell am force-stop com.example.hub
sleep 2
adb -s "$ANDROID_SERIAL" shell am instrument -w \
  -e class com.example.hub.SynergismQualificationTest#resumeAfterProcessDeath \
  com.example.hub.test/androidx.test.runner.AndroidJUnitRunner || true

adb -s "$ANDROID_SERIAL" shell am force-stop com.example.hub
adb -s "$ANDROID_SERIAL" shell run-as com.example.hub rm -f files/synergism-restore-death-result.txt files/synergism-restore-death-fired files/restore-kill-after || true
adb -s "$ANDROID_SERIAL" shell am start -n com.example.hub/.SynergismRestoreDeathHarnessActivity --es mode kill
for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
  if adb -s "$ANDROID_SERIAL" shell pidof com.example.hub >/dev/null 2>&1; then
    break
  fi
  sleep 0.25
done
for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
  if ! adb -s "$ANDROID_SERIAL" shell pidof com.example.hub >/dev/null 2>&1; then
    break
  fi
  sleep 0.25
done
sleep 0.5
adb -s "$ANDROID_SERIAL" shell am force-stop com.example.hub
adb -s "$ANDROID_SERIAL" shell run-as com.example.hub rm -f files/restore-kill-after || true
adb -s "$ANDROID_SERIAL" shell am start -n com.example.hub/.SynergismRestoreDeathHarnessActivity --es mode recover
sleep 2
echo "--- restore death ---"
adb -s "$ANDROID_SERIAL" shell run-as com.example.hub cat files/synergism-restore-death-result.txt || true
echo "--- qualification ---"
adb -s "$ANDROID_SERIAL" shell run-as com.example.hub cat files/synergism-qualification.txt || true
echo "--- resume ---"
adb -s "$ANDROID_SERIAL" shell run-as com.example.hub cat files/synergism-resume.txt || true

mkdir -p "$LOG_DIR/synergism"
for name in synergism-launcher synergism-playable synergism-offline-dialog synergism-offline-aged synergism-folded synergism-unfolded synergism-folded-again; do
  adb -s "$ANDROID_SERIAL" pull "/sdcard/$name.png" "$LOG_DIR/synergism/$name.png" || true
done
adb -s "$ANDROID_SERIAL" shell cmd device_state state reset || true
