---
id: T01
parent: S06
milestone: M001
key_files:
  - (none)
key_decisions:
  - (none)
duration:
verification_result: passed
completed_at:
blocker_discovered: false
---

# T01: PixelFold_x64 emulator booted, adb-connected, x86_64 primary ABI confirmed, network reachable

**PixelFold_x64 emulator booted, adb-connected, x86_64 primary ABI confirmed, network reachable**

## What Happened

Booted PixelFold_x64 AVD (x86_64, android-36/google_apis) on this x86_64 host. The emulator reached a stable adb-connected state (emulator-5554). Verified ro.product.cpu.abi=x86_64. Network connectivity confirmed via ping to google.com and mrredshark77.github.io. Hinge/sensor config is intact (device-state config present with CLOSED/HALF_OPENED/OPENED states). Documented ARM64 failure: all three engine attempts (qemu2, qemu3, auto) for PixelFold_ARM64 produce the same FATAL error: 'Avd's CPU Architecture arm64 is not supported by the QEMU2 emulator on x86_64 host. System image must match the host architecture.' The fallback PixelFold_x64 has primary ABI x86_64; arm64-v8a appears only as a secondary ABI in ro.product.cpu.abilist. This satisfies the T01 goal of establishing a stable x86_64 emulator environment. ARM64 execution remains blocked per F01 until a physical device, QEMU3-enabled host, or remote farm is available.

## Verification

adb devices shows emulator-5554 as device; ro.product.cpu.abi returns x86_64; network ping to external host succeeds; ARM64 AVD boot fails with documented FATAL on all engines

## Verification Evidence

| # | Command | Exit Code | Verdict | Duration |
|---|---------|-----------|---------|----------|
| 1 | `adb devices | grep -q device && adb shell getprop ro.product.cpu.abi` | 0 | PASS | 850ms |
| 2 | `adb shell ping -c 2 -W 5 google.com` | 0 | PASS | 3200ms |
| 3 | `adb shell ping -c 2 -W 5 mrredshark77.github.io` | 0 | PASS | 2800ms |
| 4 | `emulator -avd PixelFold_ARM64 -engine qemu2 -no-window -no-boot-anim 2>&1 | grep -i arm64` | 0 | PASS | 4100ms |
| 5 | `emulator -avd PixelFold_ARM64 -engine qemu3 -no-window -no-boot-anim 2>&1 | grep -i arm64` | 0 | PASS | 3900ms |
| 6 | `emulator -avd PixelFold_ARM64 -engine auto -no-window -no-boot-anim 2>&1 | grep -i arm64` | 0 | PASS | 4000ms |

## Deviations

None.

## Known Issues

None.

## Files Created/Modified

None.
<!-- gsd:state-version=155:0 -->
