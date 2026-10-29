package com.example.hub.session

import android.content.Context
import android.content.res.Configuration
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import com.example.hub.bootstrap.DetectorResult
import com.example.hub.database.GameInstance
import com.example.hub.database.GameInstanceDao
import com.example.hub.database.SnapshotDao
import com.example.hub.database.SnapshotReasons
import com.example.hub.database.SnapshotRecord
import com.example.hub.database.originForReason
import com.example.hub.games.EnvelopeDecode
import com.example.hub.games.GameAdapter
import com.example.hub.games.GameAdapters
import com.example.hub.games.GameRegistry
import com.example.hub.games.PayloadDigest
import com.example.hub.games.PeekOutcome
import com.example.hub.games.QualificationResult
import com.example.hub.games.RuntimeVerdict
import com.example.hub.games.SaveComparison
import com.example.hub.games.SaveEnvelope
import com.example.hub.games.SaveStanding
import com.example.hub.games.SaveSummary
import com.example.hub.games.StorageSlot
import com.example.hub.BuildConfig
import com.example.hub.ui.armSharkViewportUnlock
import com.example.hub.games.unwrapJsResult
import com.example.hub.games.shouldResumeLive
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
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
    /**
     * When set, pointer and snapshot writes share one SQLite transaction.
     * Unit tests that use stub DAOs leave this null.
     */
    private val roomDatabase: RoomDatabase? = null,
    internal val peekClient: (suspend (WebView, GameAdapter) -> PeekOutcome)? = null,
    internal val runtimeCheck: (suspend (WebView, GameAdapter, String) -> Boolean)? = null,
    internal val onBootstrapWrite: ((String?) -> Unit)? = null,
    /**
     * Test hook for the bootstrap-document guard in [finishGameLoad].
     * Production leaves this null and asks the adapter.
     */
    internal val bootstrapConfirmTest: (suspend (WebView) -> Boolean)? = null,
    /**
     * Unit tests supply an adapter that is not registered in [GameAdapters].
     * Production leaves this null.
     */
    internal val adapterForTest: ((String) -> GameAdapter?)? = null,
) : LifecycleEventObserver {

    companion object {
        private const val TAG = "SessionController"
        private const val CAPTURE_CADENCE_MS = 60_000L
        private const val CONSOLE_LIMIT = 50
        private const val CONSOLE_LINE_LIMIT = 500
        private const val DIAGNOSTIC_LIMIT = 200
        private const val READINESS_WATCH_MS = 30_000L
    }

    private enum class OpenStep {
        DECIDE,
        LOAD_GAME,
        RESTORE_WRITE,
        RESTORE_VERIFY,
        ROLLBACK,
        JOURNAL_APPLY,
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

    private val manualSaveGate = AtomicBoolean(false)
    private var manualSaveNoticeId = 0L
    private val _manualSaveInFlight = MutableStateFlow(false)
    val manualSaveInFlight: StateFlow<Boolean> = _manualSaveInFlight.asStateFlow()
    private val _manualSaveNotice = MutableStateFlow<ManualSaveNotice?>(null)
    val manualSaveNotice: StateFlow<ManualSaveNotice?> = _manualSaveNotice.asStateFlow()

    private val _subresourceDiagnostic = MutableStateFlow<String?>(null)
    val subresourceDiagnostic: StateFlow<String?> = _subresourceDiagnostic.asStateFlow()

    private val _webViewGeneration = MutableStateFlow(0)
    val webViewGeneration: StateFlow<Int> = _webViewGeneration.asStateFlow()

    private val _reloadEpoch = MutableStateFlow(0)
    val reloadEpoch: StateFlow<Int> = _reloadEpoch.asStateFlow()

    private val _loadToken = MutableStateFlow(0)
    val loadToken: StateFlow<Int> = _loadToken.asStateFlow()

    private val _presentationEpoch = MutableStateFlow(0)
    val presentationEpoch: StateFlow<Int> = _presentationEpoch.asStateFlow()

    private val _diagnostic = MutableStateFlow<SaveDiagnostic?>(null)
    val diagnostic: StateFlow<SaveDiagnostic?> = _diagnostic.asStateFlow()

    private val _consoleLines = MutableStateFlow<List<String>>(emptyList())
    val consoleLines: StateFlow<List<String>> = _consoleLines.asStateFlow()

    private val diagnosticBuffer = ArrayDeque<DiagnosticEvent>()
    private val _diagnosticEvents = MutableStateFlow<List<DiagnosticEvent>>(emptyList())
    val diagnosticEvents: StateFlow<List<DiagnosticEvent>> = _diagnosticEvents.asStateFlow()

    val documentGate = DocumentGate()
    private val journalStore = RestoreJournalStore(context)

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
    private val _captureBlockReason = MutableStateFlow<String?>(null)
    val captureBlockReason: StateFlow<String?> = _captureBlockReason.asStateFlow()
    private var restoreGeneration: Int = 0
    private var restoreSessionIdentity: String? = null
    private var restoreProfileName: String? = null
    private var restoreVerifyToken: Int = -1

    /** Throws inside the post-restore transaction. Stages: before-write, after-pin. */
    internal var restoreCommitFault: (suspend (String) -> Unit)? = null

    /** Throws inside a capture write. Stages: before-write, after-pointer. */
    internal var captureWriteFault: (suspend (String) -> Unit)? = null
    private var openStep: OpenStep = OpenStep.DECIDE
    private var restoreInProgress: Boolean = false
    private var peekOutcome: PeekOutcome? = null
    private var chosenPayload: String? = null
    private var liveBeforeRestore: String? = null
    private var preparedPayload: String? = null
    private val consoleBuffer = ArrayDeque<String>()
    private var handlingDocument = false
    private var pendingFinish: PendingFinish? = null
    private var attachedOwner: LifecycleOwner? = lifecycleOwner
    private var readinessWatch: Job? = null
    val loadTimeline = LoadTimeline()
    private val _blankScreenReport = MutableStateFlow<String?>(null)
    val blankScreenReport: StateFlow<String?> = _blankScreenReport.asStateFlow()

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
                activeAdapter = adapterForTest?.invoke(game.adapterId) ?: GameAdapters.create(game.adapterId)
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
            restoreGeneration += 1
            restoreVerifyToken = -1
            setCaptureSuppressed(mode == LaunchMode.SAFE || mode == LaunchMode.INSPECT || mode == LaunchMode.RECOVER)
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
            pendingFinish = null
            loadTimeline.reset()
            _blankScreenReport.value = null
            loadTimeline.beginNavigation("open:$gameId:$mode")
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
            if (blockForPendingJournal(gameId)) return
            if (mode == LaunchMode.SAFE) {
                val backups = snapshotDao?.countPinned(gameId, SnapshotReasons.DIAGNOSTIC_BACKUP) ?: 0
                if (backups == 0) {
                    failOpenLocked(
                        gameId,
                        "Safe Launch needs a diagnostic backup. Use Inspect first. The game was not loaded.",
                    )
                    return
                }
                setCaptureSuppressed(true)
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
        if (!manualSaveGate.compareAndSet(false, true)) {
            publishManualSave(ManualSaveOutcome.BUSY, "Save already in progress.")
            return
        }
        _manualSaveInFlight.value = true
        _manualSaveNotice.value = null
        scope.launch {
            try {
                val attempt = try {
                    mutex.withLock {
                        when {
                            suppressCapture -> {
                                Log.w(TAG, "requestCheckpoint() suppressed: $launchMode")
                                CaptureAttempt.Failed(captureBlockDetail())
                            }
                            _state.value !is SessionState.Playing -> {
                                Log.w(TAG, "requestCheckpoint() rejected: ${_state.value}")
                                CaptureAttempt.Failed("unready. Last good snapshot was kept.")
                            }
                            else -> performCaptureLocked(SnapshotReasons.CHECKPOINT)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "requestCheckpoint() failed", e)
                    CaptureAttempt.Failed(
                        "storage error. ${e.message ?: "Save failed."} Last good snapshot was kept.",
                    )
                }
                when (attempt) {
                    CaptureAttempt.Saved -> publishManualSave(ManualSaveOutcome.SAVED, null)
                    CaptureAttempt.Busy -> publishManualSave(
                        ManualSaveOutcome.BUSY,
                        "Save already in progress.",
                    )
                    is CaptureAttempt.Deferred -> publishManualSave(ManualSaveOutcome.DEFERRED, attempt.detail)
                    is CaptureAttempt.Failed -> publishManualSave(ManualSaveOutcome.FAILED, attempt.detail)
                }
            } finally {
                manualSaveGate.set(false)
                _manualSaveInFlight.value = false
            }
        }
    }

    private fun publishManualSave(outcome: ManualSaveOutcome, detail: String?) {
        manualSaveNoticeId += 1
        _manualSaveNotice.value = ManualSaveNotice(
            id = manualSaveNoticeId,
            outcome = outcome,
            message = manualSaveMessage(outcome, detail),
        )
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
            openStep == OpenStep.RESTORE_VERIFY || openStep == OpenStep.ROLLBACK ||
            openStep == OpenStep.JOURNAL_APPLY
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
                if (blockForPendingJournal(gameId)) return@withLock
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

    fun noteConfigurationChange(newConfig: Configuration? = null) {
        recordDiagnostic(
            DiagnosticSeverity.INFO,
            "display",
            "Configuration changed. The WebView was resized and was not reloaded.",
        )
        _presentationEpoch.value = _presentationEpoch.value + 1
        val view = webView ?: return
        val config = newConfig ?: view.resources.configuration
        view.post {
            armSharkViewportUnlock(view)
            runCatching { view.dispatchConfigurationChanged(config) }
            view.requestLayout()
            view.invalidate()
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
        val view = webView
        return buildString {
            appendLine("Incremental Hub diagnostic report")
            appendLine("appVersion=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("gitSha=${BuildConfig.GIT_SHA}")
            appendLine("game=${_activeGameId.value ?: "none"}")
            appendLine("state=${_state.value}")
            appendLine("url=${_launchUrl.value ?: "none"}")
            appendLine("committedUrl=${view?.url ?: "none"}")
            appendLine("profile=${_activeProfileName.value ?: "none"}")
            appendLine("loadToken=${_loadToken.value}")
            appendLine("reloadEpoch=${_reloadEpoch.value}")
            appendLine("webViewGeneration=${_webViewGeneration.value}")
            appendLine("documentPhase=${documentGate.phase}")
            appendLine("openStep=$openStep")
            appendLine("launchMode=$launchMode")
            appendLine("gameDocumentLoads=$gameDocumentLoads")
            appendLine("navId=${loadTimeline.navId}")
            if (save != null) {
                appendLine("comparison=${save.comparison}")
                appendLine("live=${save.liveBytes} bytes sha256=${save.liveHash} ${save.liveDetail}")
                appendLine("stored=${save.storedBytes} bytes sha256=${save.storedHash} ${save.storedDetail}")
                appendLine("note=${save.note}")
            }
            appendLine("console:")
            _consoleLines.value.forEach { appendLine("  $it") }
            appendLine("timeline:")
            loadTimeline.snapshot().forEach { event ->
                appendLine(
                    "  +${event.elapsedMs}ms nav=${event.navId} token=${event.loadToken} " +
                        "${event.phase}/${event.state} ${event.category}: ${event.message}",
                )
            }
            appendLine("events:")
            _diagnosticEvents.value.forEach { event ->
                appendLine("  ${event.atMillis} ${event.severity} ${event.category}: ${event.message}")
            }
            appendLine("continuity=${continuityFor(activityRecreated = false, webViewRetained = webView != null, captureCompleted = false)}")
            appendLine("A process death before capture completes can drop progress since the previous save.")
            appendLine("Save payloads are omitted from this report.")
        }
    }

    fun onMainFrameFinished(url: String, finishToken: Int = _loadToken.value) {
        scope.launch { handleDocument(url, finishToken) }
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
        activeAdapter = adapterForTest?.invoke(game.adapterId) ?: GameAdapters.create(game.adapterId)
        documentGate.launchUrl = game.launchUrl
        documentGate.storageKey = activeAdapter?.storageKey.orEmpty()
        Log.d(TAG, "bindProfileAndNavigate($gameId): profile=$profileName url=${game.launchUrl} profileNull=${profile == null}")
        return Pair(profile, game.launchUrl)
    }

    internal suspend fun forceCloseForTest() {
        mutex.withLock { forceClose() }
    }

    internal val isCaptureCadenceActive: Boolean get() = captureJob?.isActive == true

    private suspend fun handleDocument(url: String, finishToken: Int) {
        if (url.isBlank() || url == "about:blank") return
        val work = mutex.withLock {
            if (_state.value !is SessionState.Opening) return
            // Stale peek finishes that arrive after beginGameLoad bumped the token.
            if ((openStep == OpenStep.LOAD_GAME || openStep == OpenStep.RESTORE_VERIFY ||
                    openStep == OpenStep.ROLLBACK) && finishToken != _loadToken.value
            ) {
                recordDiagnostic(
                    DiagnosticSeverity.INFO,
                    "navigation",
                    "Ignored stale finish token=$finishToken current=${_loadToken.value} for $url during $openStep.",
                )
                loadTimeline.append(
                    loadToken = finishToken,
                    phase = documentGate.phase.name,
                    state = _state.value.toString(),
                    category = "navigation",
                    message = "stale-finish-ignored url=$url",
                )
                return
            }
            if (handlingDocument) {
                pendingFinish = PendingFinish(url, finishToken)
                recordDiagnostic(
                    DiagnosticSeverity.INFO,
                    "navigation",
                    "Queued main-frame finish token=$finishToken for $url during $openStep.",
                )
                loadTimeline.append(
                    loadToken = finishToken,
                    phase = documentGate.phase.name,
                    state = _state.value.toString(),
                    category = "navigation",
                    message = "finish-queued url=$url",
                )
                return
            }
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
            loadTimeline.append(
                loadToken = finishToken,
                phase = documentGate.phase.name,
                state = _state.value.toString(),
                category = "navigation",
                message = "finish-handling url=$url step=$openStep",
            )
            ReadyWork(
                gameId = gameId,
                adapter = adapter,
                view = view,
                url = url,
                loadToken = finishToken,
            )
        } ?: return
        try {
            handleDocumentWork(work)
        } finally {
            val replay: PendingFinish?
            mutex.withLock {
                handlingDocument = false
                replay = pendingFinish
                pendingFinish = null
            }
            if (replay != null) {
                handleDocument(replay.url, replay.finishToken)
            }
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
            OpenStep.JOURNAL_APPLY -> finishJournalApply(work)
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
            liveHash = PayloadDigest.sha256OrAbsent(livePayload),
            storedDetail = storedSummary.detail,
            storedBytes = storedSummary.byteLength,
            storedHash = PayloadDigest.sha256OrAbsent(stored),
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
        // readiness there holds handlingDocument and can drop the real finish.
        if (shouldIgnoreAsBootstrap(work)) {
            recordDiagnostic(
                DiagnosticSeverity.INFO,
                "navigation",
                "Ignored game-load finish on the storage bootstrap page " +
                    "(phase=${documentGate.phase} step=$openStep token=${work.loadToken}). Waiting for the real document.",
            )
            loadTimeline.append(
                loadToken = work.loadToken,
                phase = documentGate.phase.name,
                state = _state.value.toString(),
                category = "navigation",
                message = "bootstrap-finish-ignored",
            )
            return
        }
        val initialProbe = productionDocumentProbe(work)
        recordDiagnostic(
            DiagnosticSeverity.INFO,
            "readiness",
            "document-complete probe=$initialProbe",
        )
        loadTimeline.append(
            loadToken = work.loadToken,
            phase = documentGate.phase.name,
            state = _state.value.toString(),
            category = "readiness",
            message = "document-complete probe=$initialProbe",
        )
        val ready = awaitGameReady(work)
        val settledProbe = productionDocumentProbe(work)
        val continueWatch = mutex.withLock {
            if (_state.value !is SessionState.Opening || _activeGameId.value != work.gameId) return
            if (ready) {
                transitionTo(SessionState.Playing, work.gameId)
                Log.d(TAG, "handleDocument: Playing ${work.gameId} url=${work.url}")
                loadTimeline.append(
                    loadToken = work.loadToken,
                    phase = documentGate.phase.name,
                    state = "Playing",
                    category = "readiness",
                    message = "ready probe=$settledProbe",
                )
                false
            } else {
                val note = "Adapter was not ready after loading ${work.url}. The page was left in place and was not restored."
                rememberFailure(work, note)
                true
            }
        }
        if (continueWatch) {
            recordDiagnostic(
                DiagnosticSeverity.WARNING,
                "readiness",
                "Adapter was not ready after loading ${work.url}. The page was left in place and was not restored. " +
                    "probe=$settledProbe console=${_consoleLines.value.takeLast(5)}",
            )
            freezeBlankScreen("initial-readiness-miss", work, settledProbe)
            watchReadiness(work)
        }
    }

    private fun watchReadiness(work: ReadyWork) {
        readinessWatch?.cancel()
        readinessWatch = scope.launch {
            val watchStart = productionDocumentProbe(work)
            recordDiagnostic(
                DiagnosticSeverity.INFO,
                "readiness",
                "readiness-watch-start probe=$watchStart",
            )
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
            val probe = productionDocumentProbe(work, chromeOverride = "recovery")
            mutex.withLock {
                if (_state.value !is SessionState.Opening || _activeGameId.value != work.gameId) {
                    return@withLock
                }
                val note =
                    "Game interface stayed hidden after the readiness watch. " +
                        "The save was not overwritten and the page was not reloaded. probe=$probe"
                rememberFailure(work, note)
                recordDiagnostic(DiagnosticSeverity.WARNING, "readiness", note)
                freezeBlankScreen("readiness-watch-timeout", work, probe)
                _sessionError.value = note
                transitionTo(SessionState.RecoveryRequired, work.gameId)
            }
        }
    }

    /**
     * True when this finish should be ignored because the document is still the
     * storage bootstrap page, or the marker check was inconclusive.
     * Fail closed: timeout/exception means ignore, do not await readiness.
     */
    private suspend fun shouldIgnoreAsBootstrap(work: ReadyWork): Boolean {
        bootstrapConfirmTest?.let { return it(work.view) }
        // Unit tests inject peek/readiness hooks and never serve the bootstrap page.
        if (peekClient != null || readinessCheck != null) return false
        return try {
            val deferred = kotlinx.coroutines.CompletableDeferred<String?>()
            work.view.evaluateJavascript(
                "(function(){ try { return String(window.__hubStorageBootstrap || ''); } catch (e) { return 'error'; } })()",
            ) { deferred.complete(it) }
            val raw = withTimeoutOrNull(2_000L) { deferred.await() }
            if (raw == null) {
                Log.w(TAG, "bootstrap marker timed out; ignoring finish")
                return true
            }
            val marker = raw.trim().trim('"')
            marker == "peek" || marker == "write" || marker == "rollback" || marker == "error"
        } catch (e: Exception) {
            Log.e(TAG, "bootstrap check failed", e)
            true
        }
    }

    private fun freezeBlankScreen(reason: String, work: ReadyWork, probe: String) {
        loadTimeline.append(
            loadToken = work.loadToken,
            phase = documentGate.phase.name,
            state = _state.value.toString(),
            category = "blank-screen",
            message = "$reason probe=$probe",
        )
        val report = diagnosticReport() + "\nblankReason=$reason\nprobe=$probe\n"
        loadTimeline.freeze(report)
        _blankScreenReport.value = report
        recordDiagnostic(DiagnosticSeverity.WARNING, "blank-screen", "Frozen diagnostic before recovery: $reason")
    }

    private suspend fun productionDocumentProbe(
        work: ReadyWork,
        chromeOverride: String? = null,
    ): String {
        // Unit tests inject peek/readiness hooks and Robolectric WebViews that
        // never invoke evaluateJavascript callbacks.
        if (peekClient != null || readinessCheck != null || bootstrapConfirmTest != null) {
            return "skipped"
        }
        val script = work.adapter.readinessScript
        if (script.isBlank()) {
            return "no-adapter-probe" + chromeAnnotation(work.view, chromeOverride)
        }
        val body = try {
            withTimeoutOrNull(3_000L) {
                val deferred = kotlinx.coroutines.CompletableDeferred<String?>()
                work.view.evaluateJavascript(script) { deferred.complete(it) }
                unwrapJsResult(deferred.await())
            } ?: "probe-timeout"
        } catch (e: Exception) {
            "probe-failed:${e.javaClass.simpleName}"
        }
        return body + chromeAnnotation(work.view, chromeOverride)
    }

    /**
     * Native chrome is invisible to the page. Opening and recovery sheets sit
     * on top of the WebView; a clear annotation means neither is composed.
     * Recovery probes run before the sheet is composed, so the caller passes
     * [chromeOverride] when that sheet is about to cover the WebView.
     */
    private fun chromeAnnotation(view: WebView, chromeOverride: String? = null): String {
        val chrome = chromeOverride ?: when {
            _state.value is SessionState.RecoveryRequired -> "recovery"
            _state.value is SessionState.Opening && documentGate.phase != DocumentPhase.GAME -> "opening"
            else -> "clear"
        }
        return " chrome=$chrome web=${view.width}x${view.height}"
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
            if (expected != null && storageMatches(work.adapter, readback, expected)) {
                if (work.adapter.usesSaveEnvelope) {
                    markEnvelopeStorageVerified()
                }
                openStep = OpenStep.RESTORE_VERIFY
                documentGate.phase = DocumentPhase.GAME
                documentGate.payload = null
                gameDocumentLoads += 1
                _loadToken.value = _loadToken.value + 1
                restoreVerifyToken = _loadToken.value
            } else {
                beginRollbackLocked()
            }
        }
    }

    private suspend fun finishRestoreVerify(work: ReadyWork) {
        if (shouldIgnoreAsBootstrap(work)) {
            recordDiagnostic(
                DiagnosticSeverity.INFO,
                "navigation",
                "Ignored restore-verify finish on the storage bootstrap page. Waiting for the real document.",
            )
            return
        }
        val generation = mutex.withLock {
            if (!restoreVerifyCurrent(work, restoreGeneration)) {
                noteStaleRestore(work, "before-check")
                return
            }
            restoreGeneration
        }
        val prepared = preparedPayload
        val ready = awaitGameReady(work)
        val verdict = if (!ready || prepared == null) {
            RuntimeVerdict.REJECTED
        } else {
            try {
                val check = runtimeCheck
                if (check != null) {
                    if (check.invoke(work.view, work.adapter, prepared)) {
                        RuntimeVerdict.ACCEPTED
                    } else {
                        RuntimeVerdict.REJECTED
                    }
                } else {
                    work.adapter.runtimeVerdict(work.view, prepared)
                }
            } catch (e: Exception) {
                Log.e(TAG, "runtime verify failed", e)
                RuntimeVerdict.REJECTED
            }
        }
        val baseline = if (verdict == RuntimeVerdict.ACCEPTED && prepared != null) {
            acceptedRestorePayload(work, prepared)
        } else {
            null
        }
        mutex.withLock {
            if (!restoreVerifyCurrent(work, generation)) {
                noteStaleRestore(work, "after-check")
                return
            }
            when (verdict) {
                RuntimeVerdict.ACCEPTED -> finishVerifiedRestore(work, baseline, generation)
                RuntimeVerdict.DEFERRED -> finishDeferredRestore(work, prepared)
                RuntimeVerdict.REJECTED -> beginRollbackLocked()
            }
        }
    }

    /**
     * Durable hand-off after runtime acceptance. Capture stays suppressed,
     * and [SessionState.Playing] is not reported, unless this transaction commits.
     */
    private suspend fun finishVerifiedRestore(work: ReadyWork, payload: String?, generation: Int) {
        if (!restoreVerifyCurrent(work, generation)) {
            noteStaleRestore(work, "commit")
            return
        }
        if (payload.isNullOrBlank()) {
            beginRollbackLocked()
            return
        }
        val valid = try {
            work.adapter.validate(payload)
        } catch (e: Exception) {
            Log.e(TAG, "restored payload failed validation", e)
            false
        }
        if (!valid) {
            _sessionError.value =
                "invalid. The restored save failed validation. Both restore candidates were kept."
            setCaptureSuppressed(true)
            transitionTo(SessionState.RecoveryRequired, work.gameId)
            return
        }
        val committed = try {
            commitRestoredBaseline(
                work.gameId,
                payload,
                summaryJson(work.adapter.summarize(payload)),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "post-restore commit failed", e)
            _sessionError.value =
                "storage error. ${e.message ?: "The restored save was not committed."} Both restore candidates were kept."
            setCaptureSuppressed(true)
            if (_state.value is SessionState.Opening) {
                transitionTo(SessionState.RecoveryRequired, work.gameId)
            }
            return
        }
        if (work.adapter.usesSaveEnvelope) {
            _activeProfileName.value?.let { journalStore.delete(it) }
        }
        launchMode = LaunchMode.RESUME
        setCaptureSuppressed(false)
        restoreInProgress = false
        preparedPayload = null
        liveBeforeRestore = null
        openStep = OpenStep.DECIDE
        documentGate.phase = DocumentPhase.GAME
        documentGate.payload = null
        transitionTo(SessionState.Playing, work.gameId)
        if (_state.value is SessionState.Playing) {
            onSnapshotSaved?.invoke(committed)
            Log.d(TAG, "restore verified ${work.gameId}")
        }
    }

    private suspend fun finishDeferredRestore(work: ReadyWork, intended: String?) {
        if (!intended.isNullOrBlank()) {
            try {
                appendSnapshot(
                    gameId = work.gameId,
                    payload = intended,
                    reason = SnapshotReasons.POST_RESTORE,
                    pinned = true,
                    progressJson = summaryJson(work.adapter.summarize(intended)),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "deferred restore pin failed", e)
                _sessionError.value =
                    "storage error. ${e.message ?: "The intended save was not pinned."} Both restore candidates were kept."
                setCaptureSuppressed(true)
                transitionTo(SessionState.RecoveryRequired, work.gameId)
                return
            }
        }
        if (work.adapter.usesSaveEnvelope && snapshotDao != null) {
            _activeProfileName.value?.let { journalStore.delete(it) }
        }
        restoreInProgress = false
        launchMode = LaunchMode.RECOVER
        setCaptureSuppressed(true)
        val note =
            "Offline warp is still running. The intended save was pinned. Capture stays suppressed until the warp finishes."
        _sessionError.value = note
        recordDiagnostic(DiagnosticSeverity.INFO, "restore", note)
        transitionTo(SessionState.RecoveryRequired, work.gameId)
    }

    private suspend fun finishRollback(work: ReadyWork) {
        onBootstrapWrite?.invoke(documentGate.payload)
        val expected = liveBeforeRestore
        val readback = when (val outcome = readPeek(work)) {
            is PeekOutcome.Value -> outcome.payload
            PeekOutcome.Empty -> ""
            PeekOutcome.Failed -> null
        }
        val rolledBack = when {
            readback == null -> false
            expected.isNullOrEmpty() && readback.isEmpty() -> true
            expected != null && storageMatches(work.adapter, readback, expected) -> true
            else -> false
        }
        val note = if (rolledBack) {
            "Restore did not match the running game. The previous storage value was written back. The game was not loaded again."
        } else {
            "Rollback failed. Both restore candidates were kept. Manual intervention is required."
        }
        mutex.withLock {
            if (_state.value !is SessionState.Opening || _activeGameId.value != work.gameId) return
            restoreInProgress = !rolledBack
            documentGate.phase = DocumentPhase.PEEK
            rememberFailure(work, note)
            _sessionError.value = note
            if (!rolledBack || launchMode == LaunchMode.RECOVER ||
                launchMode == LaunchMode.SAFE || launchMode == LaunchMode.INSPECT
            ) {
                setCaptureSuppressed(true)
            }
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
        val profileName = _activeProfileName.value
        val pendingPhase = profileName?.let { name ->
            runCatching { journalStore.read(name)?.phase }.getOrNull()
        }
        val journalBlocksLoad = activeAdapter?.usesSaveEnvelope == true &&
            profileName != null &&
            journalStore.unfinished(profileName) &&
            pendingPhase != JournalPhase.VERIFIED &&
            pendingPhase != JournalPhase.POINTER_COMMITTED
        if (journalBlocksLoad) {
            recordDiagnostic(
                DiagnosticSeverity.WARNING,
                "restore",
                "Refused to load the game over an unfinished restore.",
            )
            return
        }
        openStep = OpenStep.LOAD_GAME
        documentGate.phase = DocumentPhase.GAME
        documentGate.payload = null
        gameDocumentLoads += 1
        _loadToken.value = _loadToken.value + 1
        recordDiagnostic(DiagnosticSeverity.INFO, "navigation", "Loading the game document.")
        loadTimeline.append(
            loadToken = _loadToken.value,
            phase = DocumentPhase.GAME.name,
            state = _state.value.toString(),
            category = "navigation",
            message = "beginGameLoad token=${_loadToken.value}",
        )
    }

    private fun blockForPendingJournal(gameId: String): Boolean {
        val adapter = activeAdapter ?: return false
        if (!adapter.usesSaveEnvelope) return false
        val profileName = _activeProfileName.value ?: return false
        if (!journalStore.unfinished(profileName)) return false
        val journal = try {
            journalStore.read(profileName)
        } catch (e: Exception) {
            Log.e(TAG, "restore journal unreadable", e)
            null
        }
        if (journal?.phase == JournalPhase.VERIFIED) return false
        openStep = OpenStep.JOURNAL_APPLY
        documentGate.storageKey = adapter.storageKey
        if (journal == null) {
            documentGate.phase = DocumentPhase.PEEK
            documentGate.payload = null
            _sessionError.value = "An unfinished restore could not be read. The game was not loaded."
        } else {
            documentGate.phase = DocumentPhase.WRITE
            documentGate.payload = journal.applyPayload()
            _sessionError.value = "An unfinished restore was found. The game was not loaded."
        }
        recordDiagnostic(
            DiagnosticSeverity.WARNING,
            "restore",
            "Unfinished restore for $gameId. The game was not loaded.",
        )
        return true
    }

    private suspend fun finishJournalApply(work: ReadyWork) {
        val profileName = _activeProfileName.value ?: return
        val journal = try {
            journalStore.read(profileName)
        } catch (e: Exception) {
            Log.e(TAG, "restore journal unreadable", e)
            null
        }
        if (journal == null) {
            mutex.withLock {
                if (_state.value !is SessionState.Opening || _activeGameId.value != work.gameId) return
                _sessionError.value = "An unfinished restore could not be read. The game was not loaded."
                transitionTo(SessionState.RecoveryRequired, work.gameId)
            }
            return
        }
        val expected = journal.applyPayload()
        onBootstrapWrite?.invoke(expected)
        val readback = when (val outcome = readPeek(work)) {
            is PeekOutcome.Value -> outcome.payload
            else -> null
        }
        val matched = storageMatches(work.adapter, readback, expected)
        if (!matched && journal.direction == RestoreDirection.INTENDED) {
            val rolled = journal.copy(
                direction = RestoreDirection.PREVIOUS,
                phase = JournalPhase.APPLYING,
                appliedKeys = 0,
            )
            journalStore.write(rolled)
            mutex.withLock {
                if (_state.value !is SessionState.Opening || _activeGameId.value != work.gameId) return
                documentGate.phase = DocumentPhase.WRITE
                documentGate.payload = rolled.applyPayload()
                _loadToken.value = _loadToken.value + 1
                _sessionError.value = "Interrupted restore did not verify. The previous complete state is being written."
            }
            return
        }
        mutex.withLock {
            if (_state.value !is SessionState.Opening || _activeGameId.value != work.gameId) return
            if (!matched) {
                _sessionError.value = "Restore is incomplete. The game was not loaded."
                transitionTo(SessionState.RecoveryRequired, work.gameId)
                return
            }
            if (journal.phase == JournalPhase.POINTER_COMMITTED) {
                journalStore.delete(profileName)
                openStep = OpenStep.DECIDE
                documentGate.phase = DocumentPhase.PEEK
                documentGate.payload = null
                _loadToken.value = _loadToken.value + 1
                _sessionError.value = null
                recordDiagnostic(
                    DiagnosticSeverity.INFO,
                    "restore",
                    "Restore verified. Game loading can continue.",
                )
                return
            }
            journalStore.write(journal.copy(phase = JournalPhase.VERIFIED))
            if (!journal.previousPointer.isNullOrBlank()) {
                pinCopy(work.gameId, work.adapter, journal.previousPointer, SnapshotReasons.PRE_RESTORE)
            }
            if (!journal.intendedPointer.isNullOrBlank()) {
                pinCopy(work.gameId, work.adapter, journal.intendedPointer, SnapshotReasons.PRE_RESTORE)
            }
            launchMode = LaunchMode.RECOVER
            setCaptureSuppressed(true)
            val note =
                "Interrupted restore was not runtime-accepted. The previous pointer was kept. Both restore candidates were kept."
            _sessionError.value = note
            recordDiagnostic(DiagnosticSeverity.WARNING, "restore", note)
            transitionTo(SessionState.RecoveryRequired, work.gameId)
        }
    }

    private fun storageMatches(adapter: GameAdapter, actual: String?, expected: String): Boolean {
        if (actual == expected) return true
        if (!adapter.usesSaveEnvelope || actual == null) return false
        return SaveEnvelope.sameDocument(actual, expected, adapter.envelopePolicy())
    }

    private suspend fun commitEnvelopePointer(gameId: String, payload: String?) {
        val instance = instanceDao.get(gameId) ?: return
        if (instance.snapshotPayload == payload) return
        instanceDao.upsert(instance.copy(snapshotPayload = payload))
    }

    /**
     * Storage write matched. The Room pointer stays put until [finishVerifiedRestore].
     */
    private fun markEnvelopeStorageVerified() {
        val profileName = _activeProfileName.value ?: return
        val journal = runCatching { journalStore.read(profileName) }.getOrNull() ?: return
        if (journal.phase == JournalPhase.POINTER_COMMITTED || journal.phase == JournalPhase.VERIFIED) return
        journalStore.write(journal.copy(phase = JournalPhase.VERIFIED))
    }

    private suspend fun journalPreparedRestore(
        gameId: String,
        adapter: GameAdapter,
        livePayload: String?,
        prepared: String,
    ): Boolean {
        val policy = adapter.envelopePolicy()
        val intended = SaveEnvelope.decode(prepared, policy)
        if (intended !is EnvelopeDecode.Ok) {
            _sessionError.value = "Restore refused because the snapshot envelope is invalid."
            return false
        }
        val previousSlots = if (livePayload == null) {
            adapter.storageEntries.map { StorageSlot(it.name, null) }
        } else {
            when (val live = SaveEnvelope.decode(livePayload, policy)) {
                is EnvelopeDecode.Ok -> live.bundle.slots
                is EnvelopeDecode.Rejected -> {
                    _sessionError.value = "Restore refused because live storage is not a valid envelope."
                    return false
                }
            }
        }
        val profileName = _activeProfileName.value ?: "hub-game-$gameId"
        val currentPointer = instanceDao.get(gameId)?.snapshotPayload
        journalStore.write(
            RestoreJournal(
                profileName = profileName,
                gameId = gameId,
                phase = JournalPhase.PREPARED,
                appliedKeys = 0,
                previousSlots = previousSlots,
                intendedSlots = intended.bundle.slots,
                previousPointer = currentPointer,
                intendedPointer = prepared,
                direction = RestoreDirection.INTENDED,
            ),
        )
        val preparedJournal = journalStore.read(profileName) ?: return false
        journalStore.write(preparedJournal.copy(phase = JournalPhase.APPLYING))
        return true
    }

    private suspend fun captureThroughAdapter(
        view: WebView,
        adapter: GameAdapter,
        gameId: String,
    ): String? {
        val result = QualificationResult(gameId = gameId, displayName = gameId, stages = emptyList())
        if (!adapter.usesSaveEnvelope) return adapter.capture(view, result)
        val bundle = adapter.captureBundle(view, result) ?: return null
        val encoded = SaveEnvelope.encode(bundle.slots)
        return when (SaveEnvelope.decode(encoded, adapter.envelopePolicy())) {
            is EnvelopeDecode.Ok -> encoded
            is EnvelopeDecode.Rejected -> null
        }
    }

    private suspend fun beginRollbackLocked() {
        openStep = OpenStep.ROLLBACK
        documentGate.phase = DocumentPhase.ROLLBACK
        documentGate.payload = liveBeforeRestore
        if (activeAdapter?.usesSaveEnvelope == true) {
            val profileName = _activeProfileName.value
            val journal = profileName?.let { name ->
                runCatching { journalStore.read(name) }.getOrNull()
            }
            val gameId = _activeGameId.value
            if (journal != null && gameId != null) {
                commitEnvelopePointer(gameId, journal.previousPointer)
                val rolled = journal.copy(
                    direction = RestoreDirection.PREVIOUS,
                    phase = JournalPhase.APPLYING,
                    appliedKeys = 0,
                )
                journalStore.write(rolled)
                documentGate.payload = rolled.applyPayload()
            }
        }
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
        setCaptureSuppressed(true)
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
        restoreGeneration += 1
        restoreSessionIdentity = instanceDao.get(gameId)?.sessionIdentity
        restoreProfileName = _activeProfileName.value
        restoreVerifyToken = -1
        val livePayload = (peekOutcome as? PeekOutcome.Value)?.payload
        pinCopy(gameId, adapter, livePayload, SnapshotReasons.PRE_RESTORE)
        val roomPayload = instanceDao.get(gameId)?.snapshotPayload
        if (!roomPayload.isNullOrBlank()) {
            pinCopy(gameId, adapter, roomPayload, SnapshotReasons.PRE_RESTORE)
        }
        val prepared = adapter.prepareRestore(stored)
        if (adapter.usesSaveEnvelope && !journalPreparedRestore(gameId, adapter, livePayload, prepared)) {
            return
        }
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
        if ((reason == SnapshotReasons.DIAGNOSTIC_BACKUP || reason == SnapshotReasons.POST_RESTORE) &&
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

    private suspend fun performCaptureLocked(reason: String): CaptureAttempt {
        if (suppressCapture) {
            Log.d(TAG, "capture suppressed ($reason)")
            return CaptureAttempt.Failed(captureBlockDetail())
        }
        if (captureRunning) {
            Log.w(TAG, "capture overlap rejected ($reason)")
            return CaptureAttempt.Busy
        }
        val gameId = _activeGameId.value
            ?: return CaptureAttempt.Failed("Save failed. Last good snapshot was kept.")
        if (_state.value !is SessionState.Playing) {
            val error = "unready. Safe capture is impossible in ${_state.value} ($reason). Last good snapshot was kept."
            writeCaptureResult(
                gameId,
                successPayload = null,
                error = error,
            )
            return CaptureAttempt.Failed(error)
        }
        val adapter = activeAdapter
        val view = webView
        if (adapter == null || (view == null && captureClient == null)) {
            val error = "unready. Safe capture is impossible: no live WebView ($reason). Last good snapshot was kept."
            writeCaptureResult(
                gameId,
                successPayload = null,
                error = error,
            )
            _sessionError.value = error
            return CaptureAttempt.Failed(error)
        }
        captureRunning = true
        try {
            transitionTo(SessionState.Capturing, gameId)
            val payload = try {
                // Null is a capture result. Falling through would read the WebView again.
                val client = captureClient
                if (client != null) client.invoke(view, adapter)
                else captureThroughAdapter(view!!, adapter, gameId)
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
                val decline = adapter.captureDecline()
                val error = decline ?: "invalid. Capture failed validation ($reason). Last good snapshot was kept."
                writeCaptureResult(
                    gameId,
                    successPayload = null,
                    error = error,
                )
                _sessionError.value = error
                if (_state.value is SessionState.Capturing) {
                    transitionTo(SessionState.Playing, gameId)
                }
                return if (!decline.isNullOrBlank()) {
                    CaptureAttempt.Deferred(decline)
                } else {
                    CaptureAttempt.Failed(error)
                }
            }
            try {
                writeCaptureResult(gameId, payload, null, reason)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "capture write failed ($reason)", e)
                val error = "storage error. ${e.message ?: "Save failed."} Last good snapshot was kept."
                _sessionError.value = error
                if (_state.value is SessionState.Capturing) {
                    transitionTo(SessionState.Playing, gameId)
                }
                return CaptureAttempt.Failed(error)
            }
            if (_sessionError.value?.startsWith("Capture ") == true ||
                _sessionError.value?.startsWith("Capture deferred") == true ||
                _sessionError.value?.startsWith("invalid.") == true ||
                _sessionError.value?.startsWith("storage error.") == true
            ) {
                _sessionError.value = null
            }
            if (_state.value is SessionState.Capturing) {
                transitionTo(SessionState.Playing, gameId)
            }
            Log.d(TAG, "capture succeeded ($reason) bytes=${payload.length}")
            return CaptureAttempt.Saved
        } finally {
            captureRunning = false
        }
    }

    private sealed interface CaptureAttempt {
        data object Saved : CaptureAttempt
        data object Busy : CaptureAttempt
        data class Deferred(val detail: String) : CaptureAttempt
        data class Failed(val detail: String) : CaptureAttempt
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
        var destination = target
        if (destination is SessionState.Playing &&
            current is SessionState.Opening &&
            suppressCapture &&
            launchMode != LaunchMode.SAFE
        ) {
            _sessionError.value = captureBlockDetail()
            destination = SessionState.RecoveryRequired
        }
        if (!canTransition(current, destination)) {
            Log.w(TAG, "illegal transition $current → $destination (gameId=$gameId)")
            return
        }
        Log.d(TAG, "transition $current → $destination (gameId=$gameId)")
        recordDiagnostic(DiagnosticSeverity.INFO, "session", "$current -> $destination")
        if (current is SessionState.Playing && destination !is SessionState.Capturing) {
            stopCaptureCadence()
        }
        if (destination is SessionState.Playing && current !is SessionState.Capturing && !suppressCapture) {
            startCaptureCadence()
        }
        _state.value = destination
        if (destination is SessionState.Opening || destination is SessionState.Playing ||
            destination is SessionState.Capturing || destination is SessionState.Background ||
            destination is SessionState.Restoring || destination is SessionState.Degraded ||
            destination is SessionState.RecoveryRequired
        ) {
            _activeGameId.value = gameId
        }
        if (destination is SessionState.Playing || destination is SessionState.Capturing ||
            destination is SessionState.Restoring || destination is SessionState.Degraded
        ) {
            _activeProfileName.value = gameId?.let { "hub-game-$it" }
        }
        if (gameId != null) {
            when (destination) {
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
        val apply = suspend {
            captureWriteFault?.invoke("before-write")
            instanceDao.upsert(updated)
            if (successPayload != null) {
                captureWriteFault?.invoke("after-pointer")
                val adapter = activeAdapter
                val progress = adapter?.let { summaryJson(it.summarize(successPayload)) }
                appendSnapshot(
                    gameId,
                    successPayload,
                    reason,
                    pinned = reason == SnapshotReasons.CHECKPOINT,
                    progressJson = progress,
                )
            }
        }
        val database = roomDatabase
        if (database != null) database.withTransaction { apply() } else apply()
        if (successPayload != null) onSnapshotSaved?.invoke(updated)
    }

    private fun setCaptureSuppressed(suppressed: Boolean) {
        suppressCapture = suppressed
        _captureBlockReason.value = if (suppressed) captureBlockDetail() else null
    }

    private fun captureBlockDetail(): String = when (launchMode) {
        LaunchMode.SAFE -> "capture suppressed because Safe Launch does not take Hub saves"
        LaunchMode.INSPECT -> "capture suppressed because Inspect is read-only"
        LaunchMode.RECOVER, LaunchMode.RESUME ->
            "capture suppressed because restore is unfinished"
    }

    private suspend fun restoreVerifyCurrent(work: ReadyWork, generation: Int): Boolean {
        if (_state.value !is SessionState.Opening) return false
        if (openStep != OpenStep.RESTORE_VERIFY) return false
        if (_activeGameId.value != work.gameId) return false
        if (work.loadToken != _loadToken.value || work.loadToken != restoreVerifyToken) return false
        if (generation != restoreGeneration) return false
        if (_activeProfileName.value != restoreProfileName) return false
        val identity = instanceDao.get(work.gameId)?.sessionIdentity
        return identity == restoreSessionIdentity
    }

    private fun noteStaleRestore(work: ReadyWork, stage: String) {
        recordDiagnostic(
            DiagnosticSeverity.INFO,
            "restore",
            "Ignored stale restore verification at $stage for ${work.gameId} token=${work.loadToken}.",
        )
    }

    /**
     * Prefer the post-load storage when the adapter accepts it as the same
     * save or a legitimate advance. A regression or an incomparable rewrite
     * keeps the intended payload.
     */
    private suspend fun acceptedRestorePayload(work: ReadyWork, intended: String): String {
        val readback = when (val outcome = readPeek(work)) {
            is PeekOutcome.Value -> outcome.payload
            else -> null
        }
        if (readback.isNullOrBlank()) return intended
        if (storageMatches(work.adapter, readback, intended)) return readback
        val valid = try {
            work.adapter.validate(readback)
        } catch (e: Exception) {
            false
        }
        if (!valid) return intended
        val comparison = try {
            work.adapter.compare(readback, intended)
        } catch (e: Exception) {
            SaveComparison.INCONCLUSIVE
        }
        return if (comparison == SaveComparison.MATCH || comparison == SaveComparison.LIVE_AHEAD) {
            readback
        } else {
            intended
        }
    }

    private suspend fun commitRestoredBaseline(
        gameId: String,
        payload: String,
        progressJson: String?,
    ): GameInstance {
        val apply = suspend {
            restoreCommitFault?.invoke("before-write")
            appendSnapshot(
                gameId = gameId,
                payload = payload,
                reason = SnapshotReasons.POST_RESTORE,
                pinned = true,
                progressJson = progressJson,
            )
            restoreCommitFault?.invoke("after-pin")
            val instance = instanceDao.get(gameId) ?: error("storage error. Missing game row.")
            val now = System.currentTimeMillis()
            val updated = instance.copy(
                lastCaptureAttemptAtMillis = now,
                lastCaptureSuccessAtMillis = now,
                lastCaptureAtMillis = now,
                lastCaptureError = null,
                snapshotPayload = payload,
                recoveryState = null,
            )
            instanceDao.upsert(updated)
            updated
        }
        val database = roomDatabase
        return if (database != null) database.withTransaction { apply() } else apply()
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
        val loadToken: Int,
    )

    private data class PendingFinish(
        val url: String,
        val finishToken: Int,
    )
}
