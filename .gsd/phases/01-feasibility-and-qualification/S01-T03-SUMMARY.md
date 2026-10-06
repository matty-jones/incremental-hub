---
id: T03
parent: S01
milestone: M001
key_files:
  - app/src/main/java/com/example/hub/diagnostics/ProfileIsolationTest.kt
key_decisions:
  - Used CompletableDeferred instead of suspendCancellableCoroutine to avoid Kotlin 2.1 API signature changes that removed the default onCancellation parameter
duration:
verification_result: passed
completed_at:
blocker_discovered: false
---

# T03: Created ProfileIsolationTest with suspend test(profileStore) that creates two named profiles, writes localStorage in profile A, verifies it is invisible in profile B, and cleans up.

**Created ProfileIsolationTest with suspend test(profileStore) that creates two named profiles, writes localStorage in profile A, verifies it is invisible in profile B, and cleans up.**

## What Happened

Created app/src/main/java/com/example/hub/diagnostics/ProfileIsolationTest.kt containing TestResult data class and ProfileIsolationTest class. The test flow: (1) getOrCreateProfile s01-test-a and s01-test-b, (2) create WebViews bound via WebViewCompat.setProfile(), (3) navigate to about:blank, (4) inject localStorage.setItem in profile A via evaluateJavascript, (5) query localStorage.getItem in profile B, (6) verify result is null (isolation holds), (7) clean up both profiles via deleteProfile in finally block. Wrapped in try/catch returning TestResult(passed=false, message=exception.message) on failure. Used CompletableDeferred to bridge WebViewClient callbacks to coroutines, avoiding Kotlin 2.1 suspendCancellableCoroutine signature changes. Compilation and full assemble both pass.

## Verification

Kotlin compile and full assemble both pass. ProfileIsolationTest.kt created with correct API surface matching the task plan.

## Verification Evidence

| # | Command | Exit Code | Verdict | Duration |
|---|---------|-----------|---------|----------|
| 1 | `./gradlew :app:compileDebugKotlin --no-daemon` | 0 | PASS | 6684ms |
| 2 | `./gradlew :app:assembleDebug --no-daemon` | 0 | PASS | 7263ms |

## Deviations

Switched from suspendCancellableCoroutine to CompletableDeferred due to Kotlin 2.1 removing the default onCancellation parameter and requiring explicit type annotations that caused overload resolution ambiguity.

## Known Issues

None — test profiles are retained on failure per D004; cleanup runs in finally block on success.

## Files Created/Modified

- `app/src/main/java/com/example/hub/diagnostics/ProfileIsolationTest.kt`
<!-- gsd:state-version=106:0 -->
