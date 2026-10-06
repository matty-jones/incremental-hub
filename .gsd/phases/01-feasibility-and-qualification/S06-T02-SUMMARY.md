# S06-T02: Integrated Spike Verification on PixelFold_x64

**One-liner:** Integrated spike passes all invariants on PixelFold_x64 with pre-run profile cleanup fix.

## What Was Done

1. Updated S06 plan title from "ARM64" to "PixelFold_x64" to reflect approved environment.
2. Fixed IntegratedSpike.kt to add pre-run profile cleanup: inspects `profileStore.getAllProfileNames()` before `getOrCreateProfile()` and deletes any stale `s04-source` or `s04-fresh` profiles.
3. Built debug APK and installed on PixelFold_x64 emulator (emulator-5554, x86_64, API 34).
4. Ran integrated spike from fresh app process. Verified logcat confirms pre-run cleanup ran for both profiles.
5. All 12 stages passed. Report: passed=true, destroyedWebView=true, profileDataSurvived=true, importVerified=true, capturedSaveLength=8400 (real Shark save), blocker=null.
6. Post-run profile deletion warning ("Cannot delete in-use profile") is expected AndroidX ProfileStore limitation — does not affect correctness.
7. Unit tests and assembleDebug pass.

## Key Fix

AndroidX `ProfileStore.deleteProfile()` cannot delete a profile after it has been loaded into memory. The fix deletes stale test profiles BEFORE calling `getOrCreateProfile()`, ensuring each independently started spike run starts with fresh profiles.

## Evidence

- Report: `/data/user/0/com.example.hub/files/integrated_spike_report.json`
- Logcat: `Pre-run cleanup: deleting stale source profile 's04-source'` and `Pre-run cleanup: deleting stale fresh profile 's04-fresh'`
- Build: `./gradlew assembleDebug` BUILD SUCCESSFUL
- Tests: `./gradlew testDebugUnitTest` BUILD SUCCESSFUL

## GSD Lifecycle Note

Task T02 has a stale failed attempt (2667f92d) from prior compaction. Verification passed on fresh run. Proceeding to T03 documentation and slice completion.
<!-- gsd:state-version=160:0 -->
