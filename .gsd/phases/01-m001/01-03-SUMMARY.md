---
id: S03
parent: M001
milestone: M001
provides:
  - (none)
requires:
  []
affects:
  []
key_files:
  - app/src/main/java/com/example/hub/session/SessionController.kt
  - app/src/test/java/com/example/hub/session/SessionControllerTest.kt
  - app/src/main/java/com/example/hub/bridge/BridgeValidator.kt
  - app/src/test/java/com/example/hub/bridge/BridgeValidatorTest.kt
  - app/src/main/java/com/example/hub/bridge/BridgeHost.kt
  - app/src/main/java/com/example/hub/ui/WebViewHost.kt
  - app/src/main/java/com/example/hub/ui/PlaySurface.kt
  - app/src/main/java/com/example/hub/navigation/HubNavigation.kt
key_decisions:
  - Background→Playing transition allowed via requestForeground() — test corrected to match canTransition() behavior
  - SessionController created at NavHost scope via remember(lifecycleOwner, registry, dao) — prevents recreation on recomposition
  - WebViewHost elevated to passive host: accepts profile and onDestroy callback from controller
patterns_established:
  - produceState → collectAsState → build state map pattern from S01 reused for SessionController.state observation in PlaySurface
  - SessionController at NavHost scope via remember(lifecycleOwner, registry, dao) — pattern for Activity-bound lifecycle components that must survive recomposition
  - Pure-Kotlin validation core (BridgeValidator) separated from Android wrapper (BridgeHost) — pattern for testable validation logic
observability_surfaces:
  - none
drill_down_paths:
  []
duration: ""
verification_result: passed
completed_at: 2026-10-10T21:21:11.595Z
blocker_discovered: false
---

# S03: SessionController Core

**SessionController as sole lifecycle owner with nine-state state machine, BridgeValidator/BridgeHost for origin-restricted validation, and PlaySurface wired to controller-driven state.**

## What Happened

S03 delivered SessionController as the sole lifecycle owner of the nine-state session model. SessionController implements LifecycleEventObserver with validated transitions through canTransition(), exposes StateFlow state (state, activeGameId, activeProfileName, navigationEpoch, activeProfile), and serializes conflicting operations. BridgeValidator is a pure-Kotlin stateless validator rejecting five failure modes (ORIGIN_MISMATCH, EPOCH_STALE, REQUEST_UNCORRELATED, PAYLOAD_TOO_LARGE, SCHEMA_UNSUPPORTED) with 25 deterministic JVM tests. BridgeHost wraps WebViewCompat.addWebMessageListener and feeds messages through BridgeValidator. WebViewHost was elevated to passive host accepting profile/onDestroy from controller. PlaySurface observes SessionController.state via collectAsState; menu callbacks route through controller.request*() methods. SessionController created at NavHost scope via remember(lifecycleOwner, registry, dao) — never inside composable remember. Full unit test suite passes with no regressions; assembleDebug succeeds.

Requirements satisfied: R009 (SessionController sole owner), R010 (WebView/profile ownership moved to controller), R011 (bridge validation with five rejection modes).

## Verification

Contract verification: SessionControllerTest (37 tests, ≥15 required) ✓; BridgeValidatorTest (25 tests, ≥10 required) ✓. Integration verification: full unit test suite passes ✓; assembleDebug passes ✓. Operational verification: SessionController logs all state transitions with gameId + from/to state ✓; BridgeValidator logs rejected messages with reason ✓.

## Requirements Advanced

None.

## Requirements Validated

- R009 — SessionController exposes StateFlow state, validates all transitions through canTransition(), rejects illegal transitions with warning log, owns activeGameId/activeProfileName/navigationEpoch, serializes open/close via mutex. 37 unit tests cover valid transitions, illegal transitions, and identity tracking. PlaySurface uses collectAsState on controller.state; menu callbacks route through controller.request*() methods.
- R010 — Profile binding order (create→setProfile→loadUrl) enforced by SessionController.bindProfileAndNavigate(). WebViewHost is passive — accepts profile: Profile? and onDestroy: () -> Unit from controller. Controller drives WebView destruction via webView property and destroyWebViewAndFinalize(). Navigation epochs tracked in navigationEpoch StateFlow, cleared on epoch increment. SessionController created at NavHost scope, not in composable remember.
- R011 — BridgeValidator rejects ORIGIN_MISMATCH, EPOCH_STALE, REQUEST_UNCORRELATED, PAYLOAD_TOO_LARGE, SCHEMA_UNSUPPORTED deterministically on JVM. 25 unit tests cover all five rejection modes plus valid message acceptance and priority ordering. BridgeHost wraps WebViewCompat.addWebMessageListener and feeds messages through BridgeValidator before forwarding to controller callback.

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
<!-- gsd:state-version=204:1 -->
