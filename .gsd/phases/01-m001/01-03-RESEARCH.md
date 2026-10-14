# S03: SessionController Core — Research

## Scope

Deliver the SessionController lifecycle component, bridge validation, and profile-binding ownership. Three deliverables:
1. **SessionController** — lifecycle-aware component that owns all session state transitions via the nine-state sealed model
2. **Bridge validation** — origin-restricted WebMessageListener with navigation epoch tracking and request correlation
3. **Profile binding integration** — move profile create/bind/navigate ownership from WebViewHost composable into SessionController

## Current State

- S01 produced: GameRegistry, GameInstance DAO, LauncherScreen, HubNavigation
- S02 produced: SessionState (nine states + canTransition), WebViewHost (stable AndroidView), NativeMenu (7 actions), FoldAwareLayout
- PlaySurface currently drives session state via local `remember` state — S03 replaces this with SessionController
- **No SessionController exists yet**
- **No BridgeHost / WebMessageListener code exists yet**
- **Profile binding is composable-internal** in WebViewHost — must be elevated to SessionController ownership
- Build passes; all S01+S02 unit tests pass

## Active Requirements Owned

| ID | Requirement | Class |
|---|---|---|
| R009 | SessionController as sole owner of nine-state lifecycle | core-capability |
| R010 | Move WebView/profile/navigation ownership into SessionController | integration |
| R011 | Bridge validation: origin restriction, epoch check, stale rejection | failure-visibility |

## Existing Codebase State

### What already exists (reuse)

| Asset | Location | Notes |
|---|---|---|
| `SessionState` sealed class | `session/SessionState.kt` | Nine states + `canTransition()` — S03 consumes this, does not modify |
| `SessionStateTest` | `session/SessionStateTest.kt` | 30 exhaustive tests — S03 preserves, extends with controller tests |
| `WebViewHost` composable | `ui/WebViewHost.kt` | Stable AndroidView host — S03 wires it to SessionController instead of local state |
| `NativeMenu` composable | `ui/NativeMenu.kt` | Seven actions + BackHandler — S03 wires callbacks to SessionController |
| `PlaySurface` composable | `ui/PlaySurface.kt` | Currently uses local `remember` — S03 replaces with SessionController |
| `GameRegistry` | `games/GameRegistry.kt` | Resolves game definitions — S03 consumes for session opening |
| `GameInstanceDao` | `database/GameInstanceDao.kt` | Durable instance metadata — S03 consumes for profile/session identity persistence |
| `GameAdapter` interface | `games/GameAdapter.kt` | Capture/restore contract — S03 wires through SessionController |
| Bootstrap pattern | `bootstrap/BootstrapDetector.kt`, `PreLaunchSnapshot.kt` | Origin qualification pattern — S03/S04 integrates into session opening |
| ProfileStore pattern | `ProfileStore.getInstance()` on UI thread | Established in IntegratedSpike, GameQualifier, WebViewHost |
| WebView creation pattern | `WebView(context)` → JS/domStorage → `setProfile()` → `loadUrl()` | Validated in spike code |

### What is missing (must be created)

| Gap | Action needed |
|---|---|
| `SessionController` | New lifecycle-aware component in `session/` package. Not a ViewModel (per MEM001 + spec prohibition on Activity-bound WebView in long-lived ViewModel). Owns state, transitions, WebView lifecycle, profile binding, bridge validation. |
| `BridgeHost` | New component in `bridge/` package. WebMessageListener with origin restriction, epoch tracking, request correlation. Pure validation logic testable without Android. |
| Profile binding ownership | Elevate from WebViewHost composable internal logic to SessionController-owned method. SessionController calls `createOrBindProfile()` then instructs WebViewHost to navigate. |
| PlaySurface wiring | Replace `remember { mutableStateOf() }` in PlaySurface with SessionController state observation. Menu callbacks route through controller. |

## Architecture Decisions

### 1. SessionController as lifecycle-aware component (not ViewModel)

**Decision:** SessionController extends `androidx.lifecycle.LifecycleEventObserver` (or implements it) and is created/owned by the Activity or a Activity-scoped component. It observes `ON_CREATE`, `ON_START`, `ON_RESUME`, `ON_PAUSE`, `ON_STOP`, `ON_DESTROY` to manage WebView lifecycle and coroutine cancellation.

**Rationale:** MEM001 and CONTEXT explicitly state: "SessionController will be a lifecycle-aware component (not a ViewModel) to comply with this constraint" (spec prohibits Activity-bound WebView in long-lived ViewModel). A ViewModel would survive configuration changes but outlive the Activity, violating the WebView lifetime rule.

**API sketch:**
```kotlin
class SessionController(
    private val context: Context,
    private val gameRegistry: GameRegistry,
    private val dao: GameInstanceDao,
) : LifecycleEventObserver {
    val state: StateFlow<SessionState> = ...
    val activeGameId: StateFlow<String?> = ...
    val activeProfileName: StateFlow<String?> = ...
    val navigationEpoch: StateFlow<Int> = ...

    suspend fun open(gameId: String)
    suspend fun close()
    fun requestCapture()
    fun requestBackground()
    fun requestForeground()
    fun requestRestore()
    fun requestDegraded()
    fun requestRecovery()
    fun requestClosing()

    // Bridge validation
    fun validateBridgeMessage(origin: String, epoch: Int, requestId: String): Boolean
}
```

**Key invariant:** All state transitions go through `canTransition()` validation. Illegal transitions log a warning and remain in current state.

### 2. Bridge validation with WebMessageListener

**Decision:** Create `BridgeHost` in `bridge/` package. Use `WebView.addWebMessageListener()` with origin-restricted `WebMessageListener`. Validate every incoming message against:
1. Source origin matches approved origin for the active game
2. Navigation epoch matches current session epoch
3. Request ID correlates to an outstanding request (or is a known response)
4. Message size is within bounds (8 MiB uncompressed per spec)
5. Schema version is supported

Late or mismatched messages are rejected silently (fail closed) and logged.

**Rationale:** SPEC 7.3 requires origin-restricted WebMessageListener. The existing bootstrap HTML (`native.html`) uses `addJavascriptInterface` pattern (`window.webkit.messageHandlers.hub`) — this is the spike pattern. S03 must transition to WebMessageListener per spec.

**Pure validation core:** The validation logic (origin match, epoch check, request correlation) should be extractable as pure functions in `BridgeValidator` for unit testing without Android dependencies.

```kotlin
// Pure Kotlin, no Android deps
class BridgeValidator {
    fun validateMessage(
        sourceOrigin: String,
        approvedOrigins: Set<String>,
        currentEpoch: Int,
        messageEpoch: Int,
        outstandingRequests: Set<String>,
        messageId: String,
        payloadSize: Int,
    ): ValidationResult
}

sealed class ValidationResult {
    data object Valid : ValidationResult()
    data class Invalid(val reason: Reason) : ValidationResult() {
        enum class Reason {
            ORIGIN_MISMATCH, EPOCH_STALE, REQUEST_UNCORRELATED, PAYLOAD_TOO_LARGE, SCHEMA_UNSUPPORTED
        }
    }
}
```

**Testability:** `BridgeValidator` tests run on JVM without Robolectric.

### 3. Profile binding ownership transition

**Decision:** SessionController owns the create/bind/navigate order. It exposes a `bindProfileAndNavigate(gameId: String, webView: WebView)` method that:
1. Resolves the game definition from GameRegistry
2. Creates or retrieves the named profile via ProfileStore
3. Calls `WebViewCompat.setProfile(webView, profileName)` BEFORE `loadUrl()`
4. Instructs WebViewHost to navigate

The WebViewHost composable receives the profile name as a parameter (or obtains it from SessionController via a callback) and only creates the WebView when instructed by SessionController.

**Rationale:** R010 requires "create/bind/navigate in defined order, each session has intended game profile, explicit WebView destruction, closing/switching invalidates old session." Currently WebViewHost does profile binding internally — this must be elevated to SessionController.

**Key ordering invariant (proven in S02):**
```
1. ProfileStore.getOrCreateProfile(profileName)
2. WebViewCompat.setProfile(webView, profile.name)
3. webView.loadUrl(url)
```
Steps 1→2→3 must never be violated. SessionController enforces this by controlling the sequence.

### 4. PlaySurface wiring to SessionController

**Decision:** PlaySurface observes `SessionController.state` via `collectAsState()` (or `produceState` per MEM009 pattern). Menu callbacks call SessionController methods instead of mutating local state. Navigation back is coordinated through SessionController's `close()` which transitions to Closed, then PlaySurface reads state to trigger nav back.

**Pattern from S01 (MEM009):** "produceState → collect Flow → build state map" should be reused for DAO-backed UI. For SessionController state, we use `controller.state.collectAsState()` directly since SessionController exposes StateFlow.

```kotlin
@Composable
fun PlaySurface(gameId: String, controller: SessionController) {
    val state by controller.state.collectAsState()
    // ... compose WebViewHost + NativeMenu with controller state + callbacks
}
```

## Implementation Landscape

### Task breakdown (natural seams)

**Task 1: SessionController Core**
- Files: `app/src/main/java/com/example/hub/session/SessionController.kt`
- Files (test): `app/src/test/java/com/example/hub/session/SessionControllerTest.kt`
- Delivers: State machine integration, validated transitions, game/instance identity ownership, coroutine-based lifecycle
- Uses: SessionState, canTransition, GameRegistry, GameInstanceDao, ProfileStore
- Verify: unit tests for valid/illegal transitions; assembleDebug passes; no regressions

**Task 2: Bridge Validation**
- Files: `app/src/main/java/com/example/hub/bridge/BridgeValidator.kt`
- Files (test): `app/src/test/java/com/example/hub/bridge/BridgeValidatorTest.kt`
- Files: `app/src/main/java/com/example/hub/bridge/BridgeHost.kt` (Android-side WebMessageListener wrapper)
- Delivers: Origin-restricted message validation, epoch tracking, stale rejection, request correlation
- Uses: BridgeValidator (pure), SessionController (for epoch state)
- Verify: pure validator tests on JVM; WebMessageListener integration compiles; assembleDebug passes

**Task 3: Profile Binding Integration**
- Files: `app/src/main/java/com/example/hub/session/SessionController.kt` (extend with profile binding)
- Files: `app/src/main/java/com/example/hub/ui/WebViewHost.kt` (accept profile from controller)
- Delivers: Create/bind/navigate order enforced by SessionController, profile persistence via DAO
- Uses: ProfileStore, GameInstanceDao, GameRegistry
- Verify: profile binding order invariant tested; assembleDebug passes

**Task 4: PlaySurface Wiring**
- Files: `app/src/main/java/com/example/hub/ui/PlaySurface.kt` (replace local state with SessionController)
- Files: `app/src/main/java/com/example/hub/navigation/HubNavigation.kt` (wire SessionController creation)
- Files: `app/src/main/java/com/example/hub/MainActivity.kt` (create SessionController with dependencies)
- Delivers: SessionController-driven state, menu callbacks routed through controller, navigation coordinated
- Uses: SessionController, WebViewHost, NativeMenu, GameRegistry, HubDatabase
- Verify: assembleDebug passes; no regressions in S01/S02 tests

## First Proof (Highest Risk)

**SessionController state machine integration** is the highest-risk first proof. It is the core lifecycle component that all other S03 work depends on. If transitions are wrong or illegal transitions leak through, S04 resilience work inherits bugs.

The controller must:
1. Expose state as a StateFlow<SessionState>
2. Validate every transition through canTransition()
3. Manage WebView lifecycle (create/destroy) via profile binding methods
4. Own the navigation epoch counter
5. Serialize conflicting operations (e.g., capture while closing)

**Verification command:**
```bash
./gradlew :app:testDebugUnitTest --tests "com.example.hub.session.SessionControllerTest" --no-daemon
```

## Risks and Watch-outs

| Risk | Mitigation |
|---|---|
| SessionController lifecycle binding | Must be created in Activity.onCreate and passed down — not in composable remember (would recreate on recomposition). Use Activity-scoped creation. |
| ProfileStore on background thread | ProfileStore.getInstance() must be called on UI thread. SessionController methods that touch ProfileStore must use withContext(Dispatchers.Main) or be called from Main. |
| WebView identity across navigation | NavHost back stack changes can recreate composable slots. WebViewHost must use remember(gameId, state) key. SessionController must not recreate WebView on state-only changes. |
| Bridge epoch invalidation on navigation | Each navigation increment must invalidate outstanding requests. BridgeValidator must track epoch per-session. |
| Game switching leakage | Old WebView/profile must be fully destroyed before new one is created. SessionController must serialize open/close. Late callbacks from old game must be rejected. |
| Robolectric + ProfileStore | ProfileStore.getInstance() may not work in Robolectric. BridgeValidator pure tests avoid this; WebViewHost integration tests may need conditional skips. |

## Forward Intelligence

### Fragility points
- SessionController must not be a ViewModel (spec prohibition). Use LifecycleEventObserver pattern.
- Profile binding order (create → setProfile → loadUrl) is the hardest invariant. Must be unit-tested.
- BridgeValidator pure logic is the highest-value testable unit — write tests first.
- PlaySurface composable must not re-create SessionController on recomposition — create in Activity, pass as parameter.

### Changed assumptions
- S02 used local `remember` state in PlaySurface. S03 replaces this with SessionController — the composable becomes stateless except for observing controller state.
- S02's WebViewHost did internal profile binding. S03 elevates this to SessionController — WebViewHost becomes a passive host that follows controller instructions.
- S01 used produceState for DAO flows. S03 continues this pattern for any DAO-backed UI but uses StateFlow.collectAsState() for SessionController state.

### Watch-outs
- SessionController must handle the case where GameRegistry lookup fails during open() — transition to RecoveryRequired, not Playing.
- BridgeValidator must handle null/missing epoch in messages — fail closed.
- ProfileStore.getOrCreateProfile() can throw — wrap in runCatching, fall back to no-profile WebView.
- Navigation Compose's BackHandler may conflict with NativeMenu's BackHandler — coordinate via SessionController.

## Files Summary

### New files to create
1. `app/src/main/java/com/example/hub/session/SessionController.kt` — lifecycle-aware session controller
2. `app/src/main/java/com/example/hub/bridge/BridgeValidator.kt` — pure origin/epoch validation
3. `app/src/main/java/com/example/hub/bridge/BridgeHost.kt` — WebMessageListener wrapper
4. `app/src/test/java/com/example/hub/session/SessionControllerTest.kt` — state machine + transition tests
5. `app/src/test/java/com/example/hub/bridge/BridgeValidatorTest.kt` — pure validation tests

### Files to modify
1. `app/src/main/java/com/example/hub/ui/PlaySurface.kt` — replace local state with SessionController
2. `app/src/main/java/com/example/hub/ui/WebViewHost.kt` — accept profile from controller, expose bind callback
3. `app/src/main/java/com/example/hub/navigation/HubNavigation.kt` — wire SessionController creation
4. `app/src/main/java/com/example/hub/MainActivity.kt` — create SessionController with GameRegistry + DAO

### Files preserved (no changes)
- `SessionState.kt` — existing, validated
- `SessionStateTest.kt` — existing, preserved
- `NativeMenu.kt` — existing, preserved (callbacks change but composable unchanged)
- `FoldAwareLayout.kt` — existing, preserved
- `GameRegistry.kt` — existing, preserved
- `GameInstanceDao.kt` — existing, preserved
- `GameDefinitionValidator.kt` — existing, preserved
- All existing tests — preserved, not regressed
<!-- gsd:state-version=128:1 -->
