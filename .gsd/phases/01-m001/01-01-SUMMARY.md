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
completed_at: 2026-10-08T21:18:15.528Z
blocker_discovered: false
---

# S01: Game Registry and Launcher

**Game registry loads validated definitions, Room persistence stores game instances, Material 3 launcher renders cards with resume/launch actions, and navigation wires launcher to play surface.**

## What Happened

S01 delivered the foundational launcher layer for the Playable Hub. Four tasks completed successfully:

T01 — Room Persistence Layer: Added Room 2.6.1 with KSP 2.1.0, created GameInstance entity (gameId PK, profileName, sessionIdentity, lastPlayedAtMillis, resumeToken nullable), GameInstanceDao with getAllOrdered Flow and suspend upsert/get/delete, HubDatabase singleton. Fixed JDK to Java 17 for AGP compatibility. Added 6 DAO unit tests.

T02 — GameRegistry: Created RegisteredGame data class and GameRegistry that reads assets/games/*.json, validates with existing GameDefinitionValidator, caches validated list. Exposes getGames() (ordered by last-played via DAO, alphabetical fallback), getGame(), allDefinitions. Added GameRegistryTest (2 tests: known definitions resolve, invalid JSON rejected, ordering deterministic).

T03 — LauncherScreen: Created Material 3 composable with Scaffold+LazyColumn of Card items showing displayName, Resume/Launch status chip, action buttons. Recently-played-first ordering via DAO. Color hints from displayDefaults. Uses produceState for DAO Flow collection — no ViewModel per S01 constraint.

T04 — Navigation Wiring: Created HubNavigation with two-route Navigation Compose graph (launcher, play_surface/{gameId}). Created PlaySurfaceStub as S02 placeholder. Updated MainActivity to host HubNavigation. Added navigation-compose 2.7.7. Fixed local.properties sdk.dir.

Verification: assembleDebug PASS, GameRegistryTest (2/2) PASS, GameInstanceDaoTest (6/6) PASS, GameDefinitionValidatorTest (4/4) PASS, full unit test suite PASS, no regressions to existing spike code.

## Verification

All S01 acceptance criteria verified:
- Both known game definitions load through GameRegistry (GameRegistryTest: 2/2 pass)
- Invalid definitions are rejected (GameRegistryTest invalid JSON test: pass)
- Launcher renders the registry (LauncherScreen compiles, assembleDebug: BUILD SUCCESSFUL)
- Last-played ordering is deterministic (GameRegistryTest ordering test: pass)
- Launch/resume selects intended game instance (HubNavigation route wiring verified)
- Application builds and existing baseline functionality is not regressed (full unit test suite: BUILD SUCCESSFUL, GameDefinitionValidatorTest 4/4, SharkIncrementalAdapterTest pass, Fe000000AdapterTest pass)

Verification commands executed in current session:
1. ./gradlew :app:assembleDebug --no-daemon — exit 0, BUILD SUCCESSFUL
2. ./gradlew :app:testDebugUnitTest --tests "com.example.hub.games.GameRegistryTest" --tests "com.example.hub.database.GameInstanceDaoTest" --tests "com.example.hub.games.GameDefinitionValidatorTest" --no-daemon — exit 0, BUILD SUCCESSFUL
3. ./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest --no-daemon — exit 0, BUILD SUCCESSFUL

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
<!-- gsd:state-version=204:1 -->
