# S06: Remediation: S04 Integrated Flow Re-run on PixelFold_x64

**One-liner:** Integrated S04 flow verified on PixelFold_x64 (x86_64) with all invariants passing.

## Goal

Fix and verify integrated S04 flow on PixelFold_x64, proving data-preservation and round-trip invariants with AndroidX WebView profiles.

## Demo

S04 integrated play-capture-restore spike completes successfully on PixelFold_x64 (x86_64) with profileDataSurvived=true and importVerified=true.

## What Was Done

### T01: Establish PixelFold_x64 environment
- Booted PixelFold_x64 AVD (x86_64, android-36, 6.7in Foldable skin)
- Confirmed adb-connected stable state, ro.product.cpu.abi=x86_64
- Verified network connectivity to mrredshark77.github.io
- Documented ARM64 failure: QEMU2 emulator on x86_64 host cannot run arm64 AVDs

### T02: Build, install, run integrated spike
- Fixed pre-run profile cleanup in IntegratedSpike.kt
- Updated S06 plan title to remove stale ARM64 reference
- Built and installed debug APK on PixelFold_x64
- Ran integrated spike from fresh process
- All 12 stages passed, capturedSaveLength=8400 (real Shark save)

### T03: Document results and close S06
- This summary

## Key Fix: Pre-run Profile Cleanup

AndroidX `ProfileStore.deleteProfile()` cannot delete a profile after it has been loaded into memory via `getOrCreateProfile()` or `getProfile()`. The fix adds pre-run cleanup before loading:

```kotlin
val existingProfiles = profileStore.getAllProfileNames()
if (existingProfiles.contains(SOURCE_PROFILE)) {
    profileStore.deleteProfile(SOURCE_PROFILE)
}
if (existingProfiles.contains(FRESH_PROFILE)) {
    profileStore.deleteProfile(FRESH_PROFILE)
}
```

This ensures `s04-fresh` is actually fresh for every independently started spike run.

## Verification Results

- passed=true
- destroyedWebView=true
- profileDataSurvived=true
- importVerified=true
- capturedSaveLength=8400
- blocker=null
- Pre-run cleanup confirmed in logcat for both profiles
- Unit tests: BUILD SUCCESSFUL
- assembleDebug: BUILD SUCCESSFUL

## x86_64-Specific Observations

- WebView 133 on PixelFold_x64 supports MULTI_PROFILE, DOCUMENT_START_SCRIPT, WEB_MESSAGE_LISTENER
- Shark Incremental URL: https://mrredshark77.github.io/shark-incremental/
- Real save() trigger works deterministically after game readiness
- Post-run profile deletion warning ("Cannot delete in-use profile") is expected AndroidX limitation

## ARM64 History Preserved

Prior ARM64 attempts failed with QEMU2 error: "Avd's CPU Architecture 'arm64' is not supported by the QEMU2 emulator on x86_64 host." This is a hard limitation documented in S01/T01. ARM64 verification deferred to physical device or QEMU3-enabled host.

## Files Modified

- app/src/main/java/com/example/hub/integrated/IntegratedSpike.kt (pre-run cleanup fix)
- .gsd/phases/01-feasibility-and-qualification/01-06-PLAN.md (title update)
- app/src/debug/AndroidManifest.xml (debug-only IntegratedSpikeActivity export)