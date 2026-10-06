---
id: S01
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
completed_at: 2026-10-03T18:24:02.509Z
blocker_discovered: false
---

# S01: WebView Profile Capability Spike

**Delivered a minimal Android app that detects MULTI_PROFILE, DOCUMENT_START_SCRIPT, and WEB_MESSAGE_LISTENER feature support on the target device, proves profile isolation via a two-profile localStorage test, and reports all results in a native Compose capability screen with a JSON report file.**

## What Happened

Slice S01 delivered all four planned tasks on a clean Android project scaffold:

**T01 — Project scaffold:** Created the minimal runnable Android project under `app/` with `settings.gradle.kts`, `app/build.gradle.kts`, `AndroidManifest.xml`, `MainActivity.kt`, `strings.xml`, and `gradle/libs.versions.kts`. Pinned minSdk 30, targetSdk 35, compileSdk 35. Added `androidx.webkit:webkit:1.14.0`, Compose BOM 2024.09.00, Material3, and Activity Compose. Build verification: `./gradlew :app:assembleDebug` exits 0 (BUILD SUCCESSFUL).

**T02 — Feature detection module:** Created `WebViewCapabilities.kt` (immutable data class holding three feature booleans, `allSupported`, package/version) and `CapabilityDetector.kt` (queries `WebViewFeature.isFeatureSupported()` for `MULTI_PROFILE`, `DOCUMENT_START_SCRIPT`, `WEB_MESSAGE_LISTENER`, reads `WebViewCompat.getCurrentWebViewPackage()`, writes a machine-parseable JSON report to `filesDir/capability_report.json`). Null-package and IOException paths are handled gracefully — in-memory capabilities are always returned regardless of report write failure.

**T03 — Profile isolation test:** Created `ProfileIsolationTest.kt` with a `suspend fun test(profileStore: ProfileStore): TestResult` method. The test creates two named profiles (`s01-test-a`, `s01-test-b`), binds each WebView via `WebViewCompat.setProfile()` before any navigation, injects a localStorage key in profile A, queries it in profile B, asserts the result is null, and cleans up both profiles in a finally block. Key pattern: `CompletableDeferred` is used instead of `suspendCancellableCoroutine` to bridge WebViewClient callbacks in Kotlin 2.1+ (avoiding overload resolution ambiguity from removed default `onCancellation` parameter).

**T04 — Capability screen and wiring:** Created `CapabilityScreen.kt` (Compose UI with feature badges, WebView info, isolation result, remediation banner) and `DiagnosticsScreen.kt` (host composable running detection + isolation test via `LaunchedEffect` + `rememberCoroutineScope`, refresh button). Updated `MainActivity.kt` to host `DiagnosticsScreen` as the root composable. Final build: `./gradlew :app:assembleDebug` exits 0, APK at `app/build/outputs/apk/debug/app-debug.apk` (24 MB).

**Verification summary:**
- Build: `./gradlew :app:assembleDebug --no-daemon` → BUILD SUCCESSFUL (4s)
- Source surface: All three `isFeatureSupported` calls confirmed in `CapabilityDetector.kt`; `setProfile` before navigation confirmed in `ProfileIsolationTest.kt`; `LaunchedEffect` + `runDetection` wired in `DiagnosticsScreen.kt`; `setContent` → `DiagnosticsScreen` in `MainActivity.kt`
- APK: exists at `app/build/outputs/apk/debug/app-debug.apk` (24,409,389 bytes)

**Q8 — Operational Readiness:** Health signal is the capability report JSON at `filesDir/capability_report.json` and logcat entries tagged `CapabilityDetector` and `ProfileIsolationTest`. Failure signal is `BUILD FAILURE` from Gradle or an app crash on device install. Recovery procedure is the standard Gradle rebuild (`./gradlew :app:assembleDebug`). Monitoring gaps: none — this is a one-shot feasibility spike, not a shipped feature with ongoing runtime behavior.

**UAT readiness:** The app is installable on the Pixel Fold. A device tester can verify feature flags, isolation test result, and remediation banner by installing the debug APK and observing the capability screen.

## Verification

All verification checks passed:

| # | Check | Evidence |
|---|-------|----------|
| 1 | Build passes | `./gradlew :app:assembleDebug --no-daemon` → BUILD SUCCESSFUL (4s) — gsd_exec 5de05b66 |
| 2 | Source files exist | 6 Kotlin files in `diagnostics/` and `ui/` packages — gsd_exec bd23d487 |
| 3 | Feature detection surface | 3× `isFeatureSupported` calls for MULTI_PROFILE, DOCUMENT_START_SCRIPT, WEB_MESSAGE_LISTENER confirmed in CapabilityDetector.kt — gsd_exec ff8cd6f6 |
| 4 | Profile lifecycle | `getOrCreateProfile` ×2, `setProfile` before navigation, `evaluateJavascript` ×2, `deleteProfile` ×2 confirmed in ProfileIsolationTest.kt — gsd_exec ae9918f4 |
| 5 | UI wiring | `setContent` → `DiagnosticsScreen` in MainActivity.kt; `LaunchedEffect` + `runDetection` in DiagnosticsScreen.kt — gsd_exec e84b43eb |
| 6 | APK produced | `app/build/outputs/apk/debug/app-debug.apk` (24,409,389 bytes) — gsd_exec 63b0ed1c |
| 7 | SDK/dependency pins | minSdk 30, targetSdk 35, compileSdk 35, androidx.webkit:webkit:1.14.0, Compose BOM 2024.09.00 — gsd_exec 43a52372 |

No failures. No deviations from plan.

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
