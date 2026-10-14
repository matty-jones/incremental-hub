---
phase: 01-m001
phase_name: M001: Playable Hub
project: incremental-hub-restored
generated: "2026-10-14T00:00:00.000Z"
counts:
  decisions: 4
  lessons: 3
  patterns: 3
  surprises: 2
missing_artifacts: []
---

### Decisions

- **SessionController as sole lifecycle owner** — Chose SessionController as the single owner of the nine-state session lifecycle via a sealed state machine. Rationale: single ownership prevents race conditions, illegal transitions, and cross-game leakage. Compose callbacks request actions from SessionController rather than mutating state independently.
  Source: .gsd/phases/01-m001/01-03-SUMMARY.md/Key decisions

- **WebView lifecycle outside transient Compose branches** — AndroidView host with remember/DisposableEffect provides stable identity across recomposition. Activity-bound WebView not retained in ViewModel (spec prohibits this). Explicit disposal owned by session lifecycle.
  Source: .gsd/DECISIONS.md/D002

- **Pure-Kotlin validation core separated from Android wrapper** — BridgeValidator is a pure-Kotlin stateless validator; BridgeHost wraps WebViewCompat.addWebMessageListener. Rationale: testable validation logic on JVM, Android glue only at the boundary.
  Source: .gsd/phases/01-m001/01-03-SUMMARY.md/Patterns established

- **Settle must transit through Playing after Capturing** — The nine-state transition matrix only allows Capturing → Playing or Capturing → Background, not Capturing → Closing. settleActiveSession() for a Playing session follows Playing → Capturing → delay → Playing → Closing → Closed. This is a durable convention for all future settle/abandon paths.
  Source: .gsd/DECISIONS.md/D009

### Lessons

- **Java 21/25 OpenJDK on Ubuntu ships without jlink** — AGP 8.5.2 fails with "jlink executable does not exist" on Java 21/25. Kotlin 2.1.0 still compiles correctly with Java 17 as the build JVM. Switch org.gradle.java.home to /usr/lib/jvm/java-17-openjdk-amd64.
  Source: .gsd/phases/01-m001/01-01-SUMMARY.md/What Happened

- **SessionController.onAttached() must transit through Opening** — canTransition(Closed, Degraded) is false; the state machine only allows Closed → Opening. The fix is to call transitionTo(Opening, gameId) first, then transitionTo(Degraded/RecoveryRequired, gameId). The Opening → Degraded transition was added specifically for this cold-start path.
  Source: .gsd/phases/01-m001/01-04-SUMMARY.md/What Happened

- **Settle path requires intermediate Playing state** — Direct Capturing → Closing is rejected by the state machine. settleActiveSession() must include an intermediate delay + Playing transition. This was discovered during S04 game-switching tests.
  Source: .gsd/phases/01-m001/01-04-SUMMARY.md/What Happened

### Patterns

- **produceState → collectAsState → build state map** — Launcher UI reads DAO state via produceState (not ViewModel) to avoid adding Hilt. GameRegistry.getGames(lastPlayedByGameId: Map?) accepts a pre-built map from DAO, letting the composable own the flow collection. Reused for SessionController.state observation in PlaySurface.
  Source: .gsd/phases/01-m001/01-03-SUMMARY.md/Patterns established

- **SessionController at NavHost scope via remember(lifecycleOwner, registry, dao)** — Prevents recreation on recomposition. This pattern applies to any Activity-bound lifecycle component that must survive recomposition.
  Source: .gsd/phases/01-m001/01-03-SUMMARY.md/Patterns established

- **Pure-Kotlin validation core with Android wrapper** — BridgeValidator (pure Kotlin, JVM-testable) separated from BridgeHost (Android wrapper). Pattern for any validation logic that benefits from JVM testability while integrating with Android APIs.
  Source: .gsd/phases/01-m001/01-03-SUMMARY.md/Patterns established

### Surprises

- **KSP internal compiler error on first build attempt** — The first assembleDebug run failed with "Internal compiler error" in kspDebugKotlin. A second run succeeded (likely a transient Gradle cache issue). No code changes were needed.
  Source: .gsd/phases/01-m001/01-VERIFICATION.md/Build verification

- **local.properties sdk.dir required in worktree** — The worktree at .gsd-worktrees/M001/ is isolated from the root project, so local.properties with sdk.dir must be written into the worktree. This is a recurring pattern for all GSD milestone worktrees.
  Source: .gsd/phases/01-m001/01-01-SUMMARY.md/What Happened
