# Pixel Fold emulator

The Android SDK and the host OS own the emulators. Incremental Hub only connects to a Pixel Fold virtual device that is already running. Creating, deleting, wiping, starting, stopping, or editing an AVD is not a step in this repository. Neither is installing a WebView provider.

## Devices

| AVD | Role |
| --- | --- |
| `PixelFold_API34` | The only device these scripts target. |
| `Pixel6_API34_PS` | Owned outside Incremental Hub. These scripts never select it and never change it. |

Other attached devices, including physical hardware, are not a fallback. Emulator images, AVD configuration, and WebView APKs stay off this repository. Local script logs and APKs under `scripts/android/` are gitignored.

## How a serial is chosen

`scripts/android/env.sh` lists adb devices in the `device` state and reads each emulator's AVD name. The emulator console command `avd name` is the identity. The boot property `ro.boot.qemu.avd_name` is that same name when the console command is unavailable. If both are present and they disagree, that device is not a match.

Exactly one running device must report `PixelFold_API34`. That device's adb serial is `ANDROID_SERIAL` for every later command. The port is not assumed, so `emulator-5556` is not special.

The scripts exit before doing any work when:

- `PixelFold_API34` is not running
- more than one running device reports that AVD name
- no device can be identified as that AVD

`adb` comes from `PATH`, or from `platform-tools` under `ANDROID_SDK_ROOT` or `ANDROID_HOME` when one of those is set. This repository does not install the SDK.

## WebView

Multi-profile support needs WebView major version 118 or later. `ProfileStore.getInstance()` throws `UnsupportedOperationException` on older providers. The API 34 Play image ships `com.google.android.webview` 113.0.5672.136, which is below that line.

`scripts/android/check-webview.sh` reads the current provider on the resolved Pixel Fold serial. It fails if the provider is missing or older than 118. It does not install, replace, or configure a provider. `run-instrumentation.sh` runs that check before it builds or installs the Hub. `trimps-qualify.sh` runs it before any qualification command.

Updating WebView is an external prerequisite, done on the AVD outside this project. It is not an application build step. Wiping the AVD restores the image's stock provider until that external update is done again. The historical install that put a newer provider on this AVD is recorded in `docs/expansion/emulator-webview-report.md`.

## Posture

`scripts/android/posture.sh` drives the hinge sensor on the already-running Pixel Fold. It does not call `wm size` to set a size. Folding turns one built-in display off and the other on. `wm size` changes as a result.

The console sensor is named `hinge-angle0`. `hinge_angle0` is rejected.

| Posture | Hinge | Device state | Active display | Size |
| --- | --- | --- | --- | --- |
| Unfolded | `hinge-angle0` 180 | 2 OPENED | inner | 2208 x 1840 px, 420 dpi |
| Folded | `hinge-angle0` 0 | 0 CLOSED | outer | 1080 x 2092 px, 420 dpi |
| Half open | `hinge-angle0` 90 | 1 HALF_OPENED | inner | 2208 x 1840 px, 420 dpi |

A `cmd device_state` override sticks across a later hinge change until `cmd device_state state reset`. `trimps-qualify.sh` resets that override at the end of the Trimps qualification, because that run moves the posture. Nothing else in these scripts writes emulator settings.

Differences from a physical Pixel Fold:

- The inner panel in this profile is landscape 2208 x 1840. The phone's inner panel is portrait 1840 x 2208.
- Density is 420 dpi for both panels. The phone is about 408 dpi on the cover and about 380 dpi on the inner panel.
- The cover resolution 1080 x 2092 matches the phone.

Layout viewport measured inside Shark, below the system bars:

- Unfolded: 842 x 645 CSS pixels
- Folded: 412 x 749 CSS pixels

## Commands

Run the scripts with bash. Each one resolves `PixelFold_API34` and passes that serial to every device command.

```bash
scripts/android/check-webview.sh
scripts/android/install-debug.sh
scripts/android/posture.sh unfold
scripts/android/posture.sh fold
scripts/android/posture.sh half
scripts/android/posture.sh status
scripts/android/display-metrics.sh
scripts/android/screenshot.sh shark-folded
scripts/android/logcat.sh shark
scripts/android/run-instrumentation.sh com.example.hub.SharkOfflineAcceptanceTest
scripts/android/run-instrumentation.sh com.example.hub.LivePlayableSessionTest
scripts/android/trimps-qualify.sh
scripts/android/device-target-test.sh
```

`SharkOfflineAcceptanceTest` is the Shark offline regression. `LivePlayableSessionTest` loads Shark Incremental and FE000000. `trimps-qualify.sh` runs `TrimpsQualificationTest`. Those three entry points install the debug Hub on the resolved Pixel Fold serial. They do not target `Pixel6_API34_PS`.

`device-target-test.sh` uses a fake adb. It checks serial discovery and the WebView gate, including a missing or outdated provider, without contacting a real device.

Screenshots and logcat captures go to `scripts/android/.logs/`.
