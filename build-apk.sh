#!/usr/bin/env bash
# Assemble the debug APK and publish it as apk/incrementalhub.apk.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$root"

./gradlew :app:assembleDebug --no-daemon

src="app/build/outputs/apk/debug/app-debug.apk"
dest="apk/incrementalhub.apk"

if [[ ! -f "$src" ]]; then
  echo "Build finished but $src was not produced." >&2
  exit 1
fi

mkdir -p apk
cp "$src" "$dest"
echo "Wrote $(pwd)/$dest"
