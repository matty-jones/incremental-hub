#!/usr/bin/env bash
# Wait until Android has booted and a WebView provider is installed.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

echo "Waiting for ${ANDROID_SERIAL} (timeout ${BOOT_TIMEOUT_SEC}s)"
adb -s "$ANDROID_SERIAL" wait-for-device

deadline=$((SECONDS + BOOT_TIMEOUT_SEC))
booted=0
while (( SECONDS < deadline )); do
  state="$(adb -s "$ANDROID_SERIAL" get-state 2>/dev/null || true)"
  boot="$(adb -s "$ANDROID_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
  anim="$(adb -s "$ANDROID_SERIAL" shell getprop init.svc.bootanim 2>/dev/null | tr -d '\r' || true)"
  if [[ "$state" == "device" && "$boot" == "1" && "$anim" != "running" ]]; then
    booted=1
    break
  fi
  sleep 2
done

if (( booted == 0 )); then
  echo "Emulator did not finish booting within ${BOOT_TIMEOUT_SEC}s" >&2
  exit 1
fi

adb -s "$ANDROID_SERIAL" shell 'while [[ "$(getprop dev.bootcomplete)" != "1" ]]; do sleep 1; done'

webview=""
for pkg in com.google.android.webview com.android.webview com.android.chrome; do
  if adb -s "$ANDROID_SERIAL" shell pm path "$pkg" >/dev/null 2>&1; then
    webview="$pkg"
    break
  fi
done

if [[ -z "$webview" ]]; then
  echo "No WebView provider package is installed yet" >&2
  adb -s "$ANDROID_SERIAL" shell dumpsys webviewupdate | head -n 40 >&2 || true
  exit 1
fi

adb -s "$ANDROID_SERIAL" shell settings put global window_animation_scale 0
adb -s "$ANDROID_SERIAL" shell settings put global transition_animation_scale 0
adb -s "$ANDROID_SERIAL" shell settings put global animator_duration_scale 0

echo "Booted ${ANDROID_SERIAL}. WebView provider: ${webview}"
"$(dirname "${BASH_SOURCE[0]}")/provision-webview.sh"
