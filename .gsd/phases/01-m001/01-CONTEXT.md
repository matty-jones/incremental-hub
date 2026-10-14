# M001: Playable Hub

**Gathered:** 2026-10-07
**Status:** Ready for planning

## Project Description

Deliver a usable Playable Hub in which a user can discover registered games, see them in a native launcher, launch or resume a game, play inside a stable named-profile WebView session, use the native in-session menu, have foreground progress captured, survive ordinary lifecycle/process recreation, recover explicitly from renderer failure, and switch games without cross-game session, profile, or bridge leakage.

## Why This Milestone

The repository has validated feasibility implementation (profile isolation, bootstrap, qualification, capture→restore→fresh-profile verification, integrated spike) but no production launcher or session controller. This milestone turns the spike baseline into a shippable Playable Hub.

## User-Visible Outcome

### When this milestone is complete, the user can:

- Launch the application and see a launcher showing Shark Incremental and FE000000 as cards
- Tap Resume on a previously played game to continue where they left off
- Tap Launch on a new game to start a fresh session
- Open the native in-session menu during play without losing the session
- Have progress captured automatically every 60 seconds while foregrounded
- Close and reopen the app (or survive process death) and resume the last game
- Recover explicitly from a renderer crash via native recovery UI
- Switch from Shark Incremental to FE000000 without session or profile leakage

### Entry point / environment

- Entry point: MainActivity (launcher)
- Environment: Android app, PixelFold_x64 x86_64 API 36, WebView 133.0.6943.137
- Live dependencies: AndroidX WebKit ProfileStore, Room database, Material 3/Compose UI

## Completion Class

- Contract complete means: unit tests for state machine, bridge validation, GameRegistry, and adapter tests pass; build succeeds
- Integration complete means: end-to-end launch→play→capture→resume flow works on device
- Operational complete means: process death recovery and renderer crash recovery work on device

## Final Integrated Acceptance

- User launches app, taps Shark Incremental, plays, opens menu, closes menu, session intact
- User backgrounds app, process is killed, relaunches app, resumes Shark Incremental with progress
- User triggers renderer crash (via devtools or artificial test), sees native recovery UI, recovers explicitly
- User switches from Shark Incremental to FE000000, no cross-game localStorage or profile leakage
- Both known game definitions load through GameRegistry; invalid definitions are rejected
- Launcher renders registry with last-played ordering deterministic
- `./gradlew :app:assembleDebug --no-daemon` passes

## Architectural Decisions

### SessionController as sole lifecycle owner

**Decision:** SessionController owns all session lifecycle and transitions. Compose callbacks request actions from SessionController; they do not independently implement session transitions.

**Rationale:** Single ownership prevents race conditions, illegal transitions, and cross-game leakage. The spec explicitly mandates this.

**Alternatives Considered:**
- ViewModel-owned session state — rejected because spec prohibits Activity-bound WebView in long-lived ViewModel
- Callback-driven session transitions — rejected because it invites independent state mutation and race conditions

### Nine-state sealed session model

**Decision:** Use explicit sealed class with nine states: CLOSED, OPENING, PLAYING, CAPTURING, BACKGROUND, RESTORING, DEGRADED, RECOVERY_REQUIRED, CLOSING.

**Rationale:** Explicit states make illegal transitions detectable. The spec mandates these exact nine states.

**Alternatives Considered:**
- Boolean flags — rejected because they cannot express illegal transitions
- Enum without validation — rejected because transitions must be rejected deterministically

### WebView lifetime outside Compose branches

**Decision:** AndroidView host outside transient Compose branches. WebView identity survives recomposition, menu visibility, orientation changes. Named profile bound before navigation. No Activity-bound WebView in long-lived ViewModel.

**Rationale:** Spec explicitly prohibits storing Activity-bound WebView in long-lived ViewModel. AndroidView with remember/DisposableEffect provides stable identity.

**Alternatives Considered:**
- ViewModel-held WebView — rejected by spec
- Recomposition-recreated WebView — rejected because it causes session loss

### Minimal Room persistence

**Decision:** GameInstance entity with game identity, profile/session identity, last-played/resume metadata. No archive/vault/history schemas.

**Rationale:** Spec explicitly limits persistence to Playable Hub scope. Adding broader schemas inflates scope without immediate need.

**Alternatives Considered:**
- SharedPreferences — rejected because Room provides transactional durability and query capability
- File-based persistence — rejected because Room provides structured access and migration support

### 60-second foreground capture cadence

**Decision:** Lifecycle-owned coroutine on 60-second cadence. No WorkManager for foreground cadence. Capture work cancels cleanly when session closes or changes games.

**Rationale:** Spec explicitly prohibits WorkManager for foreground cadence. Lifecycle-owned coroutine provides clean cancellation.

**Alternatives Considered:**
- WorkManager periodic work — rejected by spec
- Handler.postDelayed — rejected because lifecycle-bound coroutine provides cleaner cancellation

### Bridge validation with epoch tracking

**Decision:** Origin-restricted WebMessageListener with navigation epoch tracking. Late callbacks from old WebView/renderer/game/epoch rejected.

**Rationale:** Spec requires origin restriction and epoch-based validation. Native addJavascriptInterface is forbidden.

**Alternatives Considered:**
- addJavascriptInterface — forbidden by spec
- Unrestricted WebMessageListener — rejected because origin must be restricted

### Seven native menu actions

**Decision:** Resume, Snapshot now, Checkpoint, Vault, Reload, Display, Return to hub — exactly as specified in SPEC.md section 4.2.

**Rationale:** Spec requires all seven actions exactly as specified. No substitutes or omissions.

**Alternatives Considered:**
- Custom menu actions — rejected because spec mandates exact actions
- Fewer actions — rejected because spec mandates all seven

### Game definition parsing

**Decision:** Reuse existing GameDefinitionValidator. No competing parser.

**Rationale:** Spec explicitly states: Do not create a second competing definition parser or validator. Existing validator is validated and tested.

**Alternatives Considered:**
- New parser — rejected by spec
- Modified existing validator — rejected unless defect requires change

### Launcher as entry surface

**Decision:** Launcher becomes MainActivity entry surface. Navigation to Play Surface via explicit launch/resume actions.

**Rationale:** Current MainActivity shows DiagnosticsScreen (spike). Playable Hub requires launcher as normal entry surface.

**Alternatives Considered:**
- Keep DiagnosticsScreen as entry — rejected because it is a spike, not production
- New Activity for launcher — rejected because spec says "make launcher the normal application entry surface"

## Error Handling Strategy

- Illegal session transitions: reject with explicit error, log, remain in current state
- Renderer crash: mark WebView unusable, invalidate bridge requests, destroy WebView, transition to DEGRADED/RECOVERY_REQUIRED, show native recovery UI
- Bootstrap failure: expose real failure state, do not label failed bootstrap as success
- Process death: recover from durable state, create fresh WebView, bind from durable state
- Bridge validation failure: fail closed, reject message, log error
- Game switch: settle old session, invalidate old ownership, activate new session via valid transitions only

## Risks and Unknowns

- **Session state machine complexity** — 9 states with strict transitions is non-trivial to implement correctly; risk of illegal transition bugs
- **WebView identity stability** — AndroidView with remember/DisposableEffect must survive all recomposition scenarios; risk of accidental recreation
- **Bridge epoch tracking** — navigation epoch must be correctly incremented and validated; risk of stale callback mutations
- **Process death recovery** — durable state must survive process death and be sufficient for recovery; risk of incomplete persistence
- **Game switching isolation** — old session must be fully invalidated before new session becomes authoritative; risk of cross-game leakage

## Existing Codebase / Prior Art

- `app/src/main/java/com/example/hub/games/GameAdapter.kt` — adapter interface to reuse
- `app/src/main/java/com/example/hub/games/SharkIncrementalAdapter.kt` — validated adapter to reuse
- `app/src/main/java/com/example/hub/games/Fe000000Adapter.kt` — validated adapter to reuse
- `app/src/main/java/com/example/hub/games/GameDefinitionValidator.kt` — existing validator to reuse
- `app/src/main/java/com/example/hub/games/GameQualifier.kt` — qualification pattern to reuse
- `app/src/main/java/com/example/hub/integrated/IntegratedSpike.kt` — integrated spike pattern to reuse
- `app/src/main/java/com/example/hub/diagnostics/ProfileIsolationTest.kt` — profile isolation pattern to reuse
- `app/src/main/java/com/example/hub/bootstrap/BootstrapDetector.kt` — bootstrap pattern to reuse
- `app/src/main/java/com/example/hub/bootstrap/PreLaunchSnapshot.kt` — snapshot pattern to reuse
- `assets/games/shark-incremental.json` — game definition to reuse
- `assets/games/fe000000.json` — game definition to reuse
- `app/src/test/java/com/example/hub/games/GameDefinitionValidatorTest.kt` — existing tests to preserve

## Relevant Requirements

- R001 — Game Registry (M001/S01)
- R002 — Minimal GameInstance Persistence (M001/S01)
- R003 — Launcher UI (M001/S01)
- R004 — Navigation Wiring (M001/S01)
- R005 — SessionState Model (M001/S02)
- R006 — Stable WebViewHost (M001/S02)
- R007 — Native Menu (M001/S02)
- R008 — Fold-aware/Layout Behavior (M001/S02)
- R009 — SessionController State Machine (M001/S03)
- R010 — Profile Binding and WebView Lifecycle (M001/S03)
- R011 — Bridge Validation (M001/S03)
- R012 — Foreground Capture Cadence (M001/S04)
- R013 — Cold-start Bootstrap Integration (M001/S04)
- R014 — Renderer Crash Handling (M001/S04)
- R015 — Process-Death Recovery (M001/S04)
- R016 — Game Switching (M001/S04)

## Scope

### In Scope

- GameRegistry over assets/games/ definitions
- Minimal Room persistence for GameInstance
- Material 3 launcher UI with cards
- Nine-state sealed SessionState model
- Stable AndroidView WebView host
- Native menu with seven actions
- Fold-aware/layout behavior
- SessionController with state machine
- WebView/profile/navigation ownership in SessionController
- Bridge validation with epoch tracking
- 60-second foreground capture cadence
- Cold-start bootstrap integration
- Renderer crash handling
- Process-death recovery
- Game switching without leakage

### Out of Scope / Non-Goals

- Archive/history tracking
- Portable backup/export via SAF
- Extended display customization
- Additional games beyond Shark/FE000000
- Background preservation automation (WorkManager)
- Assistant functionality (LLM)
- Full vault scope
- Snapshot history
- Export/import library

## Technical Constraints

- minSdk 30, targetSdk 35, compileSdk 35
- AndroidX WebKit 1.14.0 (already in deps)
- Room database (not yet in deps — must be added)
- Jetpack Compose + Material 3 (already in deps)
- Validated runtime: PixelFold_x64 x86_64 API 36, WebView 133.0.6943.137
- ARM64 hardware not an acceptance requirement
- No WorkManager for foreground capture
- Origin-restricted bridge only (no addJavascriptInterface)

## Integration Points

- GameRegistry → game definitions in assets/games/
- GameRegistry → GameDefinitionValidator (existing)
- SessionController → ProfileStore (AndroidX WebKit)
- SessionController → WebViewCompat.setProfile
- SessionController → GameAdapter (existing interface)
- SessionController → Room database (GameInstance)
- Launcher UI → GameRegistry
- Native menu → SessionController actions
- Bridge → WebMessageListener with epoch validation

## Testing Requirements

- Unit tests for GameRegistry (valid/invalid definitions)
- Unit tests for SessionState transitions (valid/illegal)
- Unit tests for SessionController state machine
- Unit tests for bridge validation (epoch tracking)
- Unit tests for GameDefinitionValidator (existing, preserved)
- Integration tests for launch→play→capture→resume flow
- Runtime verification on PixelFold_x64 API 36
- Build verification: ./gradlew :app:assembleDebug --no-daemon

## Acceptance Criteria

### S01 Acceptance
- Both known game definitions load through GameRegistry
- Invalid definitions are rejected
- Launcher renders the registry
- Last-played ordering is deterministic
- Launch/resume selects the intended game instance
- Application builds and existing baseline functionality is not regressed

### S02 Acceptance
- Game remains active while menu visibility and layout state change
- WebView identity is demonstrably stable
- Named-profile binding remains correct
- Back/menu behavior is deterministic
- Build and relevant tests pass

### S03 Acceptance
- Session transition behavior is unit tested
- Illegal transitions fail deterministically
- Session/profile/WebView identity has one owner
- Stale bridge responses cannot affect current session
- Existing game adapters/bootstrap behavior are reused

### S04 Acceptance
- Foreground capture cadence is lifecycle bounded
- Cold-start launch reaches playable state or explicit failure state
- Renderer crash produces bounded native recovery behavior
- Process recreation restores through durable state and fresh WebView
- Game switching leaves no cross-game callback/profile ownership
- No reload loop
- All relevant unit/integration tests and ./gradlew :app:assembleDebug --no-daemon pass

## Open Questions

- What is the exact Room schema for GameInstance? — Will design during S01/T02 based on minimal requirements
- What does the native recovery UI look like? — Will design during S04/T03 based on spec constraints
- How to handle the seven menu actions in Compose? — Will design during S02/T03 using bottom sheet/side panel pattern
- What test hooks for 60-second cadence? — Will use deterministic clocks/triggers per spec guidance

## Assumptions

1. **Seven menu actions**: The spec references "all seven menu actions defined by the milestone contract" but the seed excerpt does not enumerate them. Based on SPEC.md section 4.2, the seven actions are: Resume, Snapshot now, Checkpoint, Vault, Reload, Display, Return to hub.

2. **Room dependency**: The existing build.gradle.kts does not include Room. Room must be added as a new dependency. The spec mandates "Room with explicit migrations, no destructive migration fallback."

3. **ViewModel architecture**: The spec prohibits "Activity-bound WebView in a long-lived ViewModel." SessionController will be a lifecycle-aware component (not a ViewModel) to comply with this constraint.

4. **Navigation wiring**: Current MainActivity shows DiagnosticsScreen. Playable Hub requires launcher as normal entry surface. Navigation to Play Surface will use explicit launch/resume actions.

5. **Bridge protocol**: SPEC.md section 7.3 describes a navigationEpoch field in bridge messages. The exact protocol format will be derived from the spec during S03/T03.

6. **Fold-aware layout**: The spec mentions "display cutouts, fold/hinge constraints, keyboard/IME interaction, relevant configuration/layout changes." These will be implemented using standard Android adaptive layout patterns.

7. **Process death recovery**: The spec requires "persist enough durable session metadata." The exact metadata will be designed during S04/T04 based on what is minimally required for recovery.

8. **Game switching ordering**: The spec provides explicit ordering (settle → capture → invalidate → destroy → activate → bootstrap → expose). This will be implemented exactly as specified.

9. **Capture cadence testing**: The spec says "Tests should use deterministic clocks/triggers where possible rather than sleeping for real 60-second intervals." Test hooks will use a configurable cadence or test clock.

10. **Validated baseline preservation**: The spec says "preserve the validated feasibility baseline foundation." Existing spike activities (BootstrapActivity, IntegratedSpikeActivity, GameQualificationActivity) will be preserved but not modified unless required.
<!-- gsd:state-version=191:1 -->
