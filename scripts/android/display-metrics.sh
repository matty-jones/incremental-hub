#!/usr/bin/env bash
# Print the active display size, density, and device posture.
# Used to prove a fold transition changed the real configuration.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

echo "=== wm ==="
adb -s "$ANDROID_SERIAL" shell wm size
adb -s "$ANDROID_SERIAL" shell wm density
echo "=== device_state ==="
adb -s "$ANDROID_SERIAL" shell cmd device_state state || true
adb -s "$ANDROID_SERIAL" shell cmd device_state print-state || true
echo "=== configuration ==="
adb -s "$ANDROID_SERIAL" shell dumpsys window displays | awk '
  /Display:|init=|cur=|app=|smallestScreenWidthDp|mBounds=/ { print }
' | head -n 40
echo "=== display devices ==="
adb -s "$ANDROID_SERIAL" shell dumpsys display | awk '
  /DisplayDeviceInfo|uniqueId=|mOverrideDisplayInfo|real |density |state / { print }
' | head -n 60
