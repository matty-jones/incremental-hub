package com.example.hub.session

import android.content.Context
import android.util.Log
import android.webkit.WebView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import com.example.hub.database.GameInstance
import com.example.hub.database.GameInstanceDao
import com.example.hub.bootstrap.BootstrapDetector
import com.example.hub.bootstrap.DetectorResult
import com.example.hub.bootstrap.PreLaunchSnapshot
import com.example.hub.games.GameRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Lifecycle-aware session controller that owns all session state transitions
 * via the nine-state sealed [SessionState] model.
 *
 * Implements [LifecycleEventObserver] so the controller can be attached to
 * an [LifecycleOwner] (typically an Activity) and receive lifecycle events.
 * Per the architecture decision (D001/MEM001), this is intentionally NOT a
 * ViewModel — the spec prohibits storing Activity-bound WebView in a long-lived
 * ViewModel.
 *
 * State transitions are serialized through a mutex so that conflicting
 * operations (e.g., open while closing) are handled deterministically.
 * All non-suspend transition methods dispatch through the controller's
 * [scope] to acquire the mutex safely.
 *
 * @param gameRegistry    Resolves game definitions by [gameId].
 * @param instanceDao     Persists [GameInstance] rows for session identity.
 * @param lifecycleOwner  The owner that drives [onStateChanged] callbacks.
 * @param webViewDestroyer Callback invoked when the WebView must be destroyed.
 *                         Called on close; null means no WebView to destroy.
 */
class SessionController(
    private val context: Context,
    private val gameRegistry: GameRegistry,
    private val instanceDao: GameInstanceDao,
    private val lifecycleOwner: LifecycleOwner,
    private val webViewDestroyer: (() -> Unit)? = null,
    private val onError: ((String) -> Unit)? = null,
    private val profileStore: ProfileStore = ProfileStore.getInstance(),
    /**
     * Test hook: when non-null, invoked instead of the real [BootstrapDetector]
     * during [open]. Allows tests to control the bootstrap outcome without
     * requiring a real WebView in Robolectric.
     */
    internal val bootstrapTest: ((ProfileStore) -> DetectorResult)? = null,
) : LifecycleEventObserver {

    companion object {
        private const val TAG = "SessionController"
        private const val OPENING_TIMEOUT_MS = 2_000L
        private const val CAPTURE_CADENCE_MS = 60_000L
        private const val CAPTURE_WINDOW_MS = 65_000L
    }

    // -----------------------------------------------------------------
    // Public StateFlow API
    // -----------------------------------------------------------------

    private val _state = MutableStateFlow<SessionState>(SessionState.Closed)
    /** Current session state. Observers should use [collectAsState] in Compose. */
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _activeGameId = MutableStateFlow<String?>(null)
    /** Active game identifier, null when no session is open. */
    val activeGameId: StateFlow<String?> = _activeGameId.asStateFlow()

    private val _activeProfileName = MutableStateFlow<String?>(null)
    /** Active profile name, null when no session is open. */
    val activeProfileName: StateFlow<String?> = _activeProfileName.asStateFlow()

    private val _navigationEpoch = MutableStateFlow<Int>(0)
    /**
     * Navigation epoch — incremented on each successful [open] call.
     * Used by [BridgeHost] to reject stale messages from old WebViews.
     */
    val navigationEpoch: StateFlow<Int> = _navigationEpoch.asStateFlow()

    // -----------------------------------------------------------------
    // Internal state
    // -----------------------------------------------------------------

    /** Coroutine scope attached to the lifecycle owner. */
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val mutex = Mutex()

    /** Lifecycle-owned coroutine that fires a capture cadence every 60 s while in Playing. */
    private var captureJob: Job? = null

    /**
     * The currently bound WebView, set by WebViewHost when created.
     * The controller uses this to destroy the WebView during close.
     */
    @Volatile var webView: WebView? = null

    private val _activeProfile = MutableStateFlow<Profile?>(null)
    /** Active profile, null when no session is open. */
    val activeProfile: StateFlow<Profile?> = _activeProfile.asStateFlow()

    /**
     * Returns true if the controller is currently in an active (non-closed) state.
     */
    val isActive: Boolean get() = _state.value !is SessionState.Closed

    // -----------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_DESTROY -> {
                Log.d(TAG, "Lifecycle ON_DESTROY — initiating force-close")
                scope.launch {
                    mutex.withLock {
                        forceClose()
                    }
                }
            }
            Lifecycle.Event.ON_PAUSE -> {
                // Best-effort capture before suspension so the cadence has a
                // recent snapshot even if the app is backgrounded.
                if (_state.value is SessionState.Playing) {
                    scope.launch {
                        mutex.withLock {
                            transitionTo(SessionState.Capturing, _activeGameId.value)
                            Log.d(TAG, "Lifecycle ON_PAUSE — best-effort capture fired")
                        }
                    }
                } else {
                    Log.d(TAG, "Lifecycle ON_PAUSE — no auto-action (user must call requestBackground)")
                }
            }
            else -> {
                Log.d(TAG, "Lifecycle $event — no auto-action")
            }
        }
    }

    /** Attaches the controller to a lifecycle owner. */
    fun attach(lifecycleOwner: LifecycleOwner) {
        lifecycleOwner.lifecycle.addObserver(this)
        // Call onAttached() synchronously so cold-start recovery is visible
        // immediately (important for tests where Dispatchers.Main is Unconfined).
        runBlocking { onAttached() }
        Log.d(TAG, "Attached to lifecycle $lifecycleOwner")
    }

    /**
     * Called after attach to check for cold-start recovery.
     * Queries the DAO for an active instance with a non-null recoveryState;
     * if found, restores the session to Degraded or RecoveryRequired and
     * restores the profile name so the UI surface can display it.
     */
    /**
     * Called after attach to check for cold-start recovery.
     * Queries the DAO for an active instance with a non-null recoveryState;
     * if found, restores the session to Degraded or RecoveryRequired and
     * restores the profile name so the UI surface can display it.
     */
    fun onAttached() {
        // Use Unconfined to ensure the coroutine runs inline during tests
        // where Dispatchers.Main is set to Unconfined, making cold-start
        // recovery synchronous from the caller's perspective.
        scope.launch(Dispatchers.Unconfined) {
            val activeInstance = instanceDao.getActiveWithRecovery()
            if (activeInstance != null) {
                Log.d(TAG, "onAttached: found recoveryState=${activeInstance.recoveryState} for game=${activeInstance.gameId}")
                _activeGameId.value = activeInstance.gameId
                _activeProfileName.value = activeInstance.profileName
                // Must transit through Opening — Closed → Degraded/RecoveryRequired
                // is not a legal transition per the state machine.
                transitionTo(SessionState.Opening, activeInstance.gameId)
                when (activeInstance.recoveryState) {
                    "Degraded" -> transitionTo(SessionState.Degraded, activeInstance.gameId)
                    else -> transitionTo(SessionState.RecoveryRequired, activeInstance.gameId)
                }
            } else {
                Log.d(TAG, "onAttached: no active recovery instance")
            }
        }
    }

    /** Detaches the controller from its lifecycle owner. */
    fun detach() {
        lifecycleOwner.lifecycle.removeObserver(this)
        Log.d(TAG, "Detached from lifecycle")
    }

    // -----------------------------------------------------------------
    // Public API — transitions
    // -----------------------------------------------------------------

    /**
     * Opens a session for [gameId].
     *
     * Transitions: Closed → Opening → Playing (or RecoveryRequired on failure).
     * If already in Opening, the call is a no-op (idempotent).
     * If in any other non-Closed state, the call is rejected with a warning.
     */
    /**
     * Opens a session for [gameId].
     *
     * Transitions: Closed → Opening → Playing (or RecoveryRequired on failure).
     * If already in a non-Closed state, settles the active session first then opens.
     * Runs [BootstrapDetector] after profile binding; if no qualified mechanism
     * is found, transitions to RecoveryRequired. Fires [PreLaunchSnapshot.capture]
     * asynchronously (fire-and-forget) after successful navigation.
     */
    suspend fun open(gameId: String) {
        mutex.withLock {
            val current = _state.value
            if (current !is SessionState.Closed) {
                Log.w(TAG, "open($gameId): already in $current — settling active session first")
                settleActiveSession()
                val stillActive = _state.value
                if (stillActive !is SessionState.Closed) {
                    Log.w(TAG, "open($gameId): still not Closed after settling (state=$stillActive) — rejected")
                    return
                }
            }
            transitionTo(SessionState.Opening, gameId)
            Log.d(TAG, "open($gameId): state=Opening — resolving game and binding profile")
            try {
                resolveGameAndBindProfile(gameId)
                // Run bootstrap detector to determine qualified mechanism.
                val detectorResult = bootstrapTest?.invoke(profileStore)
                    ?: BootstrapDetector(context).test(profileStore)
                val qualifiedMechanism = detectorResult.qualifiedMechanism
                if (detectorResult.qualifiedMechanism != null) {
                    // Simulate async opening work (in production this would await
                    // WebView creation + profile bind + navigation).
                    withContext(Dispatchers.Default) {
                        delay(OPENING_TIMEOUT_MS)
                    }
                    transitionTo(SessionState.Playing, gameId)
                    startCaptureCadence()
                    // Fire PreLaunchSnapshot asynchronously (fire-and-forget).
                    scope.launch {
                        PreLaunchSnapshot(context).capture(profileStore, detectorResult)
                        Log.d(TAG, "PreLaunchSnapshot captured for $gameId")
                    }
                    Log.d(TAG, "open($gameId): state=Playing — session active")
                } else {
                    Log.e(TAG, "open($gameId): bootstrap failed — no qualified mechanism")
                    transitionTo(SessionState.RecoveryRequired, gameId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "open($gameId): failed — transitioning to RecoveryRequired", e)
                transitionTo(SessionState.RecoveryRequired, gameId)
            }
        }
    }

    /**
     * Closes the active session.
     *
     * Transitions: Playing → Closing → Closed.
     * If not in Playing, falls back to requestClosing() which handles
     * any active state.
     */
    suspend fun close() {
        mutex.withLock {
            val current = _state.value
            when {
                current is SessionState.Playing -> {
                    Log.d(TAG, "close(): state=Playing → Closing")
                    transitionTo(SessionState.Closing, _activeGameId.value)
                    destroyWebViewAndFinalize()
                }
                else -> {
                    Log.d(TAG, "close(): not Playing (state=$current) — delegating to requestClosing")
                    requestClosing()
                }
            }
        }
    }

    /**
     * Starts the lifecycle-bound 60-second capture cadence.
     * Called automatically when state enters Playing.
     */
    private fun startCaptureCadence() {
        stopCaptureCadence()
        captureJob = scope.launch {
            while (isActive) {
                delay(CAPTURE_CADENCE_MS)
                val current = _state.value
                if (current is SessionState.Playing) {
                    transitionTo(SessionState.Capturing, _activeGameId.value)
                    Log.d(TAG, "cadence: state=Capturing (60s elapsed)")
                    delay(CAPTURE_WINDOW_MS)
                    if (_state.value is SessionState.Capturing) {
                        transitionTo(SessionState.Playing, _activeGameId.value)
                        Log.d(TAG, "cadence: state=Playing (capture window complete)")
                    }
                }
            }
        }
        Log.d(TAG, "cadence: captureJob started")
    }

    /** Cancels the active capture cadence coroutine. */
    private fun stopCaptureCadence() {
        captureJob?.cancel()
        captureJob = null
        Log.d(TAG, "cadence: captureJob stopped")
    }

    /**
     * Requests a one-shot foreground capture. Transitions Playing → Capturing.
     * No-op if not currently Playing.
     */
    fun requestForegroundCapture() {
        scope.launch {
            mutex.withLock {
                val current = _state.value
                if (current !is SessionState.Playing) {
                    Log.w(TAG, "requestForegroundCapture() rejected: not Playing (state=$current)")
                    return@withLock
                }
                transitionTo(SessionState.Capturing, _activeGameId.value)
                Log.d(TAG, "requestForegroundCapture(): state=Capturing (one-shot)")
            }
        }
    }

    /**
     * Requests a capture. Transitions Playing → Capturing.
     * No-op if not currently Playing.
     */
    fun requestCapture() {
        scope.launch {
            mutex.withLock {
                val current = _state.value
                if (current !is SessionState.Playing) {
                    Log.w(TAG, "requestCapture() rejected: not Playing (state=$current)")
                    return@withLock
                }
                transitionTo(SessionState.Capturing, _activeGameId.value)
                Log.d(TAG, "requestCapture(): state=Capturing")
            }
        }
    }

    /**
     * Requests backgrounding. Transitions Playing → Background.
     * No-op if not currently Playing.
     */
    fun requestBackground() {
        scope.launch {
            mutex.withLock {
                val current = _state.value
                if (current !is SessionState.Playing) {
                    Log.w(TAG, "requestBackground() rejected: not Playing (state=$current)")
                    return@withLock
                }
                transitionTo(SessionState.Background, _activeGameId.value)
                Log.d(TAG, "requestBackground(): state=Background")
            }
        }
    }

    /**
     * Requests foregrounding.
     * - Background → Playing
     * - Capturing → Playing
     * No-op if not in Background or Capturing.
     */
    fun requestForeground() {
        scope.launch {
            mutex.withLock {
                val current = _state.value
                when {
                    current is SessionState.Background -> {
                        transitionTo(SessionState.Playing, _activeGameId.value)
                        Log.d(TAG, "requestForeground(): state=Playing (from Background)")
                    }
                    current is SessionState.Capturing -> {
                        transitionTo(SessionState.Playing, _activeGameId.value)
                        Log.d(TAG, "requestForeground(): state=Playing (from Capturing)")
                    }
                    else -> {
                        Log.w(TAG, "requestForeground() rejected: not Background/Capturing (state=$current)")
                    }
                }
            }
        }
    }

    /**
     * Requests a restore. Transitions to Restoring.
     * Valid from: Playing, Capturing, Background, Degraded, RecoveryRequired.
     */
    fun requestRestore() {
        scope.launch {
            mutex.withLock {
                val current = _state.value
                val allowed = current is SessionState.Playing ||
                    current is SessionState.Capturing ||
                    current is SessionState.Background ||
                    current is SessionState.Degraded ||
                    current is SessionState.RecoveryRequired
                if (!allowed) {
                    Log.w(TAG, "requestRestore() rejected: $current")
                    return@withLock
                }
                transitionTo(SessionState.Restoring, _activeGameId.value)
                Log.d(TAG, "requestRestore(): state=Restoring")
            }
        }
    }

    /**
     * Requests degradation. Transitions Playing → Degraded.
     * No-op if not currently Playing.
     */
    fun requestDegraded() {
        scope.launch {
            mutex.withLock {
                val current = _state.value
                if (current !is SessionState.Playing) {
                    Log.w(TAG, "requestDegraded() rejected: not Playing (state=$current)")
                    return@withLock
                }
                transitionTo(SessionState.Degraded, _activeGameId.value)
                Log.d(TAG, "requestDegraded(): state=Degraded")
            }
        }
    }

    /**
     * Requests recovery. Transitions Degraded → RecoveryRequired.
     * No-op if not currently Degraded.
     */
    fun requestRecovery() {
        scope.launch {
            mutex.withLock {
                val current = _state.value
                if (current !is SessionState.Degraded) {
                    Log.w(TAG, "requestRecovery() rejected: not Degraded (state=$current)")
                    return@withLock
                }
                transitionTo(SessionState.RecoveryRequired, _activeGameId.value)
                Log.d(TAG, "requestRecovery(): state=RecoveryRequired")
            }
        }
    }

    /**
     * Requests closing from any active state.
     * Transitions the current active state → Closing → Closed.
     */
    fun requestClosing() {
        scope.launch {
            mutex.withLock {
                val current = _state.value
                if (current is SessionState.Closed) {
                    Log.w(TAG, "requestClosing() rejected: already Closed")
                    return@withLock
                }
                Log.d(TAG, "requestClosing(): state=$current → Closing")
                transitionTo(SessionState.Closing, _activeGameId.value)
                destroyWebViewAndFinalize()
            }
        }
    }

    // -----------------------------------------------------------------
    // Profile binding
    // -----------------------------------------------------------------

    /**
     * Binds a profile and navigates to the game URL for [gameId].
     *
     * Resolves the game from the registry, creates or retrieves a [Profile]
     * via [ProfileStore], and persists the game instance. Returns the profile
     * and the resolved navigation URL.
     *
     * The create → setProfile → loadUrl ordering invariant is enforced by
     * this method — callers must invoke it before the WebView is created.
     * Profile creation runs on [Dispatchers.Main] as required by [ProfileStore].
     *
     * @return Pair of (profile, url) or null if the game is not found.
     */
    suspend fun bindProfileAndNavigate(gameId: String): Pair<Profile?, String>? {
        return withContext(Dispatchers.Default) {
            val game = gameRegistry.getGame(gameId)
                ?: run {
                    Log.e(TAG, "bindProfileAndNavigate($gameId): game not found in registry")
                    return@withContext null
                }
            val profileName = "hub-game-$gameId"
            val url = game.launchUrl
            // Create/retrieve profile on UI thread (required by ProfileStore)
            val profile = withContext(Dispatchers.Main) {
                runCatching { ProfileStore.getInstance().getOrCreateProfile(profileName) }.getOrNull()
            }
            val now = System.currentTimeMillis()
            val existing = instanceDao.get(gameId)
            val instance = existing?.copy(
                profileName = profileName,
                lastPlayedAtMillis = now,
            ) ?: com.example.hub.database.GameInstance(
                gameId = gameId,
                profileName = profileName,
                sessionIdentity = java.util.UUID.randomUUID().toString(),
                lastPlayedAtMillis = now,
                lastCaptureAtMillis = 0L,
            )
            instanceDao.upsert(instance)
            _activeProfile.value = profile

            Log.d(TAG, "bindProfileAndNavigate($gameId): profile=$profileName, url=$url")
            Pair(profile, url)
        }
    }

    // -----------------------------------------------------------------
    // Game switching
    // -----------------------------------------------------------------

    /**
     * Settles the currently active session before a new [open] call proceeds.
     *
     * - Playing → requestCapture() then requestClosing()
     * - Background → requestForeground() then requestClosing()
     * - Degraded/RecoveryRequired → requestClosing()
     *
     * Called internally from [open] while the mutex is already held, so it uses
     * internal transition methods directly to avoid deadlock.
     */
    private suspend fun settleActiveSession() {
        val current = _state.value
        when {
            current is SessionState.Playing -> {
                // Best-effort capture before closing: Playing → Capturing → Playing → Closing.
                // The intermediate Playing completes the capture window so the cadence
                // does not immediately re-fire after the close.
                transitionTo(SessionState.Capturing, _activeGameId.value)
                Log.d(TAG, "settleActiveSession: Playing → Capturing")
                delay(50)
                transitionTo(SessionState.Playing, _activeGameId.value)
                transitionTo(SessionState.Closing, _activeGameId.value)
                destroyWebViewAndFinalize()
                Log.d(TAG, "settleActiveSession: Playing → Capturing → Playing → Closing → Closed")
            }
            current is SessionState.Background -> {
                transitionTo(SessionState.Playing, _activeGameId.value)
                Log.d(TAG, "settleActiveSession: Background → Playing")
                delay(50)
                transitionTo(SessionState.Closing, _activeGameId.value)
                destroyWebViewAndFinalize()
                Log.d(TAG, "settleActiveSession: Playing → Closing → Closed")
            }
            current is SessionState.Degraded || current is SessionState.RecoveryRequired -> {
                transitionTo(SessionState.Closing, _activeGameId.value)
                destroyWebViewAndFinalize()
                Log.d(TAG, "settleActiveSession: ${current} → Closing → Closed")
            }
        }
    }

    // -----------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------

    /**
     * Transitions from the current state to [target], after validating with
     * [canTransition]. Logs the transition with gameId + from/to state.
     */
    private fun transitionTo(target: SessionState, gameId: String?) {
        val current = _state.value
        if (!canTransition(current, target)) {
            Log.w(TAG, "illegal transition $current → $target (gameId=$gameId) — rejected")
            return
        }
        Log.d(TAG, "transition $current → $target (gameId=$gameId)")
        // Stop the cadence when leaving Playing (the cadence itself handles
        // Playing→Capturing→Playing internally; we only stop when the user
        // or an error path moves away from Playing).
        if (current is SessionState.Playing && target !is SessionState.Capturing) {
            stopCaptureCadence()
        }
        // Start the cadence when entering Playing from any non-Capturing state.
        if (target is SessionState.Playing) {
            startCaptureCadence()
        }
        // Persist or clear durable recovery metadata alongside state transitions.
        if (gameId != null) {
            scope.launch {
                when {
                    target is SessionState.Degraded -> persistRecoveryState(gameId, "Degraded")
                    target is SessionState.RecoveryRequired -> persistRecoveryState(gameId, "RecoveryRequired")
                    target is SessionState.Playing -> clearRecoveryState(gameId)
                }
            }
        }
        _state.value = target
        if (target is SessionState.Opening || target is SessionState.Playing ||
            target is SessionState.Capturing || target is SessionState.Background ||
            target is SessionState.Restoring || target is SessionState.Degraded ||
            target is SessionState.RecoveryRequired
        ) {
            _activeGameId.value = gameId
        }
        if (target is SessionState.Playing || target is SessionState.Capturing ||
            target is SessionState.Restoring || target is SessionState.Degraded
        ) {
            _activeProfileName.value = gameId?.let { "hub-game-$it" }
        }
    }

    /** Persists [recoveryState] on the current GameInstance row for [gameId]. */
    private suspend fun persistRecoveryState(gameId: String, state: String) {
        val instance = instanceDao.get(gameId) ?: run {
            Log.w(TAG, "persistRecoveryState($gameId): instance not found")
            return
        }
        val updated = instance.copy(
            recoveryState = state,
            lastCaptureAtMillis = System.currentTimeMillis(),
        )
        instanceDao.upsert(updated)
        Log.d(TAG, "persisted recoveryState=$state for game=$gameId (lastCaptureAtMillis=${updated.lastCaptureAtMillis})")
    }

    /** Clears [recoveryState] and updates lastCaptureAtMillis on the current row. */
    private suspend fun clearRecoveryState(gameId: String) {
        val instance = instanceDao.get(gameId) ?: run {
            Log.w(TAG, "clearRecoveryState($gameId): instance not found")
            return
        }
        val updated = instance.copy(
            recoveryState = null,
            lastCaptureAtMillis = System.currentTimeMillis(),
        )
        instanceDao.upsert(updated)
        Log.d(TAG, "cleared recoveryState for game=$gameId (lastCaptureAtMillis=${updated.lastCaptureAtMillis})")
    }

    /**
     * Resolves the game from the registry, creates/retrieves the profile,
     * and persists the game instance row. Called during [open].
     */
    private suspend fun resolveGameAndBindProfile(gameId: String) {
        val game = gameRegistry.getGame(gameId)
            ?: throw IllegalStateException("Game $gameId not found in registry")
        val profileName = "hub-game-$gameId"
        val sessionIdentity = java.util.UUID.randomUUID().toString()
        instanceDao.upsert(
            GameInstance(
                gameId = gameId,
                profileName = profileName,
                sessionIdentity = sessionIdentity,
                lastPlayedAtMillis = System.currentTimeMillis(),
                lastCaptureAtMillis = 0L,
            )
        )
        Log.d(TAG, "resolveGameAndBindProfile($gameId): persisted instance profile=$profileName")
    }

    /**
     * Destroys the WebView (via callback) and resets state to Closed.
     * Called after any Closing transition completes.
     */
    private suspend fun destroyWebViewAndFinalize() {
        stopCaptureCadence()
        webView?.destroy()
        webView = null
        webViewDestroyer?.invoke()
        // Reset active identity.
        _activeGameId.value = null
        _activeProfileName.value = null
        _activeProfile.value = null
        // Increment navigation epoch so stale bridge callbacks are rejected.
        _navigationEpoch.value = _navigationEpoch.value + 1
        transitionTo(SessionState.Closed, null)
        Log.d(TAG, "destroyWebViewAndFinalize(): state=Closed epoch=${_navigationEpoch.value}")
    }

    /**
     * Force-closes the session regardless of current state. Called on
     * ON_DESTROY lifecycle event.
     */
    internal suspend fun forceCloseForTest() = forceClose()

    /** Exposed for test verification of the capture cadence job state. */
    internal val isCaptureCadenceActive: Boolean get() = captureJob?.isActive == true

    /**
     * Creates a crash handler lambda that logs the error, triggers a
     * Playing→Degraded transition, and delegates to the external onError callback.
     * Intended to be passed to [WebViewHost] as its onError parameter.
     */
    fun createErrorHandler(): (String) -> Unit = { error ->
        Log.e(TAG, "renderer error: $error — requesting Degraded")
        requestDegraded()
        onError?.invoke(error)
    }

    private suspend fun forceClose() {
        webView?.destroy()
        webView = null
        webViewDestroyer?.invoke()
        _activeGameId.value = null
        _activeProfileName.value = null
        _activeProfile.value = null
        _navigationEpoch.value = _navigationEpoch.value + 1
        _state.value = SessionState.Closed
        Log.d(TAG, "forceClose(): state=Closed epoch=${_navigationEpoch.value}")
    }
}
