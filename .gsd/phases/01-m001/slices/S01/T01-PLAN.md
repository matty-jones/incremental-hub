---
estimated_steps: 8
estimated_files: 5
skills_used: []
required_workflow_tools: []
---

# T01: Room Foundation: Entity, DAO, Database, and Build Config

**Why:** S01 requires minimal Room persistence for GameInstance metadata (R002). The project currently has no Room dependency or database setup. This task establishes the foundation that all downstream S01 tasks (launcher ordering, navigation wiring) depend on.

**Do:**
1. Add Room dependencies to `app/build.gradle.kts`: `room-runtime`, `room-ktx`, `room-testing` (test), and the KSP plugin for annotation processing (Kotlin 2.1.0 compatible).
2. Create `GameInstance` entity at `app/src/main/java/com/example/hub/database/GameInstance.kt` with fields: `gameId` (PK), `profileName`, `sessionIdentity`, `lastPlayedAtMillis`, `resumeToken` (nullable). Deliberately narrow per R002 — no archive/vault/history schemas.
3. Create `GameInstanceDao` at `app/src/main/java/com/example/hub/database/GameInstanceDao.kt` with: `getAllOrdered()` returning Flow for last-played ordering, `get(gameId)` suspend, `upsert()` suspend, `delete(gameId)` suspend.
4. Create `HubDatabase` at `app/src/main/java/com/example/hub/database/HubDatabase.kt` as a Room singleton with `gameInstanceDao()` abstract method.
5. Add `GameInstanceDaoTest` at `app/src/test/java/com/example/hub/database/GameInstanceDaoTest.kt` using in-memory Room database builder. Test: upsert, get, delete, ordering by lastPlayedAtMillis DESC.

**Done when:** Build compiles; DAO unit tests pass (upsert/get/delete/ordering); no regressions to existing tests.

## Inputs

- `app/build.gradle.kts`
- `app/src/main/java/com/example/hub/games/GameDefinitionValidator.kt`

## Expected Output

- `app/src/main/java/com/example/hub/database/GameInstance.kt`
- `app/src/main/java/com/example/hub/database/GameInstanceDao.kt`
- `app/src/main/java/com/example/hub/database/HubDatabase.kt`
- `app/src/test/java/com/example/hub/database/GameInstanceDaoTest.kt`

## Verification

./gradlew :app:assembleDebug --no-daemon && ./gradlew :app:testDebugUnitTest --tests "com.example.hub.database.GameInstanceDaoTest" --no-daemon
<!-- gsd:state-version=2:0 -->
