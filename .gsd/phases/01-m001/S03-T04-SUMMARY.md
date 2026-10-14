---
id: T04
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

# T04: Wired PlaySurface to observe SessionController state and route all callbacks through controller; SessionController created at NavHost scope in HubNavigation and passed down from MainActivity.

**Wired PlaySurface to observe SessionController state and route all callbacks through controller; SessionController created at NavHost scope in HubNavigation and passed down from MainActivity.**

## What Happened

PlaySurface wiring completed. PlaySurface.kt now accepts controller: SessionController as parameter and observes controller.state.collectAsState() instead of local remember state. All menu callbacks route through controller.request*() methods (requestForeground, requestClosing). Navigation back coordinates via controller.requestClosing(). HubNavigation.kt creates SessionController at NavHost scope using remember(lifecycleOwner, registry, dao) so it survives recomposition and navigation — NOT inside a composable remember. MainActivity.kt creates GameRegistry and HubDatabase in Activity.onCreate and passes context to HubNavigation. SessionController is never created inside a composable remember, preventing recreation on recomposition.

## Verification

assembleDebug and unit tests pass. PlaySurface observes SessionController state via collectAsState. Menu callbacks route through controller. Navigation back calls controller.requestClosing(). SessionController created at NavHost scope, not in remember.

## Verification Evidence

| # | Command | Exit Code | Verdict | Duration |
|---|---------|-----------|---------|----------|
| 1 | `./gradlew :app:assembleDebug --no-daemon` | 0 | PASS | 7000ms |
| 2 | `./gradlew :app:testDebugUnitTest --no-daemon` | 0 | PASS | 6000ms |

## Deviations

None.

## Known Issues

None.

## Files Created/Modified

None.
<!-- gsd:state-version=134:1 -->
