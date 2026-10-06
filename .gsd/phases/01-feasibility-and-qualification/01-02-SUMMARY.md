---
id: S02
parent: M001
milestone: M001
provides:
  - (none)
requires:
  []
affects:
  []
key_files: []
key_decisions: []
patterns_established:
  - (none)
observability_surfaces:
  - none
drill_down_paths:
  []
duration: ""
verification_result: passed
completed_at: 2026-10-03T19:51:34.749Z
blocker_discovered: false
---

# S02: Native-Origin Bootstrap Spike

**Native HTML bootstrap document served from app assets loads at a qualified storage origin, reads existing localStorage, and captures a pre-launch snapshot envelope before remote game code executes.**

## What Happened

Four tasks delivered the native-origin bootstrap spike end-to-end. T01 created app/src/main/assets/bootstrap/native.html — a fully self-contained HTML document with inline JavaScript that reads all localStorage keys and entries, constructs a snapshot envelope, and posts it to the native side via window.webkit.messageHandlers.hub.postMessage(). T02 implemented BootstrapDetector.kt with a suspend fun test(profileStore) that tests both WebViewAssetLoader (primary) and loadDataWithBaseURL (fallback) mechanisms by creating a test profile, binding a WebView via WebViewCompat.setProfile() before navigation, capturing location.origin via evaluateJavascript, comparing against expected qualified origins, writing a JSON report to filesDir/bootstrap_report.json, and cleaning up the test profile. T03 implemented PreLaunchSnapshot.kt with a suspend fun capture(profileStore, detectorResult) that proves the R008 ordering invariant: writes a synthetic key, navigates to the bootstrap URL using the qualified mechanism, reads localStorage keys back, computes SHA-256 of the sorted key list, captures uncompressed byte count and timestamp, and returns a SnapshotResult with pass/fail indication. T04 created BootstrapScreen.kt (Compose UI with pass/fail badges and evidence cards), BootstrapActivity.kt (standalone Activity), and registered it in AndroidManifest.xml. Build passes, APK produced at 24.6 MB.

## Verification

Build verification: ./gradlew :app:assembleDebug --no-daemon exits 0 (all 4 tasks). APK produced at app/build/outputs/apk/debug/app-debug.apk (24.6 MB). All source files exist at expected paths. BootstrapActivity registered in AndroidManifest.xml. Both detector mechanisms use WebViewCompat.setProfile() before navigation. Pre-launch snapshot uses SHA-256 of sorted keys and reports uncompressed byte count. Task verification JSONs (S02-T01-VERIFY.json through S02-T04-VERIFY.json) all pass.

## Requirements Advanced

None.

## Requirements Validated

None.

## New Requirements Surfaced

None.

## Requirements Invalidated or Re-scoped

None.

## Operational Readiness

None.

## Deviations

None.

## Known Limitations

None.

## Follow-ups

None.

## Files Created/Modified

None.
<!-- gsd:state-version=106:0 -->
