---
estimated_steps: 10
estimated_files: 3
skills_used: []
required_workflow_tools: []
---

# T02: GameRegistry: Enumerate, Validate, and Resolve Game Definitions

**Why:** R001 requires enumerating registered game definitions from assets/games/, validating with GameDefinitionValidator, rejecting invalid definitions explicitly, and providing deterministic ordering/lookup with metadata for launcher rendering. This is the highest-risk task because it touches the Android asset pipeline for the first time.

**Do:**
1. Create `RegisteredGame` data class at `app/src/main/java/com/example/hub/games/RegisteredGame.kt` with fields: `gameId`, `displayName`, `launchUrl`, `adapterId`, `displayDefaults` (nested data class or existing pattern), `lastPlayedAtMillis` (nullable, from DAO).
2. Create `GameRegistry` at `app/src/main/java/com/example/hub/games/GameRegistry.kt` as a plain class taking `Context`. It reads `assets/games/*.json`, validates each with `GameDefinitionValidator`, caches the validated list, and exposes:
   - `getGames(): List<RegisteredGame>` — ordered deterministically (alphabetical by gameId for cold start; DAO last-played ordering when data exists)
   - `getGame(gameId: String): RegisteredGame?`
   - `allDefinitions: List<DefinitionWithStatus>` — for error reporting
3. Create `GameRegistryTest` at `app/src/test/java/com/example/hub/games/GameRegistryTest.kt` using classpath resources (same pattern as `GameDefinitionValidatorTest`). Test: both known definitions resolve; invalid JSON rejected; deterministic ordering.
4. Wire `GameRegistry` to consume `GameInstance` DAO for last-played ordering (depends on Task 1's HubDatabase).

**Done when:** Both known game definitions load; invalid definitions are rejected; deterministic ordering is verified by test; unit tests pass.

## Inputs

- `app/src/main/java/com/example/hub/games/GameDefinitionValidator.kt`
- `app/src/main/java/com/example/hub/database/HubDatabase.kt`
- `app/src/main/assets/games/shark-incremental.json`
- `app/src/main/assets/games/fe000000.json`

## Expected Output

- `app/src/main/java/com/example/hub/games/RegisteredGame.kt`
- `app/src/main/java/com/example/hub/games/GameRegistry.kt`
- `app/src/test/java/com/example/hub/games/GameRegistryTest.kt`

## Verification

./gradlew :app:testDebugUnitTest --tests "com.example.hub.games.GameRegistryTest" --no-daemon && ./gradlew :app:testDebugUnitTest --tests "com.example.hub.games.GameDefinitionValidatorTest" --no-daemon
<!-- gsd:state-version=3:0 -->
