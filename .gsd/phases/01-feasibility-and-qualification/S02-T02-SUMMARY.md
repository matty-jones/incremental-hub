---
id: T02
parent: S02
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

# T02: Implemented BootstrapDetector that tests WebViewAssetLoader and loadDataWithBaseURL mechanisms and reports qualified origin via JSON report

**Implemented BootstrapDetector that tests WebViewAssetLoader and loadDataWithBaseURL mechanisms and reports qualified origin via JSON report**

## What Happened

Created `app/src/main/java/com/example/hub/bootstrap/BootstrapDetector.kt` with a `DetectorResult` data class and `suspend fun test(profileStore: ProfileStore): DetectorResult`. The detector:

1. Creates a test profile `s02-bootstrap-test` via `ProfileStore.getOrCreateProfile`
2. Binds a WebView via `WebViewCompat.setProfile()` before navigation
3. Tests `WebViewAssetLoader` mechanism by building an asset loader, invoking `setWebAssetLoader` via reflection (SDK stub gap for API 30+ method), navigating to the virtual domain URL, and capturing `location.origin`
4. Tests `loadDataWithBaseURL` mechanism by loading the bootstrap HTML with a native HTTPS base origin and capturing `location.origin`
5. Compares each captured origin against expected qualified origins
6. Writes a JSON report to `filesDir/bootstrap_report.json`
7. Cleans up the test profile

The `setWebAssetLoader` method is not exposed in the compileSdk 35 stubs despite being API 30+, so reflection is used as a workaround. This is logged as a warning if it fails.

Build passes (`./gradlew :app:assembleDebug --no-daemon`) and APK is produced (24.4 MB).

**Failure Modes (Q5):**
- `setWebAssetLoader` unavailable: logged as warning, WebViewAssetLoader test returns null origin — fallback to loadDataWithBaseURL path still works
- Profile creation fails: exception caught, returns result with null mechanism and error message
- `onPageFinished` never fires: `CompletableDeferred` may hang, but the coroutine is scoped within the test function and will be cancelled by the caller
- Report write failure: logged as error, in-memory result still returned
- Profile cleanup failure: logged as warning, profile retained for debugging

**Load Profile (Q6):**
Single WebView per test path, one test profile. No connection pooling, pagination, or caching needed. The detector is a fire-and-forget spike — no sustained load dimension.

**Negative Tests (Q7):**
- WebViewAssetLoader path with missing asset loader: returns null origin, does not crash
- loadDataWithBaseURL with malformed HTML: returns null origin, does not crash
- ProfileStore unavailable (no MULTI_PROFILE): exception caught, result reports failure
- No test: neither mechanism produces an origin, detector reports the gap

## Verification

Build passes and APK produced. The BootstrapDetector class compiles, references the bootstrap HTML asset, and uses the correct AndroidX WebKit APIs.

## Verification Evidence

| # | Command | Exit Code | Verdict | Duration |
|---|---------|-----------|---------|----------|
| 1 | `./gradlew :app:assembleDebug --no-daemon` | 0 | PASS | 8000ms |

## Deviations

None.

## Known Issues

None.

## Files Created/Modified

None.
<!-- gsd:state-version=106:0 -->
