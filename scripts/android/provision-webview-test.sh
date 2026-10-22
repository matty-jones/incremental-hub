#!/usr/bin/env bash
# Disposable checks for provision-webview.sh. The below-118 cases use a fake adb.
# The live case only reads the fold AVD and must not reinstall or touch the Pixel 6.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "${here}/../.." && pwd)"
provision="${here}/provision-webview.sh"
pin="${here}/webview-provider.pin"
real_cache="${XDG_CACHE_HOME:-${HOME}/.cache}/incremental-hub/webview"
real_apk="${real_cache}/com.google.android.webview-155.0.8059.43.apk"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

failures=0
pass() { echo "PASS $*"; }
fail() { echo "FAIL $*"; failures=$((failures + 1)); }

write_fake_adb() {
  local dir="$1"
  mkdir -p "$dir"
  cat >"${dir}/adb" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == "-s" ]]; then
  shift 2
fi
cmd="${1:-}"
shift || true
if [[ "$cmd" == "install" ]]; then
  printf '%s\n' "$*" >>"${WEBVIEW_FIXTURE_INSTALL_LOG}"
  if [[ -n "${WEBVIEW_FIXTURE_AFTER_DUMP:-}" ]]; then
    cp "${WEBVIEW_FIXTURE_AFTER_DUMP}" "${WEBVIEW_FIXTURE_DUMP}"
  fi
  exit 0
fi
if [[ "$cmd" == "shell" ]]; then
  text="$*"
  if [[ "$text" == *webviewupdate* ]]; then
    cat "${WEBVIEW_FIXTURE_DUMP}"
    exit 0
  fi
  if [[ "$text" == *"dumpsys package"* ]]; then
    cat "${WEBVIEW_FIXTURE_PKG}"
    exit 0
  fi
fi
echo "unexpected adb invocation: $cmd $*" >&2
exit 1
EOF
  chmod +x "${dir}/adb"
}

dump_for() {
  local name="$1" code="$2" dest="$3"
  cat >"$dest" <<EOF
Current WebView Update Service state
  Current WebView package (name, version): (com.google.android.webview, ${name})
  Minimum WebView version code: 567263637
  Preferred WebView package (name, version): (com.google.android.webview, ${name})
  WebView packages:
    Valid package com.google.android.webview (versionName: ${name}, versionCode: ${code}, targetSdkVersion: 34) is  installed/enabled for all users
EOF
}

pkg_for() {
  local sig="$1" dest="$2"
  echo "    signatures=PackageSignatures{cf5cfb4 version:3, signatures:[${sig}], past signatures:[]}" >"$dest"
}

run_fixture() {
  local name="$1"
  shift
  local out="${work}/${name}.out"
  local err="${work}/${name}.err"
  set +e
  env "$@" "${provision}" >"$out" 2>"$err"
  local status=$?
  set -e
  printf '%s\n' "$status"
}

# Live fold: already on the pinned provider. Must keep it, and must not touch the Pixel 6.
phone_before="$(adb -s emulator-5554 shell dumpsys webviewupdate | tr -d '\r' | sed -n 's/.*Current WebView package (name, version): //p' | head -1)"
fold_path_before="$(adb -s emulator-5556 shell pm path com.google.android.webview | tr -d '\r' | head -1)"
live_out="$("${provision}")"
fold_path_after="$(adb -s emulator-5556 shell pm path com.google.android.webview | tr -d '\r' | head -1)"
phone_after="$(adb -s emulator-5554 shell dumpsys webviewupdate | tr -d '\r' | sed -n 's/.*Current WebView package (name, version): //p' | head -1)"
if [[ "$live_out" == *action=keep* && "$live_out" == *155.0.8059.43* && "$fold_path_before" == "$fold_path_after" ]]; then
  pass "already-compatible fold kept ${fold_path_after}"
else
  fail "already-compatible fold changed or did not keep the provider"
  printf '%s\n' "$live_out"
fi
if [[ "$phone_before" == "$phone_after" && -n "$phone_before" ]]; then
  pass "Pixel 6 provider unchanged ${phone_after}"
else
  fail "Pixel 6 provider changed from ${phone_before} to ${phone_after}"
fi

write_fake_adb "${work}/fake-adb"
export WEBVIEW_FIXTURE_INSTALL_LOG="${work}/install.log"
export WEBVIEW_FIXTURE_DUMP="${work}/dump.txt"
export WEBVIEW_FIXTURE_PKG="${work}/pkg.txt"
touch "$WEBVIEW_FIXTURE_INSTALL_LOG"
common=(
  WEBVIEW_ADB_DIR="${work}/fake-adb"
  WEBVIEW_PROVISION_SERIAL="fixture-113"
  WEBVIEW_FIXTURE_INSTALL_LOG="$WEBVIEW_FIXTURE_INSTALL_LOG"
  WEBVIEW_FIXTURE_DUMP="$WEBVIEW_FIXTURE_DUMP"
  WEBVIEW_FIXTURE_PKG="$WEBVIEW_FIXTURE_PKG"
)

dump_for "113.0.5672.136" "567263637" "$WEBVIEW_FIXTURE_DUMP"
dump_for "155.0.8059.43" "805904308" "${work}/after-dump.txt"
pkg_for "f4ae824f" "$WEBVIEW_FIXTURE_PKG"
: >"$WEBVIEW_FIXTURE_INSTALL_LOG"
status="$(run_fixture old "${common[@]}" WEBVIEW_FIXTURE_AFTER_DUMP="${work}/after-dump.txt")"
if [[ "$status" == "0" && "$(cat "$WEBVIEW_FIXTURE_INSTALL_LOG")" == *"-r -d ${real_apk}"* ]]; then
  pass "provider 113 installs the pinned APK"
else
  fail "provider 113 did not install the pinned APK (status ${status})"
  cat "${work}/old.err" || true
fi

dump_for "156.0.1.2" "900000000" "$WEBVIEW_FIXTURE_DUMP"
pkg_for "f4ae824f" "$WEBVIEW_FIXTURE_PKG"
: >"$WEBVIEW_FIXTURE_INSTALL_LOG"
status="$(run_fixture newer "${common[@]}")"
if [[ "$status" == "0" && ! -s "$WEBVIEW_FIXTURE_INSTALL_LOG" && "$(cat "${work}/newer.out")" == *action=keep* && "$(cat "${work}/newer.out")" == *Not\ downgrading* ]]; then
  pass "newer same-certificate provider was not downgraded"
else
  fail "newer provider was not left alone (status ${status})"
fi

dump_for "200.0.0.0" "999000000" "$WEBVIEW_FIXTURE_DUMP"
pkg_for "deadbeef" "$WEBVIEW_FIXTURE_PKG"
: >"$WEBVIEW_FIXTURE_INSTALL_LOG"
status="$(run_fixture wrong-cert "${common[@]}")"
if [[ "$status" != "0" && ! -s "$WEBVIEW_FIXTURE_INSTALL_LOG" && "$(cat "${work}/wrong-cert.err")" == *certificate\ does\ not\ match* ]]; then
  pass "high version with a different certificate was refused"
else
  fail "different certificate was accepted (status ${status})"
fi

empty="${work}/empty-cache"
mkdir -p "$empty"
dump_for "113.0.5672.136" "567263637" "$WEBVIEW_FIXTURE_DUMP"
pkg_for "f4ae824f" "$WEBVIEW_FIXTURE_PKG"
: >"$WEBVIEW_FIXTURE_INSTALL_LOG"
status="$(run_fixture missing "${common[@]}" WEBVIEW_CACHE_DIR="$empty")"
err="$(cat "${work}/missing.err")"
if [[ "$status" != "0" && ! -s "$WEBVIEW_FIXTURE_INSTALL_LOG" && "$err" == *"Do not download"* && "$err" == *"4c481c062eadc296bd7afa93295cba95c224f95ee4595a6ae5f95627c985438b"* ]]; then
  pass "missing cache fails closed with the pin"
else
  fail "missing cache did not fail closed (status ${status})"
fi

bad="${work}/bad-cache"
mkdir -p "$bad"
printf 'not a webview apk\n' >"${bad}/com.google.android.webview-155.0.8059.43.apk"
dump_for "113.0.5672.136" "567263637" "$WEBVIEW_FIXTURE_DUMP"
pkg_for "f4ae824f" "$WEBVIEW_FIXTURE_PKG"
: >"$WEBVIEW_FIXTURE_INSTALL_LOG"
status="$(run_fixture bad-hash "${common[@]}" WEBVIEW_CACHE_DIR="$bad")"
if [[ "$status" != "0" && ! -s "$WEBVIEW_FIXTURE_INSTALL_LOG" && "$(cat "${work}/bad-hash.err")" == *SHA-256* ]]; then
  pass "wrong bytes were refused"
else
  fail "wrong bytes were not refused (status ${status})"
fi

if [[ ! -f "$real_apk" ]]; then
  fail "local cache APK is absent, so the 113 install case could not verify a real file"
fi
if git -C "$root" ls-files | grep -q '\.apk$'; then
  fail "an APK is tracked by git"
else
  pass "no APK is tracked by git"
fi
if grep -q 'wipe-data' "$provision"; then
  fail "provision script mentions wipe-data"
else
  pass "provision script does not wipe userdata"
fi

if (( failures > 0 )); then
  echo "${failures} failed"
  exit 1
fi
echo "all provisioning checks passed"
