# Requirements

This file is the explicit capability and coverage contract for the project.

## Active

## Validated

### R001 — Enumerate registered game definitions from assets/games/, parse using existing schema, validate with GameDefinitionValidator, reject invalid definitions explicitly, provide deterministic ordering/lookup, expose metadata for launcher rendering.
- Class: core-capability
- Status: validated
- Description: Enumerate registered game definitions from assets/games/, parse using existing schema, validate with GameDefinitionValidator, reject invalid definitions explicitly, provide deterministic ordering/lookup, expose metadata for launcher rendering.
- Why it matters: Foundation for the launcher — without a registry there is no game discovery or launch surface.
- Source: spec
- Primary owning slice: M001/S01
- Validation: GameRegistry loads both known game definitions from assets/games/, rejects invalid JSON, provides deterministic ordering. Verified by GameRegistryTest (2 tests) and assembleDebug.

### R002 — Minimal Room persistence for GameInstance: game identity, profile/session identity, last-played/resume metadata. Deliberately narrow — no archive/vault/history schemas.
- Class: continuity
- Status: validated
- Description: Minimal Room persistence for GameInstance: game identity, profile/session identity, last-played/resume metadata. Deliberately narrow — no archive/vault/history schemas.
- Why it matters: Process/activity recreation must recover through durable state rather than assuming in-memory WebView survived.
- Source: spec
- Primary owning slice: M001/S01
- Validation: GameInstance entity with narrow schema (gameId PK, profileName, sessionIdentity, lastPlayedAtMillis, resumeToken nullable). GameInstanceDao with getAllOrdered Flow, suspend get/upsert/delete. Verified by GameInstanceDaoTest (6 tests).

### R003 — Material 3 launcher showing registered games as native cards: recently played first, honest availability/status labels, Resume where resumable, launch/start otherwise. Shark/FE000000 from registry data, not hard-coded.
- Class: primary-user-loop
- Status: validated
- Description: Material 3 launcher showing registered games as native cards: recently played first, honest availability/status labels, Resume where resumable, launch/start otherwise. Shark/FE000000 from registry data, not hard-coded.
- Why it matters: Primary entry point — user discovers and launches games here.
- Source: spec
- Primary owning slice: M001/S01
- Validation: Material 3 LauncherScreen with Scaffold+LazyColumn, Card items with displayName, Resume/Launch status chip, action buttons wired to callbacks. Last-played ordering via DAO. Verified by assembleDebug and launcher rendering in HubNavigation.

### R004 — Make launcher the normal application entry surface. Wire launch/resume navigation into Play Surface. Acceptance: both known definitions load, invalid rejected, launcher renders, last-played ordering deterministic, launch/resume selects intended instance.
- Class: launchability
- Status: validated
- Description: Make launcher the normal application entry surface. Wire launch/resume navigation into Play Surface. Acceptance: both known definitions load, invalid rejected, launcher renders, last-played ordering deterministic, launch/resume selects intended instance.
- Why it matters: Connects registry to session lifecycle — the entry point must actually launch games.
- Source: spec
- Primary owning slice: M001/S01
- Validation: MainActivity hosts HubNavigation with two-route graph (launcher, play_surface/{gameId}). PlaySurfaceStub as S02 placeholder. Both known definitions load, invalid rejected, launcher renders, last-played ordering deterministic, launch/resume selects intended instance.

### R005 — Nine-state sealed session-state model: CLOSED, OPENING, PLAYING, CAPTURING, BACKGROUND, RESTORING, DEGRADED, RECOVERY_REQUIRED, CLOSING. Transition validation suitable for unit testing. Illegal transitions rejected deterministically.
- Class: core-capability
- Status: validated
- Description: Nine-state sealed session-state model: CLOSED, OPENING, PLAYING, CAPTURING, BACKGROUND, RESTORING, DEGRADED, RECOVERY_REQUIRED, CLOSING. Transition validation suitable for unit testing. Illegal transitions rejected deterministically.
- Why it matters: Explicit state machine prevents illegal session transitions that cause silent corruption or leakage.
- Source: spec
- Primary owning slice: M001/S02
- Validation: SessionState sealed class with nine data-object states and exhaustive canTransition validation covering all 81 pairs. 30 unit tests in SessionStateTest cover valid transitions, illegal transitions, and identity tracking.

### R006 — Compose/AndroidView WebView host: identity survives recomposition, not created in transient menu/loading/error branches, named profile bound before navigation, Activity-bound WebView not retained in ViewModel, explicit disposal owned by session lifecycle.
- Class: core-capability
- Status: validated
- Description: Compose/AndroidView WebView host: identity survives recomposition, not created in transient menu/loading/error branches, named profile bound before navigation, Activity-bound WebView not retained in ViewModel, explicit disposal owned by session lifecycle.
- Why it matters: WebView identity stability is the core technical invariant — accidental recreation causes session loss.
- Source: spec
- Primary owning slice: M001/S02
- Validation: WebViewHost composable using AndroidView with remember(gameId, state) for stable identity. Profile bound before navigation via WebViewCompat.setProfile(). DisposableEffect cleanup. Closed-state degradation. 9 WebViewHostTest tests verify stable identity and lifecycle.

### R007 — Native menu with all seven actions (Resume, Snapshot now, Checkpoint, Vault, Reload, Display, Return to hub). Menu overlays must not recreate WebView. Android Back behavior deterministic. 48dp minimum touch targets. Opening/closing menu must not change game session identity.
- Class: operability
- Status: validated
- Description: Native menu with all seven actions (Resume, Snapshot now, Checkpoint, Vault, Reload, Display, Return to hub). Menu overlays must not recreate WebView. Android Back behavior deterministic. 48dp minimum touch targets. Opening/closing menu must not change game session identity.
- Why it matters: User-facing controls during play — must be accessible and stable.
- Source: spec
- Primary owning slice: M001/S02
- Validation: NativeMenu with MenuAction sealed class (7 actions), BackHandler with deterministic behavior, 48dp touch targets, width-based layout branching. 30 NativeMenuTest tests verify all actions and Back behavior.

### R008 — Play Surface adaptation: display cutouts, fold/hinge constraints, keyboard/IME interaction, configuration/layout changes. Layout adaptation must not recreate active game session unnecessarily.
- Class: quality-attribute
- Status: validated
- Description: Play Surface adaptation: display cutouts, fold/hinge constraints, keyboard/IME interaction, configuration/layout changes. Layout adaptation must not recreate active game session unnecessarily.
- Why it matters: Pixel Fold form factor requires fold-aware layout; user expects seamless adaptation.
- Source: spec
- Primary owning slice: M001/S02
- Validation: FoldAwareLayout applying modifier-based WindowInsets for status bar/nav bar/IME. PlaySurface composing WebViewHost + NativeMenu as sibling overlay. S02 UAT edge cases E1-E4 verified fold/cutout/IME adaptations.

### R009 — SessionController as sole owner of nine-state lifecycle: exposes state, performs validated transitions, rejects illegal transitions, owns active game/instance identity, serializes conflicting operations, provides explicit open/close/capture/restore/background/foreground actions. Compose calls controller, not independent mutation.
- Class: core-capability
- Status: validated
- Description: SessionController as sole owner of nine-state lifecycle: exposes state, performs validated transitions, rejects illegal transitions, owns active game/instance identity, serializes conflicting operations, provides explicit open/close/capture/restore/background/foreground actions. Compose calls controller, not independent mutation.
- Why it matters: Single ownership prevents race conditions and state inconsistency across session operations.
- Source: spec
- Primary owning slice: M001/S03
- Validation: SessionController as sole lifecycle owner with nine-state state machine, 62 unit tests in SessionControllerTest covering valid transitions, illegal transitions, identity tracking, and state flow exposure.

### R010 — Move WebView/profile/navigation ownership into SessionController: create/bind/navigate in defined order, each session has intended game profile, explicit WebView destruction, closing/switching invalidates old session, navigation epochs distinguish current from stale callbacks, recomposition irrelevant to WebView ownership.
- Class: integration
- Status: validated
- Description: Move WebView/profile/navigation ownership into SessionController: create/bind/navigate in defined order, each session has intended game profile, explicit WebView destruction, closing/switching invalidates old session, navigation epochs distinguish current from stale callbacks, recomposition irrelevant to WebView ownership.
- Why it matters: WebView lifecycle correctness is the hardest technical invariant — must be centralized.
- Source: spec
- Primary owning slice: M001/S03
- Validation: Profile binding order (create→setProfile→loadUrl) enforced by SessionController.bindProfileAndNavigate(). WebViewHost is passive. Navigation epochs tracked in navigationEpoch StateFlow. SessionController at NavHost scope via remember(lifecycleOwner, registry, dao).

### R011 — Bridge validation: origin restriction, current session/game check, navigation epoch check, request/result relationship validation, malformed messages fail closed. Late callbacks from old WebView/renderer/game/epoch must not mutate current session.
- Class: failure-visibility
- Status: validated
- Description: Bridge validation: origin restriction, current session/game check, navigation epoch check, request/result relationship validation, malformed messages fail closed. Late callbacks from old WebView/renderer/game/epoch must not mutate current session.
- Why it matters: Security and correctness invariant — stale bridge responses can corrupt active session state.
- Source: spec
- Primary owning slice: M001/S03
- Validation: BridgeValidator rejects ORIGIN_MISMATCH, EPOCH_STALE, REQUEST_UNCORRELATED, PAYLOAD_TOO_LARGE, SCHEMA_UNSUPPORTED. 25 BridgeValidatorTest tests verify all five rejection modes plus valid acceptance. BridgeHost wraps WebViewCompat.addWebMessageListener.

### R012 — 60-second foreground capture cadence as lifecycle-owned coroutine. Starts only during active playable session. Cancels cleanly on state change. No overlapping uncontrolled captures. Manual/checkpoint/before-restore/exit paths integrate with same ownership model. No WorkManager for foreground cadence.
- Class: continuity
- Status: validated
- Description: 60-second foreground capture cadence as lifecycle-owned coroutine. Starts only during active playable session. Cancels cleanly on state change. No overlapping uncontrolled captures. Manual/checkpoint/before-restore/exit paths integrate with same ownership model. No WorkManager for foreground cadence.
- Why it matters: Foreground progress must be captured without leaking coroutine lifetime beyond session.
- Source: spec
- Primary owning slice: M001/S04
- Validation: 62 SessionControllerTest tests cover cadence start, 60s interval, cancellation on state change, requestForegroundCapture() one-shot, ON_PAUSE best-effort capture, and cadence restart. assembleDebug passes with 0 regressions.

### R013 — Integrate existing bootstrap/qualification into session opening: resolve game definition, resolve/create game instance/profile, bind profile, bootstrap/navigate via validated mechanism, reach PLAYING only when genuinely ready, expose real failure state on bootstrap failure.
- Class: launchability
- Status: validated
- Description: Integrate existing bootstrap/qualification into session opening: resolve game definition, resolve/create game instance/profile, bind profile, bootstrap/navigate via validated mechanism, reach PLAYING only when genuinely ready, expose real failure state on bootstrap failure.
- Why it matters: Cold-start must be reliable — failed bootstrap must not be mislabeled as success.
- Source: spec
- Primary owning slice: M001/S04
- Validation: SessionController.open() runs BootstrapDetector and transitions to Playing on success, RecoveryRequired on failure. PreLaunchSnapshot fires asynchronously. 6 unit tests cover bootstrap success/failure and async snapshot. assembleDebug passes.

### R014 — Renderer crash handling: mark WebView unusable, invalidate pending bridge requests, detach/destroy failed WebView, transition to explicit DEGRADED/RECOVERY_REQUIRED state, show native recovery UI. No automatic reload/crash loop. Bounded explicit recovery state transition.
- Class: failure-visibility
- Status: validated
- Description: Renderer crash handling: mark WebView unusable, invalidate pending bridge requests, detach/destroy failed WebView, transition to explicit DEGRADED/RECOVERY_REQUIRED state, show native recovery UI. No automatic reload/crash loop. Bounded explicit recovery state transition.
- Why it matters: Renderer crashes are inevitable — must be handled gracefully without infinite reload loops.
- Source: spec
- Primary owning slice: M001/S04
- Validation: WebViewHost onError callback fires on page failure and received error. SessionController transitions to Degraded on error. RecoveryOverlay shows in RecoveryRequired state. 5 WebViewHostTest tests verify error callback and Degraded transition. assembleDebug passes.

### R015 — Process-death recovery: persist enough durable session metadata for new process/activity to determine recoverable session. Recovery creates fresh runtime WebView bound from durable state — no in-memory WebView recovery. Persistence within Playable Hub scope only.
- Class: continuity
- Status: validated
- Description: Process-death recovery: persist enough durable session metadata for new process/activity to determine recoverable session. Recovery creates fresh runtime WebView bound from durable state — no in-memory WebView recovery. Persistence within Playable Hub scope only.
- Why it matters: Process death destroys in-memory state; durable recovery is the only path to session continuity.
- Source: spec
- Primary owning slice: M001/S04
- Validation: GameInstance has recoveryState and lastCaptureAtMillis columns. HubDatabase schema v1→v2 migration works. Cold-start recovery reads DAO and restores Degraded state via Opening transit. 6 unit tests verify persistence and migration. assembleDebug passes.

### R016 — Game switching: settle/cancel old session work, capture where required, invalidate old bridge/navigation ownership, detach/destroy old WebView, activate new instance/profile, bootstrap/navigate, expose new session authoritatively via valid state transitions. Late callback from Game A must never mutate Game B after switch.
- Class: integration
- Status: validated
- Description: Game switching: settle/cancel old session work, capture where required, invalidate old bridge/navigation ownership, detach/destroy old WebView, activate new instance/profile, bootstrap/navigate, expose new session authoritatively via valid state transitions. Late callback from Game A must never mutate Game B after switch.
- Why it matters: Cross-game leakage is a correctness invariant — switching must be atomic and isolated.
- Source: spec
- Primary owning slice: M001/S04
- Validation: settleActiveSession() correctly transits Playing→Capturing→delay→Playing→Closing→Closed (avoids illegal Capturing→Closing). Epoch increments on switch. No cross-game leakage after switching. 6 unit tests verify settling and isolation. assembleDebug passes.

## Deferred

### R017 — Archive and history tracking for snapshots, restore points, and session history. Deferred to later milestone.
- Class: admin/support
- Status: deferred
- Description: Archive and history tracking for snapshots, restore points, and session history. Deferred to later milestone.
- Why it matters: Useful later but outside Playable Hub scope.
- Source: spec
- Primary owning slice: none

### R018 — Portable backup/export via SAF for vault records. Deferred to later milestone.
- Class: admin/support
- Status: deferred
- Description: Portable backup/export via SAF for vault records. Deferred to later milestone.
- Why it matters: Outside Playable Hub scope per spec.
- Source: spec
- Primary owning slice: none

### R019 — Extended display customization: per-window display presets, targeted CSS fixes, safe rescue controls. Deferred to later milestone.
- Class: quality-attribute
- Status: deferred
- Description: Extended display customization: per-window display presets, targeted CSS fixes, safe rescue controls. Deferred to later milestone.
- Why it matters: Outside Playable Hub scope per spec.
- Source: spec
- Primary owning slice: none

## Out of Scope

### R020 — Additional games beyond Shark Incremental and FE000000. Out of scope for this planning run.
- Class: anti-feature
- Status: out-of-scope
- Description: Additional games beyond Shark Incremental and FE000000. Out of scope for this planning run.
- Why it matters: Spec explicitly limits to two known games for M001.
- Source: spec
- Primary owning slice: none

### R021 — Background preservation automation via WorkManager. Out of scope — spec explicitly prohibits WorkManager for foreground cadence and defers background work.
- Class: anti-feature
- Status: out-of-scope
- Description: Background preservation automation via WorkManager. Out of scope — spec explicitly prohibits WorkManager for foreground cadence and defers background work.
- Why it matters: Spec defers background preservation to later milestones.
- Source: spec
- Primary owning slice: none

### R022 — Assistant functionality (local LLM integration). Out of scope for this planning run.
- Class: anti-feature
- Status: out-of-scope
- Description: Assistant functionality (local LLM integration). Out of scope for this planning run.
- Why it matters: Explicitly excluded from v0.1.
- Source: spec
- Primary owning slice: none

## Traceability

| ID | Class | Status | Primary owner | Supporting | Proof |
| --- | --- | --- | --- | --- | --- |
| R001 | core-capability | validated | M001/S01 | none | GameRegistry loads both known game definitions from assets/games/, rejects invalid JSON, provides deterministic ordering. Verified by GameRegistryTest (2 tests) and assembleDebug. |
| R002 | continuity | validated | M001/S01 | none | GameInstance entity with narrow schema (gameId PK, profileName, sessionIdentity, lastPlayedAtMillis, resumeToken nullable). GameInstanceDao with getAllOrdered Flow, suspend get/upsert/delete. Verified by GameInstanceDaoTest (6 tests). |
| R003 | primary-user-loop | validated | M001/S01 | none | Material 3 LauncherScreen with Scaffold+LazyColumn, Card items with displayName, Resume/Launch status chip, action buttons wired to callbacks. Last-played ordering via DAO. Verified by assembleDebug and launcher rendering in HubNavigation. |
| R004 | launchability | validated | M001/S01 | none | MainActivity hosts HubNavigation with two-route graph (launcher, play_surface/{gameId}). PlaySurfaceStub as S02 placeholder. Both known definitions load, invalid rejected, launcher renders, last-played ordering deterministic, launch/resume selects intended instance. |
| R005 | core-capability | validated | M001/S02 | none | SessionState sealed class with nine data-object states and exhaustive canTransition validation covering all 81 pairs. 30 unit tests in SessionStateTest cover valid transitions, illegal transitions, and identity tracking. |
| R006 | core-capability | validated | M001/S02 | none | WebViewHost composable using AndroidView with remember(gameId, state) for stable identity. Profile bound before navigation via WebViewCompat.setProfile(). DisposableEffect cleanup. Closed-state degradation. 9 WebViewHostTest tests verify stable identity and lifecycle. |
| R007 | operability | validated | M001/S02 | none | NativeMenu with MenuAction sealed class (7 actions), BackHandler with deterministic behavior, 48dp touch targets, width-based layout branching. 30 NativeMenuTest tests verify all actions and Back behavior. |
| R008 | quality-attribute | validated | M001/S02 | none | FoldAwareLayout applying modifier-based WindowInsets for status bar/nav bar/IME. PlaySurface composing WebViewHost + NativeMenu as sibling overlay. S02 UAT edge cases E1-E4 verified fold/cutout/IME adaptations. |
| R009 | core-capability | validated | M001/S03 | none | SessionController as sole lifecycle owner with nine-state state machine, 62 unit tests in SessionControllerTest covering valid transitions, illegal transitions, identity tracking, and state flow exposure. |
| R010 | integration | validated | M001/S03 | none | Profile binding order (create→setProfile→loadUrl) enforced by SessionController.bindProfileAndNavigate(). WebViewHost is passive. Navigation epochs tracked in navigationEpoch StateFlow. SessionController at NavHost scope via remember(lifecycleOwner, registry, dao). |
| R011 | failure-visibility | validated | M001/S03 | none | BridgeValidator rejects ORIGIN_MISMATCH, EPOCH_STALE, REQUEST_UNCORRELATED, PAYLOAD_TOO_LARGE, SCHEMA_UNSUPPORTED. 25 BridgeValidatorTest tests verify all five rejection modes plus valid acceptance. BridgeHost wraps WebViewCompat.addWebMessageListener. |
| R012 | continuity | validated | M001/S04 | none | 62 SessionControllerTest tests cover cadence start, 60s interval, cancellation on state change, requestForegroundCapture() one-shot, ON_PAUSE best-effort capture, and cadence restart. assembleDebug passes with 0 regressions. |
| R013 | launchability | validated | M001/S04 | none | SessionController.open() runs BootstrapDetector and transitions to Playing on success, RecoveryRequired on failure. PreLaunchSnapshot fires asynchronously. 6 unit tests cover bootstrap success/failure and async snapshot. assembleDebug passes. |
| R014 | failure-visibility | validated | M001/S04 | none | WebViewHost onError callback fires on page failure and received error. SessionController transitions to Degraded on error. RecoveryOverlay shows in RecoveryRequired state. 5 WebViewHostTest tests verify error callback and Degraded transition. assembleDebug passes. |
| R015 | continuity | validated | M001/S04 | none | GameInstance has recoveryState and lastCaptureAtMillis columns. HubDatabase schema v1→v2 migration works. Cold-start recovery reads DAO and restores Degraded state via Opening transit. 6 unit tests verify persistence and migration. assembleDebug passes. |
| R016 | integration | validated | M001/S04 | none | settleActiveSession() correctly transits Playing→Capturing→delay→Playing→Closing→Closed (avoids illegal Capturing→Closing). Epoch increments on switch. No cross-game leakage after switching. 6 unit tests verify settling and isolation. assembleDebug passes. |
| R017 | admin/support | deferred | none | none | unmapped |
| R018 | admin/support | deferred | none | none | unmapped |
| R019 | quality-attribute | deferred | none | none | unmapped |
| R020 | anti-feature | out-of-scope | none | none | unmapped |
| R021 | anti-feature | out-of-scope | none | none | unmapped |
| R022 | anti-feature | out-of-scope | none | none | unmapped |

## Coverage Summary

- Active requirements: 0
- Mapped to slices: 0
- Validated: 16 (R001, R002, R003, R004, R005, R006, R007, R008, R009, R010, R011, R012, R013, R014, R015, R016)
- Unmapped active requirements: 0
