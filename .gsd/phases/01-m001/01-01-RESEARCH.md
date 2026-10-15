# S01 Research: Game Registry and Launcher

## Slice Scope

S01 delivers the game registry (enumeration + validation of asset definitions), minimal Room persistence for game-instance metadata, a Material 3 launcher UI, and navigation wiring from the launcher to the play surface (stubbed for S02).

**Active requirements owned:** R001 (Game Registry), R002 (Minimal GameInstance Persistence), R003 (Launcher UI), R004 (Navigation Wiring).

---

## Existing Codebase State

### What already exists (reuse)

| Asset | Location | Notes |
|---|---|---|
| `GameDefinitionValidator` | `app/src/main/java/com/example/hub/games/GameDefinitionValidator.kt` | Fully implemented; validates R011 schema; exposes `validate()` and `validateR003()` |
| `GameAdapter` interface | `app/src/main/java/com/example/hub/games/GameAdapter.kt` | 5-method interface (probe, awaitReady, capture, validate, prepareRestore, verifyLoaded) |
| `SharkIncrementalAdapter` | `app/src/main/java/com/example/hub/games/SharkIncrementalAdapter.kt` | Validated implementation |
| `Fe000000Adapter` | `app/src/main/java/com/example/hub/games/Fe000000Adapter.kt` | Validated implementation |
| `GameQualifier` | `app/src/main/java/com/example/hub/games/GameQualifier.kt` | Round-trip qualification harness; reusable profile/WebView patterns |
| `IntegratedSpike` | `app/src/main/java/com/example/hub/integrated/IntegratedSpike.kt` | Full play-capture-restore spike; contains WebView creation pattern |
| Game definitions | `app/src/main/assets/games/shark-incremental.json`, `fe000000.json` | Two valid definitions; 15-field R011 schema each |
| Validator tests | `app/src/test/java/com/example/hub/games/GameDefinitionValidatorTest.kt` | 8 tests covering positive, negative, warning, and additive-compatibility cases |
| Material 3 Card pattern | `app/src/main/java/com/example/hub/ui/CapabilityScreen.kt` | `Card` + `CardDefaults` + `LazyColumn` pattern already used |
| `remember`/`mutableStateOf` state management | `app/src/main/java/com/example/hub/ui/DiagnosticsScreen.kt` | No ViewModel/Hilt — simple Compose state is the project convention |

### What is missing (must be created)

| Gap | Action needed |
|---|---|
| Room dependency | Add `room-runtime`, `room-ktx`, `room-testing` + `kotlin-kapt` plugin to `app/build.gradle.kts` |
| Navigation Compose dependency | Add `androidx.navigation:navigation-compose` to `app/build.gradle.kts` |
| Room database setup | Create `HubDatabase` (Singleton), `GameInstanceDao`, `GameInstance` entity from scratch |
| GameRegistry class | New class to enumerate `assets/games/*.json`, validate each, return ordered list |
| Launcher composable | New `LauncherScreen` composable with Material 3 cards per game |
| Navigation graph | New navigation setup (programmatic or XML) wiring launcher → play surface |
| MainActivity update | Replace `DiagnosticsScreen` with `LauncherScreen` + navigation host |
| Unit tests | `GameRegistryTest` + `GameInstanceDaoTest` (in-memory Room) |

---

## Architecture Decisions (S01-specific)

### 1. GameRegistry as a pure Kotlin class (no DI)

**Decision:** `GameRegistry` is a plain class taking `Context` in its constructor. It reads from `assets/games/`, validates each file with `GameDefinitionValidator`, and exposes `getGames()` and `getGame(gameId)`.

**Rationale:** The project has no Hilt/Dagger. Adding a DI framework for S01 would be scope creep. `GameRegistry` is stateless except for its cached validated list — a simple `Context`-based constructor is sufficient. This matches the existing pattern of `GameQualifier(Context)` and `IntegratedSpike(Context)`.

**API sketch:**
```kotlin
class GameRegistry(private val context: Context) {
    fun getGames(): List<RegisteredGame>
    fun getGame(gameId: String): RegisteredGame?
    val allDefinitions: List<DefinitionWithStatus> // for error reporting
}

data class RegisteredGame(
    val gameId: String,
    val displayName: String,
    val launchUrl: String,
    val adapterId: String,
    val displayDefaults: DisplayDefaults,
    val lastPlayedAtMillis: Long? = null, // from GameInstance DAO
)
```

### 2. Minimal GameInstance entity

**Decision:** One Room entity with minimal fields: `gameId` (PK), `profileName`, `sessionIdentity`, `lastPlayedAtMillis`, `resumeToken` (nullable).

**Rationale:** R002 explicitly limits persistence to Playable Hub scope. No archive/vault/history schemas. The entity supports the S01 acceptance criterion of "last-played ordering deterministic."

```kotlin
@Entity(tableName = "game_instances")
data class GameInstance(
    @PrimaryKey val gameId: String,
    val profileName: String,
    val sessionIdentity: String,
    val lastPlayedAtMillis: Long,
    val resumeToken: String? = null,
)

@Dao
interface GameInstanceDao {
    @Query("SELECT * FROM game_instances ORDER BY lastPlayedAtMillis DESC")
    fun getAllOrdered(): Flow<List<GameInstance>>

    @Query("SELECT * FROM game_instances WHERE gameId = :gameId")
    suspend fun get(gameId: String): GameInstance?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(instance: GameInstance)

    @Query("DELETE FROM game_instances WHERE gameId = :gameId")
    suspend fun delete(gameId: String)
}
```

### 3. HubDatabase singleton

**Decision:** Standard Room singleton with `callbacks` for schema validation.

```kotlin
@Database(entities = [GameInstance::class], version = 1, exportSchema = false)
abstract class HubDatabase : RoomDatabase() {
    abstract fun gameInstanceDao(): GameInstanceDao
    companion object {
        @Volatile private var INSTANCE: HubDatabase? = null
        fun getInstance(context: Context): HubDatabase = ...
    }
}
```

### 4. LauncherScreen composable

**Decision:** `Scaffold` + `LazyColumn` of `Card` items. Each card shows `displayName`, status label (Resume available / Launch), and action buttons. Uses `displayDefaults` from the registry for color/icon hints.

**Pattern reuse:** Directly follows `CapabilityScreen`'s `Scaffold` + `LazyColumn` + `Card` pattern.

### 5. Navigation wiring

**Decision:** Programmatic Navigation Compose (no XML) to avoid adding an XML nav graph resource. Two routes: `"launcher"` and `"play_surface"`. `play_surface` accepts `gameId` as a route argument.

```kotlin
@Composable
fun HubNavigation() {
    val navController = rememberNavController()
    NavHost(navController, startDestination = "launcher") {
        composable("launcher") {
            LauncherScreen(onLaunch = { gameId -> navController.navigate("play_surface/$gameId") })
        }
        composable("play_surface/{gameId}") { backStackEntry ->
            val gameId = backStackEntry.arguments?.getString("gameId")!!
            PlaySurfaceStub(gameId = gameId) // S02 fills this in
        }
    }
}
```

**Constraint:** `lifecycle-viewmodel-compose` is already in deps but Hilt is not. For S01, we avoid ViewModel and use simple `remember` state in MainActivity. S03 (SessionController) will introduce ViewModel later.

---

## Implementation Landscape

### Task breakdown (natural seams)

**Task 1: Room Foundation**
- Files: `app/build.gradle.kts` (add deps + kapt plugin), `app/src/main/java/com/example/hub/database/GameInstance.kt`, `GameInstanceDao.kt`, `HubDatabase.kt`
- Verify: `./gradlew :app:assembleDebug --no-daemon` (after deps)

**Task 2: GameRegistry**
- Files: `app/src/main/java/com/example/hub/games/GameRegistry.kt`
- Files (test): `app/src/test/java/com/example/hub/games/GameRegistryTest.kt`
- Uses existing `GameDefinitionValidator` and `assets/games/*.json`
- Verify: unit tests pass; both known definitions resolve; invalid JSON rejected

**Task 3: LauncherScreen**
- Files: `app/src/main/java/com/example/hub/ui/LauncherScreen.kt`
- Reuses `RegisteredGame` data from GameRegistry
- Cards with Resume/Launch actions
- Verify: renders both games; last-played ordering deterministic

**Task 4: Navigation + MainActivity**
- Files: `app/src/main/java/com/example/hub/navigation/HubNavigation.kt`, update `MainActivity.kt`
- Verify: launch → launcher shows cards; tap Launch navigates to play_surface route

**Task 5: DAO Tests**
- Files: `app/src/test/java/com/example/hub/database/GameInstanceDaoTest.kt`
- In-memory Room database
- Verify: upsert/get/delete/ordering tests pass

---

## First Proof (Highest Risk)

**GameRegistry reading from Android assets** is the highest-risk first proof. It is the first new code that:
1. Touches the Android asset pipeline (different from classpath resources used by existing tests)
2. Wraps the existing `GameDefinitionValidator` in a live context
3. Must handle the case where an asset file is missing or malformed

If GameRegistry works, all downstream (launcher UI, DAO integration, navigation) follow naturally.

**Verification command:**
```bash
./gradlew :app:testDebugUnitTest --tests "com.example.hub.games.GameRegistryTest" --no-daemon
```

---

## Risks and Watch-outs

| Risk | Mitigation |
|---|---|
| Room kapt plugin conflict with Kotlin 2.1.0 | Use `kotlin-kapt` plugin (K2-compatible) or `com.google.devtools.ksp` — test early in Task 1 |
| Asset reading on background thread | GameRegistry caches results; initial load on Main thread is acceptable for 2 files |
| Navigation Compose version compatibility | Pin to `2.7.7` (compatible with Compose BOM 2024.09.00) |
| In-memory Room test setup | Use `Room.inMemoryDatabaseBuilder` with `build()` — standard pattern |
| MainActivity state management without ViewModel | Use `remember` + `produceState` for GameRegistry; acceptable for S01 scope |

---

## Forward Intelligence (for S02)

- **GameRegistry → S02:** S02's `WebViewHost` composable will consume `RegisteredGame` to resolve launch URL and adapter ID
- **GameInstance DAO → S02:** S02's play surface will read `lastPlayedAtMillis` to show resume availability
- **Navigation graph → S02:** S02 adds the real `PlaySurface` composable at the `"play_surface/{gameId}"` route
- **Watch-out:** S01's `HubNavigation` stub must preserve the route contract (`play_surface/{gameId}`) so S02 can swap in the real composable without route changes

---

## Files Summary

### New files to create
1. `app/src/main/java/com/example/hub/database/GameInstance.kt`
2. `app/src/main/java/com/example/hub/database/GameInstanceDao.kt`
3. `app/src/main/java/com/example/hub/database/HubDatabase.kt`
4. `app/src/main/java/com/example/hub/games/GameRegistry.kt`
5. `app/src/main/java/com/example/hub/ui/LauncherScreen.kt`
6. `app/src/main/java/com/example/hub/navigation/HubNavigation.kt`
7. `app/src/test/java/com/example/hub/games/GameRegistryTest.kt`
8. `app/src/test/java/com/example/hub/database/GameInstanceDaoTest.kt`

### Files to modify
1. `app/build.gradle.kts` — add Room + Navigation deps + kapt plugin
2. `app/src/main/java/com/example/hub/MainActivity.kt` — replace DiagnosticsScreen with HubNavigation
3. `app/src/main/res/values/strings.xml` — add launcher-related strings

### Files preserved (no changes)
- `GameDefinitionValidator.kt` — existing, validated
- `GameAdapter.kt` — existing interface
- `SharkIncrementalAdapter.kt` — existing
- `Fe000000Adapter.kt` — existing
- `GameQualifier.kt` — existing
- `IntegratedSpike.kt` — existing
- `assets/games/*.json` — existing definitions
- All existing tests — preserved, not regressed
<!-- gsd:state-version=204:1 -->
