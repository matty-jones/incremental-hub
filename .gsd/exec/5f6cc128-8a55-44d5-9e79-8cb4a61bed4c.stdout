---
estimated_steps: 9
estimated_files: 3
skills_used: []
required_workflow_tools: []
---

# T04: Navigation Wiring and MainActivity Entry Surface

**Why:** R004 requires making the launcher the normal application entry surface and wiring launch/resume navigation into the play surface. This is the integration task that ties S01 together.

**Do:**
1. Create `HubNavigation` composable at `app/src/main/java/com/example/hub/navigation/HubNavigation.kt` using Navigation Compose with two routes: `"launcher"` and `"play_surface/{gameId}"`.
2. The `"play_surface/{gameId}"` route shows a `PlaySurfaceStub` composable (placeholder for S02 — accepts gameId, logs navigation, does not yet implement WebView).
3. Update `MainActivity` at `app/src/main/java/com/example/hub/MainActivity.kt` to host `HubNavigation` instead of `DiagnosticsScreen`.
4. Add Navigation Compose dependency to `app/build.gradle.kts` if not already covered by BOM.
5. Verify: application launches showing launcher; both games render as cards; tapping Launch navigates to play_surface route; tapping Resume navigates to play_surface route.
6. Run full build and all unit tests to confirm no regressions.

**Done when:** Launcher is the normal entry surface; navigation to play_surface works; existing baseline functionality is not regressed; build and all tests pass.

## Inputs

- `app/src/main/java/com/example/hub/ui/LauncherScreen.kt`
- `app/src/main/java/com/example/hub/games/GameRegistry.kt`
- `app/build.gradle.kts`

## Expected Output

- `app/src/main/java/com/example/hub/navigation/HubNavigation.kt`
- `app/src/main/java/com/example/hub/MainActivity.kt`

## Verification

./gradlew :app:assembleDebug --no-daemon && ./gradlew :app:testDebugUnitTest --no-daemon
<!-- gsd:state-version=5:0 -->
