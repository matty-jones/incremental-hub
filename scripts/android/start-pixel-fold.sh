#!/usr/bin/env bash
# Start the persistent Pixel Fold AVD without a graphical desktop.
# Does not touch any other emulator, including Pixel6_API34_PS on port 5554.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

if adb devices | awk -v serial="$ANDROID_SERIAL" '$1 == serial && $2 == "device" { found = 1 } END { exit !found }'; then
  echo "$ANDROID_SERIAL is already online"
  "$(dirname "${BASH_SOURCE[0]}")/provision-webview.sh"
  exit 0
fi

if ss -ltn | awk -v port=":${EMULATOR_PORT}$" '$4 ~ port { found = 1 } END { exit !found }'; then
  echo "Port ${EMULATOR_PORT} is in use but $ANDROID_SERIAL is not an adb device." >&2
  exit 1
fi

logfile="${LOG_DIR}/pixel-fold.log"
echo "Starting ${AVD_NAME} as ${ANDROID_SERIAL} (-no-window). Log: ${logfile}"

# swiftshader_indirect renders without a host display. -port pins the adb serial.
# -no-snapshot-load avoids restoring a folded or half-booted quickboot image.
# setsid detaches from the caller so the emulator survives the launching shell.
setsid -f emulator \
  -avd "$AVD_NAME" \
  -port "$EMULATOR_PORT" \
  -no-window \
  -no-audio \
  -no-boot-anim \
  -no-snapshot-load \
  -gpu swiftshader_indirect \
  -accel on \
  -netdelay none \
  -netspeed full \
  -no-metrics \
  >"$logfile" 2>&1 < /dev/null

sleep 1
pgrep -f "qemu-system.*${AVD_NAME}|emulator -avd ${AVD_NAME}" | head -n 1 >"${LOG_DIR}/pixel-fold.pid" || true
echo "pid $(cat "${LOG_DIR}/pixel-fold.pid" 2>/dev/null || echo unknown)"
echo "After boot, wait-ready.sh checks the WebView provider and installs the pinned APK when it is below 118."
