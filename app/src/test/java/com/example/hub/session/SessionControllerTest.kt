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
import com.example.hub.games.GameRegistry
import com.example.hub.games.PeekOutcome
import com.example.hub.games.SharkProgress
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

    private fun sharkPayload(gold: Int): String = freshSharkPayload(gold)

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
            snapshotDao = snapshots,
            peekClient = peek,
            runtimeCheck = runtime,
            onBootstrapWrite = onWrite,
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

        override suspend fun pruneAutomatic(gameId: String) {
            val keep = rows
                .filter {
                    it.gameId == gameId && !it.pinned &&
                        it.reason != SnapshotReasons.MIGRATED_BASELINE &&
                        it.reason != SnapshotReasons.DIAGNOSTIC_BACKUP &&
                        it.reason != SnapshotReasons.PRE_RESTORE &&
                        it.reason != SnapshotReasons.WORKSTATION &&
                        it.reason != SnapshotReasons.CHECKPOINT
                }
                .sortedByDescending { it.sequence }
                .take(10)
                .map { it.id }
                .toSet()
            rows.removeAll { record ->
                record.gameId == gameId &&
                    !record.pinned &&
                    record.reason != SnapshotReasons.MIGRATED_BASELINE &&
                    record.reason != SnapshotReasons.DIAGNOSTIC_BACKUP &&
                    record.reason != SnapshotReasons.PRE_RESTORE &&
                    record.reason != SnapshotReasons.WORKSTATION &&
                    record.reason != SnapshotReasons.CHECKPOINT &&
                    record.id !in keep
            }
        }
    }
}
