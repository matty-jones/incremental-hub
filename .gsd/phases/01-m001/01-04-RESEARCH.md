# S04: SessionController Resilience — Research

**Milestone:** M001 / **Slice:** S04  
**Research date:** 2026-10-10  
**Researcher:** scout

---

## Summary

S04 adds four resilience capabilities to the SessionController core built in S03: **foreground capture cadence**, **cold-start bootstrap integration**, **renderer crash handling**, and **process-death recovery**, plus **game-switching isolation**. The existing codebase has the state machine (S03), bridge validation (S03), stable WebView host (S02), and spike bootstrap code (M0) — but none of the S04 resilience logic is wired in yet. All five requirements map to targeted modifications of `SessionController`, `WebViewHost`, and `GameInstance`, plus new test coverage.

**Current build status:** `./gradlew :app:assembleDebug` passes ✓; all 10 existing unit tests pass ✓.

---

## Active Requirements This Slice Owns

| ID | Class | Description |
|----|-------|-------------|
| R012 | continuity | 60-second foreground capture cadence as lifecycle-owned coroutine |
| R013 | launchability | Integrate BootstrapDetector/PreLaunchSnapshot into session opening |
| R014 | failure-visibility | Renderer crash handling: DEGRADED/RECOVERY_REQUIRED states + native recovery UI |
| R015 | continuity | Process-death recovery: durable metadata for fresh-process session restore |
| R016 | integration | Game switching: settle old session, invalidate old ownership, activate new |

---

## What Exists (from S01–S03 and M0)

### SessionController (`session/SessionController.kt`)
- Nine-state sealed `SessionState` with validated `canTransition()` — fully tested (S03)
- `open()`, `close()`, `requestCapture()`, `requestBackground()`, `requestForeground()`, `requestRestore()`, `requestDegraded()`, `requestRecovery()`, `requestClosing()` — all exist
- `bindProfileAndNavigate()` — exists, creates/retrieves profile, persists GameInstance
- `resolveGameAndBindProfile()` — private helper called during `open()`
- `destroyWebViewAndFinalize()` — destroys WebView, resets identity, increments epoch
- `forceClose()` — for ON_DESTROY lifecycle
- `webView: WebView?` — @Volatile property set by WebViewHost
- `navigationEpoch: StateFlow<Int>` — incremented on close
- Lifecycle attachment via `LifecycleEventObserver` — handles ON_DESTROY, ON_PAUSE
- **Gap:** No periodic capture coroutine. `requestCapture()` exists but is manual-only. `open()` uses a hardcoded 2-second `delay()` instead of real bootstrap.

### WebViewHost (`ui/WebViewHost.kt`)
- Stable `AndroidView` with `remember(gameId, state)` key
- Profile-bound before `loadUrl()` — ordering invariant enforced
- Handles state transitions in `update` lambda: navigates on Opening/Restoring/Degraded, destroys on RecoveryRequired/Closing
- `onDestroy` callback passed to controller
- **Gap:** No WebView error callback (`onPageFailed`, `onReceivedError`). Crash detection is absent — crashed WebView silently stays in Playing state.

### RecoveryOverlay (`ui/RecoveryOverlay.kt`)
- Composable shown when `state is SessionState.RecoveryRequired`
- Two buttons: Retry and Close
- **Gap:** Nothing triggers RecoveryRequired in production — `requestDegraded()` is only callable via menu, no crash auto-detection.

### BootstrapDetector / PreLaunchSnapshot (`bootstrap/`)
- `BootstrapDetector.test(profileStore)` — probes WebViewAssetLoader vs loadDataWithBaseURL, writes JSON report
- `PreLaunchSnapshot.capture(profileStore, detectorResult)` — captures pre-launch snapshot envelope
- Both run on `Dispatchers.Main`, clean up test profiles
- **Gap:** Not integrated into `SessionController.open()`. Currently dead code in the production path.

### GameInstance DAO (`database/`)
- Entity: `gameId` (PK), `profileName`, `sessionIdentity`, `lastPlayedAtMillis`, `resumeToken` (nullable)
- Methods: `getAllOrdered()`, `get()`, `upsert()`, `delete()`
- **Gap:** No `recoveryState` or `lastCaptureAtMillis` field. No mechanism to track whether a session was mid-capture at process death.

### HubNavigation (`navigation/HubNavigation.kt`)
- NavHost with `launcher` and `play_surface/{gameId}` routes
- SessionController created at NavHost scope via `remember(lifecycleOwner, registry, dao)`
- **Gap:** No cold-start recovery logic. On app relaunch, controller starts in Closed state regardless of prior durable session.

### Existing Tests
- `SessionControllerTest` — 37 tests covering valid transitions, illegal transitions, identity tracking, profile binding
- `SessionStateTest` — 81-pair exhaustive transition matrix
- `BridgeValidatorTest` — 25 tests for five rejection modes
- `GameInstanceDaoTest` — 6 tests for Room DAO
- `WebViewHostTest` — 4 pure-JVM tests for factory ordering
- `NativeMenuTest` — menu behavior
- **Gap:** No tests for capture cadence, bootstrap integration, crash handling, process recovery, or game switching.

---

## Gap Analysis by Requirement

### R012 — Foreground Capture Cadence
**What exists:** `requestCapture()` manual method, `SessionState.Capturing` state.  
**What's missing:** A lifecycle-bound coroutine that:
1. Starts when state transitions to PLAYING
2. Fires every 60 seconds (transitions Playing→Capturing, waits, transitions back to Playing)
3. Cancels cleanly when state leaves PLAYING (background, close, crash)
4. Requests a best-effort capture before suspension (ON_PAUSE)

**Implementation approach:**
- Add `private var captureJob: Job? = null` to SessionController
- Add `private var captureDelayJob: Job? = null` for the 60-second delay
- In `transitionTo()`, when target is PLAYING, start the cadence coroutine
- When target leaves PLAYING, cancel the cadence
- Add `requestForegroundCapture()` that does a one-shot Playing→Capturing→Playing transition
- Wire `onPause` lifecycle event to trigger best-effort capture before suspension

**Risk:** Low. Coroutine lifecycle is well-understood. The cadence is independent of WebView mechanics.

### R013 — Cold-start Bootstrap Integration
**What exists:** `BootstrapDetector` and `PreLaunchSnapshot` classes fully implemented.  
**What's missing:** Integration into `SessionController.open()`.

**Current `open()` flow:**
```
Closed → Opening → (delay 2s) → Playing  (or RecoveryRequired on exception)
```

**Required `open()` flow:**
```
Closed → Opening → resolve game → create/retrieve profile
       → run BootstrapDetector.test() → if qualified mechanism found:
           → navigate via qualified mechanism → wait for onPageFinished
           → run PreLaunchSnapshot.capture() (non-blocking, fire-and-forget)
           → transition to Playing
         else:
           → transition to RecoveryRequired
```

**Implementation approach:**
- Modify `resolveGameAndBindProfile()` to also run bootstrap detection
- Add `bootstrapResult: DetectorResult?` state to SessionController
- In `open()`, after binding profile, call `BootstrapDetector.test(profileStore)` 
- Only transition to Playing if `qualifiedMechanism != null`
- Log real failure state if bootstrap fails (don't mask as success)
- PreLaunchSnapshot.capture() should be fire-and-forget on a background scope

**Risk:** Medium. BootstrapDetector requires ProfileStore (AndroidX WebKit) which needs Robolectric setup for testing. The spike code works on device but needs test stubs.

### R014 — Renderer Crash Handling
**What exists:** `requestDegraded()`, `requestRecovery()`, `RecoveryOverlay` composable, DEGRADED/RECOVERY_REQUIRED states.  
**What's missing:** WebView crash detection in WebViewHost.

**Current state:** Crash handling is manual-only (menu button triggers `requestDegraded()`). No automatic detection.

**Implementation approach:**
- Add `onError: (String) -> Unit` callback parameter to `WebViewHost`
- In WebViewHost's `webViewClient`, override `onPageFailed` and `onReceivedError`
- On crash: call `controller.requestDegraded()`, log the error
- In SessionController, ensure `requestDegraded()` transitions Playing→Degraded and logs with gameId
- Ensure RecoveryOverlay is shown (already wired in PlaySurface)
- Add `requestRecovery()` validation: Degraded→RecoveryRequired, then user can Retry (opens new session) or Close

**Risk:** Medium. WebView error callbacks in Robolectric may need special handling. Need to verify `onPageFailed` vs `onReceivedError` coverage.

### R015 — Process-Death Recovery
**What exists:** `GameInstance` entity with `sessionIdentity`, `profileName`, `lastPlayedAtMillis`.  
**What's missing:** Recovery metadata and cold-start recovery logic.

**Current state:** On app relaunch, SessionController starts in Closed state. No recovery from durable data.

**Implementation approach:**
- Add `recoveryState: String?` column to `GameInstance` (nullable, stores "DEGRADED" or "RECOVERY_REQUIRED")
- Add `lastCaptureAtMillis: Long` column to `GameInstance` (tracks last successful capture time)
- In `SessionController.onAttached()`, query DAO for active instance with non-null recoveryState
- If found, transition to appropriate state (Degraded or RecoveryRequired) and restore profile name
- Add `recoverSession(gameId: String)` method that reads durable state and re-initiates open
- Persist `recoveryState` on state transitions to Degraded/RecoveryRequired
- Clear `recoveryState` on successful Playing transition

**Risk:** Medium. Room schema migration (version 1→2) needed. Test stub DAO needs updating.

### R016 — Game Switching
**What exists:** `open()` rejects when already active (Playing, Capturing, etc.).  
**What's missing:** Explicit switching path.

**Current behavior:** Calling `open("game-B")` while Playing "game-A" is a no-op with a warning log.

**Required behavior:**
1. If active session exists, settle it first:
   - If Playing: requestCapture() (best-effort), then requestClosing()
   - If Background: requestForeground() then requestClosing()
   - If Degraded/RecoveryRequired: requestClosing()
2. Invalidate old bridge/navigation ownership (epoch increment, WebView destroy)
3. Activate new session via valid transitions

**Implementation approach:**
- Modify `open()` to handle the "already active" case by settling old session first
- Add `settleActiveSession()` private method that does the appropriate settle path
- Ensure epoch is incremented and WebView is destroyed before opening new game
- Add test for switching from Shark to FE000000 with no leakage

**Risk:** Low-Medium. The state machine already supports the transitions; just need to orchestrate the sequence.

---

## Natural Seams / Independent Work Units

### Seam 1: Capture Cadence (R012)
- Standalone: adds coroutine lifecycle to SessionController
- No dependency on other S04 work
- Can be tested in isolation with stub DAO
- **Files:** `SessionController.kt`, `SessionControllerTest.kt`

### Seam 2: Bootstrap Integration + Game Switching (R013 + R016)
- Both modify `SessionController.open()` and session lifecycle
- Bootstrap integration changes the Opening→Playing transition path
- Game switching changes the "already active" rejection path
- Can be implemented together as they both touch `open()`
- **Files:** `SessionController.kt`, `SessionControllerTest.kt`

### Seam 3: Crash Handling (R014)
- WebViewHost needs error callback
- SessionController needs crash response logic
- RecoveryOverlay already exists
- Independent of cadence and bootstrap
- **Files:** `WebViewHost.kt`, `SessionController.kt`, `PlaySurface.kt`, `WebViewHostTest.kt`

### Seam 4: Process-Death Recovery (R015)
- GameInstance schema change (Room migration)
- SessionController cold-start recovery logic
- DAO test updates
- Independent of other seams
- **Files:** `GameInstance.kt`, `GameInstanceDao.kt`, `HubDatabase.kt`, `SessionController.kt`, `GameInstanceDaoTest.kt`, `SessionControllerTest.kt`

---

## First Proof / Highest Risk

**Bootstrap integration (R013) is the highest risk.** Reasons:
1. `BootstrapDetector.test()` requires real `ProfileStore` (AndroidX WebKit) — Robolectric may not fully support it
2. The spike code was written for device testing, not unit testing
3. If bootstrap fails in test, the entire `open()` path fails
4. Need to decide: mock ProfileStore in tests, or use Robolectric with WebView stubs

**Mitigation:** Create a `BootstrapDetectorStub` for tests that returns a qualified mechanism without real WebView. The real detector stays for device testing.

**Second risk: Process-death recovery schema migration (R015).** Room migrations in tests require explicit migration rules. The existing `GameInstanceDaoTest` uses in-memory database — adding a column is straightforward but needs the migration path documented.

---

## Verification Strategy

### Unit Tests (JVM + Robolectric)
- **R012:** Test that cadence starts on Playing, fires at ~60s intervals, cancels on state change. Test `requestForegroundCapture()` one-shot. (≥8 tests)
- **R013:** Test that `open()` runs bootstrap, transitions to Playing on success, RecoveryRequired on failure. Test that PreLaunchSnapshot is fired asynchronously. (≥6 tests)
- **R014:** Test that WebView error callback triggers Degraded transition. Test that RecoveryOverlay is shown in RecoveryRequired state. (≥5 tests)
- **R015:** Test that cold-start recovery reads DAO and restores state. Test that recoveryState is persisted on Degraded transition. (≥6 tests)
- **R016:** Test that switching games settles old session, increments epoch, activates new game. Test no cross-game leakage. (≥6 tests)

**Total new tests: ~31**

### Build Verification
- `./gradlew :app:assembleDebug --no-daemon` must pass
- All existing tests must continue to pass (no regressions)

### Integration Verification (manual/device)
- Launch app → play game → wait 60s → verify capture logged
- Background app → foreground → verify capture cadence resumes
- Trigger renderer crash (devtools) → verify Degraded state + RecoveryOverlay
- Force-stop app → relaunch → verify last game recoverable
- Switch from Shark to FE000000 → verify no localStorage leakage

---

## Implementation Landscape

### Files to Modify
| File | Change |
|------|--------|
| `session/SessionController.kt` | Add capture cadence, bootstrap integration, crash response, process recovery, game switching |
| `ui/WebViewHost.kt` | Add error callback, wire to controller |
| `database/GameInstance.kt` | Add `recoveryState`, `lastCaptureAtMillis` columns |
| `database/HubDatabase.kt` | Bump schema version, add migration |
| `navigation/HubNavigation.kt` | Add cold-start recovery trigger |

### Files to Create
| File | Purpose |
|------|---------|
| `session/CaptureCadence.kt` (or inline in SessionController) | Cadence coroutine logic |
| `session/BootstrapIntegration.kt` (or inline) | Bootstrap result handling |
| `test/.../CaptureCadenceTest.kt` | Cadence unit tests |
| `test/.../BootstrapIntegrationTest.kt` | Bootstrap unit tests |
| `test/.../CrashHandlingTest.kt` | Crash handling tests |
| `test/.../ProcessRecoveryTest.kt` | Process recovery tests |
| `test/.../GameSwitchingTest.kt` | Game switching tests |

### Files to Update (tests)
| File | Change |
|------|--------|
| `session/SessionControllerTest.kt` | Add ~31 new tests |
| `database/GameInstanceDaoTest.kt` | Add recoveryState/lastCaptureAtMillis tests |
| `ui/WebViewHostTest.kt` | Add error callback tests |

### Dependencies Already Present
- `androidx.lifecycle:lifecycle-runtime-ktx` — CoroutineScope, LifecycleEventObserver
- `androidx.webkit:webkit:1.14.0` — ProfileStore, WebViewCompat
- `androidx.room:room-runtime:2.6.1` — Schema migration support
- `org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1` — test dispatchers

### New Dependencies Needed
- None. All required APIs are in existing deps.

---

## Key Decisions

### D1: Capture cadence coroutine scope
**Chose:** Lifecycle-bound `CoroutineScope(Dispatchers.Main + Job())` already on SessionController. Cadence job is a child of this scope, cancelled on detach/destroy.  
**Rationale:** Matches existing pattern (MEM004). No new scope needed.

### D2: Bootstrap test stub
**Chose:** `StubBootstrapDetector` for unit tests that returns qualified `WebViewAssetLoader` without real WebView. Real `BootstrapDetector` used on device.  
**Rationale:** Robolectric+ProfileStore integration is fragile. Stub isolates unit tests from WebView infrastructure.

### D3: Game switching settle sequence
**Chose:** If active session is Playing: requestCapture() → wait for Capturing → requestClosing(). If Background: requestForeground() → requestClosing(). If Degraded/RecoveryRequired: requestClosing().  
**Rationale:** Honors the state machine — each step is a valid transition. Best-effort capture before switch preserves progress.

### D4: Recovery state persistence
**Chose:** Add `recovery_state TEXT` and `last_capture_at_millis INTEGER` to GameInstance. Persist on Degraded/RecoveryRequired transitions. Clear on Playing transition.  
**Rationale:** Minimal schema change. Sufficient for M001 scope. Room migration v1→v2 handles existing rows (new columns default to null/0).

---

## Forward Intelligence

### Fragility Points
1. **BootstrapDetector in Robolectric:** The spike code uses real ProfileStore and WebView. Unit tests need stubs. If stubs are insufficient, some R013 tests may need to be Robolectric-based with `@Config(sdk = [30])`.
2. **Room migration v1→v2:** Adding columns is backward-compatible (nullable defaults). But `GameInstanceDaoTest` uses in-memory DB — need to verify migration path doesn't break existing tests.
3. **WebView error callbacks in tests:** `onPageFailed` and `onReceivedError` may not fire in Robolectric. Crash handling tests may need to exercise via direct method calls rather than real WebView errors.

### Changed Assumptions
- S03's `open()` used a hardcoded 2-second delay. S04 replaces this with real bootstrap detection. This is a behavioral change that existing tests may not cover.
- S03's `open()` rejects when already active. S04 changes this to settle-then-open. Existing test `openFromPlaying_isRejected` will need to be updated to expect switching behavior.

### Watch-outs
- The `Restoring` state is a sink state (no outgoing transitions per `canTransition`). If S04 needs to support restore-from-snapshot, that state machine may need revision. For M001 scope, Restoring is deferred.
- PreLaunchSnapshot.capture() is fire-and-forget. If it fails, it should not block session opening. Log failures but don't transition to RecoveryRequired.
- Game switching must increment `navigationEpoch` to invalidate old bridge callbacks. This is already handled by `destroyWebViewAndFinalize()`.

---

## Conclusion

S04 is a focused resilience slice. The state machine, bridge validation, and WebView host are solid from S01–S03. S04 adds operational capabilities on top: periodic capture, bootstrap-verified opening, crash detection, process recovery, and safe switching. The work is well-scoped with clear boundaries. The highest risk is bootstrap integration in the test environment; the mitigation is a stub detector. All five requirements can be delivered with targeted modifications to 5 production files and ~31 new tests.
<!-- gsd:state-version=191:1 -->
