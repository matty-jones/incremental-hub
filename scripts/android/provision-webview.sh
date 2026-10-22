#!/usr/bin/env bash
# Give the fold AVD a WebView that supports MULTI_PROFILE.
# Below the pin's min_major, install the cached APK only after its SHA-256,
# package identity, and signing certificate match webview-provider.pin.
# An already-installed provider at or above that floor, with the same package
# and signature, is left alone. A newer version is not downgraded.
# Does not download, does not wipe userdata, and does not touch emulator-5554.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=env.sh
source "${here}/env.sh"
if [[ -n "${WEBVIEW_PROVISION_SERIAL:-}" ]]; then
  ANDROID_SERIAL="$WEBVIEW_PROVISION_SERIAL"
fi
if [[ -n "${WEBVIEW_ADB_DIR:-}" ]]; then
  PATH="${WEBVIEW_ADB_DIR}:${PATH}"
fi

pin_file="${WEBVIEW_PIN_FILE:-${here}/webview-provider.pin}"
cache_dir="${WEBVIEW_CACHE_DIR:-${XDG_CACHE_HOME:-${HOME}/.cache}/incremental-hub/webview}"

if [[ "$ANDROID_SERIAL" == "emulator-5554" ]]; then
  echo "Refusing to provision WebView on emulator-5554 (Pixel6_API34_PS)." >&2
  exit 1
fi

declare -A pin=()
while IFS='=' read -r key value; do
  [[ -z "$key" || "$key" =~ ^# ]] && continue
  pin["$key"]="$value"
done <"$pin_file"

for key in package version_name version_code min_major sha256 cert_sha256 signature_id; do
  if [[ -z "${pin[$key]:-}" ]]; then
    echo "Pin ${pin_file} is missing ${key}." >&2
    exit 1
  fi
done

apk="${cache_dir}/${pin[package]}-${pin[version_name]}.apk"

fail_missing() {
  echo "WebView on ${ANDROID_SERIAL} is ${current_name:-unknown} (${current_pkg:-no provider})." >&2
  echo "MULTI_PROFILE needs WebView ${pin[min_major]} or newer. The API 34 Play image ships 113.0.5672.136, and ProfileStore.getInstance throws on that provider." >&2
  echo "The verified provider APK is not available at:" >&2
  echo "  ${apk}" >&2
  echo "Expected SHA-256: ${pin[sha256]}" >&2
  echo "Expected package: ${pin[package]} ${pin[version_name]} (${pin[version_code]})" >&2
  echo "Expected certificate SHA-256: ${pin[cert_sha256]}" >&2
  echo "Copy that exact APK to the path above and run scripts/android/provision-webview.sh again." >&2
  echo "To rebuild the cache from an emulator that already has this provider (a read-only pull, not a wipe):" >&2
  echo "  adb -s ${ANDROID_SERIAL} shell pm path ${pin[package]}" >&2
  echo "  adb -s ${ANDROID_SERIAL} pull <printed base.apk path> ${apk}" >&2
  echo "Do not download an unverified package. A file is installed only when its SHA-256, package identity, and signing certificate match ${pin_file}." >&2
  echo "This does not delete Pixel6_API34_PS or any AVD, and it does not wipe userdata." >&2
  exit 1
}

verify_apk() {
  if [[ ! -f "$apk" ]]; then
    fail_missing
  fi
  actual_sha="$(sha256sum "$apk" | awk '{print $1}')"
  if [[ "$actual_sha" != "${pin[sha256]}" ]]; then
    echo "Refusing ${apk}." >&2
    echo "SHA-256 ${actual_sha} does not match the pin ${pin[sha256]}." >&2
    echo "A higher version number is not enough. Do not download a replacement. Restore the pinned file. See docs/pixel-fold-emulator.md." >&2
    exit 1
  fi
  aapt="${ANDROID_SDK_ROOT}/build-tools/34.0.0/aapt"
  apksigner="${ANDROID_SDK_ROOT}/build-tools/34.0.0/apksigner"
  if [[ ! -x "$aapt" ]]; then
    aapt="$(command -v aapt || true)"
  fi
  if [[ ! -x "$apksigner" ]]; then
    apksigner="$(command -v apksigner || true)"
  fi
  if [[ -z "$aapt" || -z "$apksigner" ]]; then
    echo "aapt and apksigner are required to verify the cached WebView APK before install." >&2
    exit 1
  fi
  badging="$("$aapt" dump badging "$apk")"
  if [[ ! "$badging" =~ name=\'([^\']+)\'[[:space:]]versionCode=\'([0-9]+)\'[[:space:]]versionName=\'([^\']+)\' ]]; then
    echo "Refusing ${apk}: package identity could not be read." >&2
    exit 1
  fi
  if [[ "${BASH_REMATCH[1]}" != "${pin[package]}" || "${BASH_REMATCH[2]}" != "${pin[version_code]}" || "${BASH_REMATCH[3]}" != "${pin[version_name]}" ]]; then
    echo "Refusing ${apk}: package ${BASH_REMATCH[1]} ${BASH_REMATCH[3]} (${BASH_REMATCH[2]}) does not match the pin." >&2
    exit 1
  fi
  certs="$("$apksigner" verify --print-certs "$apk")"
  digest="$(printf '%s\n' "$certs" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -1 | tr -d '[:space:]')"
  if [[ "$digest" != "${pin[cert_sha256]}" ]]; then
    echo "Refusing ${apk}: certificate SHA-256 ${digest:-missing} does not match the pin." >&2
    exit 1
  fi
}

dump="$(adb -s "$ANDROID_SERIAL" shell dumpsys webviewupdate | tr -d '\r')"
current_pkg=""
current_name=""
current_code=""
if [[ "$dump" =~ Current\ WebView\ package\ \(name,\ version\):\ \(([A-Za-z0-9._]+),\ ([0-9.]+)\) ]]; then
  current_pkg="${BASH_REMATCH[1]}"
  current_name="${BASH_REMATCH[2]}"
fi
if [[ "$dump" =~ ${current_pkg}\ \(versionName:\ ${current_name},\ versionCode:\ ([0-9]+) ]]; then
  current_code="${BASH_REMATCH[1]}"
fi
major="${current_name%%.*}"
signature=""
if [[ -n "$current_pkg" ]]; then
  pkg_dump="$(adb -s "$ANDROID_SERIAL" shell dumpsys package "$current_pkg" | tr -d '\r')"
  signature="$(printf '%s\n' "$pkg_dump" | sed -n 's/.*signatures:\[\([0-9a-fA-F][0-9a-fA-F]*\)\].*/\1/p' | head -1)"
fi

compatible=0
if [[ "$current_pkg" == "${pin[package]}" && "$signature" == "${pin[signature_id]}" && "$major" =~ ^[0-9]+$ && "$major" -ge "${pin[min_major]}" ]]; then
  compatible=1
fi

if (( compatible == 1 )); then
  if [[ -n "$current_code" && "$current_code" -gt "${pin[version_code]}" ]]; then
    echo "action=keep"
    echo "WebView on ${ANDROID_SERIAL}: ${current_pkg} ${current_name} (${current_code}) is newer than ${pin[version_name]} and uses the pinned certificate. Not downgrading."
    exit 0
  fi
  echo "action=keep"
  echo "WebView on ${ANDROID_SERIAL}: ${current_pkg} ${current_name} (${current_code:-unknown}) supports MULTI_PROFILE. Not installing."
  exit 0
fi

if [[ "$current_pkg" == "${pin[package]}" && -n "$signature" && "$signature" != "${pin[signature_id]}" ]]; then
  echo "WebView on ${ANDROID_SERIAL} is ${current_pkg} ${current_name}, but its signing certificate does not match the pin." >&2
  echo "Refusing to treat a high version as compatible, and refusing to replace this install." >&2
  exit 1
fi

if [[ -n "$current_code" && "$current_code" -gt "${pin[version_code]}" ]]; then
  echo "WebView on ${ANDROID_SERIAL} is ${current_name} (${current_code}), which is newer than the pinned ${pin[version_name]} but is not a compatible provider." >&2
  echo "Refusing to downgrade it." >&2
  exit 1
fi

verify_apk
echo "action=install"
echo "Installing pinned ${pin[package]} ${pin[version_name]} onto ${ANDROID_SERIAL}."
adb -s "$ANDROID_SERIAL" install -r -d "$apk"
after="$(adb -s "$ANDROID_SERIAL" shell dumpsys webviewupdate | tr -d '\r')"
installed_name=""
if [[ "$after" =~ Current\ WebView\ package\ \(name,\ version\):\ \(${pin[package]},\ ([0-9.]+)\) ]]; then
  installed_name="${BASH_REMATCH[1]}"
fi
installed_major="${installed_name%%.*}"
if [[ -z "$installed_name" || ! "$installed_major" =~ ^[0-9]+$ || "$installed_major" -lt "${pin[min_major]}" ]]; then
  echo "Install finished, but the current provider is still ${installed_name:-unknown}." >&2
  exit 1
fi
echo "WebView on ${ANDROID_SERIAL}: ${pin[package]} ${installed_name}"
