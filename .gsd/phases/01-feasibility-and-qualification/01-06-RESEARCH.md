# S06 Research: Remediation: S04 Integrated Flow Re-run on ARM64

## Slice Goal
S04 integrated play-capture-restore spike completes successfully on ARM64 emulator or Pixel Fold with `profileDataSurvived=true` and `importVerified=true`.

## Current State Assessment

### Codebase (Complete)
The S04 integrated spike code is fully implemented and builds successfully. No code changes are expected for S06 — this is a **verification/re-run slice**, not an implementation slice.

**Key files:**
- `app/src/main/java/com/example/hub/integrated/IntegratedSpike.kt` — Core spike orchestrator
- `app/src/main/java/com/example/hub/integrated/IntegratedSpikeResult.kt` — Result data classes
- `app/src/main/java/com/example/hub/IntegratedSpikeActivity.kt` — Activity host
- `app/src/main/java/com/example/hub/ui/IntegratedSpikeScreen.kt` — Compose UI
- `app/src/main/java/com/example/hub/games/SharkIncrementalAdapter.kt` — Shark game adapter
- `app/src/main/java/com/example/hub/games/Fe000000Adapter.kt` — FE000000 game adapter
- `app/src/main/java/com/example/hub/games/GameAdapter.kt` — Interface contract
- `app/src/main/java/com/example/hub/games/GameQualifier.kt` — Qualification harness

### Build Status
- Build: **BUILD SUCCESSFUL** (36 actionable tasks, up-to-date)
- APK: `app/build/outputs/apk/debug/app-debug.apk` (24.8 MB)
- APK includes `lib/arm64-v8a/libandroidx.graphics.path.so` — ARM64 native libs are present
- No custom native code (C/C++) — pure Kotlin/Java, so no NDK cross-compilation needed

### ARM64 Environment
- **ARM64 system image available**: `android-34;google_apis;arm64-v8a` at `/home/blasky/android-sdk/system-images/android-34/google_apis/arm64-v8a/`
- **Pixel Fold skin available**: `/home/blasky/android-sdk/skins/pixel_fold/` (with `closed` and `default` variants)
- **Existing AVDs** (all x86_64):
  - `HappiestBabyEmulator` — android-34, google_apis, x86_64
  - `Pixel_8a_2` — android-36, google_apis, x86_64
  - `Pixel_8a_3` — android-36, google_apis, x86_64
- **No ARM64 AVD exists** — must be created
- **Emulator binary**: `/home/blasky/android-sdk/emulator/emulator` (supports ARM64 images)
- **No physical device connected** — must use ARM64 emulator

### S04 Previous Run
S04 was previously verified on an x86_64 emulator (emulator-5554). The spike proved:
1. Profile creation and WebView binding
2. Game readiness detection (Shark Incremental `player` JS global)
3. Save capture via `shark_inc_save` localStorage key
4. WebView.destroy() + data survival verification
5. Fresh profile import and round-trip verification

S06 repeats this on ARM64 to prove the same invariants hold on the target ABI.

## Natural Seams

### Seam 1: ARM64 AVD Setup
Independent of build/test. Creates the ARM64 emulation target.

### Seam 2: Build + Install + Run Spike
Depends on Seam 1 (needs running emulator). Runs the integrated spike and captures results.

### Seam 3: Results Documentation
Depends on Seam 2. Verifies JSON report and closes out S06.

## First Proof
The highest-risk path is **Seam 2**: getting the ARM64 emulator booted, installing the APK, and running the integrated spike to produce `profileDataSurvived=true` and `importVerified=true`. If the ARM64 emulator has WebView feature gaps (MULTI_PROFILE, WEB_MESSAGE_LISTENER, DOCUMENT_START_SCRIPT), the spike will fail at the profile creation or WebView binding stage.

## Implementation Landscape

### Task 1: Create ARM64 AVD
- Use `avdmanager create avd` with `android-34;google_apis;arm64-v8a` system image
- Target Pixel Fold device (device ID 62 or 59 — "7.6in Foldable" or "6.7in Foldable")
- Force flag to skip interactive prompts
- Verify with `emulator -list-avds`
- Boot the emulator and confirm `ro.product.cpu.abi` returns `arm64-v8a`

### Task 2: Build, Install, Run Spike, Verify
- Build debug APK: `./gradlew :app:assembleDebug`
- Install on ARM64 emulator: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- Launch IntegratedSpikeActivity: `adb shell am start -n com.example.hub/.IntegratedSpikeActivity`
- Wait for spike to complete (~60s: 30s poll for autosave + verification)
- Pull JSON report: `adb pull /data/user/0/com.example.hub/files/integrated_spike_report.json`
- Verify `profileDataSurvived: true` and `importVerified: true` in the JSON
- Also verify `destroyedWebView: true`

### Task 3: Document Results
- Save S06 summary with verification evidence
- Note any ARM64-specific deviations or observations
- Mark S06 complete

## Verification Commands

### ARM64 Emulator Confirmation
```bash
adb devices
adb shell getprop ro.product.cpu.abi   # must return "arm64-v8a"
adb shell getprop ro.hardware          # should show arm-based hardware
```

### Build Verification
```bash
./gradlew :app:assembleDebug
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep "lib/arm64"  # should show native libs
```

### Spike Run Verification
```bash
adb shell am start -n com.example.hub/.IntegratedSpikeActivity
# Wait ~60-90s for spike to complete
adb pull /data/user/0/com.example.hub/files/integrated_spike_report.json
cat integrated_spike_report.json  # verify profileDataSurvived=true, importVerified=true
```

### Logcat for Debugging
```bash
adb logcat -s IntegratedSpike:V AndroidRuntime:E
```

## Risks and Watch-outs

1. **ARM64 emulator boot time**: ARM64 emulation on x86_64 host uses QEMU translation — boot may take 2-3x longer than x86_64. Plan for 2-3 minute boot.

2. **WebView feature support on ARM64 emulator**: The ARM64 system image (android-34/google_apis/arm64-v8a) may have a different WebView version than the x86_64 image. If MULTI_PROFILE or WEB_MESSAGE_LISTENER is unavailable, the spike will fail at profile creation. Check with `adb shell am broadcast -a android.webkit.action.PROFILE_STORE_CHANGED` or run the CapabilityDetector screen first.

3. **Network access**: The spike navigates to `https://mrredshark77.github.io` (Shark Incremental). The emulator must have network connectivity. Check with `adb shell ping -c 1 mrredshark77.github.io`.

4. **Synthetic save fallback**: If the game doesn't load within 10s, the spike injects a synthetic save (`eyJWRVJTSU9OIjozLCJnb2xkIjoxMDAwLCJjbGlja3MiOjUwfQ==`). This is a valid fallback — the data-survival invariant still holds with synthetic data.

5. **Profile cleanup**: The spike cleans up test profiles in a finally block. If the emulator restarts mid-spike, profiles may leak — this is acceptable for a spike.

## Files to Create/Modify

### S06 Planning Files (GSD)
- `.gsd/phases/01-feasibility-and-qualification/slices/S06/PLAN.md` — Slice plan
- `.gsd/phases/01-feasibility-and-qualification/slices/S06/tasks/T01-PLAN.md` — ARM64 AVD setup
- `.gsd/phases/01-feasibility-and-qualification/slices/S06/tasks/T02-PLAN.md` — Build/install/run/verify
- `.gsd/phases/01-feasibility-and-qualification/slices/S06/tasks/T03-PLAN.md` — Documentation

### No Source File Changes Expected
The research finds no code changes needed for ARM64 compatibility. The slice is purely operational: create AVD, run spike, verify results.

## Forward Intelligence for S07

S07 (Device-Side UAT on Pixel Fold) will need:
- A physical Pixel Fold device (not emulator)
- Signed build installation
- Manual fold/unfold/rotate testing
- Human verification of bootstrap ordering

S06 ARM64 emulator results provide the automated foundation; S07 adds the physical device human verification layer.

## Sources
- AndroidX WebKit 1.14.0 Profile API docs
- Android emulator ARM64 emulation documentation
- Project build.gradle.kts (compileSdk 35, minSdk 30, no abiFilters)
- S04 summary (previous x86_64 verification)
- S05 summary (MockContext syntax fix, validator tests)
