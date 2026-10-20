#!/usr/bin/env bash
# Capture WebView, Chromium, and Hub startup logs from the Pixel Fold emulator.
# Usage: logcat.sh [output-name]
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

name="${1:-logcat}"
out="${LOG_DIR}/${name}.log"
adb -s "$ANDROID_SERIAL" logcat -d -v time \
  chromium:V WebViewFactory:V WebViewHost:V SessionController:V \
  AndroidRuntime:E System.err:W SharkProbe:V '*:S' >"$out" || \
  adb -s "$ANDROID_SERIAL" logcat -d -v time >"$out"
echo "$out"
echo "--- webview / console lines ---"
rg -n "shark|offline|CONSOLE|FATAL|chromium|RecoveryRequired|blank-screen" "$out" | head -n 80 || true
