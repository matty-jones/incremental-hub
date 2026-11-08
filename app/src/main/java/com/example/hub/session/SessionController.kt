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
import com.example.hub.database.SyncOutboxDao
import com.example.hub.database.SyncOutboxEntry
import com.example.hub.database.enqueueAcceptedSnapshot
import com.example.hub.database.originForReason
import com.example.hub.games.EnvelopeDecode
import com.example.hub.games.OfflineCapability
import com.example.hub.games.OfflineSettlement
import com.example.hub.games.OfflineStart
import com.example.hub.games.GameAdapter
import com.example.hub.games.GameAdapters
import com.example.hub.games.GameRegistry
import com.example.hub.games.PayloadDigest
import com.example.hub.games.PeekOutcome
import com.example.hub.games.ProgressionClass
import com.example.hub.games.combineProgression
import com.example.hub.games.QualificationResult
import com.example.hub.games.RuntimeVerdict
import com.example.hub.games.SaveComparison
import com.example.hub.games.SaveEnvelope
import com.example.hub.games.SaveStanding
import com.example.hub.games.SaveSummary
import com.example.hub.games.StorageSlot
import com.example.hub.vault.ActiveRestore
import com.example.hub.vault.ExportHold
import com.example.hub.vault.VaultAssessment
import com.example.hub.vault.assessVaultRestore
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
data class SnapshotShare(val nonce: Int, val payload: String)

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
    private val syncOutboxDao: SyncOutboxDao? = null,
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
        INSPECT_WRITE,
        INSPECT_VERIFY,
        PROMOTE_PEEK,
        FORCE_PEEK,
    }

    private enum class LiveIntent { PROMOTE, FORCE }

    private data class LiveReady(
        val gameId: String,
        val adapter: GameAdapter,
        val view: WebView,
    )

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

    private val _forceSavePrompt = MutableStateFlow<ForceSavePrompt?>(null)
    val forceSavePrompt: StateFlow<ForceSavePrompt?> = _forceSavePrompt.asStateFlow()
    private var promotionPayload: String? = null
    private var pendingLiveReturn: SessionState? = null

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
    @Volatile private var captureRunning = false
    private var pendingDocumentUrl: String? = null
    private var activeAdapter: GameAdapter? = null

    internal var cadenceStartCount: Int = 0
        private set

    internal var gameDocumentLoads: Int = 0
        private set

    @Volatile var webView: WebView? = null

    private var launchMode: LaunchMode = LaunchMode.RESUME
    private var suppressCapture: Boolean = false
    private var incidentDetail: String? = null
    private val _captureBlockReason = MutableStateFlow<String?>(null)
    val captureBlockReason: StateFlow<String?> = _captureBlockReason.asStateFlow()
    private var restoreGeneration: Int = 0
    private var restoreSessionIdentity: String? = null
    private var restoreProfileName: String? = null
    private var restoreVerifyToken: Int = -1

    /** Throws inside the post-restore transaction. Stages: before-write, after-pin. */
    internal var restoreCommitFault: (suspend (String) -> Unit)? = null

    /** Throws inside a capture write. Stages: before-write, after-pointer, after-quarantine. */
    internal var captureWriteFault: (suspend (String) -> Unit)? = null
    private var openStep: OpenStep = OpenStep.DECIDE
    @Volatile private var restoreInProgress: Boolean = false
    @Volatile private var vaultOperation: Boolean = false
    private var vaultBranchRequested: Boolean = false
    private var vaultBranchConfirmed: Boolean = false
    private var vaultHistorical: Boolean = false
    private var vaultSnapshotId: String? = null
    private var vaultLineageId: String? = null
    private var vaultBaselinePayload: String? = null
    private val _vaultPlan = MutableStateFlow<VaultAssessment?>(null)
    val vaultPlan: StateFlow<VaultAssessment?> = _vaultPlan.asStateFlow()
    private var peekOutcome: PeekOutcome? = null
    private var chosenPayload: String? = null
    private var liveBeforeRestore: String? = null
    private var preparedPayload: String? = null
    private var ambiguousLive: Boolean = false
    private var explicitRestore: Boolean = false
    private var inspectSequence: Long? = null
    private var inspectPayload: String? = null
    private var inspectVerifyToken: Int = -1
    internal var inspectionProfileName: String? = null
    private val _exportedSnapshot = MutableStateFlow<String?>(null)
    val exportedSnapshot: StateFlow<String?> = _exportedSnapshot.asStateFlow()
    private val _snapshotShare = MutableStateFlow<SnapshotShare?>(null)
    val snapshotShare: StateFlow<SnapshotShare?> = _snapshotShare.asStateFlow()
    private val _inspectedSequence = MutableStateFlow<Long?>(null)
    val inspectedSequence: StateFlow<Long?> = _inspectedSequence.asStateFlow()
    private val consoleBuffer = ArrayDeque<String>()
    private var handlingDocument = false
    private var pendingFinish: PendingFinish? = null
    private var attachedOwner: LifecycleOwner? = lifecycleOwner
    private var readinessWatch: Job? = null
    val loadTimeline = LoadTimeline()
    private val _blankScreenReport = MutableStateFlow<String?>(null)
    val blankScreenReport: StateFlow<String?> = _blankScreenReport.asStateFlow()
    private val backgroundStore = BackgroundCheckpointStore(context)
    private val backgroundClock: BackgroundClock = SystemBackgroundClock()
    private var gameTimersPaused = false
    private var offlineResumeActive = false
    private val _backgroundNotice = MutableStateFlow<BackgroundNotice?>(null)
    val backgroundNotice: StateFlow<BackgroundNotice?> = _backgroundNotice.asStateFlow()
    private var acknowledgedReport: String? = null

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
                    mutex.withLock { pauseForBackgroundLocked() }
                }
            }
            Lifecycle.Event.ON_RESUME -> {
                scope.launch {
                    val pending = mutex.withLock {
                        if (_state.value is SessionState.Background) {
                            resumeFromBackgroundLocked()
                        } else {
                            null
                        }
                    }
                    if (pending != null) finishOfflineWork(pending)
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
            val incident = activeInstance.captureIncident
            if (!incident.isNullOrBlank()) {
                incidentDetail = incident
                setCaptureSuppressed(true)
                if (game != null) {
                    _sessionError.value = incident
                    showIncidentDiagnostic(activeInstance.gameId, incident)
                }
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
                    val pending = resumeFromBackgroundLocked()
                    if (pending != null) scope.launch { finishOfflineWork(pending) }
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
            clearVaultIntent()
            _vaultPlan.value = null
            restoreGeneration += 1
            restoreVerifyToken = -1
            setCaptureSuppressed(mode == LaunchMode.SAFE || mode == LaunchMode.INSPECT || mode == LaunchMode.RECOVER)
            openStep = OpenStep.DECIDE
            restoreInProgress = false
            peekOutcome = null
            chosenPayload = null
            liveBeforeRestore = null
            preparedPayload = null
            ambiguousLive = false
            explicitRestore = false
            inspectSequence = null
            inspectPayload = null
            inspectVerifyToken = -1
            inspectionProfileName = null
            _inspectedSequence.value = null
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
            if (mode == LaunchMode.RESUME && divertForCaptureIncident(gameId)) return
            if (mode == LaunchMode.SAFE) {
                val backups = snapshotDao?.countPinned(gameId, SnapshotReasons.DIAGNOSTIC_BACKUP) ?: 0
                if (backups == 0) {
                    failOpenLocked(
                        gameId,
                        "Play without saving needs a preview copy first. The game was not opened.",
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
                    is CaptureAttempt.Saved -> publishManualSave(ManualSaveOutcome.SAVED, null)
                    CaptureAttempt.Busy -> publishManualSave(
                        ManualSaveOutcome.BUSY,
                        "Save already in progress.",
                    )
                    is CaptureAttempt.Deferred -> publishManualSave(ManualSaveOutcome.DEFERRED, attempt.detail)
                    is CaptureAttempt.Quarantined -> publishManualSave(
                        ManualSaveOutcome.QUARANTINED,
                        attempt.detail,
                    )
                    is CaptureAttempt.Rejected -> publishManualSave(ManualSaveOutcome.REJECTED, attempt.detail)
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

    internal suspend fun captureNowForTest(reason: String) {
        mutex.withLock {
            if (_state.value is SessionState.Playing) {
                performCaptureLocked(reason)
            }
        }
    }

    fun requestBackground() {
        scope.launch {
            mutex.withLock { pauseForBackgroundLocked() }
        }
    }

    fun requestForeground() {
        scope.launch {
            val pending = mutex.withLock {
                when (_state.value) {
                    is SessionState.Background -> resumeFromBackgroundLocked()
                    is SessionState.Capturing -> {
                        Log.w(TAG, "requestForeground() ignored during capture")
                        null
                    }
                    else -> {
                        Log.w(TAG, "requestForeground() rejected: ${_state.value}")
                        null
                    }
                }
            }
            if (pending != null) finishOfflineWork(pending)
        }
    }

    /**
     * Hides the menu-bar line for the current result. The sentence stays in the
     * menu until a later interval replaces it. A calculation that has not
     * finished is not acknowledged, so its result can still be shown once.
     */
    fun acknowledgeBackgroundStatus() {
        scope.launch {
            mutex.withLock {
                val current = _backgroundNotice.value ?: return@withLock
                val report = current.report ?: return@withLock
                acknowledgedReport = "${current.checkpointKey}|$report"
                val gameId = _activeGameId.value
                if (gameId != null) backgroundStore.markHandled(gameId)
                _backgroundNotice.value = current.copy(barVisible = false)
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
        } else if (incidentDetail == null) {
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

    fun confirmRecover(sequence: Long? = null) {
        scope.launch {
            mutex.withLock { confirmRecoverLocked(sequence) }
        }
    }

    fun keepWebViewSave() {
        scope.launch {
            mutex.withLock {
                if (_state.value !is SessionState.RecoveryRequired) return@withLock
                val message =
                    "Nothing changed. The game was not opened, and neither copy was replaced."
                _sessionError.value = message
                _diagnostic.value = _diagnostic.value?.copy(note = message, webViewLeftUntouched = true)
            }
        }
    }

    fun inspectSnapshot(sequence: Long) {
        scope.launch {
            mutex.withLock { inspectSnapshotLocked(sequence) }
        }
    }

    /** True while capture, restore, or a staged vault restore must keep the workstation pointer still. */
    fun blocksExternalPointerMove(): Boolean = captureRunning || restoreInProgress || vaultOperation

    fun stageVaultRestore(snapshotId: String, lineageId: String) {
        scope.launch {
            mutex.withLock { stageVaultLocked(snapshotId, lineageId) }
        }
    }

    fun confirmVaultRestore(branch: Boolean) {
        scope.launch {
            mutex.withLock { confirmVaultLocked(branch) }
        }
    }

    fun cancelVaultRestore() {
        scope.launch {
            mutex.withLock {
                if (restoreInProgress || _state.value is SessionState.Opening) return@withLock
                clearVaultIntent()
                _vaultPlan.value = null
            }
        }
    }

    fun requestSnapshotExport(sequence: Long) {
        scope.launch {
            val payload = exportSnapshotPayload(sequence) ?: return@launch
            _exportedSnapshot.value = payload
            val nonce = (_snapshotShare.value?.nonce ?: 0) + 1
            _snapshotShare.value = SnapshotShare(nonce, payload)
        }
    }

    suspend fun exportSnapshotPayload(sequence: Long): String? {
        val gameId = _activeGameId.value ?: return null
        return snapshotDao?.history(gameId)?.firstOrNull { it.sequence == sequence }?.payload
    }

    fun confirmNewLineage(confirmed: Boolean, activate: Boolean) {
        scope.launch {
            mutex.withLock { confirmNewLineageLocked(confirmed, activate) }
        }
    }

    /**
     * Revalidates the profile save and promotes it only when the order says it
     * is ahead of the pointer, the high-water snapshot, and the quarantined copy.
     * An unconfirmed call writes nothing.
     */
    fun confirmPromoteLiveSave(confirmed: Boolean) {
        if (!confirmed) return
        scope.launch {
            try {
                promoteLiveSave()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "promote live save failed", e)
                mutex.withLock {
                    _sessionError.value =
                        "storage error. ${e.message ?: "The live save was not stored."} The active save was kept."
                }
            }
        }
    }

    /** Reads the current save and shows the force-save confirmation. Writes nothing. */
    fun requestForceSavePrompt() {
        scope.launch {
            try {
                stageForceSavePrompt()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "force-save prompt failed", e)
                _forceSavePrompt.value = null
                _sessionError.value = "No current save could be read. The active save was kept."
            }
        }
    }

    /**
     * Stores the current profile save on the same lineage without consulting the
     * progression order. Cadence and other automatic captures cannot call this.
     * An unconfirmed call writes nothing.
     */
    /**
     * Saves the game the user is looking at. When Hub already agrees it is ahead,
     * the checked promotion path is used. Otherwise the confirmed override stores it.
     * Cancelling writes nothing.
     */
    fun confirmKeepGame(confirmed: Boolean) {
        if (!confirmed) {
            confirmForceSave(false)
            return
        }
        scope.launch {
            try {
                val recovery = _state.value is SessionState.RecoveryRequired ||
                    _state.value is SessionState.Degraded
                if (recovery && _forceSavePrompt.value?.ahead == true) promoteLiveSave()
                else forceSaveConfirmed()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "keep game failed", e)
                mutex.withLock {
                    _forceSavePrompt.value = null
                    _sessionError.value =
                        "storage error. ${e.message ?: "This game was not saved."} Hub's copy was kept."
                }
            }
        }
    }

    fun confirmForceSave(confirmed: Boolean) {
        if (!confirmed) {
            scope.launch { mutex.withLock { cancelForcePromptLocked() } }
            return
        }
        scope.launch {
            try {
                forceSaveConfirmed()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "force-save failed", e)
                mutex.withLock {
                    _forceSavePrompt.value = null
                    _sessionError.value =
                        "storage error. ${e.message ?: "The current save was not stored."} The active save was kept."
                }
            }
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
                _inspectedSequence.value = null
                inspectSequence = null
                inspectPayload = null
                val inspectName = inspectionProfileName
                inspectionProfileName = null
                if (inspectName != null) runCatching { profileStore.deleteProfile(inspectName) }
                if (launchMode == LaunchMode.INSPECT) {
                    launchMode = LaunchMode.RESUME
                    setCaptureSuppressed(false)
                }
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
                    openStep == OpenStep.ROLLBACK || openStep == OpenStep.INSPECT_VERIFY ||
                    openStep == OpenStep.PROMOTE_PEEK || openStep == OpenStep.FORCE_PEEK) &&
                finishToken != _loadToken.value
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
            OpenStep.INSPECT_WRITE -> finishInspectWrite(work)
            OpenStep.INSPECT_VERIFY -> finishInspectVerify(work)
            OpenStep.PROMOTE_PEEK -> finishPromotePeek(work)
            OpenStep.FORCE_PEEK -> finishForcePeek(work)
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
        val pointerValid = !pointer.isNullOrBlank() && validates(work.adapter, pointer)
        val stored = if (pointerValid) pointer else work.adapter.chooseStored(candidates)
        val livePayload = (outcome as? PeekOutcome.Value)?.payload
        val liveSummary = work.adapter.summarize(livePayload)
        val comparison = when {
            outcome is PeekOutcome.Failed -> SaveComparison.INCONCLUSIVE
            candidates.isEmpty() -> SaveComparison.NO_SNAPSHOT
            stored == null && liveSummary.standing != SaveStanding.PROGRESSED -> SaveComparison.INCONCLUSIVE
            else -> work.adapter.compare(livePayload, stored)
        }
        val milestone = if (!livePayload.isNullOrBlank() && !stored.isNullOrBlank()) {
            classifyQuiet(work.adapter, livePayload, stored)
        } else {
            null
        }
        val classifierRejects = milestone == ProgressionClass.REGRESSED ||
            milestone == ProgressionClass.INCOMPARABLE ||
            milestone == ProgressionClass.INVALID
        val sharkResume = work.gameId == "shark-incremental" &&
            (
                milestone == ProgressionClass.EQUAL ||
                    milestone == ProgressionClass.ADVANCED ||
                    (milestone == null && comparison == SaveComparison.NO_SNAPSHOT)
                )
        val shouldPlay = launchMode == LaunchMode.RESUME &&
            outcome !is PeekOutcome.Failed &&
            !classifierRejects &&
            if (work.gameId == "shark-incremental") {
                sharkResume
            } else {
                shouldResumeLive(comparison) ||
                    milestone == ProgressionClass.EQUAL ||
                    milestone == ProgressionClass.ADVANCED
            }
        if (!shouldPlay) {
            pinCopy(work.gameId, work.adapter, livePayload, SnapshotReasons.DIAGNOSTIC_BACKUP)
            if (!pointer.isNullOrBlank() && pointer != livePayload) {
                pinCopy(work.gameId, work.adapter, pointer, SnapshotReasons.DIAGNOSTIC_BACKUP)
            }
        }
        val storedSummary = work.adapter.summarize(stored)
        val backupReady = (snapshotDao?.countPinned(work.gameId, SnapshotReasons.DIAGNOSTIC_BACKUP) ?: 0) > 0
        val liveNonEmpty = !livePayload.isNullOrBlank()
        val ambiguous = liveNonEmpty &&
            (
                comparison == SaveComparison.INCONCLUSIVE ||
                    milestone == ProgressionClass.INCOMPARABLE ||
                    milestone == ProgressionClass.INVALID
                )
        val recoverAllowed = !ambiguous &&
            outcome !is PeekOutcome.Failed &&
            stored != null &&
            validates(work.adapter, stored)
        val instance = instanceDao.get(work.gameId)
        val pinnedLiveAt = snapshotDao?.history(work.gameId)
            ?.lastOrNull { it.reason == SnapshotReasons.DIAGNOSTIC_BACKUP && it.payload == livePayload }
            ?.capturedAtMillis
        val note = when {
            ambiguous ->
                "Hub cannot tell which copy is ahead, so the game was not opened. Both copies were kept."
            classifierRejects ->
                "This game is ${plainProgress(milestone!!)} Hub's copy, so it was not opened. Hub's copy was kept."
            else -> diagnosticNote(outcome, comparison)
        }
        val consequence = if (ambiguous) {
            "Loading Hub's copy would replace this game. Both copies stay. Preview a copy below before loading it."
        } else {
            ""
        }
        val diagnostic = SaveDiagnostic(
            comparison = comparison,
            liveDetail = liveSummary.detail,
            liveBytes = liveSummary.byteLength,
            liveHash = PayloadDigest.sha256OrAbsent(livePayload),
            storedDetail = storedSummary.detail,
            storedBytes = storedSummary.byteLength,
            storedHash = PayloadDigest.sha256OrAbsent(stored),
            history = historyEntries(snapshotDao?.history(work.gameId).orEmpty(), work.adapter),
            note = note,
            backupReady = backupReady,
            recoverAllowed = recoverAllowed,
            ambiguous = ambiguous,
            consequence = consequence,
            storedCapturedAtMillis = instance?.lastCaptureAtMillis,
            livePinnedAtMillis = pinnedLiveAt,
        )
        mutex.withLock {
            if (_state.value !is SessionState.Opening || _activeGameId.value != work.gameId) return
            peekOutcome = outcome
            chosenPayload = stored
            ambiguousLive = ambiguous
            explicitRestore = false
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
        val promoting = promotionPayload
        if (promoting != null) {
            completePromotionAfterLoad(work, promoting, ready)
            return
        }
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
            clearVaultCommitFlags()
            setCaptureSuppressed(true)
            transitionTo(SessionState.RecoveryRequired, work.gameId)
            return
        }
        val previous = instanceDao.get(work.gameId)?.snapshotPayload
        val relation = if (previous.isNullOrBlank()) {
            ProgressionClass.ADVANCED
        } else {
            classifyQuiet(work.adapter, payload, previous)
        }
        val liveRelation = liveBeforeRestore?.takeIf { it.isNotBlank() }?.let {
            classifyQuiet(work.adapter, payload, it)
        }
        val behindProtected = relation == ProgressionClass.REGRESSED ||
            relation == ProgressionClass.INCOMPARABLE ||
            relation == ProgressionClass.INVALID ||
            liveRelation == ProgressionClass.REGRESSED
        if (behindProtected && !explicitRestore && !vaultBranchConfirmed) {
            _sessionError.value =
                "Restore refused because the loaded save is behind the protected pointer. Both saves were kept."
            clearVaultCommitFlags()
            setCaptureSuppressed(true)
            transitionTo(SessionState.RecoveryRequired, work.gameId)
            return
        }
        if (vaultBranchConfirmed) {
            finishVaultBranch(work, payload)
            return
        }
        val forward = !ambiguousLive &&
            relation != ProgressionClass.REGRESSED &&
            relation != ProgressionClass.INCOMPARABLE &&
            relation != ProgressionClass.INVALID &&
            liveRelation != ProgressionClass.REGRESSED
        val propose = if (vaultHistorical) false else forward
        val moveHighWater = if (vaultHistorical) false else propose && payload != previous
        val historical = vaultHistorical
        val committed = try {
            commitRestoredBaseline(
                work.gameId,
                payload,
                summaryJson(work.adapter.summarize(payload)),
                proposePointer = propose,
                moveHighWater = moveHighWater,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "post-restore commit failed", e)
            _sessionError.value =
                "storage error. ${e.message ?: "The restored save was not committed."} Both restore candidates were kept."
            clearVaultCommitFlags()
            setCaptureSuppressed(true)
            if (_state.value is SessionState.Opening) {
                transitionTo(SessionState.RecoveryRequired, work.gameId)
            }
            return
        }
        if (work.adapter.usesSaveEnvelope) {
            _activeProfileName.value?.let { journalStore.delete(it) }
        }
        incidentDetail = null
        launchMode = LaunchMode.RESUME
        setCaptureSuppressed(false)
        restoreInProgress = false
        preparedPayload = null
        liveBeforeRestore = null
        openStep = OpenStep.DECIDE
        documentGate.phase = DocumentPhase.GAME
        documentGate.payload = null
        transitionTo(SessionState.Playing, work.gameId)
        explicitRestore = false
        ambiguousLive = false
        if (historical) {
            _vaultPlan.value = _vaultPlan.value?.copy(
                message = "Restored the selected snapshot. The workstation pointer and the high-water snapshot were not moved.",
                proposeWorkstationPointer = false,
                moveHighWater = false,
                localBranch = false,
                allowed = true,
                committed = true,
            )
            vaultOperation = false
            assignVaultSnapshot(null)
            vaultLineageId = null
        }
        clearVaultCommitFlags()
        if (_state.value is SessionState.Playing && propose) {
            onSnapshotSaved?.invoke(committed)
            Log.d(TAG, "restore verified ${work.gameId}")
        }
    }

    private suspend fun finishVaultBranch(work: ReadyWork, payload: String) {
        val id = vaultSnapshotId
        val lineage = vaultLineageId
        val branched = if (id == null || lineage == null) {
            null
        } else {
            try {
                captureRepository().branchToSnapshot(
                    gameId = work.gameId,
                    snapshotId = id,
                    lineageId = lineage,
                    confirmed = true,
                    adapter = work.adapter,
                    sessionId = restoreSessionIdentity,
                    stillCurrent = { _activeGameId.value == work.gameId },
                    activatedPayload = payload,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "vault branch commit failed", e)
                null
            }
        }
        if (branched == null || branched.disposition != CaptureDisposition.STORED) {
            _sessionError.value = branched?.message
                ?: "The restore was not committed. Both saves were kept."
            clearVaultCommitFlags()
            beginRollbackLocked()
            return
        }
        if (work.adapter.usesSaveEnvelope) {
            _activeProfileName.value?.let { journalStore.delete(it) }
        }
        incidentDetail = null
        launchMode = LaunchMode.RESUME
        setCaptureSuppressed(false)
        restoreInProgress = false
        preparedPayload = null
        liveBeforeRestore = null
        openStep = OpenStep.DECIDE
        documentGate.phase = DocumentPhase.GAME
        documentGate.payload = null
        val message = branched.message
            ?: "Restored on a new local lineage. The workstation pointer was not moved."
        _vaultPlan.value = _vaultPlan.value?.copy(
            message = message,
            localBranch = true,
            allowed = true,
            proposeWorkstationPointer = false,
            moveHighWater = false,
            committed = true,
        )
        _sessionError.value = message
        clearVaultIntent()
        explicitRestore = false
        ambiguousLive = false
        transitionTo(SessionState.Playing, work.gameId)
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
            "The running game did not match Hub's copy, so the game was put back."
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
        if (!matched && journal.phase == JournalPhase.POINTER_COMMITTED) {
            commitEnvelopePointer(work.gameId, journal.intendedPointer)
            mutex.withLock {
                if (_state.value !is SessionState.Opening || _activeGameId.value != work.gameId) return
                _sessionError.value =
                    "Restore committed but storage did not verify. The committed save was kept. The game was not loaded."
                transitionTo(SessionState.RecoveryRequired, work.gameId)
            }
            return
        }
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
                commitEnvelopePointer(work.gameId, journal.targetPointer())
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
            _sessionError.value = "Play without saving needs a preview copy first. The game was not opened."
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

    private suspend fun stageVaultLocked(snapshotId: String, lineageId: String) {
        val gameId = _activeGameId.value
        val adapter = activeAdapter
        if (gameId == null || adapter == null) {
            _vaultPlan.value = null
            _sessionError.value = "Open the game before restoring. Nothing was written."
            return
        }
        val record = snapshotDao?.getById(snapshotId)
        if (record == null || record.gameId != gameId) {
            vaultOperation = false
            assignVaultSnapshot(null)
            vaultLineageId = null
            _vaultPlan.value = assessVaultRestore(
                requestedId = snapshotId,
                requestedLineageId = lineageId,
                recordId = "",
                recordLineageId = "",
                recordSha256 = "",
                payload = null,
                capturedAtMillis = 0L,
                pointerPayload = instanceDao.get(gameId)?.snapshotPayload,
                adapter = adapter,
                confirmed = false,
                branchConfirmed = false,
            )
            return
        }
        val pointer = instanceDao.get(gameId)?.snapshotPayload
        val plan = assessVaultRestore(
            requestedId = snapshotId,
            requestedLineageId = lineageId,
            recordId = record.id,
            recordLineageId = record.lineageId,
            recordSha256 = record.contentSha256,
            payload = record.payload,
            capturedAtMillis = record.capturedAtMillis,
            pointerPayload = pointer,
            highWaterPayload = highWaterPayload(gameId),
            adapter = adapter,
            confirmed = false,
            branchConfirmed = false,
        )
        _vaultPlan.value = plan
        vaultOperation = plan.ready
        assignVaultSnapshot(if (plan.ready) record.id else null)
        vaultLineageId = if (plan.ready) record.lineageId else null
    }

    private suspend fun confirmVaultLocked(branch: Boolean) {
        if (captureRunning || restoreInProgress) {
            _sessionError.value = "Restore is waiting. A capture or restore is still running."
            return
        }
        val gameId = _activeGameId.value ?: return
        val adapter = activeAdapter ?: return
        val id = vaultSnapshotId
        val lineage = vaultLineageId
        if (id == null || lineage == null) {
            _sessionError.value = "Choose a snapshot before restoring. Nothing was written."
            return
        }
        val record = snapshotDao?.getById(id)
        if (record == null || record.gameId != gameId || record.lineageId != lineage) {
            _sessionError.value = "That snapshot id and lineage were not found. Nothing was written."
            return
        }
        val state = _state.value
        if (state !is SessionState.Playing && state !is SessionState.RecoveryRequired) {
            _sessionError.value = "Restore can start during play or recovery. Nothing was written."
            return
        }
        if (state is SessionState.Playing || vaultBaselinePayload == null) {
            val pinned = pinLiveBaselineLocked(gameId, adapter, state) ?: return
            vaultBaselinePayload = pinned
        }
        val instance = instanceDao.get(gameId)
        val plan = assessVaultRestore(
            requestedId = id,
            requestedLineageId = lineage,
            recordId = record.id,
            recordLineageId = record.lineageId,
            recordSha256 = record.contentSha256,
            payload = record.payload,
            capturedAtMillis = record.capturedAtMillis,
            pointerPayload = instance?.snapshotPayload,
            highWaterPayload = highWaterPayload(gameId),
            adapter = adapter,
            confirmed = true,
            branchConfirmed = branch,
        )
        _vaultPlan.value = plan
        if (!plan.ready || !plan.allowed) {
            _sessionError.value = plan.message
            return
        }
        vaultOperation = true
        if (_inspectedSequence.value != record.sequence) {
            inspectSnapshotLocked(record.sequence, fromVault = true)
            return
        }
        vaultBranchRequested = plan.requiresBranch
        vaultHistorical = !plan.requiresBranch && !plan.proposeWorkstationPointer
        confirmRecoverLocked(record.sequence)
    }

    private suspend fun clearVaultIntent() {
        vaultOperation = false
        vaultBranchRequested = false
        vaultBranchConfirmed = false
        vaultHistorical = false
        assignVaultSnapshot(null)
        vaultLineageId = null
        vaultBaselinePayload = null
    }

    private suspend fun assignVaultSnapshot(id: String?) {
        ExportHold.guard {
            val previous = vaultSnapshotId
            if (previous != null && previous != id) ActiveRestore.releaseUnlocked(previous)
            if (id != null && previous != id) ActiveRestore.holdUnlocked(id)
            vaultSnapshotId = id
        }
    }

    /**
     * Saves the running game before a vault restore replaces its WebView.
     * A launch-time peek is not a baseline. Failure leaves the WebView in place.
     */
    private suspend fun pinLiveBaselineLocked(
        gameId: String,
        adapter: GameAdapter,
        state: SessionState,
    ): String? {
        if (state is SessionState.Playing) {
            val attempt = performCaptureLocked(SnapshotReasons.PRE_RESTORE, duringVaultBaseline = true)
            if (attempt !is CaptureAttempt.Saved) {
                _sessionError.value =
                    "Restore stopped. The current game could not be saved, so the live profile was not replaced."
                return null
            }
            val pointer = instanceDao.get(gameId)?.snapshotPayload
            if (pointer.isNullOrBlank() || !validates(adapter, pointer)) {
                _sessionError.value =
                    "Restore stopped. The saved game did not validate, so the live profile was not replaced."
                return null
            }
            pinCopy(gameId, adapter, pointer, SnapshotReasons.PRE_RESTORE)
            if (!attempt.conclusive) {
                _sessionError.value =
                    "Restore stopped. The current save could not be ordered, so the live profile was not replaced."
                return null
            }
            return pointer
        }
        val pointer = instanceDao.get(gameId)?.snapshotPayload
        if (pointer.isNullOrBlank() || !validates(adapter, pointer)) {
            _sessionError.value =
                "Restore stopped. The current save is not a validated pointer, so the live profile was not replaced."
            return null
        }
        pinCopy(gameId, adapter, pointer, SnapshotReasons.PRE_RESTORE)
        return pointer
    }

    private suspend fun highWaterPayload(gameId: String): String? {
        val id = instanceDao.get(gameId)?.highWaterSnapshotId ?: return null
        return snapshotDao?.getById(id)?.payload
    }

    private fun clearVaultCommitFlags() {
        vaultBranchRequested = false
        vaultBranchConfirmed = false
        vaultHistorical = false
    }

    private suspend fun inspectSnapshotLocked(sequence: Long, fromVault: Boolean = false) {
        val gameId = _activeGameId.value ?: return
        val state = _state.value
        if (fromVault) {
            if (state !is SessionState.Playing && state !is SessionState.RecoveryRequired) {
                _sessionError.value = "Restore can start during play or recovery. Nothing was written."
                return
            }
            if (captureRunning || restoreInProgress) {
                _sessionError.value = "Restore is waiting. A capture or restore is still running."
                return
            }
        } else if (state !is SessionState.RecoveryRequired) {
            return
        }
        val record = if (fromVault) {
            val loaded = vaultSnapshotId?.let { snapshotDao?.getById(it) }
            if (loaded == null || loaded.gameId != gameId || loaded.sequence != sequence ||
                loaded.lineageId != vaultLineageId
            ) {
                _sessionError.value = "That snapshot id and lineage were not found. Nothing was written."
                return
            }
            val digest = PayloadDigest.sha256(loaded.payload)
            if (loaded.contentSha256.isNotEmpty() && loaded.contentSha256 != digest) {
                _sessionError.value = "The stored hash does not match the payload. Nothing was written."
                return
            }
            val adapter = activeAdapter
            if (adapter == null || !validates(adapter, loaded.payload)) {
                _sessionError.value = "Restore refused because the snapshot failed validation. Nothing was written."
                return
            }
            vaultOperation = true
            loaded
        } else {
            snapshotDao?.history(gameId)?.firstOrNull { it.sequence == sequence }
        }
        if (record == null) {
            _sessionError.value = "Snapshot #$sequence was not found. Nothing was written."
            return
        }
        val profileName = "hub-inspect-$gameId-$sequence"
        val profile = obtainProfile(profileName)
        _inspectedSequence.value = null
        inspectSequence = sequence
        inspectPayload = record.payload
        inspectVerifyToken = -1
        launchMode = LaunchMode.INSPECT
        setCaptureSuppressed(true)
        inspectionProfileName = profileName
        _activeProfile.value = profile
        _activeProfileName.value = profileName
        documentGate.phase = DocumentPhase.WRITE
        documentGate.payload = record.payload
        openStep = OpenStep.INSPECT_WRITE
        val dead = webView
        webView = null
        dead?.let { runCatching { it.destroy() } }
        _webViewGeneration.value = _webViewGeneration.value + 1
        val message =
            "Preview of copy #$sequence is opening. Load stays off until the game matches this copy. " +
                "The current game and Hub's save were not changed."
        _sessionError.value = message
        _diagnostic.value = _diagnostic.value?.copy(note = message)
        if (_state.value is SessionState.Playing) {
            transitionTo(SessionState.Degraded, gameId)
        }
        if (_state.value is SessionState.Degraded) {
            transitionTo(SessionState.RecoveryRequired, gameId)
        }
        transitionTo(SessionState.Opening, gameId)
    }

    private suspend fun finishInspectWrite(work: ReadyWork) {
        val expected = inspectPayload
        if (peekClient == null) {
            val marker = try {
                work.adapter.confirmBootstrap(work.view)
            } catch (e: Exception) {
                false
            }
            if (!marker) {
                mutex.withLock {
                    if (_state.value is SessionState.Opening && _activeGameId.value == work.gameId) {
                        failInspection(
                            work.gameId,
                            "Copy #${inspectSequence ?: "?"} did not load. " +
                                "Load stays off. The current game and Hub's save were not changed.",
                        )
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
                openStep = OpenStep.INSPECT_VERIFY
                documentGate.phase = DocumentPhase.GAME
                documentGate.payload = null
                gameDocumentLoads += 1
                _loadToken.value = _loadToken.value + 1
                inspectVerifyToken = _loadToken.value
            } else {
                failInspection(
                    work.gameId,
                    "The preview did not match this copy. " +
                        "Load stays off. The current game and Hub's save were not changed.",
                )
            }
        }
    }

    private suspend fun finishInspectVerify(work: ReadyWork) {
        if (shouldIgnoreAsBootstrap(work)) return
        val generation = mutex.withLock {
            if (_state.value !is SessionState.Opening || _activeGameId.value != work.gameId) return
            if (openStep != OpenStep.INSPECT_VERIFY || work.loadToken != inspectVerifyToken) return
            restoreGeneration
        }
        val payload = inspectPayload
        val sequence = inspectSequence
        val ready = awaitGameReady(work)
        val verdict = if (!ready || payload == null || sequence == null) {
            RuntimeVerdict.REJECTED
        } else {
            try {
                val check = runtimeCheck
                if (check != null) {
                    if (check.invoke(work.view, work.adapter, payload)) {
                        RuntimeVerdict.ACCEPTED
                    } else {
                        RuntimeVerdict.REJECTED
                    }
                } else {
                    work.adapter.runtimeVerdict(work.view, payload)
                }
            } catch (e: Exception) {
                Log.e(TAG, "inspect runtime verify failed", e)
                RuntimeVerdict.REJECTED
            }
        }
        mutex.withLock {
            if (_state.value !is SessionState.Opening || _activeGameId.value != work.gameId) return
            if (openStep != OpenStep.INSPECT_VERIFY || work.loadToken != inspectVerifyToken) return
            if (generation != restoreGeneration) return
            if (verdict == RuntimeVerdict.ACCEPTED && sequence != null) {
                acceptInspection(work.gameId, sequence)
            } else {
                failInspection(
                    work.gameId,
                    "The game did not match copy #${sequence ?: "?"}. " +
                        "Load stays off. The current game and Hub's save were not changed.",
                )
            }
        }
    }

    private suspend fun acceptInspection(gameId: String, sequence: Long) {
        _inspectedSequence.value = sequence
        val profileName = inspectionProfileName ?: "the inspection profile"
        val message =
            "Copy #$sequence matches the game. Load can replace the current game after you confirm. " +
                "Hub's save was not changed."
        openStep = OpenStep.DECIDE
        documentGate.phase = DocumentPhase.PEEK
        documentGate.payload = null
        _sessionError.value = message
        _diagnostic.value = _diagnostic.value?.copy(note = message)
        transitionTo(SessionState.RecoveryRequired, gameId)
    }

    private suspend fun failInspection(gameId: String, message: String) {
        _inspectedSequence.value = null
        inspectSequence = null
        inspectPayload = null
        if (!restoreInProgress) vaultOperation = false
        clearVaultCommitFlags()
        val inspectName = inspectionProfileName
        inspectionProfileName = null
        bindLiveProfile(gameId)
        if (inspectName != null) runCatching { profileStore.deleteProfile(inspectName) }
        openStep = OpenStep.DECIDE
        documentGate.phase = DocumentPhase.PEEK
        documentGate.payload = null
        launchMode = LaunchMode.RESUME
        setCaptureSuppressed(true)
        _sessionError.value = message
        _diagnostic.value = _diagnostic.value?.copy(note = message)
        if (_state.value is SessionState.Opening) {
            transitionTo(SessionState.RecoveryRequired, gameId)
        }
    }

    private suspend fun confirmRecoverLocked(sequence: Long? = null) {
        var started = false
        try {
        val gameId = _activeGameId.value ?: return
        val adapter = activeAdapter ?: return
        if (_state.value !is SessionState.RecoveryRequired) return
        if (vaultSnapshotId != null) {
            if (vaultBaselinePayload.isNullOrBlank()) {
                _sessionError.value =
                    "Restore stopped. The current game was not saved, so the live profile was not replaced."
                return
            }
        } else if (peekOutcome == null || peekOutcome is PeekOutcome.Failed) {
            _sessionError.value = "Restore refused because storage could not be inspected."
            return
        }
        if (ambiguousLive && sequence == null) {
            _sessionError.value =
                "Load refused. Hub cannot tell which copy is ahead. Both copies were kept."
            return
        }
        if (sequence != null && _inspectedSequence.value != sequence) {
            _sessionError.value =
                "Load refused. Preview copy #$sequence before replacing the game."
            return
        }
        val stored = if (sequence == null) {
            chosenPayload
        } else if ((vaultBranchRequested || vaultHistorical) && vaultSnapshotId != null) {
            val row = snapshotDao?.getById(vaultSnapshotId!!)
            if (row?.sequence == sequence && row.lineageId == vaultLineageId) row.payload else null
        } else {
            snapshotDao?.history(gameId)?.firstOrNull { it.sequence == sequence }?.payload
        }
        if (stored.isNullOrBlank() || !validates(adapter, stored)) {
            _sessionError.value = "Restore refused because no validated snapshot is available."
            return
        }
        val previous = instanceDao.get(gameId)?.snapshotPayload
        val chosenRelation = if (previous.isNullOrBlank()) {
            ProgressionClass.ADVANCED
        } else {
            classifyQuiet(adapter, stored, previous)
        }
        val branchThis = vaultBranchRequested &&
            (chosenRelation == ProgressionClass.REGRESSED || chosenRelation == ProgressionClass.INCOMPARABLE)
        if (chosenRelation == ProgressionClass.INVALID ||
            ((chosenRelation == ProgressionClass.REGRESSED || chosenRelation == ProgressionClass.INCOMPARABLE) &&
                !branchThis)
        ) {
            _sessionError.value =
                "Restore refused. The chosen snapshot is not ahead of the protected pointer. Both saves were kept."
            return
        }
        vaultBranchConfirmed = branchThis
        explicitRestore = sequence != null
        val inspectName = inspectionProfileName
        inspectionProfileName = null
        bindLiveProfile(gameId)
        if (inspectName != null) runCatching { profileStore.deleteProfile(inspectName) }
        restoreGeneration += 1
        restoreSessionIdentity = instanceDao.get(gameId)?.sessionIdentity
        restoreProfileName = _activeProfileName.value
        restoreVerifyToken = -1
        val livePayload = if (vaultSnapshotId != null) {
            vaultBaselinePayload
        } else {
            (peekOutcome as? PeekOutcome.Value)?.payload
        }
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
        started = true
        _sessionError.value = null
        val dead = webView
        webView = null
        dead?.let { runCatching { it.destroy() } }
        _webViewGeneration.value = _webViewGeneration.value + 1
        transitionTo(SessionState.Opening, gameId)
        } finally {
            if (!started) clearVaultCommitFlags()
        }
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
    ): SnapshotRecord? {
        val dao = snapshotDao ?: return null
        if (reason == SnapshotReasons.DIAGNOSTIC_BACKUP || reason == SnapshotReasons.POST_RESTORE) {
            dao.findPinnedPayload(gameId, reason, payload)?.let { return it }
        }
        val record = SnapshotRecord(
            id = UUID.randomUUID().toString(),
            gameId = gameId,
            sequence = dao.maxSequence(gameId) + 1,
            capturedAtMillis = System.currentTimeMillis(),
            reason = reason,
            pinned = pinned,
            payload = payload,
            progressJson = progressJson,
            origin = originForReason(reason),
            lineageId = instanceDao.get(gameId)?.lineageId ?: "legacy",
            contentSha256 = PayloadDigest.sha256(payload),
        )
        dao.insert(record)
        return record
    }

    private fun historyEntries(
        records: List<SnapshotRecord>,
        adapter: GameAdapter? = activeAdapter,
    ): List<HistoryEntry> {
        return records.asReversed().map { record ->
            val parsed = adapter?.summarize(record.payload)?.detail?.takeIf { it.isNotBlank() }
            val stored = record.progressJson?.let { raw ->
                runCatching { JSONObject(raw).optString("detail") }.getOrNull().orEmpty()
            }.orEmpty()
            HistoryEntry(
                sequence = record.sequence,
                reason = record.reason,
                pinned = record.pinned,
                capturedAtMillis = record.capturedAtMillis,
                byteLength = record.payload.length,
                detail = parsed ?: stored.ifBlank { record.reason },
                origin = record.origin,
                snapshotId = record.id,
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

    private fun plainProgress(milestone: ProgressionClass): String = when (milestone) {
        ProgressionClass.REGRESSED -> "behind"
        ProgressionClass.INCOMPARABLE -> "not comparable with"
        ProgressionClass.INVALID -> "not a valid save against"
        else -> "different from"
    }

    private fun diagnosticNote(outcome: PeekOutcome, comparison: SaveComparison): String {
        if (outcome is PeekOutcome.Failed) {
            return "Hub could not read the save, so the game was not opened. Nothing was changed."
        }
        if (launchMode == LaunchMode.INSPECT) {
            return "Preview only. The game was not opened."
        }
        if (launchMode == LaunchMode.RECOVER) {
            return "Hub read its copy and did not open the game."
        }
        return when (comparison) {
            SaveComparison.LIVE_BEHIND ->
                "This game looks behind Hub's copy, so it was not opened."
            SaveComparison.LIVE_MISSING ->
                "This game has no save, and Hub has a copy. The game was not opened."
            SaveComparison.INCONCLUSIVE ->
                "Hub cannot tell which copy is ahead, so the game was not opened."
            else -> "The game was not opened."
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
        if (!restoreInProgress && _state.value !is SessionState.Opening) {
            clearVaultIntent()
            _vaultPlan.value = null
        }
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

    private suspend fun performCaptureLocked(
        reason: String,
        duringVaultBaseline: Boolean = false,
    ): CaptureAttempt {
        if (offlineResumeActive && reason != "offline-resume") {
            return CaptureAttempt.Deferred(
                "Capture deferred: offline progression is running. Last good snapshot was kept.",
            )
        }
        if (suppressCapture) {
            Log.d(TAG, "capture suppressed ($reason)")
            return CaptureAttempt.Failed(captureBlockDetail())
        }
        if (captureRunning || restoreInProgress || (vaultOperation && !duringVaultBaseline)) {
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
                } else if (payload != null) {
                    CaptureAttempt.Rejected(error)
                } else {
                    CaptureAttempt.Failed(error)
                }
            }
            val sessionId = instanceDao.get(gameId)?.sessionIdentity
            val generation = restoreGeneration
            val commit = try {
                captureRepository().commitIfSafe(
                    gameId = gameId,
                    candidate = payload,
                    reason = reason,
                    adapter = adapter,
                    sessionId = sessionId,
                    stillCurrent = {
                        restoreGeneration == generation && _activeGameId.value == gameId
                    },
                    manual = reason == SnapshotReasons.CHECKPOINT,
                )
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
            when (commit.disposition) {
                CaptureDisposition.QUARANTINED -> {
                    enterCaptureIncident(gameId, payload, commit.message.orEmpty())
                    return CaptureAttempt.Quarantined(
                        commit.message ?: "Save quarantined. The previous verified save was kept.",
                    )
                }
                CaptureDisposition.REJECTED -> {
                    val error = commit.message ?: "invalid. Capture failed validation ($reason). Last good snapshot was kept."
                    _sessionError.value = error
                    if (_state.value is SessionState.Capturing) {
                        transitionTo(SessionState.Playing, gameId)
                    }
                    return CaptureAttempt.Rejected(error)
                }
                CaptureDisposition.STORED, CaptureDisposition.UNCHANGED -> {
                    if (commit.sync) {
                        commit.instance?.let { saved ->
                            try {
                                onSnapshotSaved?.invoke(saved)
                            } catch (e: Exception) {
                                Log.e(TAG, "workstation sync failed after a local commit ($reason)", e)
                            }
                        }
                    }
                }
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
            val conclusive = commit.classification == ProgressionClass.EQUAL ||
                commit.classification == ProgressionClass.ADVANCED ||
                commit.classification == ProgressionClass.RESET_APPROVED
            return CaptureAttempt.Saved(conclusive, payload)
        } finally {
            captureRunning = false
        }
    }

    private sealed interface CaptureAttempt {
        data class Saved(val conclusive: Boolean, val payload: String? = null) : CaptureAttempt
        data object Busy : CaptureAttempt
        data class Deferred(val detail: String) : CaptureAttempt
        data class Quarantined(val detail: String) : CaptureAttempt
        data class Rejected(val detail: String) : CaptureAttempt
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
            launchMode != LaunchMode.SAFE &&
            launchMode != LaunchMode.INSPECT
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
        if (inspectionProfileName == null &&
            (destination is SessionState.Playing || destination is SessionState.Capturing ||
                destination is SessionState.Restoring || destination is SessionState.Degraded)
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
    ) {
        val instance = instanceDao.get(gameId) ?: return
        val now = System.currentTimeMillis()
        if (successPayload != null) {
            error("capture commits go through CaptureRepository")
        }
        val updated = instance.copy(
            lastCaptureAttemptAtMillis = now,
            lastCaptureFailureAtMillis = now,
            lastCaptureError = error ?: "capture failed",
        )
        instanceDao.upsert(updated)
    }

    private fun setCaptureSuppressed(suppressed: Boolean) {
        suppressCapture = suppressed
        _captureBlockReason.value = if (suppressed) captureBlockDetail() else null
    }

    private fun captureBlockDetail(): String {
        incidentDetail?.let { return it }
        return when (launchMode) {
            LaunchMode.SAFE -> "Hub is not saving this session."
            LaunchMode.INSPECT -> "capture suppressed because Inspect is read-only"
            LaunchMode.RECOVER, LaunchMode.RESUME ->
                "capture suppressed because restore is unfinished"
        }
    }

    private fun captureRepository(): CaptureRepository {
        return CaptureRepository(
            instanceDao = instanceDao,
            snapshotDao = snapshotDao,
            roomDatabase = roomDatabase,
            outboxDao = syncOutboxDao,
            fault = captureWriteFault,
        )
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
     * Prefer the post-load storage when classification says it is the same
     * save or a real advance. Lexical LIVE_AHEAD is not an advance, including
     * when the classifier returns UNORDERED.
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
        val relation = classifyQuiet(work.adapter, readback, intended)
        if (relation == ProgressionClass.EQUAL || relation == ProgressionClass.ADVANCED) return readback
        val comparison = try {
            work.adapter.compare(readback, intended)
        } catch (e: Exception) {
            SaveComparison.INCONCLUSIVE
        }
        if (comparison == SaveComparison.MATCH) return readback
        return intended
    }

    private suspend fun commitRestoredBaseline(
        gameId: String,
        payload: String,
        progressJson: String?,
        proposePointer: Boolean = true,
        moveHighWater: Boolean = true,
    ): GameInstance {
        val apply = suspend {
            restoreCommitFault?.invoke("before-write")
            val pinned = appendSnapshot(
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
                highWaterSnapshotId = if (moveHighWater) {
                    pinned?.id ?: instance.highWaterSnapshotId
                } else {
                    instance.highWaterSnapshotId
                },
                captureIncident = null,
                captureIncidentSha = null,
                captureIncidentCount = 0,
            )
            instanceDao.upsert(updated)
            val pinnedId = pinned?.id
            val outbox = syncOutboxDao
            if (proposePointer && pinnedId != null && outbox != null) {
                enqueueAcceptedSnapshot(
                    dao = outbox,
                    instance = updated,
                    snapshotId = pinnedId,
                    payload = payload,
                    sha = PayloadDigest.sha256(payload),
                    capturedAtMillis = now,
                    reason = SnapshotReasons.POST_RESTORE,
                    integrity = SyncOutboxEntry.INTEGRITY_ACCEPTED,
                    proposePointer = true,
                    enqueuedAtMillis = now,
                )
            }
            updated
        }
        val database = roomDatabase
        return if (database != null) database.withTransaction { apply() } else apply()
    }

    private suspend fun divertForCaptureIncident(gameId: String): Boolean {
        val incident = instanceDao.get(gameId)?.captureIncident
        if (incident.isNullOrBlank()) return false
        incidentDetail = incident
        setCaptureSuppressed(true)
        _sessionError.value = incident
        showIncidentDiagnostic(gameId, incident)
        if (_state.value is SessionState.Opening) {
            transitionTo(SessionState.RecoveryRequired, gameId)
        }
        return true
    }

    private suspend fun enterCaptureIncident(gameId: String, candidate: String, message: String) {
        incidentDetail = message
        setCaptureSuppressed(true)
        _sessionError.value = message
        showIncidentDiagnostic(gameId, message, candidate)
        if (_state.value is SessionState.Capturing) {
            transitionTo(SessionState.Playing, gameId)
        }
        if (_state.value is SessionState.Playing) {
            transitionTo(SessionState.Degraded, gameId)
        }
        if (_state.value is SessionState.Degraded) {
            transitionTo(SessionState.RecoveryRequired, gameId)
        }
    }

    private suspend fun showIncidentDiagnostic(
        gameId: String,
        message: String,
        candidate: String? = null,
    ) {
        val adapter = activeAdapter ?: return
        val pointer = instanceDao.get(gameId)?.snapshotPayload
        val sample = candidate ?: snapshotDao?.latestByReason(gameId, SnapshotReasons.QUARANTINE)?.payload
        chosenPayload = pointer
        if (!sample.isNullOrBlank()) {
            peekOutcome = PeekOutcome.Value(sample)
        }
        val stored = adapter.summarize(pointer)
        val live = adapter.summarize(sample)
        val relation = if (sample.isNullOrBlank() || pointer.isNullOrBlank()) {
            null
        } else {
            classifyQuiet(adapter, sample, pointer)
        }
        val ambiguousSample = !sample.isNullOrBlank() &&
            (
                relation == ProgressionClass.INCOMPARABLE ||
                    relation == ProgressionClass.INVALID ||
                    live.standing == SaveStanding.INVALID
                )
        ambiguousLive = ambiguousSample
        val history = snapshotDao?.history(gameId)?.let { historyEntries(it, adapter) }.orEmpty()
        val backups = snapshotDao?.countPinned(gameId, SnapshotReasons.DIAGNOSTIC_BACKUP) ?: 0
        val instance = instanceDao.get(gameId)
        _diagnostic.value = SaveDiagnostic(
            comparison = com.example.hub.games.SaveComparison.INCONCLUSIVE,
            liveDetail = live.detail,
            liveBytes = sample?.length ?: 0,
            liveHash = PayloadDigest.sha256OrAbsent(sample),
            storedDetail = stored.detail,
            storedBytes = pointer?.length ?: 0,
            storedHash = PayloadDigest.sha256OrAbsent(pointer),
            history = history,
            note = message,
            backupReady = backups > 0,
            recoverAllowed = !ambiguousSample && !pointer.isNullOrBlank() && validates(adapter, pointer),
            ambiguous = ambiguousSample,
            consequence = if (ambiguousSample) {
                "The live save could not be ordered. Restoring the Room pointer would replace it. Both payloads stay pinned."
            } else {
                ""
            },
            storedCapturedAtMillis = instance?.lastCaptureAtMillis,
        )
    }

    private suspend fun validates(adapter: GameAdapter, payload: String?): Boolean {
        if (payload.isNullOrBlank()) return false
        return try {
            adapter.validate(payload)
        } catch (e: Exception) {
            false
        }
    }

    private fun classifyQuiet(adapter: GameAdapter, candidate: String?, reference: String?): ProgressionClass {
        return try {
            adapter.classifyProgress(candidate, reference)
        } catch (e: Exception) {
            ProgressionClass.INCOMPARABLE
        }
    }

    private suspend fun promoteLiveSave() {
        val ready = mutex.withLock {
            if (inspectionProfileName != null) {
                refuseLive("Inspection profile $inspectionProfileName was not stored. The active save was kept.")
                return@withLock null
            }
            val gameId = _activeGameId.value ?: return@withLock null
            val adapter = activeAdapter ?: return@withLock null
            if (_state.value !is SessionState.RecoveryRequired && _state.value !is SessionState.Degraded) {
                return@withLock null
            }
            val view = webView
            if (view == null) {
                beginLivePeekLocked(LiveIntent.PROMOTE)
                return@withLock null
            }
            LiveReady(gameId, adapter, view)
        } ?: return
        val payload = peekPayload(ready.view, ready.adapter) ?: run {
            mutex.withLock { refuseLive("No profile save could be read. The active save was kept.") }
            return
        }
        val refusal = promotionRefusal(ready.gameId, ready.adapter, payload)
        if (refusal != null) {
            mutex.withLock { refuseLive(refusal) }
            return
        }
        val runtimeOk = runtimeMatches(ready.view, ready.adapter, payload)
        if (!runtimeOk) {
            mutex.withLock {
                refuseLive("The running game did not match the profile save. The active save was kept.")
            }
            return
        }
        val commit = mutex.withLock {
            if (_activeGameId.value != ready.gameId) return@withLock null
            if (_state.value !is SessionState.RecoveryRequired && _state.value !is SessionState.Degraded) {
                return@withLock null
            }
            commitPromotion(ready.gameId, ready.adapter, payload)
        } ?: return
        deliverAccepted(ready.gameId, commit)
    }

    private suspend fun finishPromotePeek(work: ReadyWork) {
        val payload = peekPayload(work.view, work.adapter)
        val refusal = if (payload == null) {
            "No profile save could be read. The active save was kept."
        } else {
            promotionRefusal(work.gameId, work.adapter, payload)
        }
        mutex.withLock {
            if (_state.value !is SessionState.Opening || _activeGameId.value != work.gameId) return
            if (payload == null || refusal != null) {
                pendingLiveReturn = null
                openStep = OpenStep.DECIDE
                refuseLive(refusal ?: "No profile save could be read. The active save was kept.")
                transitionTo(SessionState.RecoveryRequired, work.gameId)
                return
            }
            promotionPayload = payload
            beginGameLoadLocked()
        }
    }

    private suspend fun completePromotionAfterLoad(work: ReadyWork, payload: String, ready: Boolean) {
        promotionPayload = null
        if (!ready || !runtimeMatches(work.view, work.adapter, payload)) {
            mutex.withLock {
                if (_state.value !is SessionState.Opening || _activeGameId.value != work.gameId) return
                val message = if (!ready) {
                    "The game was not ready. The active save was kept."
                } else {
                    "The running game did not match the profile save. The active save was kept."
                }
                pendingLiveReturn = null
                refuseLive(message)
                transitionTo(SessionState.RecoveryRequired, work.gameId)
            }
            return
        }
        val commit = mutex.withLock {
            if (_state.value !is SessionState.Opening || _activeGameId.value != work.gameId) return
            commitPromotion(work.gameId, work.adapter, payload)
        } ?: return
        deliverAccepted(work.gameId, commit)
    }

    private suspend fun commitPromotion(
        gameId: String,
        adapter: GameAdapter,
        payload: String,
    ): CaptureCommit? {
        val commit = try {
            captureRepository().commitIfSafe(
                gameId = gameId,
                candidate = payload,
                reason = SnapshotReasons.CHECKPOINT,
                adapter = adapter,
                sessionId = instanceDao.get(gameId)?.sessionIdentity,
                stillCurrent = { _activeGameId.value == gameId },
                manual = true,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            refuseLive("storage error. ${e.message ?: "The live save was not stored."} The active save was kept.")
            return null
        }
        if (commit.disposition != CaptureDisposition.STORED || commit.classification != ProgressionClass.ADVANCED) {
            val incident = instanceDao.get(gameId)?.captureIncident
            if (!incident.isNullOrBlank()) {
                incidentDetail = incident
                setCaptureSuppressed(true)
                _sessionError.value = commit.message ?: incident
            } else {
                refuseLive(commit.message ?: "The live save was not promoted. The active save was kept.")
            }
            if (_state.value is SessionState.Opening) {
                transitionTo(SessionState.RecoveryRequired, gameId)
            }
            return null
        }
        return commit
    }

    private suspend fun promotionRefusal(gameId: String, adapter: GameAdapter, live: String): String? {
        val instance = instanceDao.get(gameId)
            ?: return "The game row is missing. The active save was kept."
        val pointer = instance.snapshotPayload
        val high = instance.highWaterSnapshotId?.let { id -> snapshotDao?.getById(id)?.payload }
        val againstPointer = classifyQuiet(adapter, live, pointer)
        val verdict = if (high.isNullOrBlank() || high == pointer) {
            againstPointer
        } else {
            combineProgression(againstPointer, classifyQuiet(adapter, live, high))
        }
        if (verdict != ProgressionClass.ADVANCED) {
            return "The live save is not ahead of the active save. The active save was kept."
        }
        val quarantine = snapshotDao?.latestByReason(gameId, SnapshotReasons.QUARANTINE)?.payload
        if (!quarantine.isNullOrBlank() &&
            classifyQuiet(adapter, live, quarantine) == ProgressionClass.REGRESSED
        ) {
            return "The live save is behind the quarantined copy. The active save was kept."
        }
        return null
    }

    private suspend fun stageForceSavePrompt() {
        val ready = mutex.withLock {
            if (inspectionProfileName != null) {
                refuseLive("Inspection profile $inspectionProfileName was not stored. The active save was kept.")
                return@withLock null
            }
            val gameId = _activeGameId.value ?: return@withLock null
            val adapter = activeAdapter ?: return@withLock null
            if (!forceSaveAllowed(_state.value)) return@withLock null
            val view = webView
            if (view == null && _state.value !is SessionState.Playing && _state.value !is SessionState.Background) {
                beginLivePeekLocked(LiveIntent.FORCE)
                return@withLock null
            }
            if (view == null && captureClient == null) {
                refuseLive("No current save could be read. The active save was kept.")
                return@withLock null
            }
            gameId to adapter
        } ?: return
        val (gameId, adapter) = ready
        val payload = readForcePayload(gameId, adapter, webView) ?: run {
            mutex.withLock { refuseLive("No current save could be read. The active save was kept.") }
            return
        }
        val stored = instanceDao.get(gameId)?.snapshotPayload
        _forceSavePrompt.value = forcePrompt(gameId, adapter, payload, stored)
    }

    private suspend fun finishForcePeek(work: ReadyWork) {
        val payload = peekPayload(work.view, work.adapter)
        if (payload == null) {
            mutex.withLock {
                if (_state.value !is SessionState.Opening || _activeGameId.value != work.gameId) return
                pendingLiveReturn = null
                openStep = OpenStep.DECIDE
                refuseLive("No current save could be read. The active save was kept.")
                transitionTo(SessionState.RecoveryRequired, work.gameId)
            }
            return
        }
        val stored = instanceDao.get(work.gameId)?.snapshotPayload
        _forceSavePrompt.value = forcePrompt(work.gameId, work.adapter, payload, stored)
        mutex.withLock {
            if (_state.value !is SessionState.Opening || _activeGameId.value != work.gameId) return
            openStep = OpenStep.DECIDE
        }
    }

    private suspend fun forceSaveConfirmed() {
        val ready = mutex.withLock {
            if (inspectionProfileName != null) {
                refuseLive("Inspection profile $inspectionProfileName was not stored. The active save was kept.")
                return@withLock null
            }
            val gameId = _activeGameId.value ?: return@withLock null
            val adapter = activeAdapter ?: return@withLock null
            if (!forceSaveAllowed(_state.value)) return@withLock null
            gameId to adapter
        } ?: return
        val (gameId, adapter) = ready
        val payload = readForcePayload(gameId, adapter, webView) ?: run {
            mutex.withLock { refuseLive("No current save could be read. The active save was kept.") }
            return
        }
        val prompt = _forceSavePrompt.value
        if (prompt != null && (prompt.gameId != gameId || prompt.liveHash != PayloadDigest.sha256(payload))) {
            mutex.withLock {
                _forceSavePrompt.value = null
                refuseLive("The current save changed. Nothing was written. The active save was kept.")
            }
            return
        }
        val commit = mutex.withLock {
            if (_activeGameId.value != gameId || !forceSaveAllowed(_state.value)) return@withLock null
            if (inspectionProfileName != null) {
                refuseLive("Inspection profile $inspectionProfileName was not stored. The active save was kept.")
                return@withLock null
            }
            try {
                captureRepository().forceStore(
                    gameId = gameId,
                    candidate = payload,
                    adapter = adapter,
                    sessionId = instanceDao.get(gameId)?.sessionIdentity,
                    stillCurrent = { _activeGameId.value == gameId },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                refuseLive("storage error. ${e.message ?: "The current save was not stored."} The active save was kept.")
                null
            }
        } ?: return
        if (commit.disposition != CaptureDisposition.STORED) {
            mutex.withLock {
                refuseLive(commit.message ?: "The current save was not stored. The active save was kept.")
            }
            return
        }
        deliverAccepted(gameId, commit)
    }

    private suspend fun forcePrompt(
        gameId: String,
        adapter: GameAdapter,
        payload: String,
        stored: String?,
    ): ForceSavePrompt {
        val verdict = classifyQuiet(adapter, payload, stored)
        val ahead = promotionRefusal(gameId, adapter, payload) == null
        val relation = if (ahead) {
            "Hub agrees this game is ahead."
        } else {
            when (verdict) {
                ProgressionClass.REGRESSED -> "Hub thinks this game is behind its saved copy."
                ProgressionClass.EQUAL -> "This matches Hub's saved copy."
                ProgressionClass.ADVANCED -> "Hub thinks this game is behind a newer saved copy."
                else -> "Hub cannot tell which copy is ahead."
            }
        }
        return ForceSavePrompt(
            gameId = gameId,
            profileName = _activeProfileName.value ?: "hub-game-$gameId",
            liveDetail = adapter.summarize(payload).detail,
            liveHash = PayloadDigest.sha256(payload),
            storedDetail = adapter.summarize(stored).detail,
            storedHash = PayloadDigest.sha256OrAbsent(stored),
            ahead = ahead,
            relation = relation,
        )
    }

    private fun forceSaveAllowed(state: SessionState): Boolean {
        return state is SessionState.Playing ||
            state is SessionState.Background ||
            state is SessionState.Degraded ||
            state is SessionState.RecoveryRequired ||
            state is SessionState.Opening
    }

    private suspend fun beginLivePeekLocked(intent: LiveIntent) {
        val gameId = _activeGameId.value ?: return
        pendingLiveReturn = _state.value
        openStep = if (intent == LiveIntent.PROMOTE) OpenStep.PROMOTE_PEEK else OpenStep.FORCE_PEEK
        documentGate.phase = DocumentPhase.PEEK
        documentGate.payload = null
        _loadToken.value = _loadToken.value + 1
        when (_state.value) {
            is SessionState.Degraded -> {
                transitionTo(SessionState.RecoveryRequired, gameId)
                transitionTo(SessionState.Opening, gameId)
            }
            is SessionState.RecoveryRequired, is SessionState.Background ->
                transitionTo(SessionState.Opening, gameId)
            else -> {
                pendingLiveReturn = null
                openStep = OpenStep.DECIDE
                refuseLive("No current save could be read. The active save was kept.")
            }
        }
    }

    private suspend fun cancelForcePromptLocked() {
        if (pendingLiveReturn != null || openStep == OpenStep.FORCE_PEEK) {
            val gameId = _activeGameId.value
            val back = pendingLiveReturn
            pendingLiveReturn = null
            promotionPayload = null
            _forceSavePrompt.value = null
            openStep = OpenStep.DECIDE
            if (_state.value is SessionState.Opening && gameId != null) {
                if (back is SessionState.Playing || back is SessionState.Background) {
                    transitionTo(SessionState.Playing, gameId)
                } else {
                    transitionTo(SessionState.RecoveryRequired, gameId)
                }
            }
        } else {
            _forceSavePrompt.value = null
        }
    }

    private suspend fun readForcePayload(gameId: String, adapter: GameAdapter, view: WebView?): String? {
        val fromStorage = _state.value is SessionState.RecoveryRequired ||
            _state.value is SessionState.Degraded ||
            _state.value is SessionState.Opening
        return if (fromStorage) {
            if (view == null) null else peekPayload(view, adapter)
        } else {
            capturePayload(view, adapter, gameId)
        }
    }

    private suspend fun peekPayload(view: WebView, adapter: GameAdapter): String? {
        val outcome = try {
            peekClient?.invoke(view, adapter) ?: adapter.peekStorage(view)
        } catch (e: Exception) {
            Log.e(TAG, "profile peek failed", e)
            null
        }
        return (outcome as? PeekOutcome.Value)?.payload?.takeIf { it.isNotBlank() }
    }

    private suspend fun capturePayload(view: WebView?, adapter: GameAdapter, gameId: String): String? {
        return try {
            val client = captureClient
            val payload = if (client != null) {
                client.invoke(view, adapter)
            } else if (view != null) {
                captureThroughAdapter(view, adapter, gameId)
            } else {
                null
            }
            payload?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.e(TAG, "force-save read failed", e)
            null
        }
    }

    private suspend fun runtimeMatches(view: WebView, adapter: GameAdapter, payload: String): Boolean {
        return try {
            runtimeCheck?.invoke(view, adapter, payload) ?: adapter.acceptsRuntime(view, payload)
        } catch (e: Exception) {
            Log.e(TAG, "runtime check failed", e)
            false
        }
    }

    private suspend fun deliverAccepted(gameId: String, commit: CaptureCommit) {
        if (commit.sync) {
            commit.instance?.let { saved ->
                try {
                    onSnapshotSaved?.invoke(saved)
                } catch (e: Exception) {
                    Log.e(TAG, "snapshot listener failed", e)
                }
            }
        }
        mutex.withLock {
            if (_activeGameId.value != gameId) return@withLock
            pendingLiveReturn = null
            promotionPayload = null
            _forceSavePrompt.value = null
            resumeAfterAcceptedSave(gameId, commit.message)
        }
    }

    private suspend fun resumeAfterAcceptedSave(gameId: String, message: String?) {
        incidentDetail = null
        setCaptureSuppressed(false)
        _sessionError.value = message
        when (val state = _state.value) {
            is SessionState.RecoveryRequired, is SessionState.Degraded -> {
                if (state is SessionState.Degraded) {
                    transitionTo(SessionState.RecoveryRequired, gameId)
                }
                transitionTo(SessionState.Opening, gameId)
                transitionTo(SessionState.Playing, gameId)
            }
            is SessionState.Opening, is SessionState.Background ->
                transitionTo(SessionState.Playing, gameId)
            is SessionState.Playing -> if (captureJob?.isActive != true) startCaptureCadence()
            else -> Unit
        }
    }

    private fun refuseLive(message: String) {
        _forceSavePrompt.value = null
        _sessionError.value = message
    }

    private suspend fun bindLiveProfile(gameId: String) {
        val profileName = "hub-game-$gameId"
        _activeProfileName.value = profileName
        _activeProfile.value = obtainProfile(profileName)
    }

    private suspend fun confirmNewLineageLocked(confirmed: Boolean, activate: Boolean) {
        val gameId = _activeGameId.value ?: return
        val adapter = activeAdapter ?: return
        if (_state.value !is SessionState.RecoveryRequired) return
        val sessionId = instanceDao.get(gameId)?.sessionIdentity
        val generation = restoreGeneration
        val commit = try {
            captureRepository().openLineage(
                gameId = gameId,
                confirmed = confirmed,
                activate = activate,
                adapter = adapter,
                sessionId = sessionId,
                stillCurrent = { restoreGeneration == generation && _activeGameId.value == gameId },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _sessionError.value = "storage error. ${e.message ?: "The new lineage was not stored."} The active save was kept."
            return
        }
        _sessionError.value = commit.message
        if (commit.disposition != CaptureDisposition.STORED) return
        if (activate) {
            incidentDetail = commit.message
            chosenPayload = commit.instance?.snapshotPayload
            setCaptureSuppressed(true)
        }
        showIncidentDiagnostic(gameId, commit.message.orEmpty(), chosenPayload)
    }

    private suspend fun failOpenLocked(gameId: String, message: String) {
        Log.e(TAG, message)
        _sessionError.value = message
        if (_state.value is SessionState.Opening) {
            transitionTo(SessionState.RecoveryRequired, gameId)
        }
    }

    private suspend fun pauseForBackgroundLocked() {
        if (!isBackgroundRecoveryTrigger(
                lifecyclePause = true,
                configurationChange = retainAcrossConfiguration || configurationChanging(),
            )
        ) {
            recordDiagnostic(
                DiagnosticSeverity.INFO,
                "lifecycle",
                "Configuration change left the live session in place.",
            )
            return
        }
        if (_state.value !is SessionState.Playing) return
        val attempt = performCaptureLocked("background")
        recordBackgroundPause(attempt)
        if (_state.value is SessionState.Playing) {
            pauseGameTimers()
            transitionTo(SessionState.Background, _activeGameId.value)
        } else {
            resumeGameTimers()
        }
    }

    private suspend fun recordBackgroundPause(attempt: CaptureAttempt) {
        val gameId = _activeGameId.value ?: return
        val adapter = activeAdapter
        val sample = backgroundClock.sample()
        val saved = attempt as? CaptureAttempt.Saved
        val payload = saved?.payload
        val snapshotId = if (payload != null) snapshotIdFor(gameId, payload) else null
        val sha = if (payload != null) PayloadDigest.sha256(payload) else null
        val committed = snapshotId != null && sha != null
        backgroundStore.write(
            BackgroundCheckpoint(
                gameId = gameId,
                adapterId = adapter?.adapterId.orEmpty(),
                adapterVersion = adapter?.adapterVersion.orEmpty(),
                sessionId = instanceDao.get(gameId)?.sessionIdentity,
                snapshotId = if (committed) snapshotId else null,
                contentSha256 = if (committed) sha else null,
                wallStartMillis = sample.wallMillis,
                elapsedRealtimeMillis = sample.elapsedRealtimeMillis,
                bootToken = sample.bootToken,
                committed = committed,
                handled = false,
            ),
        )
        if (committed) {
            recordDiagnostic(
                DiagnosticSeverity.INFO,
                "offline",
                "Background checkpoint $snapshotId ${shaPrefix(sha)} committed for ${adapter?.adapterId} ${adapter?.adapterVersion}.",
            )
        } else {
            recordDiagnostic(
                DiagnosticSeverity.WARNING,
                "offline",
                "Background capture was not a committed snapshot. No recovery checkpoint was recorded.",
            )
        }
    }

    private suspend fun snapshotIdFor(gameId: String, payload: String): String? {
        val dao = snapshotDao ?: return null
        val sha = PayloadDigest.sha256(payload)
        return dao.history(gameId).lastOrNull { row ->
            row.reason != SnapshotReasons.QUARANTINE &&
                (row.contentSha256 == sha || (row.contentSha256.isEmpty() && row.payload == payload))
        }?.id
    }

    private suspend fun resumeFromBackgroundLocked(): PendingOffline? {
        val gameId = _activeGameId.value
        if (gameId == null) {
            resumeGameTimers()
            return null
        }
        val checkpoint = backgroundStore.read(gameId)
        val now = backgroundClock.sample()
        val absence = checkpoint?.let { measureAbsence(it.clock(), now) }
        val adapter = activeAdapter
        val view = webView
        val sameBoot = checkpoint != null && checkpoint.bootToken == now.bootToken
        val inspection = if (
            adapter != null && view != null && sameBoot && absence?.possiblyLong() == true
        ) {
            adapter.inspectOffline(view)
        } else {
            null
        }
        val plan = planBackgroundResume(
            ResumeInput(
                gameId = gameId,
                displayName = gameRegistry.getGame(gameId)?.displayName ?: gameId,
                capability = adapter?.offlineSupport()?.capability ?: OfflineCapability.UNSUPPORTED,
                absence = absence,
                checkpointCommitted = checkpoint?.committed == true,
                checkpointHandled = checkpoint?.handled == true,
                sameBoot = sameBoot,
                inspection = inspection,
                checkpointSha256 = checkpoint?.contentSha256,
            ),
        )
        recordDiagnostic(
            DiagnosticSeverity.INFO,
            "offline",
            offlineDiagnostic(adapter, checkpoint, absence, plan),
        )
        if (plan.action == ResumeAction.AUTO && adapter != null && view != null) {
            val started = adapter.startNativeOffline(view)
            recordDiagnostic(DiagnosticSeverity.INFO, "offline", "Native offline start returned $started.")
            if (started == OfflineStart.STARTED) {
                offlineResumeActive = true
                backgroundStore.markHandled(gameId)
                resumeGameTimers()
                transitionTo(SessionState.Playing, gameId)
                publishNotice(plan, checkpoint)
                return PendingOffline(gameId)
            }
            publishNotice(
                plan.copy(
                    action = ResumeAction.ASK,
                    detail = "The game did not start offline progression ($started). The live session was kept.",
                    showBanner = false,
                    report = BACKGROUND_REPORT_SKIPPED,
                    mention = true,
                ),
                checkpoint,
            )
            resumeGameTimers()
            transitionTo(SessionState.Playing, gameId)
            return null
        }
        publishNotice(plan, checkpoint)
        resumeGameTimers()
        transitionTo(SessionState.Playing, gameId)
        return null
    }

    private suspend fun finishOfflineWork(pending: PendingOffline) {
        val adapter = activeAdapter
        val view = webView
        val settlement = if (adapter != null && view != null && _activeGameId.value == pending.gameId) {
            adapter.awaitOfflineSettlement(view)
        } else {
            OfflineSettlement.INTERRUPTED
        }
        mutex.withLock {
            offlineResumeActive = false
            resumeGameTimers()
            if (_activeGameId.value != pending.gameId) return@withLock
            recordDiagnostic(DiagnosticSeverity.INFO, "offline", "Offline settlement $settlement.")
            val current = _backgroundNotice.value
            val finished = settlement == OfflineSettlement.COMPLETE && _state.value is SessionState.Playing
            val saved = if (finished) {
                performCaptureLocked("offline-resume") is CaptureAttempt.Saved
            } else {
                false
            }
            val (report, detail) = settledOfflineStatus(finished, saved)
            val key = current?.checkpointKey ?: pending.gameId
            _backgroundNotice.value = BackgroundNotice(
                gameId = pending.gameId,
                headline = current?.headline ?: absenceHeadline(
                    pending.gameId,
                    gameRegistry.getGame(pending.gameId)?.displayName ?: pending.gameId,
                    0L,
                ),
                detail = detail,
                elapsedLabel = current?.elapsedLabel.orEmpty(),
                report = report,
                checkpointKey = key,
                barVisible = acknowledgedReport != "$key|$report",
            )
        }
    }

    private fun publishNotice(plan: ResumePlan, checkpoint: BackgroundCheckpoint?) {
        if (!plan.mention) {
            _backgroundNotice.value = null
            return
        }
        val key = checkpoint?.let { backgroundCheckpointKey(it) }.orEmpty()
        val report = plan.report
        _backgroundNotice.value = BackgroundNotice(
            gameId = _activeGameId.value.orEmpty(),
            headline = plan.headline,
            detail = plan.detail,
            elapsedLabel = plan.elapsedLabel,
            report = report,
            checkpointKey = key,
            barVisible = report != null && acknowledgedReport != "$key|$report",
        )
    }

    private fun offlineDiagnostic(
        adapter: com.example.hub.games.GameAdapter?,
        checkpoint: BackgroundCheckpoint?,
        absence: MeasuredAbsence?,
        plan: ResumePlan,
    ): String {
        return buildString {
            append(adapter?.adapterId ?: "no-adapter")
            append(" ")
            append(adapter?.adapterVersion ?: "")
            append(" wall=")
            append(absence?.wallMillis ?: "none")
            append(" elapsed=")
            append(absence?.elapsedMillis?.toString() ?: "none")
            append(" confidence=")
            append(absence?.confidence?.name ?: "none")
            append(" checkpoint=")
            append(checkpoint?.snapshotId ?: "none")
            append(" ")
            append(shaPrefix(checkpoint?.contentSha256))
            append(" committed=")
            append(checkpoint?.committed == true)
            append(" capability=")
            append(adapter?.offlineSupport()?.capability?.name ?: "none")
            append(" action=")
            append(plan.action.name)
            append(" gap=")
            append(plan.gameGapSeconds?.toString() ?: "none")
        }.take(CONSOLE_LINE_LIMIT)
    }

    private fun pauseGameTimers() {
        val view = webView ?: return
        if (gameTimersPaused) return
        if (runCatching { view.pauseTimers() }.isSuccess) gameTimersPaused = true
    }

    private fun resumeGameTimers() {
        if (!gameTimersPaused) return
        webView?.let { view -> runCatching { view.resumeTimers() } }
        gameTimersPaused = false
    }

    private data class PendingOffline(val gameId: String)

    private suspend fun destroyWebViewAndFinalize() {
        resumeGameTimers()
        offlineResumeActive = false
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
        resumeGameTimers()
        offlineResumeActive = false
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
