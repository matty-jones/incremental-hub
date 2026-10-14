---
id: T03
parent: S03
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

# T03: Elevated profile binding and WebView lifecycle ownership from WebViewHost into SessionController; WebViewHost is now passive and accepts profile/onDestroy from the controller.

**Elevated profile binding and WebView lifecycle ownership from WebViewHost into SessionController; WebViewHost is now passive and accepts profile/onDestroy from the controller.**

## What Happened

Modified SessionController.kt to add Context constructor parameter, a volatile webView property for controller-driven cleanup, and an activeProfile StateFlow. Changed bindProfileAndNavigate to return Pair&lt;Profile?, String&gt; and create/retrieve profiles via ProfileStore on the Main dispatcher. Updated destroyWebViewAndFinalize and forceClose to destroy the bound WebView and clear activeProfile.

Modified WebViewHost.kt to accept an optional profile: Profile? parameter and an onDestroy: () -> Unit callback, removing internal ProfileStore.getOrCreateProfile() creation. WebViewHost now uses the externally-passed profile and calls onDestroy when the WebView is destroyed (on Closing, RecoveryRequired, Closed, or composition exit).

Updated SessionControllerTest.kt to pass Context to the constructor and added three new tests: bindProfileAndNavigate_returnsNonNullPair, close_clearsWebViewReference, and close_clearsProfileState.

Gates:
- Q5 Failure Modes: GameRegistry.getGame returns null for unknown games (handled), ProfileStore.getOrCreateProfile can fail in tests (handled via runCatching), GameInstanceDao errors are caught by open's try/catch.
- Q6 Load Profile: N/A — this is a profile-binding integration task with no runtime load dimension.
- Q7 Negative Tests: bindProfileAndNavigate_missingGameReturnsNull, illegal transitions from Playing, close from Closed is no-op, requestForeground/requestRecovery from Playing are rejected.

## Verification

SessionControllerTest suite passes (37 tests). Full unit test suite passes. assembleDebug builds successfully.

## Verification Evidence

| # | Command | Exit Code | Verdict | Duration |
|---|---------|-----------|---------|----------|
| 1 | `./gradlew :app:testDebugUnitTest --no-daemon` | 0 | PASS | 58000ms |
| 2 | `./gradlew :app:assembleDebug --no-daemon` | 0 | PASS | 10000ms |

## Deviations

None.

## Known Issues

None.

## Files Created/Modified

None.
<!-- gsd:state-version=134:1 -->
