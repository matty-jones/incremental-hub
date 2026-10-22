#!/usr/bin/env bash
# Read-only gate. Multi-profile needs WebView major version 118 or newer.
# This script never installs, replaces, or configures a provider.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

dump="$(adb -s "$ANDROID_SERIAL" shell dumpsys webviewupdate | tr -d '\r')"
pkg=""
version=""
if [[ "$dump" =~ Current\ WebView\ package\ \(name,\ version\):\ \(([A-Za-z0-9._]+),\ ([0-9.]+)\) ]]; then
  pkg="${BASH_REMATCH[1]}"
  version="${BASH_REMATCH[2]}"
fi

fail_incompatible() {
  echo "$1" >&2
  echo "Multi-profile support requires WebView major version 118 or later." >&2
  echo "ProfileStore.getInstance throws UnsupportedOperationException on the API 34 Play image's stock provider 113.0.5672.136." >&2
  echo "Incremental Hub does not install or replace the WebView provider." >&2
  echo "Install a compatible provider on ${AVD_NAME} outside this project, then retry." >&2
  exit 1
}

if [[ -z "$version" || -z "$pkg" ]]; then
  fail_incompatible "${AVD_NAME} (${ANDROID_SERIAL}) has no current WebView provider."
fi

major="${version%%.*}"
echo "WebView on ${AVD_NAME} (${ANDROID_SERIAL}): ${pkg} ${version}"
if [[ ! "$major" =~ ^[0-9]+$ || "$major" -lt 118 ]]; then
  fail_incompatible "WebView ${pkg} ${version} on ${AVD_NAME} (${ANDROID_SERIAL}) is incompatible."
fi
