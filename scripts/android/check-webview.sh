#!/usr/bin/env bash
# Fail when the fold AVD WebView is too old for ProfileStore.
# WebView 113 throws UnsupportedOperationException from ProfileStore.getInstance.
# Multi-profile needs WebView 118 or newer. This does not touch emulator-5554.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
"$(dirname "${BASH_SOURCE[0]}")/provision-webview.sh"

version="$(adb -s "$ANDROID_SERIAL" shell dumpsys webviewupdate | tr -d '\r' | sed -n 's/.*webview, //p' | head -1 | tr -d '()[:space:]')"
major="${version%%.*}"
echo "WebView on $ANDROID_SERIAL: ${version:-unknown}"
if [[ -z "${major}" || ! "${major}" =~ ^[0-9]+$ || "${major}" -lt 118 ]]; then
  echo "ProfileStore.getInstance is unsupported on WebView ${version:-unknown}." >&2
  echo "The API 34 Play image ships 113.0.5672.136. scripts/android/provision-webview.sh installs the pinned provider from the local cache. Do not delete Pixel6_API34_PS." >&2
  exit 1
fi
