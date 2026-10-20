#!/usr/bin/env bash
# Stop only the Pixel Fold emulator. Leaves Pixel 6 and physical devices alone.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

if adb devices | awk -v serial="$ANDROID_SERIAL" '$1 == serial { found = 1 } END { exit !found }'; then
  adb -s "$ANDROID_SERIAL" emu kill || true
fi

pidfile="${LOG_DIR}/pixel-fold.pid"
if [[ -f "$pidfile" ]]; then
  pid="$(cat "$pidfile")"
  if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
    # emu kill should exit qemu. Give it a moment before a normal TERM.
    sleep 2
    if kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null || true
    fi
  fi
  rm -f "$pidfile"
fi

echo "Stopped ${ANDROID_SERIAL}"
