#!/usr/bin/env bash
# Shared paths for the headless Pixel Fold emulator.
# The existing Pixel 6 AVD stays on console port 5554. This one uses 5556.
set -euo pipefail

export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-/opt/android-sdk}"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export PATH="${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${ANDROID_SDK_ROOT}/emulator:${ANDROID_SDK_ROOT}/platform-tools:${PATH:-}"

AVD_NAME="${AVD_NAME:-PixelFold_API34}"
EMULATOR_PORT="${EMULATOR_PORT:-5556}"
export ANDROID_SERIAL="emulator-${EMULATOR_PORT}"
BOOT_TIMEOUT_SEC="${BOOT_TIMEOUT_SEC:-240}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LOG_DIR="${ROOT}/scripts/android/.logs"
mkdir -p "$LOG_DIR"
