---
id: T02
parent: S04
milestone: M001
key_files:
  - app/src/main/java/com/example/hub/session/SessionController.kt
  - app/src/main/java/com/example/hub/session/SessionState.kt
  - app/src/test/java/com/example/hub/session/SessionStateTest.kt
key_decisions:
  - onAttached() must transit through Opening before Degraded/RecoveryRequired due to state machine constraints
duration:
verification_result: passed
completed_at:
blocker_discovered: false
---

# T02: Added process-death recovery via GameInstance.recoveryState/lastCaptureAtMillis columns, HubDatabase v2 migration, and cold-start DAO-backed state restoration in SessionController.onAttached().

**Added process-death recovery via GameInstance.recoveryState/lastCaptureAtMillis columns, HubDatabase v2 migration, and cold-start DAO-backed state restoration in SessionController.onAttached().**

## What Happened

T02 was previously interrupted (stale-worker) with implementation already committed. On resume, two tests failed: coldStart_readsDaoAndRestoresDegradedState and coldStart_readsDaoAndRestoresRecoveryRequiredState. Root cause: onAttached() attempted Closed→Degraded/RecoveryRequired directly, but canTransition rejects both. Fixed by (1) making onAttached() transit through Opening first, (2) adding Opening→Degraded to the canTransition matrix, and (3) updating SessionStateTest expectedTransition helper. All 175 unit tests pass; assembleDebug passes.

## Verification

All 175 unit tests pass. assembleDebug succeeds with no errors.

## Verification Evidence

| # | Command | Exit Code | Verdict | Duration |
|---|---------|-----------|---------|----------|
| 1 | `./gradlew :app:testDebugUnitTest --no-daemon` | 0 | PASS | 82000ms |
| 2 | `./gradlew :app:assembleDebug --no-daemon` | 0 | PASS | 6000ms |

## Deviations

None — implementation was already complete from prior attempt; only the transition-matrix fix and test update were needed.

## Known Issues

None

## Files Created/Modified

- `app/src/main/java/com/example/hub/session/SessionController.kt`
- `app/src/main/java/com/example/hub/session/SessionState.kt`
- `app/src/test/java/com/example/hub/session/SessionStateTest.kt`
<!-- gsd:state-version=204:1 -->
