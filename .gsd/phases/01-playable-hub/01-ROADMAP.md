# M001: Playable Hub

**Vision:** Deliver a usable Playable Hub in which a user can discover registered games, see them in a native launcher, launch or resume a game, play inside a stable named-profile WebView session, use the native in-session menu, have foreground progress captured, survive ordinary lifecycle/process recreation, recover explicitly from renderer failure, and switch games without cross-game session, profile, or bridge leakage.

## Success Criteria

- User can launch app, choose registered game, play in stable isolated WebView
- Open/close native menu without losing session
- Progress captured under 60-second cadence
- Survive ordinary lifecycle/process recovery
- Recover explicitly from renderer failure
- Switch games without leaking profile/session/bridge ownership
- Existing validated feasibility baseline functionality not regressed
- ./gradlew :app:assembleDebug --no-daemon passes

## Slices

- [ ] **S01: Game Registry and Launcher** `risk:high` `depends:[]`
  > After this: Launcher shows registered games with resume/launch; invalid definitions rejected; last-played ordering deterministic

- [ ] **S02: Play Surface and Native Menu** `risk:high` `depends:[S01]`
  > After this: Game remains active while menu opens/closes; WebView identity stable across recomposition and layout changes

- [ ] **S03: SessionController Core** `risk:medium` `depends:[S01,S02]`
  > After this: Session transitions unit tested; illegal transitions fail deterministically; stale bridge responses rejected

- [ ] **S04: SessionController Resilience** `risk:medium` `depends:[S03]`
  > After this: Foreground capture bounded; renderer crash produces recovery UI; process recovery works; game switching isolated

## Boundary Map

## Boundary Map

### S01 → S02
Produces:
- `GameRegistry` → enumerate/lookup validated game definitions
- `GameInstance` entity + DAO → last-played/resume metadata
- `HubDatabase` → minimal persistence for launcher/session continuity
- Launcher composable → Material 3 cards from registry data

Consumes from S01:
- GameRegistry → launcher rendering
- GameInstance DAO → resume/launch decisions

### S01 → S03
Produces:
- GameRegistry → game definition resolution for session opening
- GameInstance DAO → profile/session identity for session binding

Consumes from S01:
- nothing (S03 consumes S01 indirectly via S02)

### S01 → S04
Produces:
- GameRegistry → game definition for cold-start bootstrap
- GameInstance DAO → durable session metadata for process recovery

Consumes from S01:
- nothing (S04 consumes S01 indirectly via S02/S03)

### S02 → S03
Produces:
- `SessionState` sealed class → nine-state model with transition validation
- `WebViewHost` composable → stable AndroidView WebView host
- Native menu composable → seven actions, Back behavior, 48dp targets
- Fold-aware layout adapters → cutout/hinge/IME handling

Consumes from S02:
- SessionState → SessionController state machine
- WebViewHost → SessionController WebView lifecycle
- Native menu → SessionController action handlers

### S02 → S04
Produces:
- SessionState → states consumed by resilience logic
- WebViewHost → stable host consumed by crash handling

Consumes from S02:
- nothing additional (S04 consumes S02 indirectly via S03)

### S03 → S04
Produces:
- `SessionController` → nine-state lifecycle with validated transitions
- Bridge validation → origin/epoch/relation checks
- Profile binding logic → create/bind/navigate order

Consumes from S03:
- SessionController → capture cadence ownership
- SessionController → crash handling integration
- SessionController → process recovery integration
- SessionController → game switching orchestration
<!-- gsd:state-version=1:0 -->
