# GSD context snapshot (2026-10-13T18:30:37.355Z)

## Active context
Active: M001 / S04 / T04 - Bootstrap Integration and Game Switching (R013 + R016)

## Top project memories
- [MEM007] (gotcha) Java 21 and 25 OpenJDK on Ubuntu ship without jlink; Java 17 does. When AGP 8.5.2 fails with "jlink executable does not exist", switch org.gradle.java.home to /usr/lib/jvm/java-17-openjdk-amd64. Kotlin 2.1.0 still compiles correctly with Java 17 as the build JVM.
- [MEM014] (gotcha) SessionController.onAttached() must transit through Opening before reaching Degraded/RecoveryRequired during cold-start recovery, because canTransition(Closed, Degraded) is false — the state machine only allows Closed → Opening. The fix is to call transitionTo(Opening, gameId) first, then transitionTo(Degraded/RecoveryRequired, gameId). The Opening → Degraded transition was added to the state machine specifically for this cold-start path.
- [MEM008] (gotcha) Milestone worktrees need local.properties with sdk.dir to build. The worktree at .gsd-worktrees/M001/ is isolated from the root project, so the Android SDK path must be written into the worktree's local.properties. Use: echo "sdk.dir=/home/blasky/Android/Sdk" > local.properties
- [MEM009] (pattern) In S01, launcher UI reads DAO state via produceState (not ViewModel) to avoid adding Hilt. GameRegistry.getGames(lastPlayedByGameId: Map?) accepts a pre-built map from DAO, letting the composable own the flow collection. This pattern (produceState → collect Flow → build state map) should be reused for any DAO-backed UI in S01-S03 before ViewModel is introduced.
- [MEM001] (architecture) SessionController architecture Chose: SessionController as sole owner of session lifecycle via sealed state machine with nine states. Rationale: Single ownership prevents race conditions, illegal transitions, and cross-game leakage. Compose callbacks request actions from SessionController rather than mutating state independently..
- [MEM002] (architecture) WebView lifecycle ownership Cho
…[truncated]
