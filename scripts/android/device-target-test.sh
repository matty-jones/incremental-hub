#!/usr/bin/env bash
# Hermetic checks for Pixel Fold serial discovery and the read-only WebView gate.
# A fake adb stands in for every device. This script does not touch a real emulator.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "${here}/../.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

failures=0
pass() { echo "PASS $*"; }
fail() { echo "FAIL $*"; failures=$((failures + 1)); }

cat >"${work}/adb" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"${IH_FAKE_LOG:?}"
if [[ "${1:-}" == "devices" ]]; then
  cat "${IH_FAKE_ROOT:?}/devices"
  exit 0
fi
if [[ "${1:-}" != "-s" ]]; then
  echo "unexpected adb: $*" >&2
  exit 1
fi
serial="$2"
shift 2
cmd="${1:-}"
shift || true
if [[ "$cmd" == "emu" && "${1:-}" == "avd" && "${2:-}" == "name" ]]; then
  if [[ -f "${IH_FAKE_ROOT}/console/${serial}" ]]; then
    cat "${IH_FAKE_ROOT}/console/${serial}"
    exit 0
  fi
  echo "error: not an emulator" >&2
  exit 1
fi
if [[ "$cmd" == "shell" ]]; then
  text="$*"
  if [[ "$text" == "getprop ro.boot.qemu.avd_name" ]]; then
    if [[ -f "${IH_FAKE_ROOT}/prop/${serial}" ]]; then
      cat "${IH_FAKE_ROOT}/prop/${serial}"
    fi
    exit 0
  fi
  if [[ "$text" == *"dumpsys webviewupdate"* ]]; then
    cat "${IH_FAKE_ROOT}/webview/${serial}"
    exit 0
  fi
  if [[ "$text" == echo* ]]; then
    printf '%s\n' "${text#echo }"
    exit 0
  fi
fi
echo "unexpected adb: -s ${serial} ${cmd} $*" >&2
exit 1
EOF
chmod +x "${work}/adb"

fixture="${work}/fixture"
log="${work}/adb.log"
export IH_ADB_DIR="${work}"
export IH_FAKE_ROOT="${fixture}"
export IH_FAKE_LOG="${log}"

begin() {
  rm -rf "$fixture"
  mkdir -p "${fixture}/console" "${fixture}/prop" "${fixture}/webview"
  printf 'List of devices attached\n' >"${fixture}/devices"
  : >"$log"
}

add_device() {
  local serial="$1" name="${2:-}" state="${3:-device}"
  printf '%s\t%s\n' "$serial" "$state" >>"${fixture}/devices"
  if [[ -n "$name" ]]; then
    printf '%s\r\nOK\r\n' "$name" >"${fixture}/console/${serial}"
    printf '%s\r\n' "$name" >"${fixture}/prop/${serial}"
  fi
}

webview_dump() {
  local serial="$1" version="$2"
  if [[ "$version" == "none" ]]; then
    printf 'Current WebView package is null\n' >"${fixture}/webview/${serial}"
    return
  fi
  cat >"${fixture}/webview/${serial}" <<EOF
Current WebView Update Service state
  Current WebView package (name, version): (com.google.android.webview, ${version})
  Preferred WebView package (name, version): (com.google.android.webview, ${version})
EOF
}

run_source() {
  local preset="${1:-}"
  local out="${work}/out" err="${work}/err"
  set +e
  if [[ -n "$preset" ]]; then
    ANDROID_SERIAL="$preset" bash -c 'source "$1"; printf "%s\n" "$ANDROID_SERIAL"' bash "${here}/env.sh" >"$out" 2>"$err"
  else
    bash -c 'unset ANDROID_SERIAL; source "$1"; printf "%s\n" "$ANDROID_SERIAL"' bash "${here}/env.sh" >"$out" 2>"$err"
  fi
  local status=$?
  set -e
  printf '%s\n' "$status"
}

run_check() {
  local out="${work}/out" err="${work}/err"
  set +e
  bash -c 'unset ANDROID_SERIAL; exec "$1"' bash "${here}/check-webview.sh" >"$out" 2>"$err"
  local status=$?
  set -e
  printf '%s\n' "$status"
}

assert_serial() {
  local name="$1" expected="$2" preset="${3:-}"
  local status
  status="$(run_source "$preset")"
  if [[ "$status" == "0" && "$(cat "${work}/out")" == "$expected" && "$(cat "$log")" != *install* ]]; then
    pass "$name -> ${expected}"
  else
    fail "$name (status ${status}, out $(cat "${work}/out" 2>/dev/null || true))"
    cat "${work}/err" >&2 || true
  fi
}

assert_refuse() {
  local name="$1" needle="$2"
  local status
  status="$(run_source)"
  local err
  err="$(cat "${work}/err")"
  if [[ "$status" != "0" && "$err" == *"$needle"* && "$err" == *"will not use another device"* && "$(cat "$log")" != *install* && "$(cat "$log")" != *dumpsys\ webviewupdate* ]]; then
    pass "$name"
  else
    fail "$name (status ${status})"
    printf '%s\n' "$err" >&2
  fi
}

begin
add_device emulator-5554 Pixel6_API34_PS
add_device emulator-5560 PixelFold_API34
add_device 192.168.86.75:5555
assert_serial "both emulators and an unidentified device" emulator-5560
assert_serial "pre-set Pixel 6 serial is ignored" emulator-5560 emulator-5554

begin
add_device emulator-5556 Pixel6_API34_PS
add_device emulator-5554 PixelFold_API34
assert_serial "serial follows the AVD name when ports are swapped" emulator-5554

begin
add_device emulator-5554 Pixel6_API34_PS
assert_refuse "only Pixel 6" "PixelFold_API34 is not running"

begin
add_device emulator-5556 Pixel6_API34_PS
assert_refuse "Pixel 6 on port 5556 is not the fold" "PixelFold_API34 is not running"

begin
add_device emulator-5572 PixelFold_API34
assert_serial "only Pixel Fold" emulator-5572

begin
add_device emulator-5556 PixelFold_API34
add_device emulator-5558 PixelFold_API34
status="$(run_source)"
err="$(cat "${work}/err")"
if [[ "$status" != "0" && "$err" == *"More than one running device"* && "$err" == *"emulator-5556"* && "$err" == *"emulator-5558"* && "$err" == *"Refusing to choose"* ]]; then
  pass "two Pixel Fold identities are ambiguous"
else
  fail "two Pixel Fold identities (status ${status})"
  printf '%s\n' "$err" >&2
fi

begin
add_device emulator-5554 Pixel6_API34_PS
add_device emulator-5599 PixelFold_API34 offline
status="$(run_source)"
err="$(cat "${work}/err")"
if [[ "$status" != "0" && "$err" == *"is not running"* && "$(cat "${work}/out")" != *emulator-5554* ]]; then
  pass "offline Pixel Fold is not a device"
else
  fail "offline Pixel Fold (status ${status})"
  printf '%s\n' "$err" >&2
fi

begin
printf 'emulator-5600\tdevice\n' >>"${fixture}/devices"
printf 'PixelFold_API34\n' >"${fixture}/prop/emulator-5600"
assert_serial "boot property used when the console identity is missing" emulator-5600

begin
printf 'emulator-5602\tdevice\n' >>"${fixture}/devices"
printf 'PixelFold_API34\r\nOK\r\n' >"${fixture}/console/emulator-5602"
assert_serial "console identity used when the boot property is missing" emulator-5602

begin
printf 'emulator-5604\tdevice\n' >>"${fixture}/devices"
printf 'PixelFold_API34\r\nOK\r\n' >"${fixture}/console/emulator-5604"
printf 'Pixel6_API34_PS\n' >"${fixture}/prop/emulator-5604"
status="$(run_source)"
err="$(cat "${work}/err")"
if [[ "$status" != "0" && "$err" == *"conflicting AVD identity"* && "$err" == *"is not running"* && "$err" == *"will not use another device"* ]]; then
  pass "conflicting identity is not treated as Pixel Fold"
else
  fail "conflicting identity (status ${status})"
  printf '%s\n' "$err" >&2
fi

begin
add_device emulator-5560 PixelFold_API34
webview_dump emulator-5560 155.0.8059.43
status="$(run_check)"
if [[ "$status" == "0" && "$(cat "${work}/out")" == *"emulator-5560"* && "$(cat "${work}/out")" == *"155.0.8059.43"* && "$(cat "$log")" != *install* ]]; then
  pass "WebView 155 is accepted read-only"
else
  fail "WebView 155 (status ${status})"
  cat "${work}/err" >&2 || true
fi

begin
add_device emulator-5560 PixelFold_API34
webview_dump emulator-5560 118.0.0.0
status="$(run_check)"
if [[ "$status" == "0" && "$(cat "${work}/out")" == *"118.0.0.0"* && "$(cat "$log")" != *install* ]]; then
  pass "WebView 118 is the minimum"
else
  fail "WebView 118 (status ${status})"
  cat "${work}/err" >&2 || true
fi

begin
add_device emulator-5560 PixelFold_API34
webview_dump emulator-5560 113.0.5672.136
status="$(run_check)"
err="$(cat "${work}/err")"
if [[ "$status" != "0" && "$err" == *"113.0.5672.136"* && "$err" == *"118"* && "$err" == *"does not install or replace"* && "$(cat "$log")" != *install* ]]; then
  pass "WebView 113 fails closed without installing"
else
  fail "WebView 113 (status ${status})"
  printf '%s\n' "$err" >&2
fi

begin
add_device emulator-5560 PixelFold_API34
webview_dump emulator-5560 117.9.9
status="$(run_check)"
err="$(cat "${work}/err")"
if [[ "$status" != "0" && "$err" == *"117.9.9"* && "$err" == *"does not install or replace"* && "$(cat "$log")" != *install* ]]; then
  pass "WebView 117 is outdated"
else
  fail "WebView 117 (status ${status})"
  printf '%s\n' "$err" >&2
fi

begin
add_device emulator-5560 PixelFold_API34
webview_dump emulator-5560 none
status="$(run_check)"
err="$(cat "${work}/err")"
if [[ "$status" != "0" && "$err" == *"no current WebView provider"* && "$err" == *"does not install or replace"* && "$(cat "$log")" != *install* ]]; then
  pass "missing WebView fails closed without installing"
else
  fail "missing WebView (status ${status})"
  printf '%s\n' "$err" >&2
fi

begin
add_device emulator-5554 Pixel6_API34_PS
webview_dump emulator-5554 155.0.8059.43
status="$(run_check)"
err="$(cat "${work}/err")"
if [[ "$status" != "0" && "$err" == *"is not running"* && "$err" == *"Pixel6_API34_PS"* && "$(cat "$log")" != *dumpsys\ webviewupdate* && "$(cat "$log")" != *install* ]]; then
  pass "a compatible provider on the Pixel 6 is not used"
else
  fail "Pixel 6 provider was considered (status ${status})"
  printf '%s\n' "$err" >&2
fi

for gone in provision-webview.sh provision-webview-test.sh webview-provider.pin start-pixel-fold.sh stop-pixel-fold.sh wait-ready.sh; do
  if [[ -e "${here}/${gone}" ]]; then
    fail "${gone} is still present"
  else
    pass "${gone} is removed"
  fi
done

if grep -E 'wipe-data|avdmanager|emu kill|emulator -avd|settings put global' \
  "${here}/env.sh" "${here}/check-webview.sh" "${here}/install-debug.sh" \
  "${here}/run-instrumentation.sh" "${here}/posture.sh" "${here}/screenshot.sh" \
  "${here}/logcat.sh" "${here}/display-metrics.sh" "${here}/trimps-qualify.sh" \
  >/dev/null; then
  fail "a retained script still manages the emulator or its settings"
else
  pass "retained scripts do not start, stop, wipe, or retune the emulator"
fi

if git -C "$root" ls-files | grep -q '\.apk$'; then
  fail "an APK is tracked by git"
else
  pass "no APK is tracked by git"
fi

if (( failures > 0 )); then
  echo "${failures} failed"
  exit 1
fi
echo "all device-target checks passed"
