package com.example.hub.session

import android.content.Context
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import com.example.hub.bootstrap.DetectorResult
import com.example.hub.database.GameInstance
import com.example.hub.database.GameInstanceDao
import com.example.hub.database.SnapshotDao
import com.example.hub.database.SnapshotReasons
import com.example.hub.database.SnapshotRecord
import com.example.hub.database.originForReason
import com.example.hub.games.GameAdapter
import com.example.hub.games.GameAdapters
import com.example.hub.games.GameRegistry
import com.example.hub.games.PeekOutcome
import com.example.hub.games.QualificationResult
import com.example.hub.games.SaveComparison
import com.example.hub.games.SaveStanding
import com.example.hub.games.SaveSummary
import com.example.hub.games.SharkLayout
import com.example.hub.games.shouldResumeLive
import org.json.JSONObject
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Lifecycle-aware session controller that owns session transitions.
 *
 * Opening reaches [SessionState.Playing] only after a read-only storage peek
 * and, when that peek is safe, one load of the real game. Room snapshots are
 * not written back unless the user confirms a restore.
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
     * Test hook for the bootstrap capability gate. Production leaves this null
     * and waits for the real document instead of a detector timer.
     */
    internal val bootstrapTest: ((ProfileStore) -> DetectorResult)? = null,
    /**
     * When non-null, unit tests complete or fail opening without a live page.
     * Production leaves this null so [onMainFrameFinished] is required.
     */
    internal val documentReadyTest: (suspend (String) -> Boolean)? = null,
    internal val readinessCheck: (suspend (WebView, GameAdapter) -> Boolean)? = null,
    internal val restoreCheck: (suspend (WebView, GameAdapter, String) -> Boolean)? = null,
    internal val captureClient: (suspend (WebView?, GameAdapter) -> String?)? = null,
    /**
     * Fired after a successful snapshot write. Must return immediately;
     * capture may be holding the session lock.
     */
    private val onSnapshotSaved: ((GameInstance) -> Unit)? = null,
    private val snapshotDao: SnapshotDao? = null,
    internal val peekClient: (suspend (WebView, GameAdapter) -> PeekOutcome)? = null,
    internal val runtimeCheck: (suspend (WebView, GameAdapter, String) -> Boolean)? = null,
    internal val onBootstrapWrite: ((String?) -> Unit)? = null,
    /**
     * Test hook for the bootstrap-document guard in [finishGameLoad].
     * Production leaves this null and asks the adapter.
     */
    internal val bootstrapConfirmTest: (suspend (WebView) -> Boolean)? = null,
) : LifecycleEventObserver {

    companion object {
        private const val TAG = "SessionController"
        private const val CAPTURE_CADENCE_MS = 60_000L
        private const val CONSOLE_LIMIT = 50
        private const val CONSOLE_LINE_LIMIT = 500
        private const val DIAGNOSTIC_LIMIT = 200
        private const val READINESS_WATCH_MS = 30_000L
        private const val DOCUMENT_PROBE_JS =
            "(function(){ try {" +
                " var app = document.getElementById('app');" +
                " var offline = document.getElementById('offline');" +
                " return JSON.stringify({" +
                " bootstrap: window.__hubStorageBootstrap || ''," +
                " player: typeof player !== 'undefined' && player !== null," +
                " appDisplay: app ? String(app.style.display) : null," +
                " offlineDisplay: offline ? String(offline.style.display) : null," +
                " title: document.title || ''," +
                " readyState: document.readyState || ''" +
                " }); } catch (e) { return JSON.stringify({ error: String(e) }); } })()"
    }

    private enum class OpenStep {
        DECIDE,
        LOAD_GAME,
        RESTORE_WRITE,
        RESTORE_VERIFY,
        ROLLBACK,
    }

    private val _state = MutableStateFlow<SessionState>(SessionState.Closed)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _activeGameId = MutableStateFlow<String?>(null)
    val activeGameId: StateFlow<String?> = _activeGameId.asStateFlow()

    private val _activeProfileName = MutableStateFlow<String?>(null)
    val activeProfileName: StateFlow<String?> = _activeProfileName.asStateFlow()

    private val _navigationEpoch = MutableStateFlow(0)
    val navigationEpoch: StateFlow<Int> = _navigationEpoch.asStateFlow()

    private val _activeProfile = MutableStateFlow<Profile?>(null)
    val activeProfile: StateFlow<Profile?> = _activeProfile.asStateFlow()

    private val _launchUrl = MutableStateFlow<String?>(null)
    val launchUrl: StateFlow<String?> = _launchUrl.asStateFlow()

    private val _sessionError = MutableStateFlow<String?>(null)
    val sessionError: StateFlow<String?> = _sessionError.asStateFlow()

    private val _subresourceDiagnostic = MutableStateFlow<String?>(null)
    val subresourceDiagnostic: StateFlow<String?> = _subresourceDiagnostic.asStateFlow()

    private val _webViewGeneration = MutableStateFlow(0)
    val webViewGeneration: StateFlow<Int> = _webViewGeneration.asStateFlow()

    private val _reloadEpoch = MutableStateFlow(0)
    val reloadEpoch: StateFlow<Int> = _reloadEpoch.asStateFlow()

    private val _loadToken = MutableStateFlow(0)
    val loadToken: StateFlow<Int> = _loadToken.asStateFlow()

    private val _diagnostic = MutableStateFlow<SaveDiagnostic?>(null)
    val diagnostic: StateFlow<SaveDiagnostic?> = _diagnostic.asStateFlow()

    private val _consoleLines = MutableStateFlow<List<String>>(emptyList())
    val consoleLines: StateFlow<List<String>> = _consoleLines.asStateFlow()

    private val diagnosticBuffer = ArrayDeque<DiagnosticEvent>()
    private val _diagnosticEvents = MutableStateFlow<List<DiagnosticEvent>>(emptyList())
    val diagnosticEvents: StateFlow<List<DiagnosticEvent>> = _diagnosticEvents.asStateFlow()

    val documentGate = DocumentGate()

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val mutex = Mutex()
    private var captureJob: Job? = null
    private var captureRunning = false
    private var pendingDocumentUrl: String? = null
    private var activeAdapter: GameAdapter? = null

    internal var cadenceStartCount: Int = 0
        private set

    internal var gameDocumentLoads: Int = 0
        private set

    @Volatile var webView: WebView? = null

    private var launchMode: LaunchMode = LaunchMode.RESUME
    private var suppressCapture: Boolean = false
    private var openStep: OpenStep = OpenStep.DECIDE
    private var restoreInProgress: Boolean = false
    private var peekOutcome: PeekOutcome? = null
    private var chosenPayload: String? = null
    private var liveBeforeRestore: String? = null
    private var preparedPayload: String? = null
    private val consoleBuffer = ArrayDeque<String>()
    private var handlingDocument = false
    private var attachedOwner: LifecycleOwner? = lifecycleOwner
    private var readinessWatch: Job? = null

    /** Set while a configuration change is moving the existing WebView. */
    @Volatile var retainAcrossConfiguration: Boolean = false

    /** Activity hook. True only while Android is recreating for a configuration change. */
    internal var configurationChanging: () -> Boolean = { false }

    val isActive: Boolean get() = _state.value !is SessionState.Closed

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_DESTROY -> {
                scope.launch {
                    mutex.withLock {
                        if (retainAcrossConfiguration || configurationChanging()) {
                            recordDiagnostic(
                                DiagnosticSeverity.INFO,
                                "lifecycle",
                                "Configuration change kept the WebView. The page was not reloaded.",
                            )
                            return@withLock
                        }
                        forceClose()
                    }
                }
            }
            Lifecycle.Event.ON_PAUSE -> {
                scope.launch {
                    mutex.withLock {
                        if (retainAcrossConfiguration || configurationChanging()) {
                            recordDiagnostic(
                                DiagnosticSeverity.INFO,
                                "lifecycle",
                                "Configuration change left the live session in place.",
                            )
                            return@withLock
                        }
                        if (_state.value is SessionState.Playing) {
                            performCaptureLocked("background")
                            if (_state.value is SessionState.Playing) {
                                transitionTo(SessionState.Background, _activeGameId.value)
                            }
                        }
                    }
                }
            }
            Lifecycle.Event.ON_RESUME -> {
                scope.launch {
                    mutex.withLock {
                        if (_state.value is SessionState.Background) {
                            transitionTo(SessionState.Playing, _activeGameId.value)
                        }
                    }
                }
            }
            else -> Log.d(TAG, "Lifecycle $event — no auto-action")
        }
    }

    fun attach(owner: LifecycleOwner) {
        if (attachedOwner !== owner) {
            attachedOwner?.lifecycle?.removeObserver(this)
            attachedOwner = owner
        }
        owner.lifecycle.addObserver(this)
        scope.launch { onAttached() }
        Log.d(TAG, "Attached to lifecycle $owner")
    }

    /**
     * Restores a cold-start recovery row with its named profile and launch URL.
     * Does not mark the session playable until a new page load succeeds.
     */
    suspend fun onAttached() {
        val activeInstance = instanceDao.getActiveWithRecovery()
        if (activeInstance == null) {
            Log.d(TAG, "onAttached: no active recovery instance")
            return
        }
        mutex.withLock {
            if (_state.value !is SessionState.Closed) {
                recordDiagnostic(
                    DiagnosticSeverity.INFO,
                    "lifecycle",
                    "Left the open session in place. Stored recovery was not applied.",
                )
                return@withLock
            }
            Log.d(TAG, "onAttached: recoveryState=${activeInstance.recoveryState} game=${activeInstance.gameId}")
            launchMode = LaunchMode.RESUME
            openStep = OpenStep.DECIDE
            documentGate.phase = DocumentPhase.PEEK
            documentGate.payload = null
            _activeGameId.value = activeInstance.gameId
            _activeProfileName.value = activeInstance.profileName
            val game = gameRegistry.getGame(activeInstance.gameId)
            if (game != null) {
                _launchUrl.value = game.launchUrl
                activeAdapter = GameAdapters.create(game.adapterId)
                _activeProfile.value = obtainProfile(activeInstance.profileName)
            } else {
                _sessionError.value = "Recovery row ${activeInstance.gameId} is not in the registry"
            }
            transitionTo(SessionState.Opening, activeInstance.gameId)
            when (activeInstance.recoveryState) {
                "Degraded" -> transitionTo(SessionState.Degraded, activeInstance.gameId)
                else -> transitionTo(SessionState.RecoveryRequired, activeInstance.gameId)
            }
        }
    }

    fun detach() {
        attachedOwner?.lifecycle?.removeObserver(this)
        Log.d(TAG, "Detached from lifecycle")
    }

    suspend fun open(gameId: String, mode: LaunchMode = LaunchMode.RESUME) {
        mutex.withLock {
            if (_activeGameId.value == gameId &&
                launchMode == mode &&
                _state.value !is SessionState.Closed &&
                _state.value !is SessionState.Closing &&
                _state.value !is SessionState.RecoveryRequired &&
                _state.value !is SessionState.Degraded
            ) {
                if (_state.value is SessionState.Background) {
                    transitionTo(SessionState.Playing, gameId)
                }
                recordDiagnostic(
                    DiagnosticSeverity.INFO,
                    "navigation",
                    "Kept the live $gameId session without reloading.",
                )
                return
            }
            if (_state.value !is SessionState.Closed) {
                Log.w(TAG, "open($gameId): settling ${_state.value}")
                settleActiveSession()
                if (_state.value !is SessionState.Closed) {
                    Log.w(TAG, "open($gameId): rejected, still ${_state.value}")
                    return
                }
            }
            launchMode = mode
            suppressCapture = mode == LaunchMode.SAFE || mode == LaunchMode.INSPECT || mode == LaunchMode.RECOVER
            openStep = OpenStep.DECIDE
            restoreInProgress = false
            peekOutcome = null
            chosenPayload = null
            liveBeforeRestore = null
            preparedPayload = null
            gameDocumentLoads = 0
            documentGate.phase = DocumentPhase.PEEK
            documentGate.payload = null
            consoleBuffer.clear()
            _consoleLines.value = emptyList()
            _diagnostic.value = null
            transitionTo(SessionState.Opening, gameId)
            _sessionError.value = null
            Log.d(TAG, "open($gameId): Opening mode=$mode")
            val bound = bindProfileAndNavigate(gameId)
            if (bound == null) {
                failOpenLocked(gameId, "Game $gameId is not in the registry")
                return
            }
            if (activeAdapter == null) {
                failOpenLocked(gameId, "No adapter for $gameId (${bound.second})")
                return
            }
            if (mode == LaunchMode.SAFE) {
                val backups = snapshotDao?.countPinned(gameId, SnapshotReasons.DIAGNOSTIC_BACKUP) ?: 0
                if (backups == 0) {
                    failOpenLocked(
                        gameId,
                        "Safe Launch needs a diagnostic backup. Use Inspect first. The game was not loaded.",
                    )
                    return
                }
                suppressCapture = true
                openStep = OpenStep.LOAD_GAME
                documentGate.phase = DocumentPhase.GAME
                gameDocumentLoads = 1
            }
            val bootstrap = bootstrapTest?.invoke(profileStore)
            if (bootstrap != null && bootstrap.qualifiedMechanism == null) {
                failOpenLocked(gameId, bootstrap.message)
                return
            }
            when (documentReadyTest?.invoke(gameId)) {
                true -> {
                    transitionTo(SessionState.Playing, gameId)
                    Log.d(TAG, "open($gameId): Playing via explicit readiness hook")
                }
                false -> failOpenLocked(gameId, "Document readiness failed for ${bound.second}")
                null -> Log.d(TAG, "open($gameId): waiting for ${bound.second}")
            }
        }
    }

    suspend fun close() {
        mutex.withLock { closeLocked() }
    }

    fun requestCapture() {
        scope.launch {
            mutex.withLock {
                if (_state.value !is SessionState.Playing) {
                    Log.w(TAG, "requestCapture() rejected: ${_state.value}")
                    return@withLock
                }
                performCaptureLocked("request")
            }
        }
    }

    fun requestCheckpoint() {
        scope.launch {
            mutex.withLock {
                if (_state.value !is SessionState.Playing) {
                    Log.w(TAG, "requestCheckpoint() rejected: ${_state.value}")
                    return@withLock
                }
                performCaptureLocked(SnapshotReasons.CHECKPOINT)
            }
        }
    }

    fun requestForegroundCapture() {
        requestCapture()
    }

    fun requestBackground() {
        scope.launch {
            mutex.withLock {
                if (_state.value !is SessionState.Playing) return@withLock
                performCaptureLocked("background")
                if (_state.value is SessionState.Playing) {
                    transitionTo(SessionState.Background, _activeGameId.value)
                }
            }
        }
    }

    fun requestForeground() {
        scope.launch {
            mutex.withLock {
                when (_state.value) {
                    is SessionState.Background ->
                        transitionTo(SessionState.Playing, _activeGameId.value)
                    is SessionState.Capturing ->
                        Log.w(TAG, "requestForeground() ignored during capture")
                    else -> Log.w(TAG, "requestForeground() rejected: ${_state.value}")
                }
            }
        }
    }

    fun requestRestore() {
        scope.launch {
            val url = _launchUrl.value ?: return@launch
            onMainFrameFinished(url)
        }
    }

    fun requestDegraded() {
        scope.launch {
            mutex.withLock {
                if (_state.value !is SessionState.Playing) return@withLock
                transitionTo(SessionState.Degraded, _activeGameId.value)
            }
        }
    }

    fun requestRecovery() {
        scope.launch {
            mutex.withLock {
                if (_state.value !is SessionState.Degraded) return@withLock
                transitionTo(SessionState.RecoveryRequired, _activeGameId.value)
            }
        }
    }

    fun requestClosing() {
        scope.launch {
            mutex.withLock { closeLocked() }
        }
    }

    fun requestReload() {
        if (restoreInProgress || openStep == OpenStep.RESTORE_WRITE ||
            openStep == OpenStep.RESTORE_VERIFY || openStep == OpenStep.ROLLBACK
        ) {
            _sessionError.value = "Reload is unavailable during restore"
            return
        }
        if (_state.value is SessionState.Playing && webView != null) {
            _reloadEpoch.value = _reloadEpoch.value + 1
            Log.d(TAG, "requestReload epoch=${_reloadEpoch.value}")
        } else {
            _sessionError.value = "Reload is unavailable until the page is playing"
        }
    }

    fun onConsoleMessage(message: String) {
        val line = message.take(CONSOLE_LINE_LIMIT)
        if (consoleBuffer.size >= CONSOLE_LIMIT) consoleBuffer.removeFirst()
        consoleBuffer.addLast(line)
        _consoleLines.value = consoleBuffer.toList()
        if (line.startsWith("ERROR") || line.contains("Uncaught")) {
            recordDiagnostic(DiagnosticSeverity.WARNING, "javascript", line)
        }
    }

    fun requestSafeLaunch() {
        scope.launch {
            mutex.withLock { safeLaunchLocked() }
        }
    }

    fun confirmRecover() {
        scope.launch {
            mutex.withLock { confirmRecoverLocked() }
        }
    }

    fun retryRecovery() {
        scope.launch {
            mutex.withLock {
                val gameId = _activeGameId.value ?: return@withLock
                if (_state.value is SessionState.Degraded) {
                    transitionTo(SessionState.RecoveryRequired, gameId)
                }
                if (_state.value !is SessionState.RecoveryRequired) return@withLock
                val dead = webView
                webView = null
                dead?.let { runCatching { it.destroy() } }
                _webViewGeneration.value = _webViewGeneration.value + 1
                _sessionError.value = null
                pendingDocumentUrl = null
                restoreInProgress = false
                preparedPayload = null
                if (launchMode == LaunchMode.SAFE) {
                    openStep = OpenStep.LOAD_GAME
                    documentGate.phase = DocumentPhase.GAME
                    documentGate.payload = null
                    gameDocumentLoads = 1
                } else {
                    openStep = OpenStep.DECIDE
                    documentGate.phase = DocumentPhase.PEEK
                    documentGate.payload = null
                    gameDocumentLoads = 0
                }
                transitionTo(SessionState.Opening, gameId)
                val bound = bindProfileAndNavigate(gameId)
                if (bound == null) {
                    failOpenLocked(gameId, "Retry failed: $gameId is not in the registry")
                    return@withLock
                }
                when (documentReadyTest?.invoke(gameId)) {
                    true -> transitionTo(SessionState.Playing, gameId)
                    false -> failOpenLocked(gameId, "Retry readiness failed for ${bound.second}")
                    null -> Log.d(TAG, "retryRecovery($gameId): waiting for ${bound.second}")
                }
            }
        }
    }

    fun registerWebView(view: WebView) {
        if (webView === view) {
            recordDiagnostic(
                DiagnosticSeverity.INFO,
                "webview",
                "Reattached the existing WebView without loading a new document.",
            )
            return
        }
        webView = view
        recordDiagnostic(DiagnosticSeverity.INFO, "webview", "WebView created.")
        val pending = pendingDocumentUrl
        if (pending != null) {
            pendingDocumentUrl = null
            onMainFrameFinished(pending)
        }
    }

    fun releaseWebView(view: WebView) {
        if (retainAcrossConfiguration && webView === view) {
            recordDiagnostic(
                DiagnosticSeverity.INFO,
                "webview",
                "Composition released the WebView during a configuration change. It was not destroyed.",
            )
            return
        }
        if (webView === view) {
            webView = null
            recordDiagnostic(DiagnosticSeverity.INFO, "webview", "WebView destroyed.")
            runCatching { view.destroy() }
        }
    }

    /**
     * Called from the activity before a configuration recreation. The same
     * instance is later reattached and must not receive [WebView.loadUrl].
     */
    fun retainForConfigurationChange(): WebView? {
        retainAcrossConfiguration = true
        val view = webView ?: return null
        detachForRetain(view)
        recordDiagnostic(DiagnosticSeverity.INFO, "lifecycle", "Retained WebView for configuration change.")
        return view
    }

    fun takeRetainedWebView(): WebView? {
        if (!retainAcrossConfiguration) return null
        val view = webView ?: return null
        retainAcrossConfiguration = false
        return view
    }

    fun noteConfigurationChange() {
        recordDiagnostic(
            DiagnosticSeverity.INFO,
            "display",
            "Configuration changed. The WebView was resized and was not reloaded.",
        )
        val view = webView ?: return
        view.post {
            view.requestLayout()
            view.invalidate()
            runCatching {
                view.evaluateJavascript("window.dispatchEvent(new Event('resize'));", null)
                view.evaluateJavascript(SharkLayout.injectionScript(), null)
            }
        }
    }

    fun onWebViewLoad(reason: String) {
        val unexpected = reason == "load-token" && _state.value is SessionState.Playing
        recordDiagnostic(
            if (unexpected) DiagnosticSeverity.WARNING else DiagnosticSeverity.INFO,
            "navigation",
            "loadUrl ($reason) while ${_state.value}",
        )
    }

    fun recordDiagnostic(severity: DiagnosticSeverity, category: String, message: String) {
        val event = DiagnosticEvent(
            atMillis = System.currentTimeMillis(),
            severity = severity,
            category = category,
            message = message.take(CONSOLE_LINE_LIMIT),
        )
        synchronized(diagnosticBuffer) {
            if (diagnosticBuffer.size >= DIAGNOSTIC_LIMIT) diagnosticBuffer.removeFirst()
            diagnosticBuffer.addLast(event)
            _diagnosticEvents.value = diagnosticBuffer.toList()
        }
        Log.d(TAG, "diagnostic ${event.severity} ${event.category}: ${event.message}")
    }

    fun diagnosticReport(): String {
        val save = _diagnostic.value
        return buildString {
            appendLine("Incremental Hub diagnostic report")
            appendLine("game=${_activeGameId.value ?: "none"}")
            appendLine("state=${_state.value}")
            appendLine("url=${_launchUrl.value ?: "none"}")
            appendLine("profile=${_activeProfileName.value ?: "none"}")
            appendLine("loadToken=${_loadToken.value}")
            appendLine("reloadEpoch=${_reloadEpoch.value}")
            appendLine("webViewGeneration=${_webViewGeneration.value}")
            appendLine("documentPhase=${documentGate.phase}")
            appendLine("openStep=$openStep")
            appendLine("launchMode=$launchMode")
            appendLine("gameDocumentLoads=$gameDocumentLoads")
            if (save != null) {
                appendLine("comparison=${save.comparison}")
                appendLine("live=${save.liveBytes} bytes ${save.liveDetail}")
                appendLine("stored=${save.storedBytes} bytes ${save.storedDetail}")
                appendLine("note=${save.note}")
            }
            appendLine("console:")
            _consoleLines.value.forEach { appendLine("  $it") }
            appendLine("events:")
            _diagnosticEvents.value.forEach { event ->
                appendLine("  ${event.atMillis} ${event.severity} ${event.category}: ${event.message}")
            }
            appendLine("continuity=${continuityFor(activityRecreated = false, webViewRetained = webView != null, captureCompleted = false)}")
            appendLine("A process death before capture completes can drop progress since the previous save.")
        }
    }

    fun onMainFrameFinished(url: String) {
        scope.launch { handleDocument(url) }
    }

    fun onMainFrameFailed(message: String) {
        Log.e(TAG, message)
        onError?.invoke(message)
        recordDiagnostic(DiagnosticSeverity.FATAL, "network", message)
        scope.launch {
            mutex.withLock {
                val gameId = _activeGameId.value
                _sessionError.value = message
                when (_state.value) {
                    is SessionState.Opening ->
                        transitionTo(SessionState.RecoveryRequired, gameId)
                    is SessionState.Playing -> {
                        transitionTo(SessionState.Degraded, gameId)
                        transitionTo(SessionState.RecoveryRequired, gameId)
                    }
                    else -> Unit
                }
            }
        }
    }

    fun onSubresourceFailed(message: String) {
        val severity = if (message.contains("favicon.ico")) {
            DiagnosticSeverity.INFO
        } else if (message.contains(".js") || message.contains(".css")) {
            DiagnosticSeverity.WARNING
        } else {
            DiagnosticSeverity.INFO
        }
        Log.w(TAG, message)
        recordDiagnostic(severity, "network", message)
        if (severity == DiagnosticSeverity.FATAL) {
            _subresourceDiagnostic.value = message
        }
    }

    fun onRendererGone(message: String) {
        scope.launch {
            mutex.withLock {
                val gameId = _activeGameId.value
                _sessionError.value = message
                onError?.invoke(message)
                recordDiagnostic(DiagnosticSeverity.FATAL, "renderer", message)
                if (gameId != null) {
                    writeCaptureResult(
                        gameId,
                        successPayload = null,
                        error = "Renderer gone; a safe capture is impossible. Last good snapshot was kept.",
                    )
                }
                val dead = webView
                webView = null
                dead?.let { runCatching { it.destroy() } }
                _webViewGeneration.value = _webViewGeneration.value + 1
                when (_state.value) {
                    is SessionState.Capturing -> transitionTo(SessionState.Playing, gameId)
                    else -> Unit
                }
                when (_state.value) {
                    is SessionState.Playing -> transitionTo(SessionState.Degraded, gameId)
                    else -> Unit
                }
                when (_state.value) {
                    is SessionState.Degraded, is SessionState.Opening ->
                        transitionTo(SessionState.RecoveryRequired, gameId)
                    else -> Unit
                }
            }
        }
    }

    fun createErrorHandler(): (String) -> Unit = { error ->
        onMainFrameFailed(error)
    }

    suspend fun bindProfileAndNavigate(gameId: String): Pair<Profile?, String>? {
        val game = gameRegistry.getGame(gameId) ?: run {
            Log.e(TAG, "bindProfileAndNavigate($gameId): game not found")
            return null
        }
        val profileName = "hub-game-$gameId"
        val profile = obtainProfile(profileName)
        val now = System.currentTimeMillis()
        val existing = instanceDao.get(gameId)
        val instance = if (existing == null) {
            GameInstance(
                gameId = gameId,
                profileName = profileName,
                sessionIdentity = UUID.randomUUID().toString(),
                lastPlayedAtMillis = now,
                lastCaptureAtMillis = 0L,
            )
        } else {
            existing.copy(
                profileName = profileName,
                lastPlayedAtMillis = now,
            )
        }
        instanceDao.upsert(instance)
        _activeProfile.value = profile
        _activeProfileName.value = profileName
        _launchUrl.value = game.launchUrl
        _activeGameId.value = gameId
        activeAdapter = GameAdapters.create(game.adapterId)
        documentGate.launchUrl = game.launchUrl
        documentGate.storageKey = activeAdapter?.storageKey.orEmpty()
        Log.d(TAG, "bindProfileAndNavigate($gameId): profile=$profileName url=${game.launchUrl} profileNull=${profile == null}")
        return Pair(profile, game.launchUrl)
    }

    internal suspend fun forceCloseForTest() {
        mutex.withLock { forceClose() }
    }

    internal val isCaptureCadenceActive: Boolean get() = captureJob?.isActive == true

    private suspend fun handleDocument(url: String) {
        if (url.isBlank() || url == "about:blank") return
        val work = mutex.withLock {
            if (handlingDocument) {
                recordDiagnostic(
                    DiagnosticSeverity.INFO,
                    "navigation",
                    "Ignored duplicate main-frame finish for $url during $openStep.",
                )
                return
            }
            if (_state.value !is SessionState.Opening) return
            val gameId = _activeGameId.value ?: return
            val launch = _launchUrl.value
            if (launch.isNullOrBlank()) return
            val adapter = activeAdapter ?: run {
                failOpenLocked(gameId, "No adapter for $gameId ($launch)")
                return
            }
            val view = webView ?: run {
                pendingDocumentUrl = url
                return
            }
            handlingDocument = true
            ReadyWork(
                gameId = gameId,
                adapter = adapter,
                view = view,
                url = url,
            )
        } ?: return
        try {
            handleDocumentWork(work)
        } finally {
            mutex.withLock { handlingDocument = false }
        }
    }

    private suspend fun handleDocumentWork(work: ReadyWork) {
        if (peekClient == null && readinessCheck != null &&
            openStep == OpenStep.DECIDE && launchMode == LaunchMode.RESUME
        ) {
            val ready = try {
                readinessCheck.invoke(work.view, work.adapter)
            } catch (e: Exception) {
                Log.e(TAG, "readiness failed", e)
                false
            }
            mutex.withLock {
                if (_state.value is SessionState.Opening && _activeGameId.value == work.gameId) {
                    if (ready) {
                        transitionTo(SessionState.Playing, work.gameId)
                        Log.d(TAG, "handleDocument: Playing ${work.gameId} url=${work.url}")
                    } else {
                        failOpenLocked(work.gameId, "Adapter was not ready after loading ${work.url}")
                    }
                }
            }
            return
        }
        when (openStep) {
            OpenStep.DECIDE -> decideResume(work)
            OpenStep.LOAD_GAME -> finishGameLoad(work)
            OpenStep.RESTORE_WRITE -> finishRestoreWrite(work)
            OpenStep.RESTORE_VERIFY -> finishRestoreVerify(work)
            OpenStep.ROLLBACK -> finishRollback(work)
        }
    }

    private suspend fun decideResume(work: ReadyWork) {
        val outcome = readPeek(work)
        val history = snapshotDao?.history(work.gameId).orEmpty()
        val pointer = instanceDao.get(work.gameId)?.snapshotPayload
        val candidates = buildList {
            if (!pointer.isNullOrBlank()) add(pointer)
            history.forEach { record ->
                if (record.payload.isNotBlank()) add(record.payload)
            }
        }
        val stored = work.adapter.chooseStored(candidates)
        val livePayload = (outcome as? PeekOutcome.Value)?.payload
        val liveSummary = work.adapter.summarize(livePayload)
        val comparison = when {
            outcome is PeekOutcome.Failed -> SaveComparison.INCONCLUSIVE
            candidates.isEmpty() -> SaveComparison.NO_SNAPSHOT
            stored == null && liveSummary.standing != SaveStanding.PROGRESSED -> SaveComparison.INCONCLUSIVE
            else -> work.adapter.compare(livePayload, stored)
        }
        val shouldPlay = launchMode == LaunchMode.RESUME &&
            outcome !is PeekOutcome.Failed &&
            shouldResumeLive(comparison)
        if (!shouldPlay) {
            pinCopy(work.gameId, work.adapter, livePayload, SnapshotReasons.DIAGNOSTIC_BACKUP)
            if (!pointer.isNullOrBlank() && pointer != livePayload) {
                pinCopy(work.gameId, work.adapter, pointer, SnapshotReasons.DIAGNOSTIC_BACKUP)
            }
        }
        val storedSummary = work.adapter.summarize(stored)
        val backupReady = (snapshotDao?.countPinned(work.gameId, SnapshotReasons.DIAGNOSTIC_BACKUP) ?: 0) > 0
        val recoverAllowed = outcome !is PeekOutcome.Failed &&
            stored != null &&
            work.adapter.validate(stored)
        val note = diagnosticNote(outcome, comparison)
        val diagnostic = SaveDiagnostic(
            comparison = comparison,
            liveDetail = liveSummary.detail,
            liveBytes = liveSummary.byteLength,
            storedDetail = storedSummary.detail,
            storedBytes = storedSummary.byteLength,
            history = historyEntries(snapshotDao?.history(work.gameId).orEmpty()),
            note = note,
            backupReady = backupReady,
            recoverAllowed = recoverAllowed,
        )
        mutex.withLock {
            if (_state.value !is SessionState.Opening || _activeGameId.value != work.gameId) return
            peekOutcome = outcome
            chosenPayload = stored
            _diagnostic.value = diagnostic
            if (shouldPlay) {
                beginGameLoadLocked()
            } else {
                _sessionError.value = note
                transitionTo(SessionState.RecoveryRequired, work.gameId)
            }
        }
    }

    private suspend fun finishGameLoad(work: ReadyWork) {
        // A duplicate onPageFinished for the storage-bootstrap document arrives
        // after beginGameLoadLocked flips openStep to LOAD_GAME. Awaiting
        // readiness there holds handlingDocument for up to 30s and drops the
        // real game finish, leaving Shark's turquoise body with #app hidden.
        if (documentStillBootstrap(work)) {
            recordDiagnostic(
                DiagnosticSeverity.INFO,
                "navigation",
                "Ignored game-load finish on the storage bootstrap page (phase=${documentGate.phase} step=$openStep). Waiting for the real document.",
            )
            return
        }
        val ready = awaitGameReady(work)
        val continueWatch = mutex.withLock {
            if (_state.value !is SessionState.Opening || _activeGameId.value != work.gameId) return
            if (ready) {
                transitionTo(SessionState.Playing, work.gameId)
                Log.d(TAG, "handleDocument: Playing ${work.gameId} url=${work.url}")
                false
            } else {
                val note = "Adapter was not ready after loading ${work.url}. The page was left in place and was not restored."
                rememberFailure(work, note)
                true
            }
        }
        if (continueWatch) {
            val probe = productionDocumentProbe(work)
            recordDiagnostic(
                DiagnosticSeverity.WARNING,
                "readiness",
                "Adapter was not ready after loading ${work.url}. The page was left in place and was not restored. " +
                    "probe=$probe console=${_consoleLines.value.takeLast(5)}",
            )
            watchReadiness(work)
        }
    }

    private fun watchReadiness(work: ReadyWork) {
        readinessWatch?.cancel()
        readinessWatch = scope.launch {
            val ready = try {
                readinessCheck?.invoke(work.view, work.adapter)
                    ?: work.adapter.awaitReady(work.view, READINESS_WATCH_MS)
            } catch (e: Exception) {
                Log.e(TAG, "readiness watch failed", e)
                false
            }
            mutex.withLock {
                if (_state.value !is SessionState.Opening || _activeGameId.value != work.gameId) {
                    return@withLock
                }
                if (ready && openStep == OpenStep.LOAD_GAME) {
                    transitionTo(SessionState.Playing, work.gameId)
                    return@withLock
                }
            }
            val probe = productionDocumentProbe(work)
            mutex.withLock {
                if (_state.value !is SessionState.Opening || _activeGameId.value != work.gameId) {
                    return@withLock
                }
                val note =
                    "Game interface stayed hidden after the readiness watch. " +
                        "The save was not overwritten and the page was not reloaded. probe=$probe"
                rememberFailure(work, note)
                recordDiagnostic(DiagnosticSeverity.WARNING, "readiness", note)
                _sessionError.value = note
                transitionTo(SessionState.RecoveryRequired, work.gameId)
            }
        }
    }

    private suspend fun documentStillBootstrap(work: ReadyWork): Boolean {
        bootstrapConfirmTest?.let { return it(work.view) }
        // Unit tests inject peek/readiness hooks and never serve the bootstrap page.
        if (peekClient != null || readinessCheck != null) return false
        return try {
            work.adapter.confirmBootstrap(work.view)
        } catch (e: Exception) {
            Log.e(TAG, "bootstrap check failed", e)
            false
        }
    }

    private suspend fun productionDocumentProbe(work: ReadyWork): String {
        // Unit tests inject peek/readiness hooks and Robolectric WebViews that
        // never invoke evaluateJavascript callbacks.
        if (peekClient != null || readinessCheck != null || bootstrapConfirmTest != null) {
            return "skipped"
        }
        return try {
            withTimeoutOrNull(3_000L) {
                val deferred = kotlinx.coroutines.CompletableDeferred<String?>()
                work.view.evaluateJavascript(DOCUMENT_PROBE_JS) { deferred.complete(it) }
                deferred.await()
            }.orEmpty().trim('"')
        } catch (e: Exception) {
            "probe-failed:${e.javaClass.simpleName}"
        }
    }

    private suspend fun finishRestoreWrite(work: ReadyWork) {
        val expected = preparedPayload
        if (peekClient == null) {
            val marker = try {
                work.adapter.confirmBootstrap(work.view)
            } catch (e: Exception) {
                false
            }
            if (!marker) {
                mutex.withLock {
                    if (_state.value is SessionState.Opening && _activeGameId.value == work.gameId) {
                        beginRollbackLocked()
                    }
                }
                return
            }
        }
        onBootstrapWrite?.invoke(expected)
        val readback = when (val outcome = readPeek(work)) {
            is PeekOutcome.Value -> outcome.payload
            else -> null
        }
        mutex.withLock {
            if (_state.value !is SessionState.Opening || _activeGameId.value != work.gameId) return
            if (expected != null && readback == expected) {
                openStep = OpenStep.RESTORE_VERIFY
                documentGate.phase = DocumentPhase.GAME
                documentGate.payload = null
                gameDocumentLoads += 1
                _loadToken.value = _loadToken.value + 1
            } else {
                beginRollbackLocked()
            }
        }
    }

    private suspend fun finishRestoreVerify(work: ReadyWork) {
        val prepared = preparedPayload
        val ready = awaitGameReady(work)
        val accepted = ready && prepared != null && try {
            runtimeCheck?.invoke(work.view, work.adapter, prepared)
                ?: work.adapter.acceptsRuntime(work.view, prepared)
        } catch (e: Exception) {
            Log.e(TAG, "runtime verify failed", e)
            false
        }
        mutex.withLock {
            if (_state.value !is SessionState.Opening || _activeGameId.value != work.gameId) return
            if (accepted) {
                restoreInProgress = false
                transitionTo(SessionState.Playing, work.gameId)
                Log.d(TAG, "restore verified ${work.gameId}")
            } else {
                beginRollbackLocked()
            }
        }
    }

    private suspend fun finishRollback(work: ReadyWork) {
        onBootstrapWrite?.invoke(documentGate.payload)
        val note = "Restore did not match the running game. The previous storage value was written back. The game was not loaded again."
        mutex.withLock {
            if (_state.value !is SessionState.Opening || _activeGameId.value != work.gameId) return
            restoreInProgress = false
            documentGate.phase = DocumentPhase.PEEK
            rememberFailure(work, note)
            _sessionError.value = note
            transitionTo(SessionState.RecoveryRequired, work.gameId)
        }
    }

    private suspend fun readPeek(work: ReadyWork): PeekOutcome {
        val client = peekClient
        if (client != null) return client.invoke(work.view, work.adapter)
        val marker = try {
            work.adapter.confirmBootstrap(work.view)
        } catch (e: Exception) {
            Log.e(TAG, "bootstrap marker failed", e)
            false
        }
        if (!marker) return PeekOutcome.Failed
        return try {
            work.adapter.peekStorage(work.view)
        } catch (e: Exception) {
            Log.e(TAG, "peek failed", e)
            PeekOutcome.Failed
        }
    }

    private suspend fun awaitGameReady(work: ReadyWork): Boolean = try {
        readinessCheck?.invoke(work.view, work.adapter) ?: work.adapter.awaitReady(work.view, 30_000L)
    } catch (e: Exception) {
        Log.e(TAG, "readiness failed", e)
        false
    }

    private fun beginGameLoadLocked() {
        if (openStep == OpenStep.LOAD_GAME) return
        openStep = OpenStep.LOAD_GAME
        documentGate.phase = DocumentPhase.GAME
        documentGate.payload = null
        gameDocumentLoads += 1
        _loadToken.value = _loadToken.value + 1
        recordDiagnostic(DiagnosticSeverity.INFO, "navigation", "Loading the game document.")
    }

    private fun beginRollbackLocked() {
        openStep = OpenStep.ROLLBACK
        documentGate.phase = DocumentPhase.ROLLBACK
        documentGate.payload = liveBeforeRestore
        _loadToken.value = _loadToken.value + 1
    }

    private suspend fun safeLaunchLocked() {
        val gameId = _activeGameId.value ?: return
        if (_state.value !is SessionState.RecoveryRequired) return
        val backups = snapshotDao?.countPinned(gameId, SnapshotReasons.DIAGNOSTIC_BACKUP) ?: 0
        if (backups == 0) {
            _sessionError.value = "Safe Launch needs a diagnostic backup. Use Inspect first. The game was not loaded."
            return
        }
        launchMode = LaunchMode.SAFE
        suppressCapture = true
        openStep = OpenStep.LOAD_GAME
        documentGate.phase = DocumentPhase.GAME
        documentGate.payload = null
        restoreInProgress = false
        gameDocumentLoads += 1
        _sessionError.value = null
        val dead = webView
        webView = null
        dead?.let { runCatching { it.destroy() } }
        _webViewGeneration.value = _webViewGeneration.value + 1
        transitionTo(SessionState.Opening, gameId)
    }

    private suspend fun confirmRecoverLocked() {
        val gameId = _activeGameId.value ?: return
        val adapter = activeAdapter ?: return
        if (_state.value !is SessionState.RecoveryRequired) return
        if (peekOutcome == null || peekOutcome is PeekOutcome.Failed) {
            _sessionError.value = "Restore refused because storage could not be inspected."
            return
        }
        val stored = chosenPayload
        if (stored.isNullOrBlank() || !adapter.validate(stored)) {
            _sessionError.value = "Restore refused because no validated snapshot is available."
            return
        }
        val livePayload = (peekOutcome as? PeekOutcome.Value)?.payload
        pinCopy(gameId, adapter, livePayload, SnapshotReasons.PRE_RESTORE)
        val roomPayload = instanceDao.get(gameId)?.snapshotPayload
        if (!roomPayload.isNullOrBlank()) {
            pinCopy(gameId, adapter, roomPayload, SnapshotReasons.PRE_RESTORE)
        }
        val prepared = adapter.prepareRestore(stored)
        liveBeforeRestore = livePayload
        preparedPayload = prepared
        openStep = OpenStep.RESTORE_WRITE
        documentGate.phase = DocumentPhase.WRITE
        documentGate.payload = prepared
        restoreInProgress = true
        _sessionError.value = null
        val dead = webView
        webView = null
        dead?.let { runCatching { it.destroy() } }
        _webViewGeneration.value = _webViewGeneration.value + 1
        transitionTo(SessionState.Opening, gameId)
    }

    private suspend fun pinCopy(gameId: String, adapter: GameAdapter, payload: String?, reason: String) {
        val summary = adapter.summarize(payload)
        appendSnapshot(
            gameId = gameId,
            payload = payload.orEmpty(),
            reason = reason,
            pinned = true,
            progressJson = summaryJson(summary),
        )
    }

    private suspend fun appendSnapshot(
        gameId: String,
        payload: String,
        reason: String,
        pinned: Boolean,
        progressJson: String?,
    ) {
        val dao = snapshotDao ?: return
        if (reason == SnapshotReasons.DIAGNOSTIC_BACKUP &&
            dao.findPinnedPayload(gameId, reason, payload) != null
        ) {
            return
        }
        dao.insert(
            SnapshotRecord(
                id = UUID.randomUUID().toString(),
                gameId = gameId,
                sequence = dao.maxSequence(gameId) + 1,
                capturedAtMillis = System.currentTimeMillis(),
                reason = reason,
                pinned = pinned,
                payload = payload,
                progressJson = progressJson,
                origin = originForReason(reason),
            ),
        )
        if (!pinned) dao.pruneAutomatic(gameId)
    }

    private fun historyEntries(records: List<SnapshotRecord>): List<HistoryEntry> {
        return records.asReversed().map { record ->
            val detail = record.progressJson?.let { raw ->
                runCatching { JSONObject(raw).optString("detail") }.getOrNull().orEmpty()
            }.orEmpty().ifBlank { record.reason }
            HistoryEntry(
                sequence = record.sequence,
                reason = record.reason,
                pinned = record.pinned,
                capturedAtMillis = record.capturedAtMillis,
                byteLength = record.payload.length,
                detail = detail,
                origin = record.origin,
            )
        }
    }

    private fun summaryJson(summary: SaveSummary): String {
        return JSONObject()
            .put("standing", summary.standing.name)
            .put("detail", summary.detail)
            .put("bytes", summary.byteLength)
            .put("version", summary.version ?: JSONObject.NULL)
            .toString()
    }

    private fun diagnosticNote(outcome: PeekOutcome, comparison: SaveComparison): String {
        if (outcome is PeekOutcome.Failed) {
            return "Inspection inconclusive. The bootstrap page did not run, so the game was not loaded. " +
                "A service worker may have intercepted the request. Storage was not changed."
        }
        if (launchMode == LaunchMode.INSPECT) {
            return "Inspect read storage without loading the game."
        }
        if (launchMode == LaunchMode.RECOVER) {
            return "Recover from Snapshot read storage without loading the game."
        }
        return when (comparison) {
            SaveComparison.LIVE_BEHIND ->
                "Live storage is behind the saved snapshot. The game was not loaded."
            SaveComparison.LIVE_MISSING ->
                "Live storage has no save key, and a snapshot exists. The game was not loaded."
            SaveComparison.INCONCLUSIVE ->
                "Storage could not be compared safely. The game was not loaded."
            else -> "Resume held. The game was not loaded."
        }
    }

    private fun rememberFailure(work: ReadyWork, note: String) {
        val current = _diagnostic.value
        _diagnostic.value = current?.copy(note = note, recoverAllowed = false) ?: SaveDiagnostic(
            comparison = SaveComparison.INCONCLUSIVE,
            liveDetail = "Unavailable",
            liveBytes = 0,
            storedDetail = "Unavailable",
            storedBytes = 0,
            history = emptyList(),
            note = note,
            backupReady = false,
            recoverAllowed = false,
        )
        Log.e(TAG, "rememberFailure ${work.gameId}: $note")
    }

    private suspend fun closeLocked() {
        when (val current = _state.value) {
            is SessionState.Closed -> {
                _activeProfile.value = null
                _activeProfileName.value = null
                _launchUrl.value = null
                activeAdapter = null
            }
            is SessionState.Playing -> {
                performCaptureLocked("close")
                if (_state.value is SessionState.Playing) {
                    transitionTo(SessionState.Closing, _activeGameId.value)
                    destroyWebViewAndFinalize()
                }
            }
            is SessionState.Background -> {
                transitionTo(SessionState.Playing, _activeGameId.value)
                performCaptureLocked("close")
                if (_state.value is SessionState.Playing) {
                    transitionTo(SessionState.Closing, _activeGameId.value)
                    destroyWebViewAndFinalize()
                }
            }
            is SessionState.Opening -> {
                transitionTo(SessionState.RecoveryRequired, _activeGameId.value)
                transitionTo(SessionState.Closing, _activeGameId.value)
                destroyWebViewAndFinalize()
            }
            is SessionState.Degraded, is SessionState.RecoveryRequired -> {
                transitionTo(SessionState.Closing, _activeGameId.value)
                destroyWebViewAndFinalize()
            }
            else -> Log.w(TAG, "close(): no safe path from $current")
        }
    }

    private suspend fun settleActiveSession() {
        val gameId = _activeGameId.value
        when (_state.value) {
            is SessionState.Playing -> {
                performCaptureLocked("switch")
                if (_state.value is SessionState.Playing) {
                    transitionTo(SessionState.Closing, gameId)
                    destroyWebViewAndFinalize()
                }
            }
            is SessionState.Background -> {
                transitionTo(SessionState.Playing, gameId)
                performCaptureLocked("switch")
                if (_state.value is SessionState.Playing) {
                    transitionTo(SessionState.Closing, gameId)
                    destroyWebViewAndFinalize()
                }
            }
            is SessionState.Degraded, is SessionState.RecoveryRequired -> {
                transitionTo(SessionState.Closing, gameId)
                destroyWebViewAndFinalize()
            }
            is SessionState.Opening -> {
                transitionTo(SessionState.RecoveryRequired, gameId)
                transitionTo(SessionState.Closing, gameId)
                destroyWebViewAndFinalize()
            }
            else -> Log.d(TAG, "settleActiveSession: no action from ${_state.value}")
        }
    }

    private suspend fun performCaptureLocked(reason: String): Boolean {
        if (suppressCapture) {
            Log.d(TAG, "capture suppressed ($reason)")
            return false
        }
        if (captureRunning) {
            Log.w(TAG, "capture overlap rejected ($reason)")
            return false
        }
        val gameId = _activeGameId.value ?: return false
        if (_state.value !is SessionState.Playing) {
            writeCaptureResult(
                gameId,
                successPayload = null,
                error = "Safe capture is impossible in ${_state.value} ($reason). Last good snapshot was kept.",
            )
            return false
        }
        val adapter = activeAdapter
        val view = webView
        if (adapter == null || (view == null && captureClient == null)) {
            writeCaptureResult(
                gameId,
                successPayload = null,
                error = "Safe capture is impossible: no live WebView ($reason). Last good snapshot was kept.",
            )
            _sessionError.value = "Capture failed ($reason): no live WebView. Last good snapshot was kept."
            return false
        }
        captureRunning = true
        try {
            transitionTo(SessionState.Capturing, gameId)
            val payload = try {
                captureClient?.invoke(view, adapter)
                    ?: adapter.capture(
                        view!!,
                        QualificationResult(gameId = gameId, displayName = gameId, stages = emptyList()),
                    )
            } catch (e: Exception) {
                Log.e(TAG, "capture threw ($reason)", e)
                null
            }
            val valid = payload != null && try {
                adapter.validate(payload)
            } catch (e: Exception) {
                false
            }
            if (payload == null || !valid) {
                writeCaptureResult(
                    gameId,
                    successPayload = null,
                    error = "Capture failed validation ($reason). Last good snapshot was kept.",
                )
                _sessionError.value = "Capture failed ($reason). Last good snapshot was kept."
                if (_state.value is SessionState.Capturing) {
                    transitionTo(SessionState.Playing, gameId)
                }
                return false
            }
            writeCaptureResult(gameId, payload, null, reason)
            if (_sessionError.value?.startsWith("Capture failed") == true) {
                _sessionError.value = null
            }
            if (_state.value is SessionState.Capturing) {
                transitionTo(SessionState.Playing, gameId)
            }
            Log.d(TAG, "capture succeeded ($reason) bytes=${payload.length}")
            return true
        } finally {
            captureRunning = false
        }
    }

    private fun startCaptureCadence() {
        if (captureJob?.isActive == true) return
        cadenceStartCount += 1
        captureJob = scope.launch {
            while (isActive) {
                delay(CAPTURE_CADENCE_MS)
                mutex.withLock {
                    if (_state.value is SessionState.Playing) {
                        performCaptureLocked("cadence")
                    }
                }
            }
        }
    }

    private fun stopCaptureCadence() {
        captureJob?.cancel()
        captureJob = null
        readinessWatch?.cancel()
        readinessWatch = null
    }

    private suspend fun transitionTo(target: SessionState, gameId: String?) {
        val current = _state.value
        if (!canTransition(current, target)) {
            Log.w(TAG, "illegal transition $current → $target (gameId=$gameId)")
            return
        }
        Log.d(TAG, "transition $current → $target (gameId=$gameId)")
        recordDiagnostic(DiagnosticSeverity.INFO, "session", "$current -> $target")
        if (current is SessionState.Playing && target !is SessionState.Capturing) {
            stopCaptureCadence()
        }
        if (target is SessionState.Playing && current !is SessionState.Capturing && !suppressCapture) {
            startCaptureCadence()
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
        if (gameId != null) {
            when (target) {
                is SessionState.Degraded -> persistRecoveryState(gameId, "Degraded")
                is SessionState.RecoveryRequired -> persistRecoveryState(gameId, "RecoveryRequired")
                is SessionState.Playing -> clearRecoveryState(gameId)
                else -> Unit
            }
        }
    }

    private suspend fun persistRecoveryState(gameId: String, recovery: String) {
        val instance = instanceDao.get(gameId) ?: return
        instanceDao.upsert(instance.copy(recoveryState = recovery))
    }

    private suspend fun clearRecoveryState(gameId: String) {
        val instance = instanceDao.get(gameId) ?: return
        if (instance.recoveryState == null) return
        instanceDao.upsert(instance.copy(recoveryState = null))
    }

    private suspend fun writeCaptureResult(
        gameId: String,
        successPayload: String?,
        error: String?,
        reason: String = "capture",
    ) {
        val instance = instanceDao.get(gameId) ?: return
        val now = System.currentTimeMillis()
        val updated = if (successPayload != null) {
            instance.copy(
                lastCaptureAttemptAtMillis = now,
                lastCaptureSuccessAtMillis = now,
                lastCaptureAtMillis = now,
                lastCaptureError = null,
                snapshotPayload = successPayload,
            )
        } else {
            instance.copy(
                lastCaptureAttemptAtMillis = now,
                lastCaptureFailureAtMillis = now,
                lastCaptureError = error ?: "capture failed",
            )
        }
        instanceDao.upsert(updated)
        if (successPayload != null) {
            val adapter = activeAdapter
            val progress = adapter?.let { summaryJson(it.summarize(successPayload)) }
            appendSnapshot(gameId, successPayload, reason, pinned = reason == SnapshotReasons.CHECKPOINT, progressJson = progress)
            onSnapshotSaved?.invoke(updated)
        }
    }

    private suspend fun failOpenLocked(gameId: String, message: String) {
        Log.e(TAG, message)
        _sessionError.value = message
        if (_state.value is SessionState.Opening) {
            transitionTo(SessionState.RecoveryRequired, gameId)
        }
    }

    private suspend fun destroyWebViewAndFinalize() {
        stopCaptureCadence()
        val gameId = _activeGameId.value
        if (gameId != null) {
            clearRecoveryState(gameId)
        }
        val view = webView
        webView = null
        view?.let { runCatching { it.destroy() } }
        webViewDestroyer?.invoke()
        activeAdapter = null
        pendingDocumentUrl = null
        _activeGameId.value = null
        _activeProfileName.value = null
        _activeProfile.value = null
        _launchUrl.value = null
        _navigationEpoch.value = _navigationEpoch.value + 1
        transitionTo(SessionState.Closed, null)
    }

    private suspend fun forceClose() {
        stopCaptureCadence()
        val view = webView
        webView = null
        view?.let { runCatching { it.destroy() } }
        webViewDestroyer?.invoke()
        activeAdapter = null
        pendingDocumentUrl = null
        _activeGameId.value = null
        _activeProfileName.value = null
        _activeProfile.value = null
        _launchUrl.value = null
        _navigationEpoch.value = _navigationEpoch.value + 1
        _state.value = SessionState.Closed
    }

    private suspend fun obtainProfile(profileName: String): Profile? {
        val load = {
            runCatching { profileStore.getOrCreateProfile(profileName) }.getOrNull()
        }
        return if (Looper.myLooper() == Looper.getMainLooper()) {
            load()
        } else {
            withContext(Dispatchers.Main) { load() }
        }
    }

    private data class ReadyWork(
        val gameId: String,
        val adapter: GameAdapter,
        val view: WebView,
        val url: String,
    )
}
