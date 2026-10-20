#!/usr/bin/env bash
# Switch the Pixel Fold emulator between folded and unfolded postures.
#
# This drives the hinge sensor and Android's device-state service.
# It does not call `wm size`, which would only override resolution.
#
# Verified on API 34 google_apis_playstore / pixel_fold (emulator 36.1.9):
# the console sensor is named hinge-angle0 (not hinge_angle0).
#   unfold: hinge-angle0 180 -> device state 2 OPENED,  inner 2208x1840
#   fold:   hinge-angle0 0   -> device state 0 CLOSED,  outer 1080x2092
#   half:   hinge-angle0 90  -> device state 1 HALF_OPENED, inner 2208x1840
# Folding switches the active built-in display (different uniqueId), which
# `wm size` does not do. This script never calls `wm size` to set a size.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

usage() {
  echo "Usage: $0 fold | unfold | half | status" >&2
  exit 2
}

[[ $# -eq 1 ]] || usage
mode="$1"

case "$mode" in
  unfold|opened|open) adb -s "$ANDROID_SERIAL" emu sensor set hinge-angle0 180 ;;
  fold|folded|closed) adb -s "$ANDROID_SERIAL" emu sensor set hinge-angle0 0 ;;
  half|half-open) adb -s "$ANDROID_SERIAL" emu sensor set hinge-angle0 90 ;;
  status) ;;
  *) usage ;;
esac

# Let SurfaceFlinger and WindowManager apply the new configuration.
sleep 1
"$(dirname "${BASH_SOURCE[0]}")/display-metrics.sh"
