# Incremental Hub — Playable Hub GSD Planning Seed

## Headless planner contract

This file is the complete project vision for this GSD planning run.

Create exactly **one GSD milestone: M001 — Playable Hub**.

The GSD database is fresh. For this planning run there are no historical GSD
milestones and there are no future GSD milestones to register or queue.

This is a single-milestone project for GSD planning purposes.

Do not derive, register, queue, reserve, or materialise any additional
milestones from repository documents, git history, historical planning
artefacts, or broader product ideas.

Do not use other repository documents to expand scope. They may be inspected
only as implementation evidence for the work explicitly described below.

The generated PROJECT Milestone Sequence must contain exactly one entry:

- M001: Playable Hub

Do not call `gsd_milestone_generate_id` merely to inspect or reserve further
milestone IDs.

## Existing implementation baseline

The repository already contains validated feasibility implementation that is
an input to this milestone and must be reused rather than recreated:

- AndroidX WebKit named-profile isolation;
- profile binding before navigation;
- native-origin/bootstrap behaviour;
- versioned game-definition and adapter concepts;
- Shark Incremental and FE000000 qualification work;
- capture → restore → fresh-profile verification;
- an integrated Shark Incremental spike completing its verification sequence.

The validated runtime is PixelFold_x64, x86_64, Android API 36,
WebView 133.0.6943.137.

ARM64 hardware is not an acceptance requirement.

## Milestone goal

Deliver a usable Playable Hub in which a user can:

1. discover registered games;
2. see them in a native launcher;
3. launch or resume a game;
4. play inside a stable named-profile WebView session;
5. use the native in-session menu;
6. have foreground progress captured;
7. survive ordinary lifecycle/process recreation;
8. recover explicitly from renderer failure;
9. switch games without cross-game session, profile, or bridge leakage.

Archive/history, portable backup/export, extended display customisation,
support for additional games, background preservation automation, and
assistant functionality are outside this planning run.

# Architecture invariants

## SessionController ownership

`SessionController` is the sole owner of session lifecycle and session transitions.

It owns:

- active game instance identity;
- WebView creation/destruction;
- named-profile binding;
- navigation;
- navigation epoch;
- bridge validity;
- capture/restore lifecycle state;
- renderer-failure handling;
- game switching.

Compose callbacks must request actions from `SessionController`; they must not independently implement session transitions.

## Session state

Use one explicit sealed session-state model with these nine states:

- `CLOSED`
- `OPENING`
- `PLAYING`
- `CAPTURING`
- `BACKGROUND`
- `RESTORING`
- `DEGRADED`
- `RECOVERY_REQUIRED`
- `CLOSING`

Transitions belong in `SessionController`.

Illegal transitions must be rejected rather than silently coerced.

## WebView lifetime

The playable WebView must remain outside transient Compose branches.

Normal recomposition, native-menu visibility, orientation/layout changes, and other UI-state changes must not create a new WebView or change its identity.

Do not store an Activity-bound WebView in a long-lived ViewModel.

Bind the correct AndroidX WebKit named profile before navigation.

Never silently fall back to a shared/default profile when a game requires its named profile.

## Bridge

Use the existing bridge/bootstrap patterns where applicable.

Web message handling must be origin restricted.

Bridge requests/results belong to the current navigation/session epoch. Requests associated with a destroyed renderer or obsolete navigation epoch must not later mutate the active session.

## Persistence

Process/activity recreation must recover through durable state rather than by assuming an in-memory WebView survived.

Use only the minimum persistence necessary for Playable Hub/session lifecycle.

Do **not** pull later archive, backup, snapshot-history, export/import-library, or full vault scope into this milestone.

## Capture policy

While a game is actively foregrounded, schedule capture from a lifecycle-owned coroutine on a **60-second cadence**.

Also support the capture triggers required by the this milestone contract, including manual/checkpoint, before-restore, and exit/lifecycle boundaries where applicable.

Do not use WorkManager for the 60-second foreground cadence.

Capture work must not continue indefinitely after its owning session closes or changes games.

## Failure behavior

Renderer failure must:

1. mark the current WebView unusable;
2. invalidate pending bridge requests for that renderer/navigation epoch;
3. detach/destroy the failed WebView;
4. transition to an explicit degraded/recovery state;
5. show native recovery UI.

Do not create an automatic reload/crash loop.

Recovery must be an explicit, bounded state transition.

---

# Slice S01 — Game Registry and Launcher

## Goal

Turn the existing game definitions into the native entry point for the application.

### T01 — GameRegistry

Implement `GameRegistry` over the game definitions in `assets/games`.

Requirements:

- enumerate registered game definitions;
- parse them using the existing schema/model;
- validate them using the existing `GameDefinitionValidator`;
- reject invalid definitions explicitly;
- provide deterministic ordering/lookup;
- expose enough metadata for launcher rendering.

Do not create a second competing definition parser or validator.

Tests must cover valid Shark/FE000000 definitions and invalid-definition rejection.

### T02 — Minimal GameInstance persistence

Add the minimum Room persistence required for launcher/session continuity:

- `GameInstance`;
- DAO;
- `HubDatabase`;
- game identity;
- profile/session identity required by this milestone;
- last-played/resume metadata required by launcher behavior.

Keep this deliberately narrow.

Do not implement later archive/vault/history schemas merely because Room now exists.

### T03 — Launcher UI

Implement a Material 3 launcher showing registered games as native cards.

Requirements:

- recently played first;
- honest availability/status labels;
- primary Resume behavior where a resumable instance exists;
- otherwise launch/start behavior;
- Shark Incremental and FE000000 must be represented from registry data rather than hard-coded duplicate UI metadata.

### T04 — Navigation wiring

Make the launcher the normal application entry surface and wire launch/resume navigation into the Play Surface.

Acceptance for S01:

- both known game definitions load through `GameRegistry`;
- invalid definitions are rejected;
- launcher renders the registry;
- last-played ordering is deterministic;
- launch/resume selects the intended game instance;
- application builds and existing validated feasibility baseline functionality is not regressed.

---

# Slice S02 — Play Surface and Native Menu

Depends on S01.

## Goal

Provide the stable native shell in which a game session can live without Compose lifecycle events accidentally recreating the game.

### T01 — SessionState

Implement the nine-state sealed session-state model defined above.

Include transition validation suitable for unit testing.

### T02 — Stable WebViewHost

Implement the Compose/AndroidView WebView host.

Requirements:

- WebView identity survives ordinary recomposition;
- WebView is not created inside transient menu/loading/error branches;
- named profile is bound before navigation;
- Activity-bound WebView is not retained in a long-lived ViewModel;
- disposal is explicit and owned by the session lifecycle.

### T03 — Native menu

Implement the native menu and menu handle.

Implement **all seven menu actions defined by the this milestone contract exactly as specified there**. Do not invent substitutes or silently omit actions.

Requirements:

- menu overlays/adjacent UI must not recreate the WebView;
- Android Back behavior is defined and deterministic;
- touch targets meet the original UX requirements, including the 48dp minimum target;
- opening/closing the menu does not change game session identity.

### T04 — Fold-aware/layout behavior

Implement the Play Surface adaptation required by this milestone contract, including:

- display cutouts;
- fold/hinge constraints;
- keyboard/IME interaction;
- relevant configuration/layout changes.

Layout adaptation must not recreate the active game session unnecessarily.

Acceptance for S02:

- a game can remain active while menu visibility and layout state change;
- WebView identity is demonstrably stable;
- named-profile binding remains correct;
- Back/menu behavior is deterministic;
- build and relevant tests pass.

---

# Slice S03 — SessionController Core

Depends on S01 and S02.

## Goal

Move session ownership out of UI callbacks and into a single testable lifecycle controller.

### T01 — SessionController state machine

Implement `SessionController` as the owner of the nine-state lifecycle.

It must:

- expose current session state;
- perform validated transitions;
- reject illegal transitions;
- own active game/instance identity;
- serialize conflicting lifecycle operations where required;
- provide explicit open, close, capture, restore, background/foreground, and related session actions.

Compose must call this controller instead of independently mutating lifecycle state.

### T02 — Profile binding and WebView lifecycle

Move WebView/profile/navigation ownership into `SessionController`.

Requirements:

- create/bind/navigate in a defined order;
- each active session has the intended game profile;
- WebView destruction is explicit;
- closing/switching invalidates the old session before a replacement becomes authoritative;
- navigation epochs distinguish current callbacks from stale callbacks;
- ordinary recomposition remains irrelevant to WebView ownership.

### T03 — Bridge validation

Implement validation of incoming bridge messages/results.

At minimum validate:

- expected origin;
- current session/game;
- current navigation epoch;
- expected request/result relationship where applicable;
- malformed/unexpected messages fail closed.

A late callback from an old WebView, renderer, game, or navigation epoch must not mutate the current session.

Acceptance for S03:

- session transition behavior is unit tested;
- illegal transitions fail deterministically;
- session/profile/WebView identity has one owner;
- stale bridge responses cannot affect the current session;
- existing game adapters/bootstrap behavior are reused rather than duplicated.

---

# Slice S04 — SessionController Resilience

Depends on S03.

## Goal

Make the playable session robust enough for ordinary foreground use and bounded recovery.

### T01 — Foreground capture cadence

Implement the 60-second foreground capture cadence as a lifecycle-owned coroutine.

Requirements:

- starts only while appropriate for the active playable session;
- cancels cleanly when ownership/state changes;
- does not create overlapping uncontrolled captures;
- manual/checkpoint/before-restore/exit capture paths integrate with the same capture ownership model;
- no WorkManager polling for this foreground cadence.

Tests should use deterministic clocks/triggers where possible rather than sleeping for real 60-second intervals.

### T02 — Cold-start bootstrap integration

Integrate the existing bootstrap/qualification mechanisms into normal session opening.

Opening a game must:

- resolve the correct game definition;
- resolve/create the intended game instance/profile;
- bind the profile;
- bootstrap/navigate using the validated mechanism;
- reach `PLAYING` only when the session is genuinely ready;
- expose a real failure state when readiness/bootstrap fails.

Do not label a failed bootstrap as success merely because navigation occurred.

### T03 — Renderer crash handling

Handle WebView renderer failure as specified in the architecture invariants.

The failed renderer must not be reused.

Invalidate its bridge requests and transition through explicit recovery UI/state.

Do not implement an uncontrolled automatic reload loop.

### T04 — Process-death recovery

Persist enough durable session metadata that a newly created process/activity can determine what session should be recovered.

Recovery must create a fresh runtime WebView and bind it from durable state; it must not depend on recovering an in-memory WebView object.

Keep persistence within Playable Hub scope and do not implement later archive/vault features.

### T05 — Game switching

Implement switching from one registered game to another.

Required ordering:

- settle/cancel work belonging to the old session;
- capture where required by lifecycle policy;
- invalidate old bridge/navigation ownership;
- detach/destroy old WebView;
- activate the new instance/profile;
- bootstrap/navigate;
- expose the new session as authoritative only through valid state transitions.

A late callback from Game A must never mutate Game B after the switch.

Acceptance for S04:

- foreground capture cadence is lifecycle bounded;
- cold-start launch reaches a real playable state or an explicit failure state;
- renderer crash produces bounded native recovery behavior;
- process recreation restores through durable state and a fresh WebView;
- game switching leaves no cross-game callback/profile ownership;
- no reload loop;
- all relevant unit/integration tests and `./gradlew :app:assembleDebug --no-daemon` pass.

---

# Global implementation constraints

- Read existing implementation before adding new abstractions.
- Prefer extension/refactoring of validated validated feasibility baseline components over parallel replacement code.
- Preserve the existing game definitions and adapters unless this milestone establishes a concrete defect requiring change.
- Do not weaken tests or acceptance criteria to make failures disappear.
- Do not classify a failure introduced by current work as “pre-existing” without evidence.
- Do not change production manifest/security behavior solely to enable diagnostics.
- Use deterministic test hooks rather than arbitrary wall-clock sleeps where practical.
- Android runtime verification may use the validated PixelFold_x64 x86_64 API 36 environment. Do not introduce an ARM64 requirement.
- A build passing is necessary but not sufficient where the task requires runtime/lifecycle proof.
- If an environmental limitation genuinely prevents an acceptance check, block with specific evidence rather than silently weakening the check.
- Do not expand into later milestone scope.

## Definition of Done

Playable Hub is done when a user can launch the application, choose a registered game, play it inside a stable isolated WebView session, open/close the native menu without losing that session, have progress captured under the defined policy, survive ordinary lifecycle/process recovery, recover explicitly from renderer failure, and switch games without leaking profile/session/bridge ownership.

The implementation must preserve the validated validated feasibility baseline foundation and leave later archive/vault/history functionality for its designated milestones.
