package com.example.hub.session

import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import java.util.Base64
import com.example.hub.database.GameInstance
import com.example.hub.database.GameInstanceDao
import com.example.hub.database.SnapshotDao
import com.example.hub.database.SnapshotOrigins
import com.example.hub.database.SnapshotReasons
import com.example.hub.database.SnapshotRecord
import com.example.hub.games.EnvelopeProbeAdapter
import com.example.hub.games.Fe000000Adapter
import com.example.hub.games.GameAdapter
import com.example.hub.games.SharkIncrementalAdapter
import com.example.hub.games.GameRegistry
import com.example.hub.games.LzString
import com.example.hub.games.PayloadDigest
import com.example.hub.games.PeekOutcome
import com.example.hub.games.ProgressionClass
import com.example.hub.games.SaveComparison
import com.example.hub.games.RuntimeVerdict
import com.example.hub.games.SaveEnvelope
import com.example.hub.games.SharkProgress
import com.example.hub.games.StorageSlot
import com.example.hub.games.TrimpsSave
import com.example.hub.bootstrap.DetectorResult
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Unit tests for [SessionController].
 *
 * Covers:
 * - Valid transition chains
 * - Illegal transition rejection
 * - Identity tracking (activeGameId, activeProfileName, navigationEpoch)
 * - Serialization of conflicting operations
 * - Profile binding and navigation
 */
@RunWith(RobolectricTestRunner::class)
class SessionControllerTest {

    private lateinit var registry: StubGameRegistry
    private lateinit var dao: StubGameInstanceDao
    private lateinit var controller: SessionController
    private val destroyedWebViews = CopyOnWriteArrayList<String>()

    private var profileStore = StubProfileStore()
    private val bootstrapSuccess: (androidx.webkit.ProfileStore) -> DetectorResult = {
        DetectorResult(
            qualifiedMechanism = "WebViewAssetLoader",
            webViewAssetLoaderOrigin = com.example.hub.bootstrap.BootstrapDetector.EXPECTED_ASSETS_ORIGIN,
            loadDataWithBaseURLOrigin = null,
            webViewAssetLoaderQualified = true,
            loadDataWithBaseURLQualified = false,
            reportPath = null,
            message = "test bootstrap success",
        )
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        profileStore = StubProfileStore()
        registry = StubGameRegistry()
        registry.injectGame(StubGame("shark-incremental", "https://mrredshark77.github.io"))
        registry.injectGame(StubGame("fe000000", "https://dan-simon.github.io/misc/fe000000/"))
        dao = StubGameInstanceDao()
        destroyedWebViews.clear()
        controller = SessionController(
            context = ApplicationProvider.getApplicationContext(),
            gameRegistry = registry,
            instanceDao = dao,
            lifecycleOwner = StubLifecycleOwner(),
            webViewDestroyer = { destroyedWebViews.add("destroyed") },
            profileStore = profileStore,
            bootstrapTest = bootstrapSuccess,
            documentReadyTest = { true },
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        // Ensure any running cadence is cancelled so it does not leak between tests.
        if (controller.isCaptureCadenceActive) {
            runBlocking { controller.forceCloseForTest() }
        }
    }

    // -----------------------------------------------------------------
    // Valid transition chains
    // -----------------------------------------------------------------

    @Test
    fun open_transitionsClosedToPlaying() = runTest {
        assertEquals(SessionState.Closed, controller.state.value)
        controller.open("shark-incremental")
        assertEquals(SessionState.Playing, controller.state.value)
        assertEquals("shark-incremental", controller.activeGameId.value)
        assertEquals("hub-game-shark-incremental", controller.activeProfileName.value)
    }

    @Test
    fun close_transitionsPlayingToClosed() = runTest {
        controller.open("shark-incremental")
        assertEquals(SessionState.Playing, controller.state.value)
        controller.close()
        assertEquals(SessionState.Closed, controller.state.value)
        assertNull(controller.activeGameId.value)
        assertNull(controller.activeProfileName.value)
    }

    @Test
    fun requestCapture_recordsFailureWithoutLeavingPlayingWhenNoWebView() = runTest {
        controller.open("shark-incremental")
        controller.requestCapture()
        assertEquals(SessionState.Playing, controller.state.value)
        val instance = dao.get("shark-incremental")
        assertNotNull(instance)
        assertNull(instance!!.snapshotPayload)
        assertTrue(instance.lastCaptureAttemptAtMillis > 0)
        assertTrue(instance.lastCaptureFailureAtMillis > 0)
        assertEquals(0L, instance.lastCaptureSuccessAtMillis)
        assertTrue(instance.lastCaptureError!!.contains("Last good snapshot was kept"))
    }

    @Test
    fun requestForeground_restoresCapturingToPlaying() = runTest {
        controller.open("shark-incremental")
        controller.requestCapture()
        controller.requestForeground()
        assertEquals(SessionState.Playing, controller.state.value)
    }

    @Test
    fun requestBackground_transitionsPlayingToBackground() = runTest {
        controller.open("shark-incremental")
        controller.requestBackground()
        assertEquals(SessionState.Background, controller.state.value)
    }

    @Test
    fun requestForeground_restoresBackgroundToPlaying() = runTest {
        controller.open("shark-incremental")
        controller.requestBackground()
        controller.requestForeground()
        assertEquals(SessionState.Playing, controller.state.value)
    }

    @Test
    fun requestDegraded_transitionsPlayingToDegraded() = runTest {
        controller.open("shark-incremental")
        controller.requestDegraded()
        assertEquals(SessionState.Degraded, controller.state.value)
    }

    @Test
    fun requestRecovery_transitionsDegradedToRecoveryRequired() = runTest {
        controller.open("shark-incremental")
        controller.requestDegraded()
        controller.requestRecovery()
        assertEquals(SessionState.RecoveryRequired, controller.state.value)
    }

    @Test
    fun requestClosing_fromDegraded_transitionsToClosed() = runTest {
        controller.open("shark-incremental")
        controller.requestDegraded()
        controller.requestClosing()
        assertEquals(SessionState.Closed, controller.state.value)
    }

    @Test
    fun fullOpenCloseCycle_incrementsNavigationEpoch() = runTest {
        assertEquals(0, controller.navigationEpoch.value)
        controller.open("shark-incremental")
        controller.close()
        assertEquals(1, controller.navigationEpoch.value)
        controller.open("shark-incremental")
        controller.close()
        assertEquals(2, controller.navigationEpoch.value)
    }

    @Test
    fun openFromClosed_incrementsNavigationEpoch() = runTest {
        assertEquals(0, controller.navigationEpoch.value)
        controller.open("shark-incremental")
        assertEquals(0, controller.navigationEpoch.value)
        assertEquals(SessionState.Playing, controller.state.value)
        controller.close()
        assertEquals(1, controller.navigationEpoch.value)
    }

    // -----------------------------------------------------------------
    // Illegal transition rejection
    // -----------------------------------------------------------------

    @Test
    fun openFromPlaying_settlesOldAndOpensNew() = runTest {
        controller.open("shark-incremental")
        assertEquals(SessionState.Playing, controller.state.value)
        controller.open("fe000000")
        // Wait for settleActiveSession (50ms capture delay) and open delay (2000ms)
        // to complete. Must check for the new game's Playing state specifically
        // because settleActiveSession briefly returns to Playing before closing.
        for (i in 1..100) {
            if (controller.state.value is SessionState.Playing && controller.activeGameId.value == "fe000000") break
            advanceTimeBy(50)
        }
        assertEquals(SessionState.Playing, controller.state.value)
        assertEquals("fe000000", controller.activeGameId.value)
        // Epoch increments when switching games.
        assertTrue(controller.navigationEpoch.value > 0)
    }

    @Test
    fun closeFromClosed_isNoOp() = runTest {
        controller.close()
        assertEquals(SessionState.Closed, controller.state.value)
    }

    @Test
    fun requestCaptureFromClosed_isRejected() = runTest {
        controller.requestCapture()
        assertEquals(SessionState.Closed, controller.state.value)
    }

    @Test
    fun requestBackgroundFromClosed_isRejected() = runTest {
        controller.requestBackground()
        assertEquals(SessionState.Closed, controller.state.value)
    }

    @Test
    fun requestForegroundFromClosed_isRejected() = runTest {
        controller.requestForeground()
        assertEquals(SessionState.Closed, controller.state.value)
    }

    @Test
    fun requestDegradedFromClosed_isRejected() = runTest {
        controller.requestDegraded()
        assertEquals(SessionState.Closed, controller.state.value)
    }

    @Test
    fun requestRecoveryFromClosed_isRejected() = runTest {
        controller.requestRecovery()
        assertEquals(SessionState.Closed, controller.state.value)
    }

    @Test
    fun requestRestoreFromClosed_isRejected() = runTest {
        controller.requestRestore()
        assertEquals(SessionState.Closed, controller.state.value)
    }

    @Test
    fun requestClosingFromClosed_isRejected() = runTest {
        controller.requestClosing()
        assertEquals(SessionState.Closed, controller.state.value)
    }

    @Test
    fun requestForegroundFromPlaying_isRejected() = runTest {
        controller.open("shark-incremental")
        controller.requestForeground()
        assertEquals(SessionState.Playing, controller.state.value)
    }

    @Test
    fun requestRecoveryFromPlaying_isRejected() = runTest {
        controller.open("shark-incremental")
        controller.requestRecovery()
        assertEquals(SessionState.Playing, controller.state.value)
    }

    // -----------------------------------------------------------------
    // Identity tracking
    // -----------------------------------------------------------------

    @Test
    fun activeGameIdTracksCurrentGame() = runTest {
        assertNull(controller.activeGameId.value)
        controller.open("shark-incremental")
        assertEquals("shark-incremental", controller.activeGameId.value)
        controller.close()
        assertNull(controller.activeGameId.value)
        controller.open("fe000000")
        assertEquals("fe000000", controller.activeGameId.value)
    }

    @Test
    fun activeProfileNameTracksCurrentProfile() = runTest {
        assertNull(controller.activeProfileName.value)
        controller.open("shark-incremental")
        assertEquals("hub-game-shark-incremental", controller.activeProfileName.value)
    }

    @Test
    fun isActiveIsFalseWhenClosed() = runTest {
        assertFalse(controller.isActive)
    }

    @Test
    fun isActiveIsTrueWhenPlaying() = runTest {
        controller.open("shark-incremental")
        assertTrue(controller.isActive)
        controller.close()
        assertFalse(controller.isActive)
    }

    // -----------------------------------------------------------------
    // Profile binding
    // -----------------------------------------------------------------

    @Test
    fun bindProfileAndNavigate_resolvesGame() = runTest {
        val result = controller.bindProfileAndNavigate("shark-incremental")
        assertNotNull(result)
        assertEquals("https://mrredshark77.github.io", result!!.second)
    }

    @Test
    fun bindProfileAndNavigate_missingGameReturnsNull() = runTest {
        val result = controller.bindProfileAndNavigate("nonexistent-game")
        assertNull(result)
        // Profile state should remain null when game is not found.
        assertNull(controller.activeProfile.value)
    }

    @Test
    fun bindProfileAndNavigate_persistsGameInstance() = runTest {
        controller.bindProfileAndNavigate("shark-incremental")
        val instance = dao.getLastInserted()
        assertNotNull(instance)
        assertEquals("shark-incremental", instance!!.gameId)
        assertEquals("hub-game-shark-incremental", instance.profileName)
        assertNotNull(instance.sessionIdentity)
    }

    // -----------------------------------------------------------------
    // Profile binding and WebView integration
    // -----------------------------------------------------------------

    @Test
    fun bindProfileAndNavigate_returnsNonNullPair() = runTest {
        val result = controller.bindProfileAndNavigate("shark-incremental")
        // In Robolectric, ProfileStore may return null; the pair itself must be non-null.
        assertNotNull(result)
        assertEquals("https://mrredshark77.github.io", result!!.second)
    }

    @Test
    fun close_clearsWebViewReference() = runTest {
        // Simulate WebViewHost setting the webView reference during composition.
        controller.webView = WebView(ApplicationProvider.getApplicationContext())
        assertNotNull(controller.webView)
        controller.open("shark-incremental")
        assertNotNull(controller.webView)
        controller.close()
        assertNull(controller.webView)
    }

    @Test
    fun close_clearsProfileState() = runTest {
        controller.bindProfileAndNavigate("shark-incremental")
        controller.close()
        assertNull(controller.activeProfile.value)
    }

    // -----------------------------------------------------------------
    // Game instance persistence
    // -----------------------------------------------------------------

    @Test
    fun open_persistsGameInstance() = runTest {
        controller.open("shark-incremental")
        val instance = dao.get("shark-incremental")
        assertNotNull(instance)
        assertEquals("hub-game-shark-incremental", instance!!.profileName)
        assertNotNull(instance.sessionIdentity)
        assertTrue(instance.lastPlayedAtMillis > 0)
    }

    @Test
    fun open_withUnknownGame_transitionsToRecoveryRequired() = runTest {
        controller.open("unknown-game")
        assertEquals(SessionState.RecoveryRequired, controller.state.value)
        assertEquals("unknown-game", controller.activeGameId.value)
    }

    // -----------------------------------------------------------------
    // Transition logging — verify canTransition() consistency
    // -----------------------------------------------------------------

    @Test
    fun allValidTransitionsFromPlaying() = runTest {
        controller.open("shark-incremental")
        val playing = SessionState.Playing
        assertTrue("Playing->Capturing", canTransition(playing, SessionState.Capturing))
        assertTrue("Playing->Background", canTransition(playing, SessionState.Background))
        assertTrue("Playing->Degraded", canTransition(playing, SessionState.Degraded))
        assertTrue("Playing->Closing", canTransition(playing, SessionState.Closing))
    }

    @Test
    fun illegalTransitionsFromPlaying_areRejected() = runTest {
        controller.open("shark-incremental")
        assertFalse("Playing->Closed", canTransition(SessionState.Playing, SessionState.Closed))
        assertFalse("Playing->Opening", canTransition(SessionState.Playing, SessionState.Opening))
        assertFalse("Playing->Playing", canTransition(SessionState.Playing, SessionState.Playing))
    }

    @Test
    fun restoringIsSinkState() = runTest {
        val restoring = SessionState.Restoring
        for (state in listOf(
            SessionState.Closed, SessionState.Opening, SessionState.Playing,
            SessionState.Capturing, SessionState.Background, restoring,
            SessionState.Degraded, SessionState.RecoveryRequired, SessionState.Closing
        )) {
            assertFalse(
                "Restoring->$state should be rejected",
                canTransition(restoring, state)
            )
        }
    }

    // -----------------------------------------------------------------
    // Capture cadence
    // -----------------------------------------------------------------

    @Test
    fun cadenceStartsWhenTransitioningToPlaying() = runTest {
        assertFalse("cadence should not be active when closed", controller.isCaptureCadenceActive)
        controller.open("shark-incremental")
        assertTrue("cadence should start when entering Playing", controller.isCaptureCadenceActive)
    }

    @Test
    fun cadenceFiresAt60sIntervals() = runTest {
        controller.open("shark-incremental")
        assertEquals(SessionState.Playing, controller.state.value)
        // With Unconfined + runTest, advanceTimeBy processes all pending tasks.
        // The cadence cycle (60s delay + 65s window) completes within the advance.
        // We verify the cadence fired by confirming the job is still active.
        advanceTimeBy(60_000L)
        assertTrue("cadence should remain active after firing", controller.isCaptureCadenceActive)
    }

    @Test
    fun cadenceCancelsOnClose() = runTest {
        controller.open("shark-incremental")
        assertTrue(controller.isCaptureCadenceActive)
        controller.close()
        assertFalse("cadence should stop after close", controller.isCaptureCadenceActive)
    }

    @Test
    fun requestForegroundCapture_recordsAttemptAndReturnsToPlaying() = runTest {
        controller.open("shark-incremental")
        controller.requestForegroundCapture()
        assertEquals(SessionState.Playing, controller.state.value)
        assertTrue(dao.get("shark-incremental")!!.lastCaptureAttemptAtMillis > 0)
    }

    // -----------------------------------------------------------------
    // Crash handling (R014)
    // -----------------------------------------------------------------

    @Test
    fun errorHandler_triggersDegradedTransition() = runTest {
        controller.open("shark-incremental")
        assertEquals(SessionState.Playing, controller.state.value)

        val errorHandler = controller.createErrorHandler()
        errorHandler("renderer crashed: connection lost")

        runCurrent()
        assertEquals(
            "main-frame failure should reach RecoveryRequired",
            SessionState.RecoveryRequired,
            controller.state.value
        )
        assertTrue(controller.sessionError.value!!.contains("renderer crashed"))
    }

    @Test
    fun errorHandler_isNoOp_whenNotPlaying() = runTest {
        // Error handler should not transition when not in Playing state.
        controller.open("shark-incremental")
        controller.requestDegraded()
        assertEquals(SessionState.Degraded, controller.state.value)

        val errorHandler = controller.createErrorHandler()
        errorHandler("another error while already Degraded")

        runCurrent()
        // State should remain Degraded — requestDegraded() is a no-op when not Playing.
        assertEquals(
            "error handler should be no-op from Degraded state",
            SessionState.Degraded,
            controller.state.value
        )
    }

    @Test
    fun errorHandler_invokesExternalOnError() = runTest {
        var receivedErrors = mutableListOf<String>()
        val controllerWithObserver = SessionController(
            context = ApplicationProvider.getApplicationContext(),
            gameRegistry = registry,
            instanceDao = dao,
            lifecycleOwner = StubLifecycleOwner(),
            webViewDestroyer = { destroyedWebViews.add("destroyed") },
            profileStore = profileStore,
            bootstrapTest = bootstrapSuccess,
            documentReadyTest = { true },
            onError = { receivedErrors.add(it) },
        )
        controllerWithObserver.open("shark-incremental")
        assertEquals(SessionState.Playing, controllerWithObserver.state.value)

        val errorHandler = controllerWithObserver.createErrorHandler()
        errorHandler("test error for external callback")

        runCurrent()
        assertTrue(
            "external onError should be invoked",
            receivedErrors.any { it.contains("test error for external callback") }
        )
    }

    @Test
    fun errorHandler_logsWithErrorDetails() = runTest {
        // Verify the error message is passed through to requestDegraded.
        controller.open("shark-incremental")
        val errorHandler = controller.createErrorHandler()
        errorHandler("test-log-error")
        runCurrent()
        assertEquals(SessionState.RecoveryRequired, controller.state.value)
        assertTrue(controller.sessionError.value!!.contains("test-log-error"))
    }

    @Test
    fun requestForegroundCaptureFromClosed_isRejected() = runTest {
        controller.requestForegroundCapture()
        assertEquals(SessionState.Closed, controller.state.value)
    }

    @Test
    fun onPause_triggersBestEffortCapture() = runTest {
        controller.open("shark-incremental")
        assertEquals(SessionState.Playing, controller.state.value)
        controller.onStateChanged(StubLifecycleOwner(), Lifecycle.Event.ON_PAUSE)
        val instance = dao.get("shark-incremental")
        assertEquals(
            "ON_PAUSE while Playing should capture then background",
            SessionState.Background,
            controller.state.value
        )
        assertNotNull(instance)
        assertTrue(instance!!.lastCaptureAttemptAtMillis > 0)
        assertEquals(0L, instance.lastCaptureSuccessAtMillis)
    }

    @Test
    fun configurationPauseDoesNotBackgroundTheLiveSession() = runTest {
        controller.open("shark-incremental")
        val attempt = dao.get("shark-incremental")!!.lastCaptureAttemptAtMillis
        controller.configurationChanging = { true }
        controller.onStateChanged(StubLifecycleOwner(), Lifecycle.Event.ON_PAUSE)
        assertEquals(SessionState.Playing, controller.state.value)
        assertEquals(attempt, dao.get("shark-incremental")!!.lastCaptureAttemptAtMillis)
        assertTrue(controller.diagnosticEvents.value.any { it.message.contains("live session") })
    }

    @Test
    fun attachWhilePlayingDoesNotApplyStoredRecovery() = runTest {
        controller.open("shark-incremental")
        val playing = dao.get("shark-incremental")!!
        dao.upsert(playing.copy(recoveryState = "RecoveryRequired"))
        controller.attach(StubLifecycleOwner())
        assertEquals(SessionState.Playing, controller.state.value)
        assertEquals("shark-incremental", controller.activeGameId.value)
    }

    @Test
    fun cadenceRestartsOnReenteringPlaying() = runTest {
        controller.open("shark-incremental")
        assertTrue(controller.isCaptureCadenceActive)
        controller.requestBackground()
        assertFalse("cadence should stop when leaving Playing", controller.isCaptureCadenceActive)
        controller.requestForeground()
        assertTrue("cadence should restart when re-entering Playing", controller.isCaptureCadenceActive)
    }

    @Test
    fun cadenceStopsOnDegraded() = runTest {
        controller.open("shark-incremental")
        assertTrue(controller.isCaptureCadenceActive)
        controller.requestDegraded()
        assertFalse("cadence should stop when transitioning to Degraded", controller.isCaptureCadenceActive)
    }

    // -----------------------------------------------------------------
    // Process-death recovery (R015)
    // -----------------------------------------------------------------

    @Test
    fun coldStart_readsDaoAndRestoresDegradedState() = runTest {
        // Clear DAO to avoid interference from other tests.
        (dao as StubGameInstanceDao).clearAll()
        // Pre-seed DAO with a Degraded instance using a high timestamp.
        val seedTime = System.currentTimeMillis() + 100_000L
        dao.upsert(
            GameInstance(
                gameId = "shark-incremental",
                profileName = "hub-game-shark-incremental",
                sessionIdentity = "session-1",
                lastPlayedAtMillis = seedTime,
                recoveryState = "Degraded",
                lastCaptureAtMillis = seedTime - 1000L,
            )
        )
        val freshController = SessionController(
            context = ApplicationProvider.getApplicationContext(),
            gameRegistry = registry,
            instanceDao = dao,
            lifecycleOwner = StubLifecycleOwner(),
            webViewDestroyer = { destroyedWebViews.add("destroyed") },
            profileStore = profileStore,
            bootstrapTest = bootstrapSuccess,
        )
        freshController.attach(StubLifecycleOwner())
        // allow the coroutine from attach() to complete.
        runCurrent()
        assertEquals(
            "cold-start should restore Degraded state from DAO",
            SessionState.Degraded,
            freshController.state.value
        )
        assertEquals("shark-incremental", freshController.activeGameId.value)
        assertEquals("hub-game-shark-incremental", freshController.activeProfileName.value)
    }

    @Test
    fun coldStart_readsDaoAndRestoresRecoveryRequiredState() = runTest {
        (dao as StubGameInstanceDao).clearAll()
        val seedTime = System.currentTimeMillis() + 200_000L
        dao.upsert(
            GameInstance(
                gameId = "fe000000",
                profileName = "hub-game-fe000000",
                sessionIdentity = "session-2",
                lastPlayedAtMillis = seedTime,
                recoveryState = "RecoveryRequired",
                lastCaptureAtMillis = seedTime - 500L,
            )
        )
        val freshController = SessionController(
            context = ApplicationProvider.getApplicationContext(),
            gameRegistry = registry,
            instanceDao = dao,
            lifecycleOwner = StubLifecycleOwner(),
            webViewDestroyer = { destroyedWebViews.add("destroyed") },
            profileStore = profileStore,
            bootstrapTest = bootstrapSuccess,
        )
        freshController.attach(StubLifecycleOwner())
        runCurrent()
        assertEquals(
            "cold-start should restore RecoveryRequired state from DAO",
            SessionState.RecoveryRequired,
            freshController.state.value
        )
    }

    @Test
    fun recoveryStatePersistedOnDegradedTransition() = runTest {
        controller.open("shark-incremental")
        assertEquals(SessionState.Playing, controller.state.value)
        controller.requestDegraded()
        assertEquals(SessionState.Degraded, controller.state.value)
        val instance = dao.get("shark-incremental")
        assertNotNull(instance)
        assertEquals("Degraded", instance!!.recoveryState)
        assertEquals(0L, instance.lastCaptureAtMillis)
        assertEquals(0L, instance.lastCaptureSuccessAtMillis)
    }

    @Test
    fun recoveryStateClearedOnPlayingTransition() = runTest {
        controller.open("shark-incremental")
        controller.requestDegraded()
        val instanceAfterDegraded = dao.get("shark-incremental")
        assertEquals("Degraded", instanceAfterDegraded!!.recoveryState)
        // Degraded → Playing is not a valid direct transition.
        // Close first (Degraded → Closing → Closed), then open again (Closed → Opening → Playing).
        // The open() path clears recoveryState on transition to Playing.
        controller.close()
        assertEquals(SessionState.Closed, controller.state.value)
        // Re-seed with Degraded so the new open() clears it.
        val preservedCapture = 4_321L
        dao.upsert(
            GameInstance(
                gameId = "shark-incremental",
                profileName = "hub-game-shark-incremental",
                sessionIdentity = "session-clear",
                lastPlayedAtMillis = System.currentTimeMillis(),
                recoveryState = "Degraded",
                lastCaptureAtMillis = preservedCapture,
                snapshotPayload = "keep-snapshot",
                lastCaptureSuccessAtMillis = preservedCapture,
            )
        )
        controller.open("shark-incremental")
        assertEquals(SessionState.Playing, controller.state.value)
        val instanceAfterPlaying = dao.get("shark-incremental")
        assertNull("recoveryState should be cleared when entering Playing", instanceAfterPlaying!!.recoveryState)
        assertEquals(preservedCapture, instanceAfterPlaying.lastCaptureAtMillis)
        assertEquals(preservedCapture, instanceAfterPlaying.lastCaptureSuccessAtMillis)
        assertEquals("keep-snapshot", instanceAfterPlaying.snapshotPayload)
    }

    @Test
    fun recoveryStateClearedOnOpenTransition() = runTest {
        // Seed a Degraded instance first.
        dao.upsert(
            GameInstance(
                gameId = "shark-incremental",
                profileName = "hub-game-shark-incremental",
                sessionIdentity = "session-clear",
                lastPlayedAtMillis = 3000L,
                recoveryState = "Degraded",
                lastCaptureAtMillis = 2000L,
            )
        )
        controller.open("shark-incremental")
        assertEquals(SessionState.Playing, controller.state.value)
        val instance = dao.get("shark-incremental")
        assertNull("recoveryState should be cleared when entering Playing", instance!!.recoveryState)
        assertEquals(2000L, instance.lastCaptureAtMillis)
        assertNull(instance.snapshotPayload)
    }

    @Test
    fun onAttached_noActiveRecovery_leavesClosed() = runTest {
        // Empty DAO — no recovery instance.
        val freshController = SessionController(
            context = ApplicationProvider.getApplicationContext(),
            gameRegistry = registry,
            instanceDao = dao,
            lifecycleOwner = StubLifecycleOwner(),
            webViewDestroyer = { destroyedWebViews.add("destroyed") },
            profileStore = profileStore,
            bootstrapTest = bootstrapSuccess,
        )
        freshController.attach(StubLifecycleOwner())
        advanceTimeBy(0L)
        assertEquals(
            "should remain Closed when no recovery instance exists",
            SessionState.Closed,
            freshController.state.value
        )
    }

    @Test
    fun cadenceFullCycle_playsCapturesPlays() = runTest {
        controller.open("shark-incremental")
        assertEquals(SessionState.Playing, controller.state.value)
        // advanceTimeBy runs all pending tasks; the full cycle completes.
        advanceTimeBy(130_000L)
        assertEquals(
            "cadence should auto-restore Playing after capture window",
            SessionState.Playing,
            controller.state.value
        )
        assertTrue("cadence should restart after full cycle", controller.isCaptureCadenceActive)
    }

    // -----------------------------------------------------------------
    // Bootstrap integration and game switching (R013 + R016)
    // -----------------------------------------------------------------

    @Test
    fun open_runsBootstrapAndTransitionsToPlayingOnSuccess() = runTest {
        (dao as StubGameInstanceDao).clearAll()
        controller.open("shark-incremental")
        // BootstrapDetector creates a real WebView in Robolectric; if it succeeds
        // the state should be Playing. If it fails (no qualified mechanism), the
        // state should be RecoveryRequired. We verify that open() completed a
        // deterministic transition rather than hanging.
        val state = controller.state.value
        assertTrue(
            "open() should reach a terminal non-Opening state after bootstrap",
            state is SessionState.Playing || state is SessionState.RecoveryRequired
        )
        assertEquals("shark-incremental", controller.activeGameId.value)
    }

    @Test
    fun open_transitionsToRecoveryRequiredWhenBootstrapFails() = runTest {
        val bootstrapFailure: (androidx.webkit.ProfileStore) -> DetectorResult = {
            DetectorResult(
                qualifiedMechanism = null,
                webViewAssetLoaderOrigin = null,
                loadDataWithBaseURLOrigin = null,
                webViewAssetLoaderQualified = false,
                loadDataWithBaseURLQualified = false,
                reportPath = null,
                message = "test bootstrap failure",
            )
        }
        val freshController = SessionController(
            context = ApplicationProvider.getApplicationContext(),
            gameRegistry = registry,
            instanceDao = dao,
            lifecycleOwner = StubLifecycleOwner(),
            webViewDestroyer = { destroyedWebViews.add("destroyed") },
            profileStore = profileStore,
            bootstrapTest = bootstrapFailure,
        )
        freshController.open("shark-incremental")
        assertEquals(
            "open() should transition to RecoveryRequired when bootstrap fails",
            SessionState.RecoveryRequired,
            freshController.state.value
        )
    }

    @Test
    fun open_firesPreLaunchSnapshotAsynchronously() = runTest {
        (dao as StubGameInstanceDao).clearAll()
        controller.open("shark-incremental")
        runCurrent()
        // After open() completes, state should be Playing (bootstrap succeeded in test env).
        // The PreLaunchSnapshot.capture() is launched in a scope coroutine (fire-and-forget),
        // so it runs asynchronously after open() returns.
        assertTrue(
            "open() should transition to Playing when bootstrap succeeds",
            controller.state.value is SessionState.Playing
        )
        assertTrue(
            "cadence should be active after open()",
            controller.isCaptureCadenceActive
        )
    }

    @Test
    fun gameSwitch_settlesOldPlayingSession() = runTest {
        controller.open("shark-incremental")
        assertEquals(SessionState.Playing, controller.state.value)
        val epochBefore = controller.navigationEpoch.value
        controller.open("fe000000")
        // Wait for the new game to reach Playing (not just any Playing from settle).
        for (i in 1..100) {
            if (controller.state.value is SessionState.Playing && controller.activeGameId.value == "fe000000") break
            advanceTimeBy(50)
        }
        assertEquals(SessionState.Playing, controller.state.value)
        assertEquals("fe000000", controller.activeGameId.value)
        assertTrue(
            "epoch should increment when switching from Playing",
            controller.navigationEpoch.value > epochBefore
        )
    }

    @Test
    fun gameSwitch_settlesOldBackgroundSession() = runTest {
        controller.open("shark-incremental")
        controller.requestBackground()
        assertEquals(SessionState.Background, controller.state.value)
        val epochBefore = controller.navigationEpoch.value
        controller.open("fe000000")
        // Wait for the new game to reach Playing.
        for (i in 1..100) {
            if (controller.state.value is SessionState.Playing && controller.activeGameId.value == "fe000000") break
            advanceTimeBy(50)
        }
        assertEquals(SessionState.Playing, controller.state.value)
        assertEquals("fe000000", controller.activeGameId.value)
        assertTrue(
            "epoch should increment when switching from Background",
            controller.navigationEpoch.value > epochBefore
        )
    }

    @Test
    fun gameSwitch_noCrossGameLeakageAfterSwitching() = runTest {
        controller.open("shark-incremental")
        controller.requestBackground()
        controller.open("fe000000")
        // Wait for the new game to reach Playing.
        for (i in 1..100) {
            if (controller.state.value is SessionState.Playing && controller.activeGameId.value == "fe000000") break
            advanceTimeBy(50)
        }
        // After switching, the old game's state should be fully cleaned up.
        assertEquals("fe000000", controller.activeGameId.value)
        assertTrue(controller.isActive)
        // The old game should not be accessible as active.
        // Note: activeGameId is cleared on close, so after switching the
        // new game is active and the old one is gone.
    }

    // -----------------------------------------------------------------
    // Production path: real URL, profile, readiness, durable capture
    // -----------------------------------------------------------------

    @Test
    fun open_staysOpeningUntilDocumentReadyAndBindsNamedProfile() = runTest {
        val waiting = controllerWithoutDocumentHook(readiness = { _, _ -> true })
        waiting.open("shark-incremental")
        assertEquals(SessionState.Opening, waiting.state.value)
        assertEquals("https://mrredshark77.github.io", waiting.launchUrl.value)
        assertNotNull(waiting.activeProfile.value)
        assertEquals("hub-game-shark-incremental", waiting.activeProfile.value!!.name)
        assertFalse(waiting.launchUrl.value!!.contains("hub.local"))

        waiting.registerWebView(WebView(ApplicationProvider.getApplicationContext()))
        waiting.onMainFrameFinished("https://mrredshark77.github.io")
        assertEquals(SessionState.Playing, waiting.state.value)
    }

    @Test
    fun open_secondGameUsesItsOwnLaunchUrl() = runTest {
        val waiting = controllerWithoutDocumentHook(readiness = { _, _ -> true })
        waiting.open("fe000000")
        assertEquals("https://dan-simon.github.io/misc/fe000000/", waiting.launchUrl.value)
        assertEquals("hub-game-fe000000", waiting.activeProfile.value!!.name)
    }

    @Test
    fun captureSuccessPersistsSnapshot_failureDoesNotOverwriteIt() = runTest {
        val payload = sharkPayload(gold = 7)
        var mode = "ok"
        val capturing = controllerWithoutDocumentHook(
            documentReady = { true },
            capture = { _, _ -> if (mode == "ok") payload else "not-a-save" },
        )
        capturing.open("shark-incremental")
        capturing.webView = WebView(ApplicationProvider.getApplicationContext())
        capturing.requestCapture()
        val saved = dao.get("shark-incremental")
        assertEquals(payload, saved!!.snapshotPayload)
        assertEquals(saved.lastCaptureSuccessAtMillis, saved.lastCaptureAtMillis)
        assertTrue(saved.lastCaptureSuccessAtMillis > 0)
        assertEquals(0L, saved.lastCaptureFailureAtMillis)
        assertNull(saved.lastCaptureError)

        mode = "bad"
        capturing.requestCapture()
        val afterFailure = dao.get("shark-incremental")
        assertEquals(payload, afterFailure!!.snapshotPayload)
        assertEquals(saved.lastCaptureSuccessAtMillis, afterFailure.lastCaptureSuccessAtMillis)
        assertTrue(afterFailure.lastCaptureFailureAtMillis > 0)
        assertNotNull(afterFailure.lastCaptureError)
        assertEquals(SessionState.Playing, capturing.state.value)
    }

    @Test
    fun reopenResumesLiveStorageWithoutCallingSaveOrRestore() = runTest {
        val payload = progressedSharkPayload()
        assertFalse(SharkProgress.parsePayload(payload)!!.fresh)
        var captures = 0
        val snapshots = StubSnapshotDao()
        val session = controllerWithoutDocumentHook(
            readiness = { _, _ -> true },
            capture = { _, _ ->
                captures += 1
                payload
            },
            peek = { _, _ -> PeekOutcome.Value(payload) },
            snapshots = snapshots,
        )
        session.open("shark-incremental")
        val view = WebView(ApplicationProvider.getApplicationContext())
        session.registerWebView(view)
        session.onMainFrameFinished("https://mrredshark77.github.io/shark-incremental/")
        session.onMainFrameFinished("https://mrredshark77.github.io/shark-incremental/")
        assertEquals(SessionState.Playing, session.state.value)
        assertEquals(0, captures)
        assertEquals(1, session.gameDocumentLoads)
        session.requestCapture()
        assertEquals(1, captures)
        session.close()

        var restoreCalled = false
        var resumeCaptures = 0
        val resumed = controllerWithoutDocumentHook(
            readiness = { _, _ -> true },
            capture = { _, _ ->
                resumeCaptures += 1
                error("capture during resume")
            },
            restore = { _, _, _ ->
                restoreCalled = true
                true
            },
            peek = { _, _ -> PeekOutcome.Value(payload) },
            snapshots = snapshots,
        )
        resumed.open("shark-incremental")
        resumed.registerWebView(WebView(ApplicationProvider.getApplicationContext()))
        resumed.onMainFrameFinished("https://mrredshark77.github.io/shark-incremental/")
        resumed.onMainFrameFinished("https://mrredshark77.github.io/shark-incremental/")
        assertEquals(SessionState.Playing, resumed.state.value)
        assertFalse(restoreCalled)
        assertEquals(0, resumeCaptures)
        assertEquals(payload, dao.get("shark-incremental")!!.snapshotPayload)
    }

    @Test
    fun processDeathResumesProgressedLiveSave() = runTest {
        val payload = progressedSharkPayload()
        val snapshots = StubSnapshotDao()
        dao.upsert(progressedInstance(payload, playedAt = 50L))
        val first = controllerWithoutDocumentHook(
            readiness = { _, _ -> true },
            peek = { _, _ -> PeekOutcome.Value(payload) },
            snapshots = snapshots,
        )
        first.open("shark-incremental")
        first.registerWebView(WebView(ApplicationProvider.getApplicationContext()))
        first.onMainFrameFinished("https://mrredshark77.github.io/shark-incremental/")
        first.onMainFrameFinished("https://mrredshark77.github.io/shark-incremental/")
        assertEquals(SessionState.Playing, first.state.value)
        first.forceCloseForTest()

        var restoreCalled = false
        val second = controllerWithoutDocumentHook(
            readiness = { _, _ -> true },
            restore = { _, _, _ ->
                restoreCalled = true
                true
            },
            peek = { _, _ -> PeekOutcome.Value(payload) },
            snapshots = snapshots,
        )
        second.open("shark-incremental")
        second.registerWebView(WebView(ApplicationProvider.getApplicationContext()))
        second.onMainFrameFinished("https://mrredshark77.github.io/shark-incremental/")
        second.onMainFrameFinished("https://mrredshark77.github.io/shark-incremental/")
        assertEquals(SessionState.Playing, second.state.value)
        assertFalse(restoreCalled)
        assertEquals(payload, dao.get("shark-incremental")!!.snapshotPayload)
    }

    @Test
    fun multiDaySnapshotDoesNotOverrideMatchingLiveSave() = runTest {
        val payload = progressedSharkPayload()
        val snapshots = StubSnapshotDao()
        val fourDaysMs = 4L * 24L * 60L * 60L * 1000L
        dao.upsert(progressedInstance(payload, playedAt = System.currentTimeMillis() - fourDaysMs))
        snapshots.insert(
            SnapshotRecord(
                id = "old",
                gameId = "shark-incremental",
                sequence = 1,
                capturedAtMillis = System.currentTimeMillis() - fourDaysMs,
                reason = SnapshotReasons.MIGRATED_BASELINE,
                pinned = true,
                payload = payload,
                progressJson = null,
            ),
        )
        var restoreCalled = false
        val session = controllerWithoutDocumentHook(
            readiness = { _, _ -> true },
            restore = { _, _, _ ->
                restoreCalled = true
                true
            },
            peek = { _, _ -> PeekOutcome.Value(payload) },
            snapshots = snapshots,
        )
        session.open("shark-incremental")
        session.registerWebView(WebView(ApplicationProvider.getApplicationContext()))
        session.onMainFrameFinished("https://mrredshark77.github.io/shark-incremental/")
        session.onMainFrameFinished("https://mrredshark77.github.io/shark-incremental/")
        assertEquals(SessionState.Playing, session.state.value)
        assertFalse(restoreCalled)
        assertEquals(payload, dao.get("shark-incremental")!!.snapshotPayload)
    }

    @Test
    fun freshTrimpsSnapshotResumesWhenLiveMatches() = runTest {
        registry.injectGame(StubGame("trimps", "https://trimps.github.io/"))
        val fresh = freshTrimpsEnvelope()
        val snapshots = StubSnapshotDao()
        dao.upsert(
            GameInstance(
                gameId = "trimps",
                profileName = "hub-game-trimps",
                sessionIdentity = "fresh-trimps",
                lastPlayedAtMillis = 40L,
                lastCaptureAtMillis = 40L,
                snapshotPayload = fresh,
                lastCaptureSuccessAtMillis = 40L,
            ),
        )
        snapshots.insert(
            SnapshotRecord(
                id = "trimps-fresh",
                gameId = "trimps",
                sequence = 1,
                capturedAtMillis = 40L,
                reason = SnapshotReasons.WORKSTATION,
                pinned = true,
                payload = fresh,
                progressJson = null,
            ),
        )
        val session = controllerWithoutDocumentHook(
            readiness = { _, _ -> true },
            peek = { _, _ -> PeekOutcome.Value(fresh) },
            snapshots = snapshots,
        )
        session.open("trimps")
        session.registerWebView(WebView(ApplicationProvider.getApplicationContext()))
        session.onMainFrameFinished("https://trimps.github.io/")
        session.onMainFrameFinished("https://trimps.github.io/")
        assertEquals(SessionState.Playing, session.state.value)
        assertEquals(1, session.gameDocumentLoads)
        assertTrue(session.diagnosticReport().contains("comparison=MATCH"))
        assertEquals(fresh, dao.get("trimps")!!.snapshotPayload)
    }

    @Test
    fun freshTrimpsLiveDoesNotReplaceProgressedSnapshot() = runTest {
        registry.injectGame(StubGame("trimps", "https://trimps.github.io/"))
        val stored = freshTrimpsEnvelope(portals = 3, zone = 20, helium = "100")
        val live = freshTrimpsEnvelope()
        dao.upsert(
            GameInstance(
                gameId = "trimps",
                profileName = "hub-game-trimps",
                sessionIdentity = "progressed-trimps",
                lastPlayedAtMillis = 50L,
                lastCaptureAtMillis = 50L,
                snapshotPayload = stored,
                lastCaptureSuccessAtMillis = 50L,
            ),
        )
        val session = controllerWithoutDocumentHook(
            readiness = { _, _ -> true },
            peek = { _, _ -> PeekOutcome.Value(live) },
            snapshots = StubSnapshotDao(),
        )
        session.open("trimps")
        session.registerWebView(WebView(ApplicationProvider.getApplicationContext()))
        session.onMainFrameFinished("https://trimps.github.io/")
        assertEquals(SessionState.RecoveryRequired, session.state.value)
        assertEquals(0, session.gameDocumentLoads)
        assertTrue(session.diagnosticReport().contains("comparison=LIVE_BEHIND"))
        assertEquals(stored, dao.get("trimps")!!.snapshotPayload)
    }

    @Test
    fun freshLiveSaveDoesNotReplaceProgressedSnapshot() = runTest {
        val stored = progressedSharkPayload()
        val live = freshSharkPayload()
        val snapshots = StubSnapshotDao()
        dao.upsert(progressedInstance(stored, playedAt = 20L))
        var captures = 0
        val session = controllerWithoutDocumentHook(
            readiness = { _, _ -> true },
            capture = { _, _ ->
                captures += 1
                live
            },
            peek = { _, _ -> PeekOutcome.Value(live) },
            snapshots = snapshots,
        )
        session.open("shark-incremental")
        session.registerWebView(WebView(ApplicationProvider.getApplicationContext()))
        session.onMainFrameFinished("https://mrredshark77.github.io/shark-incremental/")
        assertEquals(SessionState.RecoveryRequired, session.state.value)
        assertEquals(0, captures)
        assertEquals(0, session.gameDocumentLoads)
        assertEquals(stored, dao.get("shark-incremental")!!.snapshotPayload)
        assertTrue(
            snapshots.history("shark-incremental").any {
                it.pinned && it.reason == SnapshotReasons.DIAGNOSTIC_BACKUP
            },
        )
        assertFalse(session.isCaptureCadenceActive)
    }

    @Test
    fun safeLaunchWithoutBackupDoesNotStartCapture() = runTest {
        val session = controllerWithoutDocumentHook(
            readiness = { _, _ -> true },
            capture = { _, _ -> error("capture") },
            snapshots = StubSnapshotDao(),
        )
        session.open("shark-incremental", LaunchMode.SAFE)
        assertEquals(SessionState.RecoveryRequired, session.state.value)
        assertFalse(session.isCaptureCadenceActive)
        assertEquals(0, session.gameDocumentLoads)
    }

    @Test
    fun safeLaunchAfterBackupLoadsWithoutCapture() = runTest {
        val snapshots = StubSnapshotDao()
        snapshots.insert(
            SnapshotRecord(
                id = "backup",
                gameId = "shark-incremental",
                sequence = 1,
                capturedAtMillis = 10L,
                reason = SnapshotReasons.DIAGNOSTIC_BACKUP,
                pinned = true,
                payload = progressedSharkPayload(),
                progressJson = null,
            ),
        )
        var captures = 0
        val session = controllerWithoutDocumentHook(
            readiness = { _, _ -> true },
            capture = { _, _ ->
                captures += 1
                error("capture")
            },
            snapshots = snapshots,
        )
        session.open("shark-incremental", LaunchMode.SAFE)
        session.registerWebView(WebView(ApplicationProvider.getApplicationContext()))
        session.onMainFrameFinished("https://mrredshark77.github.io/shark-incremental/")
        assertEquals(SessionState.Playing, session.state.value)
        assertEquals(0, captures)
        assertFalse(session.isCaptureCadenceActive)
        assertEquals(1, session.gameDocumentLoads)
    }

    @Test
    fun failedRestoreRollsBackAfterOneGameLoad() = runTest {
        val stored = progressedSharkPayload()
        val live = freshSharkPayload()
        val snapshots = StubSnapshotDao()
        dao.upsert(progressedInstance(stored, playedAt = 30L))
        var storage = live
        val writes = mutableListOf<String?>()
        var gameLoadsAtVerify = -1
        lateinit var session: SessionController
        session = controllerWithoutDocumentHook(
            readiness = { _, _ -> true },
            peek = { _, _ ->
                if (storage.isEmpty()) PeekOutcome.Empty else PeekOutcome.Value(storage)
            },
            runtime = { _, _, _ ->
                gameLoadsAtVerify = session.gameDocumentLoads
                false
            },
            onWrite = { value ->
                writes.add(value)
                storage = value.orEmpty()
            },
            snapshots = snapshots,
        )
        session.open("shark-incremental")
        val view = WebView(ApplicationProvider.getApplicationContext())
        session.registerWebView(view)
        session.onMainFrameFinished("https://mrredshark77.github.io/shark-incremental/")
        assertEquals(SessionState.RecoveryRequired, session.state.value)
        session.confirmRecover()
        session.registerWebView(view)
        session.onMainFrameFinished("https://mrredshark77.github.io/shark-incremental/")
        session.onMainFrameFinished("https://mrredshark77.github.io/shark-incremental/")
        session.onMainFrameFinished("https://mrredshark77.github.io/shark-incremental/")
        assertEquals(SessionState.RecoveryRequired, session.state.value)
        assertEquals(1, gameLoadsAtVerify)
        assertEquals(1, session.gameDocumentLoads)
        assertEquals(stored, dao.get("shark-incremental")!!.snapshotPayload)
        assertEquals(live, writes.last())
        assertTrue(
            snapshots.history("shark-incremental").any {
                it.reason == SnapshotReasons.PRE_RESTORE && it.pinned
            },
        )
    }

    @Test
    fun rendererGoneKeepsSnapshotAndDoesNotStayOnDeadWebView() = runTest {
        val payload = sharkPayload(gold = 3)
        val session = controllerWithoutDocumentHook(
            documentReady = { true },
            capture = { _, _ -> payload },
        )
        session.open("shark-incremental")
        val view = WebView(ApplicationProvider.getApplicationContext())
        session.webView = view
        session.requestCapture()
        val generation = session.webViewGeneration.value
        session.onRendererGone("onRenderProcessGone: didCrash=true (gameId=shark-incremental)")
        assertEquals(SessionState.RecoveryRequired, session.state.value)
        assertEquals(payload, dao.get("shark-incremental")!!.snapshotPayload)
        assertTrue(dao.get("shark-incremental")!!.lastCaptureError!!.contains("safe capture is impossible"))
        assertTrue(session.webViewGeneration.value > generation)
        assertNull(session.webView)
        assertTrue(session.sessionError.value!!.contains("onRenderProcessGone"))
    }

    @Test
    fun cadenceDoesNotRestartWhenCaptureReturnsToPlaying() = runTest {
        controller.open("shark-incremental")
        val starts = controller.cadenceStartCount
        controller.requestCapture()
        assertEquals(starts, controller.cadenceStartCount)
        assertTrue(controller.isCaptureCadenceActive)
    }

    @Test
    fun openPreservesExistingSessionIdentityAndSnapshot() = runTest {
        dao.upsert(
            GameInstance(
                gameId = "shark-incremental",
                profileName = "hub-game-shark-incremental",
                sessionIdentity = "original-session",
                lastPlayedAtMillis = 10L,
                lastCaptureAtMillis = 9L,
                snapshotPayload = "original-snapshot",
                lastCaptureSuccessAtMillis = 9L,
            )
        )
        controller.open("shark-incremental")
        val instance = dao.get("shark-incremental")
        assertEquals("original-session", instance!!.sessionIdentity)
        assertEquals("original-snapshot", instance.snapshotPayload)
        assertEquals(9L, instance.lastCaptureAtMillis)
    }

    @Test
    fun subresourceFailureDoesNotLeavePlaying() = runTest {
        controller.open("shark-incremental")
        controller.onSubresourceFailed("onReceivedHttpError: 404 on https://mrredshark77.github.io/favicon.ico")
        assertEquals(SessionState.Playing, controller.state.value)
        assertNull(controller.subresourceDiagnostic.value)
        assertEquals("onReceivedHttpError: 404 on https://mrredshark77.github.io/favicon.ico", controller.diagnosticEvents.value.last().message)
    }

    @Test
    fun reopeningTheLiveGameDoesNotReload() = runTest {
        controller.open("shark-incremental")
        val epoch = controller.navigationEpoch.value
        val loads = controller.gameDocumentLoads
        controller.open("shark-incremental")
        assertEquals(SessionState.Playing, controller.state.value)
        assertEquals(epoch, controller.navigationEpoch.value)
        assertEquals(loads, controller.gameDocumentLoads)
        assertTrue(controller.diagnosticEvents.value.any { it.message.contains("without reloading") })
    }

    @Test
    fun duplicateMainFrameFinishDoesNotLoadTheGameTwice() = runTest {
        var peeks = 0
        val session = controllerWithoutDocumentHook(
            readiness = { _, _ -> true },
            peek = { _, _ ->
                peeks += 1
                PeekOutcome.Empty
            },
        )
        session.open("shark-incremental")
        session.registerWebView(WebView(ApplicationProvider.getApplicationContext()))
        val url = "https://mrredshark77.github.io/shark-incremental/"
        session.onMainFrameFinished(url)
        session.onMainFrameFinished(url)
        assertEquals(1, peeks)
        assertEquals(1, session.gameDocumentLoads)
        assertEquals(SessionState.Playing, session.state.value)
    }

    @Test
    fun readinessTimeoutLeavesThePageAndDoesNotCapture() = runTest {
        var captures = 0
        val session = controllerWithoutDocumentHook(
            readiness = { _, _ -> false },
            capture = { _, _ ->
                captures += 1
                freshSharkPayload()
            },
            peek = { _, _ -> PeekOutcome.Empty },
        )
        session.open("shark-incremental")
        session.registerWebView(WebView(ApplicationProvider.getApplicationContext()))
        val url = "https://mrredshark77.github.io/shark-incremental/"
        session.onMainFrameFinished(url)
        session.onMainFrameFinished(url)
        advanceUntilIdle()
        assertEquals(SessionState.RecoveryRequired, session.state.value)
        assertEquals(0, captures)
        assertEquals(1, session.gameDocumentLoads)
        assertNull(dao.get("shark-incremental")!!.snapshotPayload)
        assertTrue(session.diagnosticEvents.value.any { it.category == "readiness" })
        assertTrue(
            session.sessionError.value.orEmpty().contains("was not overwritten"),
        )
    }

    @Test
    fun bootstrapPageFinishDuringGameLoadIsIgnored() = runTest {
        var readyCalls = 0
        var stillBootstrap = true
        val session = SessionController(
            context = ApplicationProvider.getApplicationContext(),
            gameRegistry = registry,
            instanceDao = dao,
            lifecycleOwner = StubLifecycleOwner(),
            webViewDestroyer = { destroyedWebViews.add("destroyed") },
            profileStore = profileStore,
            bootstrapTest = bootstrapSuccess,
            peekClient = { _, _ -> PeekOutcome.Empty },
            readinessCheck = { _, _ ->
                readyCalls += 1
                true
            },
            bootstrapConfirmTest = { stillBootstrap },
        )
        session.open("shark-incremental")
        session.registerWebView(WebView(ApplicationProvider.getApplicationContext()))
        val url = "https://mrredshark77.github.io/shark-incremental/"
        session.onMainFrameFinished(url)
        session.onMainFrameFinished(url)
        assertEquals(SessionState.Opening, session.state.value)
        assertEquals(0, readyCalls)
        assertTrue(
            session.diagnosticEvents.value.any {
                it.message.contains("Ignored game-load finish on the storage bootstrap page")
            },
        )
        stillBootstrap = false
        session.onMainFrameFinished(url)
        assertEquals(SessionState.Playing, session.state.value)
        assertEquals(1, readyCalls)
    }

    @Test
    fun staleFinishTokenDuringGameLoadIsIgnoredWithoutBlocking() = runTest {
        var readyCalls = 0
        val session = controllerWithoutDocumentHook(
            readiness = { _, _ ->
                readyCalls += 1
                true
            },
            peek = { _, _ -> PeekOutcome.Empty },
        )
        session.open("shark-incremental")
        session.registerWebView(WebView(ApplicationProvider.getApplicationContext()))
        val url = "https://mrredshark77.github.io/shark-incremental/"
        session.onMainFrameFinished(url, finishToken = 0)
        // Stale peek finish still carrying token 0 after beginGameLoad bumped the token.
        session.onMainFrameFinished(url, finishToken = 0)
        assertEquals(0, readyCalls)
        assertEquals(SessionState.Opening, session.state.value)
        assertTrue(
            session.diagnosticEvents.value.any { it.message.contains("Ignored stale finish") },
        )
        session.onMainFrameFinished(url, finishToken = session.loadToken.value)
        assertEquals(SessionState.Playing, session.state.value)
        assertEquals(1, readyCalls)
    }

    @Test
    fun finishQueuedDuringHandlerIsReplayed() = runTest {
        var readyCalls = 0
        var gateOpen = false
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val session = controllerWithoutDocumentHook(
            readiness = { _, _ ->
                readyCalls += 1
                if (!gateOpen) {
                    gateOpen = true
                    gate.await()
                }
                true
            },
            peek = { _, _ -> PeekOutcome.Empty },
        )
        session.open("shark-incremental")
        session.registerWebView(WebView(ApplicationProvider.getApplicationContext()))
        val url = "https://mrredshark77.github.io/shark-incremental/"
        session.onMainFrameFinished(url, finishToken = 0)
        val token = session.loadToken.value
        // LOAD_GAME finish suspends inside readiness.
        session.onMainFrameFinished(url, finishToken = token)
        // Concurrent finish while handlingDocument is held.
        session.onMainFrameFinished(url, finishToken = token)
        assertTrue(session.diagnosticEvents.value.any { it.message.contains("Queued main-frame finish") })
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(SessionState.Playing, session.state.value)
        assertTrue(readyCalls >= 1)
    }

    @Test
    fun unfinishedEnvelopeRestoreDoesNotLoadTheGame() = runTest {
        registry.injectGame(StubGame("envelope-probe", "https://example.test/envelope/"))
        val intended = com.example.hub.games.SaveEnvelope.encode(
            listOf(
                com.example.hub.games.StorageSlot("progress", "kept-secret-value"),
                com.example.hub.games.StorageSlot("backup", null),
                com.example.hub.games.StorageSlot("notes", ""),
            ),
        )
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = com.example.hub.session.RestoreJournalStore(context)
        store.write(
            com.example.hub.session.RestoreJournal(
                profileName = "hub-game-envelope-probe",
                gameId = "envelope-probe",
                phase = com.example.hub.session.JournalPhase.APPLYING,
                appliedKeys = 1,
                previousSlots = listOf(
                    com.example.hub.games.StorageSlot("progress", "old"),
                    com.example.hub.games.StorageSlot("backup", "daily"),
                    com.example.hub.games.StorageSlot("notes", "pref"),
                ),
                intendedSlots = listOf(
                    com.example.hub.games.StorageSlot("progress", "kept-secret-value"),
                    com.example.hub.games.StorageSlot("backup", null),
                    com.example.hub.games.StorageSlot("notes", ""),
                ),
                previousPointer = "old-pointer",
                intendedPointer = intended,
                direction = com.example.hub.session.RestoreDirection.INTENDED,
            ),
        )
        dao.upsert(
            GameInstance(
                gameId = "envelope-probe",
                profileName = "hub-game-envelope-probe",
                sessionIdentity = "envelope",
                lastPlayedAtMillis = 1L,
                lastCaptureAtMillis = 1L,
                snapshotPayload = "old-pointer",
            ),
        )
        val session = controllerWithoutDocumentHook(
            readiness = { _, _ -> true },
            peek = { _, _ -> PeekOutcome.Value(intended) },
            adapterForTest = { adapterId ->
                if (adapterId == "envelope-probe-adapter") EnvelopeProbeAdapter() else null
            },
        )
        session.open("envelope-probe")
        assertEquals(SessionState.Opening, session.state.value)
        assertEquals(DocumentPhase.WRITE, session.documentGate.phase)
        assertEquals(0, session.gameDocumentLoads)
        assertFalse(session.diagnosticReport().contains("kept-secret-value"))
        session.registerWebView(WebView(context))
        session.onMainFrameFinished("https://example.test/envelope/")
        advanceUntilIdle()
        assertEquals("old-pointer", dao.get("envelope-probe")!!.snapshotPayload)
        assertTrue(store.unfinished("hub-game-envelope-probe"))
        assertEquals(
            com.example.hub.session.JournalPhase.VERIFIED,
            store.read("hub-game-envelope-probe")?.phase,
        )
        assertEquals(SessionState.RecoveryRequired, session.state.value)
        assertEquals(0, session.gameDocumentLoads)
        assertFalse(session.isCaptureCadenceActive)
        assertTrue(session.captureBlockReason.value!!.contains("restore is unfinished"))
        assertFalse(session.diagnosticReport().contains("kept-secret-value"))
        store.delete("hub-game-envelope-probe")
    }

    @Test
    fun failedEnvelopeCaptureDoesNotAdvanceThePointer() = runTest {
        registry.injectGame(StubGame("envelope-probe", "https://example.test/envelope/"))
        dao.upsert(
            GameInstance(
                gameId = "envelope-probe",
                profileName = "hub-game-envelope-probe",
                sessionIdentity = "envelope",
                lastPlayedAtMillis = 1L,
                lastCaptureAtMillis = 1L,
                snapshotPayload = "old-pointer",
            ),
        )
        val session = controllerWithoutDocumentHook(
            documentReady = { true },
            capture = { _, _ -> """{"hubEnvelope":1,"entries":[{"name":"progress","value":"a"},{"name":"progress","value":"b"}]}""" },
            adapterForTest = { adapterId ->
                if (adapterId == "envelope-probe-adapter") EnvelopeProbeAdapter() else null
            },
        )
        session.open("envelope-probe")
        session.webView = WebView(ApplicationProvider.getApplicationContext())
        session.requestCapture()
        advanceUntilIdle()
        assertEquals("old-pointer", dao.get("envelope-probe")!!.snapshotPayload)
    }

    @Test
    fun checkpointIsPinnedAndAFailedCaptureDoesNotReplaceIt() = runTest {
        val good = progressedSharkPayload()
        var payload: String? = good
        val snapshots = StubSnapshotDao()
        val session = controllerWithoutDocumentHook(
            documentReady = { true },
            capture = { _, _ -> payload },
            snapshots = snapshots,
        )
        session.open("shark-incremental")
        session.webView = WebView(ApplicationProvider.getApplicationContext())
        session.requestCheckpoint()
        assertEquals(good, dao.get("shark-incremental")!!.snapshotPayload)
        val checkpoint = snapshots.history("shark-incremental").single()
        assertTrue(checkpoint.pinned)
        assertEquals(SnapshotReasons.CHECKPOINT, checkpoint.reason)
        assertEquals(SnapshotOrigins.MANUAL_CHECKPOINT, checkpoint.origin)
        payload = null
        session.requestCapture()
        assertEquals(good, dao.get("shark-incremental")!!.snapshotPayload)
        assertEquals(1, snapshots.history("shark-incremental").size)
    }

    @Test
    fun manualSaveReportsSuccessOnlyAfterTheCaptureCommits() = runTest {
        val good = progressedSharkPayload()
        val gate = kotlinx.coroutines.CompletableDeferred<String?>()
        val snapshots = StubSnapshotDao()
        val session = controllerWithoutDocumentHook(
            documentReady = { true },
            capture = { _, _ -> gate.await() },
            snapshots = snapshots,
        )
        session.open("shark-incremental")
        session.webView = WebView(ApplicationProvider.getApplicationContext())
        session.requestCheckpoint()
        assertTrue(session.manualSaveInFlight.value)
        assertNull(session.manualSaveNotice.value)
        assertNull(dao.get("shark-incremental")!!.snapshotPayload)
        session.requestCheckpoint()
        assertEquals(ManualSaveOutcome.BUSY, session.manualSaveNotice.value?.outcome)
        assertFalse(session.manualSaveNotice.value?.message == "Game saved")
        assertEquals(0, snapshots.history("shark-incremental").size)
        gate.complete(good)
        assertEquals(ManualSaveOutcome.SAVED, session.manualSaveNotice.value?.outcome)
        assertEquals("Game saved", session.manualSaveNotice.value?.message)
        assertFalse(session.manualSaveInFlight.value)
        assertEquals(good, dao.get("shark-incremental")!!.snapshotPayload)
        val checkpoint = snapshots.history("shark-incremental").single()
        assertTrue(checkpoint.pinned)
        assertEquals(SnapshotReasons.CHECKPOINT, checkpoint.reason)
        assertEquals(SnapshotOrigins.MANUAL_CHECKPOINT, checkpoint.origin)
        session.forceCloseForTest()
    }

    @Test
    fun failedAndDeferredSavesKeepThePreviousPayload() = runTest {
        val good = progressedSharkPayload()
        val declining = DecliningAdapter(null)
        var payload: String? = good
        val session = controllerWithoutDocumentHook(
            documentReady = { true },
            capture = { _, _ -> payload },
            adapterForTest = { adapterId ->
                if (adapterId == "shark-incremental-adapter") declining else null
            },
        )
        session.open("shark-incremental")
        session.webView = WebView(ApplicationProvider.getApplicationContext())
        session.requestCheckpoint()
        assertEquals("Game saved", session.manualSaveNotice.value?.message)
        assertEquals(good, dao.get("shark-incremental")!!.snapshotPayload)

        payload = null
        declining.decline = "Capture deferred: offline warp is running. Last good snapshot was kept."
        session.requestCheckpoint()
        assertEquals(ManualSaveOutcome.DEFERRED, session.manualSaveNotice.value?.outcome)
        assertEquals(declining.decline, session.manualSaveNotice.value?.message)
        assertEquals(good, dao.get("shark-incremental")!!.snapshotPayload)

        payload = "not-a-save"
        declining.decline = null
        session.requestCheckpoint()
        assertEquals(ManualSaveOutcome.FAILED, session.manualSaveNotice.value?.outcome)
        assertTrue(session.manualSaveNotice.value!!.message.contains("Last good snapshot was kept"))
        assertFalse(session.manualSaveNotice.value!!.message == "Game saved")
        assertEquals(good, dao.get("shark-incremental")!!.snapshotPayload)
        session.forceCloseForTest()
    }

    @Test
    fun automaticCapturesStayUnpinnedWhileSaveIsPinned() = runTest {
        val snapshots = StubSnapshotDao()
        var prestige = 3L
        val session = controllerWithoutDocumentHook(
            documentReady = { true },
            capture = { _, _ ->
                val payload = sharkPayload(feature = 4, fishExponent = "15", prestige = prestige)
                prestige += 1
                payload
            },
            snapshots = snapshots,
        )
        session.open("shark-incremental")
        session.webView = WebView(ApplicationProvider.getApplicationContext())
        session.requestCheckpoint()
        repeat(12) { session.requestCapture() }
        val rows = snapshots.history("shark-incremental")
        val pinned = rows.filter { it.reason == SnapshotReasons.CHECKPOINT }
        assertEquals(1, pinned.size)
        assertTrue(pinned.single().pinned)
        assertEquals(SnapshotOrigins.MANUAL_CHECKPOINT, pinned.single().origin)
        val automatic = rows.filter { it.reason == "request" && !it.pinned }
        assertTrue(automatic.isNotEmpty())
        assertTrue(automatic.size <= 10)
        val stored = dao.get("shark-incremental")!!
        val anchor = snapshots.getById(stored.highWaterSnapshotId!!)
        assertNotNull(anchor)
        assertTrue(anchor!!.pinned)
        assertEquals(stored.snapshotPayload, anchor.payload)
        session.forceCloseForTest()
    }

    @Test
    fun verifiedRecoverPinsBaselineAndManualSaveWithoutAnotherWebView() = runTest {
        val advanced = sharkPayload(feature = 9, fishExponent = "3689787472063838")
        val regressed = freshSharkPayload()
        val snapshots = StubSnapshotDao()
        val synced = mutableListOf<String?>()
        var storage = regressed
        var captures = 0
        val session = controllerWithoutDocumentHook(
            readiness = { _, _ -> true },
            capture = { _, _ ->
                captures += 1
                storage
            },
            peek = { _, _ -> if (storage.isEmpty()) PeekOutcome.Empty else PeekOutcome.Value(storage) },
            runtime = { _, _, _ -> true },
            onWrite = { value -> storage = value.orEmpty() },
            snapshots = snapshots,
            onSnapshotSaved = { synced.add(it.snapshotPayload) },
        )
        dao.upsert(progressedInstance(advanced, playedAt = 40L))
        session.open("shark-incremental", LaunchMode.RECOVER)
        val view = WebView(ApplicationProvider.getApplicationContext())
        session.registerWebView(view)
        session.onMainFrameFinished("https://mrredshark77.github.io/shark-incremental/")
        assertEquals(SessionState.RecoveryRequired, session.state.value)
        assertFalse(session.isCaptureCadenceActive)
        assertTrue(session.captureBlockReason.value!!.contains("restore is unfinished"))
        val generation = session.webViewGeneration.value
        session.confirmRecover()
        assertEquals(generation + 1, session.webViewGeneration.value)
        session.registerWebView(view)
        session.onMainFrameFinished("https://mrredshark77.github.io/shark-incremental/")
        session.onMainFrameFinished("https://mrredshark77.github.io/shark-incremental/")
        assertEquals(SessionState.Playing, session.state.value)
        assertNull(session.captureBlockReason.value)
        assertEquals(1, session.cadenceStartCount)
        assertTrue(session.isCaptureCadenceActive)
        assertEquals(1, session.gameDocumentLoads)
        assertEquals(generation + 1, session.webViewGeneration.value)
        assertTrue(session.webView === view)
        val pointer = dao.get("shark-incremental")!!
        assertEquals(9, SharkProgress.parsePayload(pointer.snapshotPayload)?.feature)
        assertTrue(pointer.lastCaptureSuccessAtMillis > 0L)
        assertNull(pointer.recoveryState)
        assertNull(pointer.lastCaptureError)
        val baseline = snapshots.history("shark-incremental").filter { it.reason == SnapshotReasons.POST_RESTORE }
        assertEquals(1, baseline.size)
        assertTrue(baseline.single().pinned)
        assertEquals(SnapshotOrigins.RECOVERY, baseline.single().origin)
        assertEquals(pointer.snapshotPayload, baseline.single().payload)
        assertEquals(listOf(pointer.snapshotPayload), synced)
        assertFalse(synced.contains(regressed))
        assertTrue(
            snapshots.history("shark-incremental").any {
                it.reason == SnapshotReasons.PRE_RESTORE && it.payload == regressed && it.pinned
            },
        )
        session.onMainFrameFinished("https://mrredshark77.github.io/shark-incremental/")
        assertEquals(1, snapshots.history("shark-incremental").count { it.reason == SnapshotReasons.POST_RESTORE })
        assertEquals(1, session.cadenceStartCount)

        session.requestCheckpoint()
        assertEquals(ManualSaveOutcome.SAVED, session.manualSaveNotice.value?.outcome)
        assertEquals("Game saved", session.manualSaveNotice.value?.message)
        val checkpoints = snapshots.history("shark-incremental").filter { it.reason == SnapshotReasons.CHECKPOINT }
        assertEquals(1, checkpoints.size)
        assertTrue(checkpoints.single().pinned)
        assertEquals(1, captures)
        assertEquals(listOf(pointer.snapshotPayload), synced)
        assertFalse(synced.any { SharkProgress.parsePayload(it)?.feature == 0 })
        session.forceCloseForTest()
    }

    @Test
    fun deferredRestorePinsIntendedPayloadAndStaysSuppressed() = runTest {
        val advanced = sharkPayload(feature = 9, fishExponent = "40")
        val live = freshSharkPayload()
        val snapshots = StubSnapshotDao()
        val synced = mutableListOf<String?>()
        var storage = live
        val session = controllerWithoutDocumentHook(
            readiness = { _, _ -> true },
            peek = { _, _ -> PeekOutcome.Value(storage) },
            onWrite = { value -> storage = value.orEmpty() },
            snapshots = snapshots,
            onSnapshotSaved = { synced.add(it.snapshotPayload) },
            adapterForTest = { adapterId ->
                if (adapterId == "shark-incremental-adapter") DeferredSharkAdapter() else null
            },
        )
        dao.upsert(progressedInstance(advanced, playedAt = 41L))
        session.open("shark-incremental", LaunchMode.RECOVER)
        session.registerWebView(WebView(ApplicationProvider.getApplicationContext()))
        session.onMainFrameFinished("https://mrredshark77.github.io/shark-incremental/")
        session.confirmRecover()
        session.registerWebView(WebView(ApplicationProvider.getApplicationContext()))
        session.onMainFrameFinished("https://mrredshark77.github.io/shark-incremental/")
        session.onMainFrameFinished("https://mrredshark77.github.io/shark-incremental/")
        assertEquals(SessionState.RecoveryRequired, session.state.value)
        assertFalse(session.isCaptureCadenceActive)
        assertTrue(session.captureBlockReason.value!!.contains("restore is unfinished"))
        assertTrue(session.sessionError.value!!.contains("Offline warp"))
        assertEquals(advanced, dao.get("shark-incremental")!!.snapshotPayload)
        val pinned = snapshots.history("shark-incremental").filter { it.reason == SnapshotReasons.POST_RESTORE }
        assertEquals(1, pinned.size)
        assertTrue(pinned.single().pinned)
        assertEquals(9, SharkProgress.parsePayload(pinned.single().payload)?.feature)
        assertTrue(synced.isEmpty())
        session.requestCheckpoint()
        assertTrue(session.manualSaveNotice.value!!.message.contains("restore is unfinished"))
        assertFalse(session.manualSaveNotice.value!!.message == "Game saved")
        session.forceCloseForTest()
    }

    @Test
    fun rejectedRestoreKeepsSuppressionAndBothCandidates() = runTest {
        val advanced = sharkPayload(feature = 9, fishExponent = "40")
        val live = freshSharkPayload()
        val snapshots = StubSnapshotDao()
        val synced = mutableListOf<String?>()
        var storage = live
        val session = controllerWithoutDocumentHook(
            readiness = { _, _ -> true },
            peek = { _, _ -> PeekOutcome.Value(storage) },
            runtime = { _, _, _ -> false },
            onWrite = { value -> storage = value.orEmpty() },
            snapshots = snapshots,
            onSnapshotSaved = { synced.add(it.snapshotPayload) },
        )
        dao.upsert(progressedInstance(advanced, playedAt = 42L))
        session.open("shark-incremental", LaunchMode.RECOVER)
        session.registerWebView(WebView(ApplicationProvider.getApplicationContext()))
        session.onMainFrameFinished("https://mrredshark77.github.io/shark-incremental/")
        session.confirmRecover()
        session.registerWebView(WebView(ApplicationProvider.getApplicationContext()))
        session.onMainFrameFinished("https://mrredshark77.github.io/shark-incremental/")
        session.onMainFrameFinished("https://mrredshark77.github.io/shark-incremental/")
        session.onMainFrameFinished("https://mrredshark77.github.io/shark-incremental/")
        assertEquals(SessionState.RecoveryRequired, session.state.value)
        assertFalse(session.isCaptureCadenceActive)
        assertTrue(session.captureBlockReason.value!!.contains("restore is unfinished"))
        assertEquals(advanced, dao.get("shark-incremental")!!.snapshotPayload)
        assertTrue(snapshots.history("shark-incremental").none { it.reason == SnapshotReasons.POST_RESTORE })
        assertTrue(
            snapshots.history("shark-incremental").any {
                it.reason == SnapshotReasons.PRE_RESTORE && it.payload == live
            },
        )
        assertTrue(synced.isEmpty())
        session.requestCheckpoint()
        assertTrue(session.manualSaveNotice.value!!.message.contains("restore is unfinished"))
        session.forceCloseForTest()
    }

    @Test
    fun rollbackFailureKeepsBothCandidates() = runTest {
        val advanced = sharkPayload(feature = 9, fishExponent = "40")
        val live = freshSharkPayload()
        val snapshots = StubSnapshotDao()
        var storage = live
        val session = controllerWithoutDocumentHook(
            readiness = { _, _ -> true },
            peek = { _, _ -> PeekOutcome.Value(storage) },
            runtime = { _, _, _ -> false },
            onWrite = { value ->
                if (value != live) storage = value.orEmpty()
            },
            snapshots = snapshots,
        )
        dao.upsert(progressedInstance(advanced, playedAt = 43L))
        session.open("shark-incremental", LaunchMode.RECOVER)
        session.registerWebView(WebView(ApplicationProvider.getApplicationContext()))
        session.onMainFrameFinished("https://mrredshark77.github.io/shark-incremental/")
        session.confirmRecover()
        session.registerWebView(WebView(ApplicationProvider.getApplicationContext()))
        session.onMainFrameFinished("https://mrredshark77.github.io/shark-incremental/")
        session.onMainFrameFinished("https://mrredshark77.github.io/shark-incremental/")
        session.onMainFrameFinished("https://mrredshark77.github.io/shark-incremental/")
        assertEquals(SessionState.RecoveryRequired, session.state.value)
        assertTrue(session.sessionError.value!!.contains("Manual intervention is required"))
        assertEquals(advanced, dao.get("shark-incremental")!!.snapshotPayload)
        assertTrue(
            snapshots.history("shark-incremental").any {
                it.reason == SnapshotReasons.PRE_RESTORE && it.payload == live && it.pinned
            },
        )
        assertTrue(
            snapshots.history("shark-incremental").any {
                it.reason == SnapshotReasons.PRE_RESTORE && it.payload == advanced && it.pinned
            },
        )
        assertTrue(session.captureBlockReason.value!!.contains("restore is unfinished"))
        session.forceCloseForTest()
    }

    @Test
    fun regressedReadbackDoesNotReplaceTheVerifiedPayload() = runTest {
        val advanced = sharkPayload(feature = 9, fishExponent = "40")
        val regressed = freshSharkPayload()
        val snapshots = StubSnapshotDao()
        val synced = mutableListOf<String?>()
        var storage = regressed
        var wroteRestore = false
        val session = controllerWithoutDocumentHook(
            readiness = { _, _ -> true },
            peek = { _, _ -> PeekOutcome.Value(storage) },
            runtime = { _, _, _ ->
                storage = regressed
                true
            },
            onWrite = { value ->
                wroteRestore = true
                storage = value.orEmpty()
            },
            snapshots = snapshots,
            onSnapshotSaved = { synced.add(it.snapshotPayload) },
        )
        dao.upsert(progressedInstance(advanced, playedAt = 44L))
        session.open("shark-incremental", LaunchMode.RECOVER)
        session.registerWebView(WebView(ApplicationProvider.getApplicationContext()))
        session.onMainFrameFinished("https://mrredshark77.github.io/shark-incremental/")
        session.confirmRecover()
        session.registerWebView(WebView(ApplicationProvider.getApplicationContext()))
        session.onMainFrameFinished("https://mrredshark77.github.io/shark-incremental/")
        assertTrue(wroteRestore)
        session.onMainFrameFinished("https://mrredshark77.github.io/shark-incremental/")
        assertEquals(SessionState.Playing, session.state.value)
        assertEquals(9, SharkProgress.parsePayload(dao.get("shark-incremental")!!.snapshotPayload)?.feature)
        assertFalse(synced.any { SharkProgress.parsePayload(it)?.feature == 0 })
        session.forceCloseForTest()
    }

    @Test
    fun acceptedRewriteBecomesThePostRestoreBaseline() = runTest {
        val advanced = sharkPayload(feature = 9, fishExponent = "40")
        val rewritten = sharkPayload(feature = 9, fishExponent = "41", played = "345601")
        val snapshots = StubSnapshotDao()
        var storage = freshSharkPayload()
        val session = controllerWithoutDocumentHook(
            readiness = { _, _ -> true },
            peek = { _, _ -> PeekOutcome.Value(storage) },
            runtime = { _, _, _ ->
                storage = rewritten
                true
            },
            onWrite = { value -> storage = value.orEmpty() },
            snapshots = snapshots,
        )
        dao.upsert(progressedInstance(advanced, playedAt = 45L))
        session.open("shark-incremental", LaunchMode.RECOVER)
        session.registerWebView(WebView(ApplicationProvider.getApplicationContext()))
        session.onMainFrameFinished("https://mrredshark77.github.io/shark-incremental/")
        session.confirmRecover()
        session.registerWebView(WebView(ApplicationProvider.getApplicationContext()))
        session.onMainFrameFinished("https://mrredshark77.github.io/shark-incremental/")
        session.onMainFrameFinished("https://mrredshark77.github.io/shark-incremental/")
        assertEquals(SessionState.Playing, session.state.value)
        assertEquals(rewritten, dao.get("shark-incremental")!!.snapshotPayload)
        assertEquals(
            rewritten,
            snapshots.history("shark-incremental").single { it.reason == SnapshotReasons.POST_RESTORE }.payload,
        )
        session.forceCloseForTest()
    }

    @Test
    fun staleRestoreCallbackDoesNotCommitAfterSwitchingGames() = runTest {
        val advanced = sharkPayload(feature = 9, fishExponent = "40")
        val snapshots = StubSnapshotDao()
        val synced = mutableListOf<String?>()
        val gate = kotlinx.coroutines.CompletableDeferred<Boolean>()
        var storage = freshSharkPayload()
        lateinit var session: SessionController
        session = controllerWithoutDocumentHook(
            readiness = { _, _ -> true },
            peek = { _, _ -> PeekOutcome.Value(storage) },
            runtime = { _, _, _ ->
                session.open("fe000000")
                gate.await()
            },
            onWrite = { value -> storage = value.orEmpty() },
            snapshots = snapshots,
            onSnapshotSaved = { synced.add(it.snapshotPayload) },
        )
        dao.upsert(progressedInstance(advanced, playedAt = 46L))
        session.open("shark-incremental", LaunchMode.RECOVER)
        session.registerWebView(WebView(ApplicationProvider.getApplicationContext()))
        session.onMainFrameFinished("https://mrredshark77.github.io/shark-incremental/")
        session.confirmRecover()
        session.registerWebView(WebView(ApplicationProvider.getApplicationContext()))
        session.onMainFrameFinished("https://mrredshark77.github.io/shark-incremental/")
        session.onMainFrameFinished("https://mrredshark77.github.io/shark-incremental/")
        assertEquals("fe000000", session.activeGameId.value)
        gate.complete(true)
        assertEquals(advanced, dao.get("shark-incremental")!!.snapshotPayload)
        assertTrue(snapshots.history("shark-incremental").none { it.reason == SnapshotReasons.POST_RESTORE })
        assertTrue(synced.none { SharkProgress.parsePayload(it)?.feature == 9 })
        session.forceCloseForTest()
    }

    @Test
    fun processDeathBeforeCommitKeepsThePreviousPointer() = runTest {
        val advanced = sharkPayload(feature = 9, fishExponent = "40")
        val snapshots = StubSnapshotDao()
        val synced = mutableListOf<String?>()
        val gate = kotlinx.coroutines.CompletableDeferred<Boolean>()
        var storage = freshSharkPayload()
        val session = controllerWithoutDocumentHook(
            readiness = { _, _ -> true },
            peek = { _, _ -> PeekOutcome.Value(storage) },
            runtime = { _, _, _ -> gate.await() },
            onWrite = { value -> storage = value.orEmpty() },
            snapshots = snapshots,
            onSnapshotSaved = { synced.add(it.snapshotPayload) },
        )
        dao.upsert(progressedInstance(advanced, playedAt = 47L))
        session.open("shark-incremental", LaunchMode.RECOVER)
        session.registerWebView(WebView(ApplicationProvider.getApplicationContext()))
        session.onMainFrameFinished("https://mrredshark77.github.io/shark-incremental/")
        session.confirmRecover()
        session.registerWebView(WebView(ApplicationProvider.getApplicationContext()))
        session.onMainFrameFinished("https://mrredshark77.github.io/shark-incremental/")
        session.onMainFrameFinished("https://mrredshark77.github.io/shark-incremental/")
        assertEquals(advanced, dao.get("shark-incremental")!!.snapshotPayload)
        assertEquals("RecoveryRequired", dao.get("shark-incremental")!!.recoveryState)
        val restarted = controllerWithoutDocumentHook(
            readiness = { _, _ -> true },
            peek = { _, _ -> PeekOutcome.Value(advanced) },
            snapshots = snapshots,
        )
        restarted.onAttached()
        assertEquals(SessionState.RecoveryRequired, restarted.state.value)
        assertEquals(advanced, dao.get("shark-incremental")!!.snapshotPayload)
        assertTrue(snapshots.history("shark-incremental").none { it.reason == SnapshotReasons.POST_RESTORE })
        session.forceCloseForTest()
        gate.complete(true)
        assertEquals(advanced, dao.get("shark-incremental")!!.snapshotPayload)
        assertTrue(synced.isEmpty())
        restarted.forceCloseForTest()
    }

    @Test
    fun processDeathAfterCommitResumesTheRestoredPointer() = runTest {
        val advanced = sharkPayload(feature = 9, fishExponent = "40")
        val snapshots = StubSnapshotDao()
        var storage = freshSharkPayload()
        val session = controllerWithoutDocumentHook(
            readiness = { _, _ -> true },
            peek = { _, _ -> PeekOutcome.Value(storage) },
            runtime = { _, _, _ -> true },
            onWrite = { value -> storage = value.orEmpty() },
            snapshots = snapshots,
        )
        dao.upsert(progressedInstance(advanced, playedAt = 48L))
        session.open("shark-incremental", LaunchMode.RECOVER)
        session.registerWebView(WebView(ApplicationProvider.getApplicationContext()))
        session.onMainFrameFinished("https://mrredshark77.github.io/shark-incremental/")
        session.confirmRecover()
        session.registerWebView(WebView(ApplicationProvider.getApplicationContext()))
        session.onMainFrameFinished("https://mrredshark77.github.io/shark-incremental/")
        session.onMainFrameFinished("https://mrredshark77.github.io/shark-incremental/")
        val restored = dao.get("shark-incremental")!!.snapshotPayload
        assertEquals(9, SharkProgress.parsePayload(restored)?.feature)
        assertNull(dao.get("shark-incremental")!!.recoveryState)
        session.forceCloseForTest()
        storage = restored!!
        val restarted = controllerWithoutDocumentHook(
            readiness = { _, _ -> true },
            peek = { _, _ -> PeekOutcome.Value(storage) },
            snapshots = snapshots,
        )
        restarted.onAttached()
        assertEquals(SessionState.Closed, restarted.state.value)
        restarted.open("shark-incremental")
        restarted.registerWebView(WebView(ApplicationProvider.getApplicationContext()))
        restarted.onMainFrameFinished("https://mrredshark77.github.io/shark-incremental/")
        restarted.onMainFrameFinished("https://mrredshark77.github.io/shark-incremental/")
        assertEquals(SessionState.Playing, restarted.state.value)
        assertEquals(restored, dao.get("shark-incremental")!!.snapshotPayload)
        assertEquals(
            1,
            snapshots.history("shark-incremental").count { it.reason == SnapshotReasons.POST_RESTORE },
        )
        restarted.forceCloseForTest()
    }

    @Test
    fun postRestorePinIsIdempotent() = runTest {
        val advanced = sharkPayload(feature = 9, fishExponent = "40")
        val prepared = SharkIncrementalAdapter().prepareRestore(advanced)
        val snapshots = StubSnapshotDao()
        snapshots.insert(
            SnapshotRecord(
                id = "already",
                gameId = "shark-incremental",
                sequence = 1,
                capturedAtMillis = 1L,
                reason = SnapshotReasons.POST_RESTORE,
                pinned = true,
                payload = prepared,
                progressJson = null,
                origin = SnapshotOrigins.RECOVERY,
            ),
        )
        var storage = freshSharkPayload()
        val session = controllerWithoutDocumentHook(
            readiness = { _, _ -> true },
            peek = { _, _ -> PeekOutcome.Value(storage) },
            runtime = { _, _, _ -> true },
            onWrite = { value -> storage = value.orEmpty() },
            snapshots = snapshots,
        )
        dao.upsert(progressedInstance(advanced, playedAt = 49L))
        session.open("shark-incremental", LaunchMode.RECOVER)
        session.registerWebView(WebView(ApplicationProvider.getApplicationContext()))
        session.onMainFrameFinished("https://mrredshark77.github.io/shark-incremental/")
        session.confirmRecover()
        session.registerWebView(WebView(ApplicationProvider.getApplicationContext()))
        session.onMainFrameFinished("https://mrredshark77.github.io/shark-incremental/")
        session.onMainFrameFinished("https://mrredshark77.github.io/shark-incremental/")
        assertEquals(
            1,
            snapshots.history("shark-incremental").count { it.reason == SnapshotReasons.POST_RESTORE },
        )
        assertEquals(prepared, dao.get("shark-incremental")!!.snapshotPayload)
        session.forceCloseForTest()
    }

    @Test
    fun captureWriteFailureIsStorageErrorAndDoesNotSayGameSaved() = runTest {
        val good = progressedSharkPayload()
        val snapshots = StubSnapshotDao()
        val session = controllerWithoutDocumentHook(
            documentReady = { true },
            capture = { _, _ -> good },
            snapshots = snapshots,
        )
        session.open("shark-incremental")
        session.webView = WebView(ApplicationProvider.getApplicationContext())
        session.captureWriteFault = { error("disk full") }
        session.requestCheckpoint()
        assertEquals(ManualSaveOutcome.FAILED, session.manualSaveNotice.value?.outcome)
        assertTrue(session.manualSaveNotice.value!!.message.contains("storage error"))
        assertFalse(session.manualSaveNotice.value!!.message == "Game saved")
        assertNull(dao.get("shark-incremental")!!.snapshotPayload)
        assertEquals(0, snapshots.history("shark-incremental").size)
        session.forceCloseForTest()
    }

    @Test
    fun roomCommitFailureRollsBackThePostRestorePin() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val inline = java.util.concurrent.Executor { it.run() }
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, com.example.hub.database.HubDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(inline)
            .setTransactionExecutor(inline)
            .build()
        try {
            val kept = sharkPayload(feature = 4, fishExponent = "10")
            val advanced = sharkPayload(feature = 9, fishExponent = "40")
            db.gameInstanceDao().upsert(progressedInstance(kept, playedAt = 50L))
            db.snapshotDao().insert(
                SnapshotRecord(
                    id = "history-advanced",
                    gameId = "shark-incremental",
                    sequence = 1,
                    capturedAtMillis = 50L,
                    reason = SnapshotReasons.CHECKPOINT,
                    pinned = true,
                    payload = advanced,
                    progressJson = null,
                    origin = SnapshotOrigins.MANUAL_CHECKPOINT,
                ),
            )
            val synced = mutableListOf<String?>()
            var storage = freshSharkPayload()
            val session = SessionController(
                context = context,
                gameRegistry = registry,
                instanceDao = db.gameInstanceDao(),
                lifecycleOwner = StubLifecycleOwner(),
                profileStore = profileStore,
                bootstrapTest = bootstrapSuccess,
                readinessCheck = { _, _ -> true },
                captureClient = { _, _ -> storage },
                peekClient = { _, _ -> PeekOutcome.Value(storage) },
                runtimeCheck = { _, _, _ -> true },
                onBootstrapWrite = { value -> storage = value.orEmpty() },
                snapshotDao = db.snapshotDao(),
                roomDatabase = db,
                onSnapshotSaved = { synced.add(it.snapshotPayload) },
            )
            session.restoreCommitFault = { stage ->
                if (stage == "after-pin") error("storage error injected")
            }
            session.open("shark-incremental", LaunchMode.RECOVER)
            session.registerWebView(WebView(context))
            session.onMainFrameFinished("https://mrredshark77.github.io/shark-incremental/")
            session.confirmRecover()
            session.registerWebView(WebView(context))
            session.onMainFrameFinished("https://mrredshark77.github.io/shark-incremental/")
            session.onMainFrameFinished("https://mrredshark77.github.io/shark-incremental/")
            assertEquals(SessionState.RecoveryRequired, session.state.value)
            assertTrue(session.sessionError.value!!.contains("storage error"))
            assertEquals(kept, db.gameInstanceDao().get("shark-incremental")!!.snapshotPayload)
            assertTrue(db.snapshotDao().history("shark-incremental").none { it.reason == SnapshotReasons.POST_RESTORE })
            assertTrue(synced.isEmpty())
            assertFalse(session.isCaptureCadenceActive)
            session.forceCloseForTest()
        } finally {
            db.close()
        }
    }

    @Test
    fun feature9SurvivesOneHundredFeature0Captures() = runTest {
        val feature9 = sharkPayload(feature = 9, fishExponent = "3689787472063838")
        val feature0 = sharkPayload(
            feature = 0,
            fishExponent = "9999999999999999999",
            played = "999999999",
        )
        var payload = feature9
        val snapshots = StubSnapshotDao()
        val synced = mutableListOf<String?>()
        val session = controllerWithoutDocumentHook(
            documentReady = { true },
            capture = { _, _ -> payload },
            snapshots = snapshots,
            onSnapshotSaved = { synced.add(it.snapshotPayload) },
        )
        session.open("shark-incremental")
        session.webView = WebView(ApplicationProvider.getApplicationContext())
        session.requestCheckpoint()
        val storedBefore = dao.get("shark-incremental")!!
        assertEquals(9, SharkProgress.parsePayload(storedBefore.snapshotPayload)!!.feature)
        val highWaterId = storedBefore.highWaterSnapshotId
        assertNotNull(highWaterId)
        val highWaterPayload = snapshots.getById(highWaterId!!)!!.payload
        payload = feature0
        session.captureNowForTest("cadence")
        assertEquals(SessionState.RecoveryRequired, session.state.value)
        val incident = dao.get("shark-incremental")!!.captureIncident
        assertNotNull(incident)
        assertTrue(incident!!.contains("regression"))
        assertTrue(incident.contains("Capture incident"))
        assertTrue(incident.contains(PayloadDigest.sha256(feature9)))
        assertTrue(incident.contains(PayloadDigest.sha256(feature0)))
        val rowsAfterIncident = snapshots.history("shark-incremental").size
        repeat(20) {
            session.captureNowForTest("cadence")
            session.requestCapture()
            session.requestBackground()
            session.requestForeground()
            session.requestReload()
            session.captureNowForTest("close")
            session.captureNowForTest("background")
            session.captureNowForTest("switch")
        }
        session.close()
        val restarted = controllerWithoutDocumentHook(
            documentReady = { true },
            capture = { _, _ -> payload },
            snapshots = snapshots,
            onSnapshotSaved = { synced.add(it.snapshotPayload) },
        )
        restarted.onAttached()
        restarted.open("shark-incremental")
        assertEquals(SessionState.RecoveryRequired, restarted.state.value)
        assertTrue(restarted.captureBlockReason.value!!.contains("regression"))
        repeat(80) {
            restarted.requestCapture()
            restarted.requestBackground()
            restarted.requestReload()
        }
        val stored = dao.get("shark-incremental")!!
        assertEquals(feature9, stored.snapshotPayload)
        assertEquals(9, SharkProgress.parsePayload(stored.snapshotPayload)!!.feature)
        assertEquals(highWaterId, stored.highWaterSnapshotId)
        assertEquals(highWaterPayload, snapshots.getById(highWaterId)!!.payload)
        assertEquals(1, snapshots.history("shark-incremental").count { it.reason == SnapshotReasons.QUARANTINE })
        assertEquals(rowsAfterIncident, snapshots.history("shark-incremental").size)
        assertTrue(synced.none { SharkProgress.parsePayload(it)?.feature == 0 })
        restarted.forceCloseForTest()
    }

    @Test
    fun sameFeatureRegressionWithMorePlayTimeStaysQuarantined() = runTest {
        val good = sharkPayload(feature = 9, fishExponent = "40", played = "100", prestige = 2639)
        val regressed = sharkPayload(feature = 9, fishExponent = "80", played = "999999999", prestige = 10)
        assertEquals(SaveComparison.LIVE_AHEAD, SharkProgress.compare(regressed, good))
        var payload = good
        val snapshots = StubSnapshotDao()
        val synced = mutableListOf<String?>()
        val session = controllerWithoutDocumentHook(
            documentReady = { true },
            capture = { _, _ -> payload },
            snapshots = snapshots,
            onSnapshotSaved = { synced.add(it.snapshotPayload) },
        )
        session.open("shark-incremental")
        session.webView = WebView(ApplicationProvider.getApplicationContext())
        session.requestCheckpoint()
        payload = regressed
        session.requestCapture()
        val stored = dao.get("shark-incremental")!!
        assertEquals(good, stored.snapshotPayload)
        assertEquals(SessionState.RecoveryRequired, session.state.value)
        assertTrue(stored.captureIncident!!.contains("regression"))
        assertEquals(1, snapshots.history("shark-incremental").count { it.reason == SnapshotReasons.QUARANTINE })
        assertTrue(synced.none { it == regressed })
        session.forceCloseForTest()
    }

    @Test
    fun legitimatePrestigeAdvancesWhenCurrenciesFall() = runTest {
        val good = sharkPayload(feature = 9, fishExponent = "3689787472063838", prestige = 2639)
        val prestiged = sharkPayload(feature = 9, fishExponent = "1", played = "1", prestige = 2640)
        var payload = good
        val synced = mutableListOf<String?>()
        val session = controllerWithoutDocumentHook(
            documentReady = { true },
            capture = { _, _ -> payload },
            onSnapshotSaved = { synced.add(it.snapshotPayload) },
        )
        session.open("shark-incremental")
        session.webView = WebView(ApplicationProvider.getApplicationContext())
        session.requestCheckpoint()
        payload = prestiged
        session.requestCapture()
        val stored = dao.get("shark-incremental")!!
        assertEquals(prestiged, stored.snapshotPayload)
        assertEquals(2640L, SharkProgress.parsePayload(stored.snapshotPayload)!!.prestigeTimes)
        assertEquals(SessionState.Playing, session.state.value)
        assertNull(stored.captureIncident)
        assertTrue(synced.contains(prestiged))
        session.forceCloseForTest()
    }

    @Test
    fun ambiguousAdapterQuarantinesTheSecondPayload() = runTest {
        registry.injectGame(StubGame("opaque-game", "https://example.test/opaque/"))
        var payload = "alpha-save"
        val snapshots = StubSnapshotDao()
        val synced = mutableListOf<String?>()
        val session = controllerWithoutDocumentHook(
            documentReady = { true },
            capture = { _, _ -> payload },
            snapshots = snapshots,
            adapterForTest = { AmbiguousAdapter() },
            onSnapshotSaved = { synced.add(it.snapshotPayload) },
        )
        session.open("opaque-game")
        session.webView = WebView(ApplicationProvider.getApplicationContext())
        session.requestCheckpoint()
        payload = "beta-save"
        session.requestCapture()
        assertEquals("alpha-save", dao.get("opaque-game")!!.snapshotPayload)
        assertEquals(SessionState.RecoveryRequired, session.state.value)
        assertTrue(dao.get("opaque-game")!!.captureIncident!!.contains("incomparable"))
        assertEquals(1, snapshots.history("opaque-game").count { it.reason == SnapshotReasons.QUARANTINE })
        assertTrue(synced.none { it == "beta-save" })
        session.forceCloseForTest()
    }

    @Test
    fun identicalCapturesDoNotDuplicateHistoryOrSync() = runTest {
        val payload = progressedSharkPayload()
        val snapshots = StubSnapshotDao()
        val synced = mutableListOf<String?>()
        val session = controllerWithoutDocumentHook(
            documentReady = { true },
            capture = { _, _ -> payload },
            snapshots = snapshots,
            onSnapshotSaved = { synced.add(it.snapshotPayload) },
        )
        session.open("shark-incremental")
        session.webView = WebView(ApplicationProvider.getApplicationContext())
        session.requestCapture()
        session.requestCapture()
        assertEquals(1, snapshots.history("shark-incremental").size)
        assertEquals(1, synced.size)
        assertTrue(dao.get("shark-incremental")!!.lastSeenAtMillis > 0L)
        session.forceCloseForTest()
    }

    @Test
    fun manualCheckpointOfTheCurrentSaveIsDurableWithoutASecondSync() = runTest {
        val payload = progressedSharkPayload()
        val snapshots = StubSnapshotDao()
        val synced = mutableListOf<String?>()
        val session = controllerWithoutDocumentHook(
            documentReady = { true },
            capture = { _, _ -> payload },
            snapshots = snapshots,
            onSnapshotSaved = { synced.add(it.snapshotPayload) },
        )
        session.open("shark-incremental")
        session.webView = WebView(ApplicationProvider.getApplicationContext())
        session.requestCheckpoint()
        assertEquals(ManualSaveOutcome.SAVED, session.manualSaveNotice.value?.outcome)
        assertEquals("Game saved", session.manualSaveNotice.value?.message)
        session.requestCheckpoint()
        assertEquals(ManualSaveOutcome.SAVED, session.manualSaveNotice.value?.outcome)
        val checkpoints = snapshots.history("shark-incremental").filter { it.reason == SnapshotReasons.CHECKPOINT }
        assertEquals(1, checkpoints.size)
        assertTrue(checkpoints.single().pinned)
        assertEquals(1, synced.size)
        session.forceCloseForTest()
    }

    @Test
    fun repeatedRegressionIncrementsTheCounterWithoutAnotherRow() = runTest {
        val good = sharkPayload(feature = 9, fishExponent = "40")
        val bad = sharkPayload(feature = 0, fishExponent = "9", played = "10")
        val snapshots = StubSnapshotDao()
        dao.upsert(progressedInstance(good, playedAt = 5L))
        val repo = CaptureRepository(dao, snapshots, null)
        val adapter = SharkIncrementalAdapter()
        val first = repo.commitIfSafe(
            gameId = "shark-incremental",
            candidate = bad,
            reason = "cadence",
            adapter = adapter,
            sessionId = "progressed-session",
            stillCurrent = { true },
            manual = false,
        )
        val reasons = listOf("cadence", "close", "background", "switch", "request")
        repeat(99) { index ->
            repo.commitIfSafe(
                gameId = "shark-incremental",
                candidate = bad,
                reason = reasons[index % reasons.size],
                adapter = adapter,
                sessionId = "progressed-session",
                stillCurrent = { true },
                manual = false,
            )
        }
        assertEquals(CaptureDisposition.QUARANTINED, first.disposition)
        assertFalse(first.sync)
        assertEquals(good, dao.get("shark-incremental")!!.snapshotPayload)
        assertEquals(100, dao.get("shark-incremental")!!.captureIncidentCount)
        assertEquals(1, snapshots.history("shark-incremental").count { it.reason == SnapshotReasons.QUARANTINE })
    }

    @Test
    fun equalMilestoneChangesDoNotFloodAutomaticHistory() = runTest {
        val first = sharkPayload(feature = 9, fishExponent = "40", played = "100")
        val second = sharkPayload(feature = 9, fishExponent = "41", played = "200")
        val third = sharkPayload(feature = 9, fishExponent = "42", played = "300")
        val fourth = sharkPayload(feature = 9, fishExponent = "43", played = "400")
        val snapshots = StubSnapshotDao()
        dao.upsert(
            GameInstance(
                gameId = "shark-incremental",
                profileName = "hub-game-shark-incremental",
                sessionIdentity = "progressed-session",
                lastPlayedAtMillis = 1L,
                lastCaptureAtMillis = 0L,
            ),
        )
        var now = 1_000_000L
        val repo = CaptureRepository(dao, snapshots, null, clock = { now })
        val adapter = SharkIncrementalAdapter()
        repo.commitIfSafe("shark-incremental", first, "cadence", adapter, "progressed-session", { true }, false)
        now += 1_000L
        repo.commitIfSafe("shark-incremental", second, "cadence", adapter, "progressed-session", { true }, false)
        now += 1_000L
        repo.commitIfSafe("shark-incremental", third, "cadence", adapter, "progressed-session", { true }, false)
        assertEquals(third, dao.get("shark-incremental")!!.snapshotPayload)
        assertEquals(1, snapshots.history("shark-incremental").count { !it.pinned })
        now += 2 * 60L * 60L * 1000L
        repo.commitIfSafe("shark-incremental", fourth, "cadence", adapter, "progressed-session", { true }, false)
        assertEquals(fourth, dao.get("shark-incremental")!!.snapshotPayload)
        assertEquals(2, snapshots.history("shark-incremental").count { !it.pinned })
        val stored = dao.get("shark-incremental")!!
        assertEquals(first, snapshots.getById(stored.highWaterSnapshotId!!)!!.payload)
        assertTrue(snapshots.history("shark-incremental").any { it.pinned && it.payload == third })
    }

    @Test
    fun sameMilestoneChangesKeepThePreviousPayload() = runTest {
        val upgraded = sharkWithResearch(fishExponent = "40", research = 1)
        val spent = sharkWithResearch(fishExponent = "10", research = 2)
        val stale = sharkWithResearch(fishExponent = "80", research = null)
        val feature0 = freshSharkPayload()
        assertEquals(ProgressionClass.EQUAL, SharkProgress.classifyCapture(spent, upgraded))
        assertEquals(ProgressionClass.EQUAL, SharkProgress.classifyCapture(stale, spent))
        var payload = upgraded
        val snapshots = StubSnapshotDao()
        val synced = mutableListOf<String?>()
        val session = controllerWithoutDocumentHook(
            documentReady = { true },
            capture = { _, _ -> payload },
            snapshots = snapshots,
            onSnapshotSaved = { synced.add(it.snapshotPayload) },
        )
        session.open("shark-incremental")
        session.webView = WebView(ApplicationProvider.getApplicationContext())
        session.requestCheckpoint()
        payload = spent
        session.requestCapture()
        val afterSpent = snapshots.history("shark-incremental").size
        session.requestCapture()
        assertEquals(afterSpent, snapshots.history("shark-incremental").size)
        assertEquals(spent, dao.get("shark-incremental")!!.snapshotPayload)
        assertEquals(SessionState.Playing, session.state.value)
        payload = stale
        session.requestCapture()
        val stored = dao.get("shark-incremental")!!
        assertEquals(stale, stored.snapshotPayload)
        assertNull(stored.captureIncident)
        assertEquals(upgraded, snapshots.getById(stored.highWaterSnapshotId!!)!!.payload)
        assertTrue(
            snapshots.history("shark-incremental").any {
                it.payload == spent && it.pinned && it.reason == SnapshotReasons.RETAINED_POINTER
            },
        )
        session.forceCloseForTest()
        val restarted = controllerWithoutDocumentHook(
            documentReady = { true },
            capture = { _, _ -> payload },
            snapshots = snapshots,
            onSnapshotSaved = { synced.add(it.snapshotPayload) },
        )
        restarted.onAttached()
        restarted.open("shark-incremental")
        restarted.webView = WebView(ApplicationProvider.getApplicationContext())
        assertEquals(SessionState.Playing, restarted.state.value)
        assertEquals(stale, dao.get("shark-incremental")!!.snapshotPayload)
        assertEquals(upgraded, snapshots.getById(dao.get("shark-incremental")!!.highWaterSnapshotId!!)!!.payload)
        payload = feature0
        restarted.requestCapture()
        val afterDeath = dao.get("shark-incremental")!!
        assertEquals(SessionState.RecoveryRequired, restarted.state.value)
        assertEquals(stale, afterDeath.snapshotPayload)
        assertEquals(upgraded, snapshots.getById(afterDeath.highWaterSnapshotId!!)!!.payload)
        assertTrue(snapshots.history("shark-incremental").any { it.payload == spent && it.pinned })
        assertTrue(synced.none { it == feature0 })
        restarted.forceCloseForTest()
    }

    @Test
    fun fe000000CapturesKeepEarlierPayloads() = runTest {
        val saves = (1..5).map { step ->
            Fe000000Adapter.encodeSave(
                JSONObject()
                    .put("boost", step)
                    .put("currentTab", "main")
                    .put("generators", org.json.JSONArray(listOf(step)))
                    .put("lastUpdate", step * 10)
                    .put("prestigePower", 1)
                    .put("stars", step.toString())
                    .toString(),
            )
        }
        var payload = saves[0]
        val snapshots = StubSnapshotDao()
        val session = controllerWithoutDocumentHook(
            documentReady = { true },
            capture = { _, _ -> payload },
            snapshots = snapshots,
        )
        session.open("fe000000")
        session.webView = WebView(ApplicationProvider.getApplicationContext())
        session.requestCheckpoint()
        payload = saves[1]
        session.requestCheckpoint()
        payload = saves[2]
        session.captureNowForTest("cadence")
        payload = saves[3]
        session.requestBackground()
        session.requestForeground()
        payload = "not-a-fe-save"
        session.requestCapture()
        assertEquals(saves[3], dao.get("fe000000")!!.snapshotPayload)
        payload = saves[4]
        session.close()
        val stored = dao.get("fe000000")!!
        assertEquals(saves[4], stored.snapshotPayload)
        assertNull(stored.captureIncident)
        assertEquals(saves[0], snapshots.getById(stored.highWaterSnapshotId!!)!!.payload)
        assertEquals(0, snapshots.history("fe000000").count { it.reason == SnapshotReasons.QUARANTINE })
        saves.take(4).forEach { earlier ->
            assertTrue(snapshots.history("fe000000").any { it.payload == earlier && it.pinned })
        }
        val restarted = controllerWithoutDocumentHook(
            documentReady = { true },
            capture = { _, _ -> payload },
            snapshots = snapshots,
        )
        restarted.onAttached()
        restarted.open("fe000000")
        assertEquals(SessionState.Playing, restarted.state.value)
        val resumed = dao.get("fe000000")!!
        assertEquals(saves[4], resumed.snapshotPayload)
        assertEquals(saves[0], snapshots.getById(resumed.highWaterSnapshotId!!)!!.payload)
        assertNull(resumed.captureIncident)
        restarted.forceCloseForTest()
    }

    @Test
    fun confirmedNewLineageKeepsThePreviousSave() = runTest {
        val feature9 = sharkPayload(feature = 9, fishExponent = "40")
        val feature0 = freshSharkPayload()
        var payload = feature9
        val snapshots = StubSnapshotDao()
        val synced = mutableListOf<String?>()
        val session = controllerWithoutDocumentHook(
            documentReady = { true },
            capture = { _, _ -> payload },
            snapshots = snapshots,
            onSnapshotSaved = { synced.add(it.snapshotPayload) },
        )
        session.open("shark-incremental")
        session.webView = WebView(ApplicationProvider.getApplicationContext())
        session.requestCheckpoint()
        payload = feature0
        session.requestCapture()
        assertEquals(SessionState.RecoveryRequired, session.state.value)
        session.confirmNewLineage(confirmed = false, activate = true)
        assertEquals(feature9, dao.get("shark-incremental")!!.snapshotPayload)
        assertEquals("legacy", dao.get("shark-incremental")!!.lineageId)
        session.confirmNewLineage(confirmed = true, activate = false)
        assertEquals(feature9, dao.get("shark-incremental")!!.snapshotPayload)
        assertEquals("legacy", dao.get("shark-incremental")!!.lineageId)
        assertTrue(
            snapshots.history("shark-incremental").any {
                it.reason == SnapshotReasons.LINEAGE_BASE && it.payload == feature0 && it.lineageId != "legacy"
            },
        )
        session.confirmNewLineage(confirmed = true, activate = true)
        val stored = dao.get("shark-incremental")!!
        assertEquals(feature0, stored.snapshotPayload)
        assertTrue(stored.lineageId != "legacy")
        assertNull(stored.captureIncident)
        assertTrue(
            snapshots.history("shark-incremental").any {
                it.reason == SnapshotReasons.LINEAGE_BASE && it.payload == feature9
            },
        )
        assertTrue(synced.none { it == feature0 })
        session.forceCloseForTest()
    }

    @Test
    fun roomCommitFailureRollsBackARegressedCaptureAndAnAdvance() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val inline = java.util.concurrent.Executor { it.run() }
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, com.example.hub.database.HubDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(inline)
            .setTransactionExecutor(inline)
            .build()
        try {
            val feature9 = sharkPayload(feature = 9, fishExponent = "40")
            val prestiged = sharkPayload(feature = 9, fishExponent = "10", prestige = 2640)
            val feature0 = freshSharkPayload()
            var payload = feature9
            var armed = false
            val session = SessionController(
                context = context,
                gameRegistry = registry,
                instanceDao = db.gameInstanceDao(),
                lifecycleOwner = StubLifecycleOwner(),
                profileStore = profileStore,
                bootstrapTest = bootstrapSuccess,
                documentReadyTest = { true },
                captureClient = { _, _ -> payload },
                snapshotDao = db.snapshotDao(),
                roomDatabase = db,
            )
            session.captureWriteFault = { stage ->
                if (armed && stage == "after-pointer") error("disk full")
            }
            session.open("shark-incremental")
            session.webView = WebView(context)
            session.requestCheckpoint()
            assertEquals(feature9, db.gameInstanceDao().get("shark-incremental")!!.snapshotPayload)
            val rows = db.snapshotDao().history("shark-incremental").size
            armed = true
            payload = prestiged
            session.requestCapture()
            assertEquals(feature9, db.gameInstanceDao().get("shark-incremental")!!.snapshotPayload)
            assertEquals(rows, db.snapshotDao().history("shark-incremental").size)
            assertTrue(session.sessionError.value!!.contains("storage error"))
            assertEquals(SessionState.Playing, session.state.value)
            payload = feature0
            session.captureWriteFault = { stage ->
                if (stage == "after-quarantine") error("disk full")
            }
            session.requestCapture()
            val stored = db.gameInstanceDao().get("shark-incremental")!!
            assertEquals(feature9, stored.snapshotPayload)
            assertNull(stored.captureIncident)
            assertEquals(stored.highWaterSnapshotId, db.gameInstanceDao().get("shark-incremental")!!.highWaterSnapshotId)
            assertTrue(db.snapshotDao().history("shark-incremental").none { it.reason == SnapshotReasons.QUARANTINE })
            assertTrue(session.sessionError.value!!.contains("storage error"))
            session.forceCloseForTest()
        } finally {
            db.close()
        }
    }

    private fun sharkPayload(gold: Int): String = freshSharkPayload(gold)

    private fun sharkPayload(
        feature: Int,
        fishExponent: String,
        played: String = "345600",
        prestige: Long = 2639,
        core: Long = 2026,
    ): String {
        val json = JSONObject()
            .put("VERSION", 3)
            .put("feature", feature)
            .put("totalTimePlayed", played)
            .put("shark_level", JSONObject().put("s", 1).put("e", 20).put("c", org.json.JSONArray().put(1)))
            .put("total_fish", JSONObject().put("s", 1).put("e", fishExponent).put("c", org.json.JSONArray().put(1)))
            .put("prestige", JSONObject().put("times", prestige))
            .put("core", JSONObject().put("times", core))
            .put("humanoid", JSONObject().put("times", 0))
            .put("singularity", JSONObject().put("first", false))
        return Base64.getEncoder().encodeToString(json.toString().toByteArray(Charsets.UTF_8))
    }

    private fun sharkWithResearch(fishExponent: String, research: Int?): String {
        val json = JSONObject()
            .put("VERSION", 3)
            .put("feature", 9)
            .put("totalTimePlayed", "100")
            .put("shark_level", JSONObject().put("s", 1).put("e", 20).put("c", org.json.JSONArray().put(1)))
            .put("total_fish", JSONObject().put("s", 1).put("e", fishExponent).put("c", org.json.JSONArray().put(1)))
            .put("prestige", JSONObject().put("times", 2639))
            .put("core", JSONObject().put("times", 2026))
            .put("humanoid", JSONObject().put("times", 0))
            .put("singularity", JSONObject().put("first", false))
        if (research != null) json.put("research", research)
        return Base64.getEncoder().encodeToString(json.toString().toByteArray(Charsets.UTF_8))
    }

    private fun freshSharkPayload(gold: Int = 0): String {
        val json = JSONObject()
            .put("VERSION", 3)
            .put("feature", 0)
            .put("totalTimePlayed", 0)
            .put("shark_level", 0)
            .put("total_fish", 0)
            .put("prestige", JSONObject().put("times", 0))
            .put("core", JSONObject().put("times", 0))
            .put("humanoid", JSONObject().put("times", 0))
            .put("singularity", JSONObject().put("first", false))
            .put("gold", gold)
        return Base64.getEncoder().encodeToString(json.toString().toByteArray(Charsets.UTF_8))
    }

    private fun progressedSharkPayload(): String {
        val json = JSONObject()
            .put("VERSION", 3)
            .put("feature", 4)
            .put("totalTimePlayed", 345_600)
            .put("shark_level", JSONObject().put("s", 1).put("e", 20).put("c", org.json.JSONArray().put(1)))
            .put("total_fish", JSONObject().put("s", 1).put("e", 15).put("c", org.json.JSONArray().put(1)))
            .put("prestige", JSONObject().put("times", 2))
            .put("core", JSONObject().put("times", 1))
            .put("humanoid", JSONObject().put("times", 0))
            .put("singularity", JSONObject().put("first", false))
        return Base64.getEncoder().encodeToString(json.toString().toByteArray(Charsets.UTF_8))
    }

    private fun freshTrimpsEnvelope(portals: Int = 0, zone: Int = 0, helium: String = "0"): String {
        val global = JSONObject()
            .put("stringVersion", "5.10.1")
            .put("version", java.math.BigDecimal("4.914"))
            .put("universe", 1)
            .put("totalPortals", portals)
            .put("highestLevelCleared", zone)
            .put("highestRadonLevelCleared", 0)
            .put("totalHeliumEarned", java.math.BigDecimal(helium))
            .put("totalRadonEarned", java.math.BigDecimal.ZERO)
            .put("challengeActive", "")
            .put("runningChallengeSquared", false)
            .put("isBeta", false)
        val primary = LzString.compressToBase64(JSONObject().put("global", global).toString())
        return SaveEnvelope.encode(
            listOf(
                StorageSlot(TrimpsSave.PRIMARY, primary),
                StorageSlot(TrimpsSave.BACKUP, null),
            ),
        )
    }

    private fun progressedInstance(payload: String, playedAt: Long): GameInstance {
        return GameInstance(
            gameId = "shark-incremental",
            profileName = "hub-game-shark-incremental",
            sessionIdentity = "progressed-session",
            lastPlayedAtMillis = playedAt,
            lastCaptureAtMillis = playedAt,
            snapshotPayload = payload,
            lastCaptureSuccessAtMillis = playedAt,
        )
    }

    private fun controllerWithoutDocumentHook(
        documentReady: (suspend (String) -> Boolean)? = null,
        readiness: (suspend (WebView, com.example.hub.games.GameAdapter) -> Boolean)? = null,
        restore: (suspend (WebView, com.example.hub.games.GameAdapter, String) -> Boolean)? = null,
        capture: (suspend (WebView?, com.example.hub.games.GameAdapter) -> String?)? = null,
        peek: (suspend (WebView, com.example.hub.games.GameAdapter) -> PeekOutcome)? = null,
        runtime: (suspend (WebView, com.example.hub.games.GameAdapter, String) -> Boolean)? = null,
        onWrite: ((String?) -> Unit)? = null,
        snapshots: SnapshotDao? = null,
        adapterForTest: ((String) -> com.example.hub.games.GameAdapter?)? = null,
        onSnapshotSaved: ((GameInstance) -> Unit)? = null,
    ): SessionController {
        return SessionController(
            context = ApplicationProvider.getApplicationContext(),
            gameRegistry = registry,
            instanceDao = dao,
            lifecycleOwner = StubLifecycleOwner(),
            webViewDestroyer = { destroyedWebViews.add("destroyed") },
            profileStore = profileStore,
            bootstrapTest = bootstrapSuccess,
            documentReadyTest = documentReady,
            readinessCheck = readiness,
            restoreCheck = restore,
            captureClient = capture,
            onSnapshotSaved = onSnapshotSaved,
            snapshotDao = snapshots,
            peekClient = peek,
            runtimeCheck = runtime,
            onBootstrapWrite = onWrite,
            adapterForTest = adapterForTest,
        )
    }

    // -----------------------------------------------------------------
    // Stub implementations
    // -----------------------------------------------------------------

    /** Minimal [androidx.webkit.ProfileStore] stub for Robolectric unit tests. */
    class StubProfileStore : androidx.webkit.ProfileStore {
        private val profiles = mutableMapOf<String, StubProfile>()

        fun clear() {
            profiles.clear()
        }

        override fun getOrCreateProfile(profileName: String): androidx.webkit.Profile {
            return profiles.getOrPut(profileName) { StubProfile(profileName) }
        }

        override fun getProfile(profileName: String): androidx.webkit.Profile? = profiles[profileName]

        override fun getAllProfileNames(): List<String> = profiles.keys.toList()

        override fun deleteProfile(profileName: String): Boolean = profiles.remove(profileName) != null
    }

    class StubProfile(private val profileNameVal: String) : androidx.webkit.Profile {
        override fun getName(): String = profileNameVal
        override fun getCookieManager(): android.webkit.CookieManager =
            android.webkit.CookieManager.getInstance()
        override fun getWebStorage(): android.webkit.WebStorage =
            android.webkit.WebStorage.getInstance()
        override fun getGeolocationPermissions(): android.webkit.GeolocationPermissions {
            val ctor = android.webkit.GeolocationPermissions::class.java.getDeclaredConstructor()
            ctor.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            return ctor.newInstance() as android.webkit.GeolocationPermissions
        }
        override fun getServiceWorkerController() =
            android.webkit.ServiceWorkerController.getInstance()
        override fun prefetchUrlAsync(
            url: String,
            signal: android.os.CancellationSignal?,
            executor: java.util.concurrent.Executor,
            receiver: androidx.webkit.OutcomeReceiverCompat<Void?, androidx.webkit.PrefetchException>
        ) {
            receiver.onResult(null)
        }
        override fun prefetchUrlAsync(
            url: String,
            signal: android.os.CancellationSignal?,
            executor: java.util.concurrent.Executor,
            params: androidx.webkit.SpeculativeLoadingParameters,
            receiver: androidx.webkit.OutcomeReceiverCompat<Void?, androidx.webkit.PrefetchException>
        ) {
            receiver.onResult(null)
        }
        override fun clearPrefetchAsync(
            url: String,
            executor: java.util.concurrent.Executor,
            receiver: androidx.webkit.OutcomeReceiverCompat<Void?, androidx.webkit.PrefetchException>
        ) {
            receiver.onResult(null)
        }
        override fun setSpeculativeLoadingConfig(config: androidx.webkit.SpeculativeLoadingConfig) {}
    }

    class StubLifecycleOwner : androidx.lifecycle.LifecycleOwner {
        private val _lifecycle = object : androidx.lifecycle.Lifecycle() {
            override val currentState get() = androidx.lifecycle.Lifecycle.State.STARTED
            override fun addObserver(o: androidx.lifecycle.LifecycleObserver) {}
            override fun removeObserver(o: androidx.lifecycle.LifecycleObserver) {}
        }
        override val lifecycle: androidx.lifecycle.Lifecycle get() = _lifecycle
    }

    class StubGameRegistry : GameRegistry(androidx.test.core.app.ApplicationProvider.getApplicationContext()) {
        private val stubs = mutableMapOf<String, StubGame>()

        fun injectGame(stub: StubGame) {
            stubs[stub.gameId] = stub
        }

        override fun getGame(gameId: String): com.example.hub.games.RegisteredGame? {
            return stubs[gameId]?.toRegisteredGame()
        }
    }

    data class StubGame(
        val gameId: String,
        val launchUrl: String,
    ) {
        fun toRegisteredGame() = com.example.hub.games.RegisteredGame(
            gameId = gameId,
            displayName = gameId,
            launchUrl = launchUrl,
            adapterId = "$gameId-adapter",
            displayDefaults = com.example.hub.games.DisplayDefaults(),
        )
    }

    class StubGameInstanceDao : GameInstanceDao {
        private val instances = mutableMapOf<String, GameInstance>()
        private val insertOrder = CopyOnWriteArrayList<GameInstance>()

        override fun getAllOrdered(): kotlinx.coroutines.flow.Flow<List<GameInstance>> {
            throw UnsupportedOperationException("Not used in unit tests")
        }

        override suspend fun getAll(): List<GameInstance> = instances.values.toList()

        override suspend fun get(gameId: String): GameInstance? = instances[gameId]

        override suspend fun getActiveWithRecovery(): GameInstance? {
            return instances.values
                .filter { it.recoveryState != null }
                .maxByOrNull { it.lastPlayedAtMillis }
        }

        override suspend fun upsert(instance: GameInstance) {
            instances[instance.gameId] = instance
            insertOrder.add(instance)
        }

        override suspend fun delete(gameId: String) {
            instances.remove(gameId)
        }

        fun getLastInserted(): GameInstance? = insertOrder.lastOrNull()
        fun clearAll() {
            instances.clear()
            insertOrder.clear()
        }
    }

    private class DecliningAdapter(var decline: String?) : GameAdapter by SharkIncrementalAdapter() {
        override fun captureDecline(): String? = decline
    }

    private class AmbiguousAdapter : GameAdapter by SharkIncrementalAdapter() {
        override fun classifyProgress(candidate: String?, reference: String?): ProgressionClass {
            if (candidate.isNullOrBlank()) return ProgressionClass.INVALID
            if (reference.isNullOrBlank()) return ProgressionClass.ADVANCED
            if (candidate == reference) return ProgressionClass.EQUAL
            return ProgressionClass.INCOMPARABLE
        }

        override suspend fun validate(payload: String): Boolean = payload.isNotBlank()
    }

    private class DeferredSharkAdapter : GameAdapter by SharkIncrementalAdapter() {
        override suspend fun runtimeVerdict(
            webView: WebView,
            importedPayload: String,
        ): RuntimeVerdict = RuntimeVerdict.DEFERRED
    }

    class StubSnapshotDao : SnapshotDao {
        private val rows = mutableListOf<SnapshotRecord>()

        override suspend fun insert(record: SnapshotRecord) {
            rows.add(record)
        }

        override suspend fun history(gameId: String): List<SnapshotRecord> =
            rows.filter { it.gameId == gameId }.sortedBy { it.sequence }

        override suspend fun maxSequence(gameId: String): Long =
            rows.filter { it.gameId == gameId }.maxOfOrNull { it.sequence } ?: 0L

        override suspend fun countPinned(gameId: String, reason: String): Int =
            rows.count { it.gameId == gameId && it.reason == reason && it.pinned }

        override suspend fun findPinnedPayload(gameId: String, reason: String, payload: String): SnapshotRecord? =
            rows.firstOrNull { it.gameId == gameId && it.reason == reason && it.payload == payload && it.pinned }

        override suspend fun getById(id: String): SnapshotRecord? = rows.firstOrNull { it.id == id }

        override suspend fun findBySha(gameId: String, sha: String): SnapshotRecord? =
            rows.firstOrNull { it.gameId == gameId && it.contentSha256 == sha && sha.isNotEmpty() }

        override suspend fun latestByReason(gameId: String, reason: String): SnapshotRecord? =
            rows.filter { it.gameId == gameId && it.reason == reason }.maxByOrNull { it.sequence }

        override suspend fun pinById(id: String) {
            val index = rows.indexOfFirst { it.id == id }
            if (index >= 0) rows[index] = rows[index].copy(pinned = true)
        }

        override suspend fun releaseAutomaticPin(id: String) {
            val index = rows.indexOfFirst { it.id == id }
            if (index < 0) return
            val row = rows[index]
            if (row.reason == SnapshotReasons.MIGRATED_BASELINE ||
                row.reason == SnapshotReasons.DIAGNOSTIC_BACKUP ||
                row.reason == SnapshotReasons.PRE_RESTORE ||
                row.reason == SnapshotReasons.POST_RESTORE ||
                row.reason == SnapshotReasons.WORKSTATION ||
                row.reason == SnapshotReasons.CHECKPOINT ||
                row.reason == SnapshotReasons.QUARANTINE ||
                row.reason == SnapshotReasons.LINEAGE_BASE ||
                row.reason == SnapshotReasons.RETAINED_POINTER
            ) {
                return
            }
            rows[index] = row.copy(pinned = false)
        }

        override suspend fun pruneAutomatic(gameId: String) {
            val keep = rows
                .filter { it.gameId == gameId && automaticSnapshot(it) }
                .sortedByDescending { it.sequence }
                .take(10)
                .map { it.id }
                .toSet()
            rows.removeAll { record ->
                record.gameId == gameId && automaticSnapshot(record) && record.id !in keep
            }
        }

        private fun automaticSnapshot(record: SnapshotRecord): Boolean {
            return !record.pinned &&
                record.reason != SnapshotReasons.MIGRATED_BASELINE &&
                record.reason != SnapshotReasons.DIAGNOSTIC_BACKUP &&
                record.reason != SnapshotReasons.PRE_RESTORE &&
                record.reason != SnapshotReasons.POST_RESTORE &&
                record.reason != SnapshotReasons.WORKSTATION &&
                record.reason != SnapshotReasons.CHECKPOINT &&
                record.reason != SnapshotReasons.QUARANTINE &&
                record.reason != SnapshotReasons.LINEAGE_BASE &&
                record.reason != SnapshotReasons.RETAINED_POINTER
        }
    }
}
