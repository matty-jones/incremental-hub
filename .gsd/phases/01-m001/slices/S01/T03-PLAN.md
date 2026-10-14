---
estimated_steps: 9
estimated_files: 1
skills_used: []
required_workflow_tools: []
---

# T03: LauncherScreen: Material 3 Card UI

**Why:** R003 requires a Material 3 launcher showing registered games as native cards with recently-played-first ordering, honest availability/status labels, and Resume/Launch actions. The launcher is the normal application entry surface per R004.

**Do:**
1. Create `LauncherScreen` composable at `app/src/main/java/com/example/hub/ui/LauncherScreen.kt` using `Scaffold` + `LazyColumn` of `Card` items.
2. Each card shows: `displayName`, status label (Resume available / Launch), action buttons (Resume where resumable, Launch/start otherwise).
3. Use `displayDefaults` from `RegisteredGame` for color hints (colorPrimary from game definition).
4. Order games: games with `lastPlayedAtMillis` first (sorted DESC), then alphabetical by gameId.
5. Read `GameRegistry` via `remember` + `produceState` (no ViewModel per S01 constraint).
6. Expose `onLaunch(gameId: String)` and `onResume(gameId: String)` callbacks.

**Done when:** Launcher renders both games as cards; cards show correct displayName and status; last-played ordering is visually deterministic; build passes.

## Inputs

- `app/src/main/java/com/example/hub/games/GameRegistry.kt`
- `app/src/main/java/com/example/hub/games/RegisteredGame.kt`

## Expected Output

- `app/src/main/java/com/example/hub/ui/LauncherScreen.kt`

## Verification

./gradlew :app:assembleDebug --no-daemon
<!-- gsd:state-version=7:0 -->
