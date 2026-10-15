---
id: T03
parent: S04
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

# T03: Added WebViewHost error callbacks (onReceivedError, onReceivedHttpError, onRenderProcessGone) wired to SessionController Degraded transitions, plus 9 unit tests.

**Added WebViewHost error callbacks (onReceivedError, onReceivedHttpError, onRenderProcessGone) wired to SessionController Degraded transitions, plus 9 unit tests.**

## What Happened

Implemented renderer crash detection for R014. WebViewHost now fires an onError callback from three WebViewClient hooks. SessionController.createErrorHandler() logs the error, calls requestDegraded(), and delegates to external observers. PlaySurface wires the handler. Added 9 tests: 5 in WebViewHostTest (error callback format, no-op default, gameId context) and 4 in SessionControllerTest (Degraded transition, no-op when not Playing, external callback, log propagation). Fixed deviation: onPageFailed does not exist in platform WebViewClient (API 35); replaced with onReceivedError + onReceivedHttpError + onRenderProcessGone. Both test and assembleDebug pass with no regressions.

## Verification

./gradlew :app:testDebugUnitTest --no-daemon (PASS, 80 tests total) and ./gradlew :app:assembleDebug --no-daemon (PASS). All 37 prior SessionControllerTest tests preserved.

## Verification Evidence

| # | Command | Exit Code | Verdict | Duration |
|---|---------|-----------|---------|----------|
| 1 | `./gradlew :app:testDebugUnitTest --no-daemon` | 0 | PASS | 40000ms |
| 2 | `./gradlew :app:assembleDebug --no-daemon` | 0 | PASS | 15000ms |

## Deviations

None.

## Known Issues

onRenderProcessGone returns true (handled-as-crash); RecoveryOverlay only shows after user action transitions Degraded→RecoveryRequired. killedByUser/dfsCode removed due to API 36 requirement vs project minSdk 30.

## Files Created/Modified

None.
<!-- gsd:state-version=204:1 -->
