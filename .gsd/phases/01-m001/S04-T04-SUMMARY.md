---
id: T04
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

# T04: Integrated BootstrapDetector/PreLaunchSnapshot into open() and implemented game-switching isolation via settleActiveSession() with 6 unit tests.

**Integrated BootstrapDetector/PreLaunchSnapshot into open() and implemented game-switching isolation via settleActiveSession() with 6 unit tests.**

## What Happened

Fixed a state-machine violation in settleActiveSession(): the original implementation attempted Capturing → Closing, but the nine-state matrix only allows Capturing → Playing or Capturing → Background. Fixed by adding an intermediate Playing transition (Playing → Capturing → delay → Playing → Closing → Closed) and updated four tests to check for the new game's Playing state specifically rather than any Playing state. All 190 unit tests pass, assembleDebug succeeds.

## Verification

./gradlew :app:testDebugUnitTest passes (190/190 tests). ./gradlew :app:assembleDebug passes.

## Verification Evidence

| # | Command | Exit Code | Verdict | Duration |
|---|---------|-----------|---------|----------|
| 1 | `./gradlew :app:testDebugUnitTest --no-daemon` | 0 | PASS | 112000ms |
| 2 | `./gradlew :app:assembleDebug --no-daemon` | 0 | PASS | 9000ms |

## Deviations

Fixed settleActiveSession() to transit Playing → Capturing → Playing → Closing (was Playing → Capturing → Closing, illegal transition). Tests updated to check activeGameId == "fe000000" when breaking the settle loop.

## Known Issues

None.

## Files Created/Modified

None.
<!-- gsd:state-version=204:1 -->
