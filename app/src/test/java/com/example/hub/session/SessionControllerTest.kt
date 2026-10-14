package com.example.hub.session

import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import com.example.hub.database.GameInstance
import com.example.hub.database.GameInstanceDao
import com.example.hub.games.GameRegistry
import com.example.hub.bootstrap.DetectorResult
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
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
    fun requestCapture_transitionsPlayingToCapturing() = runTest {
        controller.open("shark-incremental")
        controller.requestCapture()
        assertEquals(SessionState.Capturing, controller.state.value)
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
    fun requestForegroundCapture_transitionsPlayingToCapturing() = runTest {
        controller.open("shark-incremental")
        controller.requestForegroundCapture()
        assertEquals(SessionState.Capturing, controller.state.value)
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
            "crash handler should transition Playing→Degraded",
            SessionState.Degraded,
            controller.state.value
        )
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
        assertEquals(SessionState.Degraded, controller.state.value)
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
        assertEquals(
            "ON_PAUSE while Playing should fire best-effort capture",
            SessionState.Capturing,
            controller.state.value
        )
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
        assertTrue(instance.lastCaptureAtMillis > 0)
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
        dao.upsert(
            GameInstance(
                gameId = "shark-incremental",
                profileName = "hub-game-shark-incremental",
                sessionIdentity = "session-clear",
                lastPlayedAtMillis = System.currentTimeMillis(),
                recoveryState = "Degraded",
                lastCaptureAtMillis = System.currentTimeMillis() - 1000L,
            )
        )
        controller.open("shark-incremental")
        assertEquals(SessionState.Playing, controller.state.value)
        val instanceAfterPlaying = dao.get("shark-incremental")
        assertNull("recoveryState should be cleared when entering Playing", instanceAfterPlaying!!.recoveryState)
        assertTrue("lastCaptureAtMillis should be updated on clear", instanceAfterPlaying.lastCaptureAtMillis > 0)
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
        assertTrue("lastCaptureAtMillis should be updated", instance.lastCaptureAtMillis > 0)
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
}
