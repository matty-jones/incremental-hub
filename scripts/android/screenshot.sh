#!/usr/bin/env bash
# Capture the Pixel Fold emulator framebuffer.
# Usage: screenshot.sh <name>
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

name="${1:-screen}"
out="${LOG_DIR}/${name}.png"
adb -s "$ANDROID_SERIAL" exec-out screencap -p >"$out"
echo "$out"
