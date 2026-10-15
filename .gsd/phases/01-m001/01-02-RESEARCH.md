# S02: Play Surface and Native Menu — Research

## Scope

Deliver the playable WebView surface and native in-session menu. Four deliverables:
1. **SessionState** sealed class — nine-state model with transition validation
2. **WebViewHost** composable — stable AndroidView WebView host
3. **NativeMenu** composable — seven actions, Back behavior, 48dp targets
4. **Fold-aware layout adapters** — cutout/hinge/IME handling

## Current State

- S01 produced: GameRegistry, GameInstance DAO, LauncherScreen, HubNavigation, PlaySurfaceStub
- Build passes: `./gradlew :app:assembleDebug --no-daemon` → BUILD SUCCESSFUL
- No ViewModel in use (S01 constraint: produceState pattern for DAO-backed UI)
- No AndroidView usage in UI layer yet
- No BackHandler, WindowInsets, or fold-aware code
- No lifecycle-owned coroutines in UI
- ProfileStore used via `ProfileStore.getInstance()` on UI thread (pattern from IntegratedSpike, GameQualifier)
- WebView creation pattern established: `WebView(context)` → `settings.javaScriptEnabled` → `settings.domStorageEnabled` → `WebViewCompat.setProfile(this, profileName)` → `webViewClient` → `loadUrl(url)`

## Deliverable 1: SessionState Sealed Class

### Design

```kotlin
sealed class SessionState {
    data object Closed : SessionState()
    data object Opening : SessionState()
    data object Playing : SessionState()
    data object Capturing : SessionState()
    data object Background : SessionState()
    data object Restoring : SessionState()
    data object Degraded : SessionState()
    data object RecoveryRequired : SessionState()
    data object Closing : SessionState()
}
```

### Transition validation

A pure function `canTransition(from: SessionState, to: SessionState): Boolean` encodes the legal transitions:

- CLOSED → OPENING (launch/resume)
- OPENING → PLAYING (game ready)
- OPENING → RECOVERY_REQUIRED (bootstrap failure)
- PLAYING → CAPTURING (periodic/manual)
- PLAYING → BACKGROUND (app backgrounded)
- PLAYING → DEGRADED (renderer crash)
- PLAYING → CLOSING (return to hub)
- CAPTURING → PLAYING (capture complete)
- CAPTURING → BACKGROUND (background during capture)
- BACKGROUND → OPENING (resume from background)
- BACKGROUND → CLOSING (return to hub)
- DEGRADED → RECOVERY_REQUIRED (crash confirmed)
- DEGRADED → CLOSING (give up)
- RECOVERY_REQUIRED → OPENING (user initiates recovery)
- RECOVERY_REQUIRED → CLOSING (return to hub)
- CLOSING → CLOSED (cleanup complete)

### Natural seam

SessionState is a pure Kotlin sealed class with no Android dependencies. It lives in `session/SessionState.kt`. Tests can exercise transition validation without Robolectric.

### First proof

The transition validation function is the highest-risk pure logic. If transitions are wrong, S03/S04 session controller will inherit bugs. Test it first with property-based style: for every (from, to) pair, verify `canTransition` matches the spec matrix.

## Deliverable 2: WebViewHost Composable

### Pattern from existing code

The IntegratedSpike and GameQualifier already establish the WebView creation pattern:

```kotlin
// From IntegratedSpike.createBoundWebView()
val webView = WebView(context).apply {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    WebViewCompat.setProfile(this, profileName)
    webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView, u: String) {
            deferred.complete(view)
        }
    }
    loadUrl(url)
}
```

### AndroidView stability requirement

SPEC 6.2: *"Keep the WebView outside transient composable branches; recomposition must reuse its identity."*

This means AndroidView with `remember` key, not inside an `if` branch that can toggle. The host must use `remember` with the session identity as key:

```kotlin
@Composable
fun WebViewHost(
    gameId: String,
    state: SessionState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val webViewRef = remember(gameId, state) { ... }
    AndroidView(
        factory = { ctx -> createWebView(ctx, gameId) },
        modifier = modifier.fillMaxSize(),
        update = { webView ->
            when (state) {
                is SessionState.Playing -> {/* stable */}
                is SessionState.Closed -> {/* destroy */}
                ...
            }
        },
    )
}
```

### Key constraint

AndroidView's `factory` is called once per unique key. The `update` lambda runs on each recomposition. We must ensure the WebView identity survives recomposition by using a stable key (gameId + session identity), not by recreating the WebView in `update`.

### Profile binding order

SPEC 6.1: *"Bind a newly constructed WebView to its profile before configuring scripts, calling JavaScript or navigating."*

The AndroidX API requires: `WebViewCompat.setProfile(webView, profileName)` BEFORE `loadUrl()`. This is already followed in the spike pattern.

### Risk

- AndroidView with WebView inside Compose Navigation can cause identity loss if the NavHost recomposes the back stack entry. Must use `remember` with explicit key, not implicit composition context.
- WebView lifecycle must be tied to the composable's DisposableEffect, not the Activity lifecycle (per MEM002: "Activity-bound WebView not retained in ViewModel").

## Deliverable 3: Native Menu Composable

### Seven actions (SPEC 4.2)

1. **Resume** — continue current session (no-op if already playing)
2. **Snapshot now** — trigger immediate capture
3. **Checkpoint** — pin current snapshot with optional title
4. **Vault** — open vault/browse snapshots
5. **Reload** — snapshot then reload page
6. **Display** — display preferences (fullscreen, text zoom, etc.)
7. **Return to hub** — close session, navigate back

### Back behavior (SPEC 4.2)

- Android Back opens the native game menu when no modal is open
- Back closes the menu first when already open
- Back must not silently navigate back to reset/start page or close session

Implementation: `BackHandler` that toggles menu visibility when menu is the top layer.

### Touch target

- 48dp minimum touch targets (Material 3 spec)
- Menu handle: small, movable, accessible

### Layout strategy

- Compact windows: bottom sheet
- Wider windows: side panel

### Compose implementation

```kotlin
@Composable
fun NativeMenu(
    state: SessionState,
    onAction: (MenuAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Menu items as a LazyColumn or Row depending on width
    // BackHandler integration
    // 48dp targets via contentPadding
}
```

### Risk

- Menu must open/close without recreating the WebView. The menu composable must be a sibling overlay, not a parent that wraps the WebViewHost.
- BackHandler must not intercept system back when a modal dialog is open.

## Deliverable 4: Fold-aware Layout Adapters

### Current state

No fold-aware, IME, or WindowInsets handling exists in the codebase. This is a greenfield addition.

### SPEC requirements

- Correct cutout, hinge, and keyboard handling
- Fold-aware layout adapters for cutout/hinge/IME

### Approach

Use AndroidX Compose WindowInsets API:

```kotlin
// Status bar
WindowInsets.statusBars
// Navigation bar
WindowInsets.navigationBars
// IME (keyboard)
WindowInsets.ime
// Cutout/hinge (display cutout)
WindowInsets.displayCutout
```

For fold-specific behavior, use `WindowSizeClass` or `LocalWindowMetricsCalculator` to detect fold state:

```kotlin
val metrics = WindowMetricsCalculator.calculateCurrentMetrics()
val width = metrics.bounds.width()
val height = metrics.bounds.height()
```

Or use `calculateWindowSizeClass()` for responsive breakpoints.

### Risk

- PixelFold has a hinge that creates a physical crease. WebView content should not be forced across the hinge line in folded state.
- IME handling must not cover the WebView content area. Use `windowInsetsPadding` or `navigationBarsPadding` + `imePadding`.

## Files to Create/Modify

### New files

1. **`app/src/main/java/com/example/hub/session/SessionState.kt`**
   - Sealed class with nine states
   - `canTransition(from, to): Boolean` pure function
   - Unit tests in `app/src/test/java/com/example/hub/session/SessionStateTest.kt`

2. **`app/src/main/java/com/example/hub/ui/WebViewHost.kt`**
   - AndroidView-based WebView host composable
   - Stable identity via remember with gameId key
   - Profile binding before navigation
   - DisposableEffect for WebView cleanup

3. **`app/src/main/java/com/example/hub/ui/NativeMenu.kt`**
   - Seven-action menu composable
   - BackHandler integration
   - 48dp touch targets
   - Compact/wide layout branching

4. **`app/src/main/java/com/example/hub/ui/FoldAwareLayout.kt`**
   - WindowInsets handling (status bar, nav bar, IME, cutout)
   - WindowSizeClass-based layout branching
   - Hinge-aware WebView positioning

### Modified files

5. **`app/src/main/java/com/example/hub/ui/PlaySurface.kt`** (rename from PlaySurfaceStub)
   - Replace stub with real WebViewHost + NativeMenu composition
   - Wire SessionState lifecycle

6. **`app/src/main/java/com/example/hub/navigation/HubNavigation.kt`**
   - Update play_surface route to use real PlaySurface instead of PlaySurfaceStub

## Dependencies

- Existing: `androidx.webkit:webkit:1.14.0` (ProfileStore, WebViewCompat)
- Existing: `androidx.lifecycle:lifecycle-runtime-ktx:2.8.7` (for LifecycleOwner)
- Existing: `androidx.compose.material3:material3` (for BottomSheet, BackHandler)
- Existing: `androidx.navigation:navigation-compose:2.7.7`
- **New**: `androidx.lifecycle:lifecycle-service:2.8.7` (for ProcessLifecycleOwner, optional for S02)
- **New**: `androidx.window:window:1.3.0` (for fold/hinge detection, optional for S02)

Check if `androidx.window` is already available or needs addition to build.gradle.kts.

## Verification Strategy

### Unit tests (Robolectric)
- `SessionStateTest`: exhaustively test all 81 (9×9) transition pairs
- `WebViewHostTest`: verify AndroidView factory is called once per unique key
- `NativeMenuTest`: verify BackHandler behavior, menu item count

### Build verification
- `./gradlew :app:assembleDebug --no-daemon` must pass
- Existing tests must not regress: `GameRegistryTest`, `GameInstanceDaoTest`, `GameDefinitionValidatorTest`

### Runtime verification (device)
- Launch app, tap a game, verify WebView loads
- Open menu, verify WebView remains active
- Close menu, verify WebView remains active
- Fold/unfold device, verify WebView adapts
- Open keyboard, verify WebView adjusts for IME
- Press Back, verify menu closes (not session)

## First Proof Priority

1. **SessionState transition validation** — pure logic, no Android deps, testable immediately
2. **WebViewHost stable identity** — highest risk of Compose recomposition bugs
3. **Native menu without WebView recreation** — critical acceptance criterion
4. **Fold-aware insets** — greenfield, but lower risk (insets are additive)

## Forward Intelligence

### Fragility points
- AndroidView with WebView inside Navigation Compose: the NavHost can recreate composable slots on back stack changes. Must use `remember` with explicit key tied to the route argument, not the composition scope.
- ProfileStore.getInstance() must be called on UI thread. Any coroutine that creates WebViews must use `withContext(Dispatchers.Main)`.
- WebView.destroy() releases the view but NOT the profile's localStorage. This invariant (proven in S01 spike) must be preserved.

### Changed assumptions
- S01 assumed no ViewModel. S02 continues this: SessionState lives in the composable via `remember`, not in a ViewModel. S03 will introduce SessionController as a lifecycle-bound object.
- S01 used produceState for DAO flows. S02 will use LaunchedEffect + StateFlow for session state, still no ViewModel.

### Watch-outs
- AndroidView's `update` lambda can be called before the WebView is fully attached to the window. Must guard against null/zero-size WebView in update.
- The native menu's BackHandler must not conflict with Navigation Compose's default BackHandler. Use `LocalOnBackPressedDispatcherOwner` to coordinate.
- Fold state changes can trigger recomposition of the entire NavHost. WebViewHost must use a stable key that survives this.

## Sources

- SPEC.md section 4.2 (play surface, native menu)
- SPEC.md section 6.2 (lifecycle states, WebView lifetime)
- SPEC.md section 6.1 (profile binding order)
- MEM002 (WebView lifecycle ownership)
- MEM001 (SessionController as sole lifecycle owner)
- IntegratedSpike.kt (validated WebView creation pattern)
- GameQualifier.kt (validated profile binding pattern)
- AndroidX WebKit docs: ProfileStore, WebViewCompat.setProfile
- Android Compose docs: AndroidView, BackHandler, WindowInsets
<!-- gsd:state-version=204:1 -->
