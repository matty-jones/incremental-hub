# Project Knowledge

Append-only register of project-specific rules, patterns, and lessons learned.
Agents read this before every unit. Add entries when you discover something worth remembering.

## Rules

| # | Scope | Rule | Why | Added |
|---|-------|------|-----|-------|

## Patterns

| # | Pattern | Where | Notes |
|---|---------|-------|-------|
| P001 | produceState → collectAsState → build state map pattern: launcher UI reads DAO state via produceState (not ViewModel) to avoid adding Hilt; GameRegistry.getGames accepts a pre-built map from DAO, letting the composable own the flow collection. Reused for SessionController.state observation in PlaySurface. | — | — |
| P002 | SessionController at NavHost scope via remember(lifecycleOwner, registry, dao) prevents recreation on recomposition. This is the pattern for any Activity-bound lifecycle component that must survive recomposition — create at NavHost scope, not inside composable remember. | — | — |
| P003 | Pure-Kotlin validation core (BridgeValidator) separated from Android wrapper (BridgeHost). Validation logic lives in testable JVM code; Android glue (WebViewCompat.addWebMessageListener) is minimal wrapper. Pattern for any validation that benefits from JVM testability. | — | — |
| MEM009 | In S01, launcher UI reads DAO state via produceState (not ViewModel) to avoid adding Hilt. GameRegistry.getGames(lastPlayedByGameId: Map?) accepts a pre-built map from DAO, letting the composable own the flow collection. This pattern (produceState → collect Flow → build state map) should be reused for any DAO-backed UI in S01-S03 before ViewModel is introduced. | — | — |

## Lessons Learned

| # | What Happened | Root Cause | Fix | Scope |
|---|--------------|------------|-----|-------|
| L001 | Robolectric 4.12.1 + createComposeRule has a known activity-resolution limitation: ActivityScenario cannot resolve the launcher activity when running isolated test classes. The full test suite may pass (when other tests initialize Robolectric first) but isolated execution fails with "Unable to resolve activity for Intent". Mitigation: use pure-Kotlin tests for ordering/logic verification, or add a test/AndroidManifest.xml with a dummy launcher activity. Also ensure androidx.test:runner and androidx.test:rules are in testImplementation. | — | — | incremental-hub |
| L002 | In Compose foundation 1.7.0 (BOM 2024.09.00), WindowInsets companion extension properties (safeDrawing, ime, systemBars, navigationBars, displayCutout) are defined in WindowInsets_androidKt but may not resolve as static properties in some compiler configurations. Workaround: use modifier-based APIs (statusBarsPadding, navigationBarsPadding, imePadding) instead, or use computeCurrentWindowMetrics(Context) from androidx.window:window:1.3.0 instead of the non-existent getCurrentWindowMetrics(). | — | — | incremental-hub |
| L003 | SessionController.onAttached() must transit through Opening before reaching Degraded/RecoveryRequired during cold-start recovery, because canTransition(Closed, Degraded) is false — the state machine only allows Closed → Opening. The fix is to call transitionTo(Opening, gameId) first, then transitionTo(Degraded/RecoveryRequired, gameId). The Opening → Degraded transition was added to the state machine specifically for this cold-start path. | — | — | project |
| L004 | SessionController.settleActiveSession() must transit through Playing after Capturing (Playing → Capturing → delay → Playing → Closing → Closed), because canTransition(Capturing, Closing) is false — the nine-state matrix only allows Capturing → Playing or Capturing → Background. Any direct Capturing → Closing transition will be silently rejected by transitionTo() with a warning log. | — | — | incremental-hub |
| L005 | SessionController.settleActiveSession() must transit through Playing after Capturing (Playing → Capturing → delay → Playing → Closing → Closed), because canTransition(Capturing, Closing) is false — the nine-state matrix only allows Capturing → Playing or Capturing → Background. Any direct Capturing → Closing transition will be silently rejected by transitionTo() with a warning log. | — | — | incremental-hub |
| MEM007 | Java 21 and 25 OpenJDK on Ubuntu ship without jlink; Java 17 does. When AGP 8.5.2 fails with "jlink executable does not exist", switch org.gradle.java.home to /usr/lib/jvm/java-17-openjdk-amd64. Kotlin 2.1.0 still compiles correctly with Java 17 as the build JVM. | — | — | incremental-hub |
| MEM008 | Milestone worktrees need local.properties with sdk.dir to build. The worktree at .gsd-worktrees/M001/ is isolated from the root project, so the Android SDK path must be written into the worktree's local.properties. Use: echo "sdk.dir=/home/blasky/Android/Sdk" > local.properties | — | — | M001 |
