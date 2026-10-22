# Headless Pixel Fold emulator

The Pixel 6 AVD (`Pixel6_API34_PS`, adb serial `emulator-5554`) is left in place. The foldable AVD is separate.

## Host

- SDK: `/opt/android-sdk` (`ANDROID_SDK_ROOT`)
- Emulator 36.1.9, platform android-34, image `system-images;android-34;google_apis_playstore;x86_64` (already installed; nothing else was downloaded)
- KVM is present (`/dev/kvm`, VT-x) and `emulator -accel-check` reports KVM usable
- This user is in group `kvm`
- RAM about 46 GiB, disk free about 300 GiB on `/`

## AVD

Name: `PixelFold_API34`

```bash
avdmanager create avd -n PixelFold_API34 \
  -k "system-images;android-34;google_apis_playstore;x86_64" \
  -d pixel_fold
```

`pixel_fold` is a Google hardware profile in this SDK (`avdmanager list device`, id 50). It was not replaced with the generic 7.6" foldable.

Config adjustments in `~/.android/avd/PixelFold_API34.avd/config.ini`:

- `hw.ramSize=3072`
- `disk.dataPartition.size=6G`
- `hw.gpu.mode=swiftshader_indirect` and `hw.gpu.enabled=yes` so the emulator can render without a desktop
- `firstboot.bootFromDownloadableSnapshot=no`

The profile's own display values were not rewritten.

### Screen

Measured with the hinge sensor, not with `wm size` as an input:

| Posture | Hinge | Device state | Active display | Size |
| --- | --- | --- | --- | --- |
| Unfolded (boot default) | `hinge-angle0` 180 | 2 OPENED | inner `local:4619827259835644672` | 2208 x 1840 px, 420 dpi |
| Folded | `hinge-angle0` 0 | 0 CLOSED | outer `local:4619827551948147201` | 1080 x 2092 px, 420 dpi |
| Half open | `hinge-angle0` 90 | 1 HALF_OPENED | inner, same as unfolded | 2208 x 1840 px, 420 dpi |

Folding turns one built-in display off and the other on. `wm size` changes as a result. Setting `wm size` is not used and is not equivalent.

Differences from a physical Pixel Fold:

- The inner panel in this profile is landscape 2208 x 1840. The phone's inner panel is portrait 1840 x 2208.
- Density is 420 dpi for both panels. The phone is about 408 dpi on the cover and about 380 dpi on the inner panel.
- The cover resolution 1080 x 2092 matches the phone.
- The API 34 Play image ships `com.google.android.webview` 113.0.5672.136. That build throws `UnsupportedOperationException` from `ProfileStore.getInstance()` because multi-profile needs WebView 118. `PixelFold_API34` now has a user-installed provider `155.0.8059.43` (same package name, installed with `adb install -r -d`). Wiping userdata restores 113. `scripts/android/check-webview.sh` fails closed below major 118. The APK is not in this repository. `Pixel6_API34_PS` was not deleted or modified. See `docs/expansion/emulator-webview-report.md`.

Layout viewport measured inside Shark, below the system bars:

- Unfolded: 842 x 645 CSS pixels
- Folded: 412 x 749 CSS pixels

## Commands

Run the scripts with bash. They select serial `emulator-5556` and do not touch `emulator-5554`.

```bash
scripts/android/start-pixel-fold.sh
scripts/android/wait-ready.sh
scripts/android/install-debug.sh
scripts/android/posture.sh unfold
scripts/android/posture.sh fold
scripts/android/posture.sh status
scripts/android/display-metrics.sh
scripts/android/screenshot.sh shark-folded
scripts/android/logcat.sh shark
scripts/android/run-instrumentation.sh com.example.hub.SharkOfflineAcceptanceTest
scripts/android/stop-pixel-fold.sh
```

Startup flags: `-port 5556 -no-window -no-audio -no-boot-anim -no-snapshot-load -gpu swiftshader_indirect -accel on`. Boot timeout defaults to 240 seconds. `setsid -f` keeps the process after the launching shell exits.

`posture.sh fold` runs `adb emu sensor set hinge-angle0 0`. The sensor name is `hinge-angle0`. `hinge_angle0` is rejected. `cmd device_state state 0` also switches the physical displays; the scripts use the hinge so the posture stays sensor-driven.
