#!/usr/bin/env bash
# Resolve the already-running Pixel Fold AVD. Does not create, start, stop,
# wipe, or reconfigure PixelFold_API34 or Pixel6_API34_PS.
set -euo pipefail

if [[ -z "${ANDROID_SDK_ROOT:-}" && -n "${ANDROID_HOME:-}" ]]; then
  ANDROID_SDK_ROOT="${ANDROID_HOME}"
fi
if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
  export ANDROID_SDK_ROOT
  export ANDROID_HOME="${ANDROID_SDK_ROOT}"
  export PATH="${ANDROID_SDK_ROOT}/platform-tools:${PATH:-}"
fi
if [[ -n "${IH_ADB_DIR:-}" ]]; then
  export PATH="${IH_ADB_DIR}:${PATH}"
fi

AVD_NAME="${AVD_NAME:-PixelFold_API34}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LOG_DIR="${ROOT}/scripts/android/.logs"
mkdir -p "$LOG_DIR"

if ! command -v adb >/dev/null 2>&1; then
  echo "adb is not on PATH. Set ANDROID_SDK_ROOT to an Android SDK that provides platform-tools." >&2
  echo "Incremental Hub does not install the SDK or start an emulator." >&2
  exit 1
fi

# Console `avd name` is the emulator's AVD identity. The boot property is the
# same name when the console command is unavailable. A disagreement is not a match.
adb_identity_line() {
  local serial="$1"
  local console="" prop="" line="" console_name="" prop_name=""
  console="$(timeout 10 adb -s "$serial" emu avd name 2>/dev/null | tr -d '\r' || true)"
  while IFS= read -r line; do
    [[ -z "$line" || "$line" == "OK" ]] && continue
    [[ "$line" == KO:* ]] && break
    console_name="$line"
    break
  done <<<"$console"
  prop="$(adb -s "$serial" shell getprop ro.boot.qemu.avd_name 2>/dev/null | tr -d '\r' || true)"
  prop_name="${prop%%$'\n'*}"
  prop_name="${prop_name#"${prop_name%%[![:space:]]*}"}"
  prop_name="${prop_name%"${prop_name##*[![:space:]]}"}"
  console_name="${console_name#"${console_name%%[![:space:]]*}"}"
  console_name="${console_name%"${console_name##*[![:space:]]}"}"
  if [[ -n "$console_name" && -n "$prop_name" && "$console_name" != "$prop_name" ]]; then
    printf 'CONFLICT\n'
    return 0
  fi
  if [[ -n "$console_name" ]]; then
    printf 'OK %s\n' "$console_name"
    return 0
  fi
  if [[ -n "$prop_name" ]]; then
    printf 'OK %s\n' "$prop_name"
    return 0
  fi
  printf 'NONE\n'
}

resolve_android_serial() {
  local -a serials=() matches=() notes=()
  local line serial state identity
  while IFS= read -r line; do
    line="${line//$'\r'/}"
    [[ -z "$line" || "$line" == "List of devices attached" ]] && continue
    serial="${line%%[[:space:]]*}"
    state="$(awk '{print $2}' <<<"$line")"
    [[ "$state" == "device" ]] || continue
    [[ -n "$serial" ]] || continue
    serials+=("$serial")
  done < <(adb devices | tr -d '\r')

  if ((${#serials[@]} > 0)); then
    for serial in "${serials[@]}"; do
      identity="$(adb_identity_line "$serial")"
      case "$identity" in
        "OK ${AVD_NAME}")
          matches+=("$serial")
          notes+=("${serial} ${AVD_NAME}")
          ;;
        OK\ *)
          notes+=("${serial} ${identity#OK }")
          ;;
        CONFLICT)
          notes+=("${serial} conflicting AVD identity")
          ;;
        *)
          notes+=("${serial} no AVD identity")
          ;;
      esac
    done
  fi

  if ((${#matches[@]} == 1)); then
    ANDROID_SERIAL="${matches[0]}"
    export ANDROID_SERIAL
    return 0
  fi
  if ((${#matches[@]} > 1)); then
    echo "More than one running device reports AVD ${AVD_NAME}:" >&2
    printf '  %s\n' "${matches[@]}" >&2
    echo "Refusing to choose a serial." >&2
    echo "Incremental Hub does not start, stop, or reconfigure emulators." >&2
    exit 1
  fi
  echo "${AVD_NAME} is not running, so Incremental Hub has no test device." >&2
  if ((${#notes[@]} == 0)); then
    echo "No adb devices in the device state are attached." >&2
  else
    echo "Attached devices:" >&2
    printf '  %s\n' "${notes[@]}" >&2
  fi
  echo "Incremental Hub will not use another device, including Pixel6_API34_PS." >&2
  echo "Start ${AVD_NAME} outside this project and retry." >&2
  exit 1
}

resolve_android_serial
