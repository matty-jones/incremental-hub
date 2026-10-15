---
id: T01
parent: S01
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

# T01: Added Room 2.6.1 persistence layer with GameInstance entity, GameInstanceDao, HubDatabase singleton, and 6 passing DAO unit tests

**Added Room 2.6.1 persistence layer with GameInstance entity, GameInstanceDao, HubDatabase singleton, and 6 passing DAO unit tests**

## What Happened

Added Room dependencies (room-runtime, room-ktx, room-testing, KSP) to app/build.gradle.kts. Created GameInstance entity with narrow R002 schema (gameId PK, profileName, sessionIdentity, lastPlayedAtMillis, resumeToken nullable). Created GameInstanceDao with getAllOrdered Flow, suspend get/upsert/delete. Created HubDatabase singleton. Added GameInstanceDaoTest with 6 tests covering upsert, get, delete, ordering by lastPlayedAtMillis DESC, and empty-table edge case. Fixed gradle.properties JDK to Java 17 (required because Ubuntu Java 21/25 packages omit jlink, which AGP 8.5.2 needs for the JDK image transform). All builds and tests pass with no regressions.

## Verification

Build compiles; all 6 DAO unit tests pass; no regressions to existing GameDefinitionValidatorTest

## Verification Evidence

| # | Command | Exit Code | Verdict | Duration |
|---|---------|-----------|---------|----------|
| 1 | `./gradlew :app:assembleDebug --no-daemon` | 0 | PASS | 11000ms |
| 2 | `./gradlew :app:testDebugUnitTest --tests com.example.hub.database.GameInstanceDaoTest --no-daemon` | 0 | PASS | 12000ms |
| 3 | `./gradlew :app:testDebugUnitTest --tests com.example.hub.games.GameDefinitionValidatorTest --no-daemon` | 0 | PASS | 11000ms |
| 4 | `./gradlew :app:testDebugUnitTest --no-daemon` | 0 | PASS | 7000ms |

## Deviations

None.

## Known Issues

None.

## Files Created/Modified

None.
<!-- gsd:state-version=204:1 -->
