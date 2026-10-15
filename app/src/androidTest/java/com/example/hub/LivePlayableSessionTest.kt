package com.example.hub

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.hub.database.HubDatabase
import com.example.hub.games.Fe000000Adapter
import com.example.hub.games.SharkIncrementalAdapter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.InputStreamReader

/**
 * Device acceptance for the production launcher path.
 * Requires network so Shark Incremental and FE000000 can load.
 */
@RunWith(AndroidJUnit4::class)
class LivePlayableSessionTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun bothGamesOpenCaptureAndResumeFromRealUrls() {
        composeRule.onNodeWithText("https://mrredshark77.github.io/shark-incremental/", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("https://dan-simon.github.io/misc/fe000000/", substring = true)
            .assertIsDisplayed()

        play("shark-incremental", "https://mrredshark77.github.io/shark-incremental/") { payload ->
            runBlocking { SharkIncrementalAdapter().validate(payload) }
        }
        play("fe000000", "https://dan-simon.github.io/misc/fe000000/") { payload ->
            runBlocking { Fe000000Adapter().validate(payload) }
        }
    }

    private fun play(gameId: String, launchUrl: String, validate: (String) -> Boolean) {
        composeRule.onNodeWithTag("launch-$gameId").performClick()
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText(launchUrl, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(timeoutMillis = 90_000) {
            val opening = composeRule.onAllNodesWithText("Opening $gameId", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
            val recovery = composeRule.onAllNodesWithText("Session Recovery Required")
                .fetchSemanticsNodes().isNotEmpty()
            !opening || recovery
        }
        assertFalse(
            "recovery overlay for $gameId",
            composeRule.onAllNodesWithText("Session Recovery Required").fetchSemanticsNodes().isNotEmpty(),
        )

        composeRule.onNodeWithText("Menu").performClick()
        composeRule.onNodeWithText("Snapshot now").performScrollTo().performClick()

        val dao = HubDatabase.getInstance(
            InstrumentationRegistry.getInstrumentation().targetContext,
        ).gameInstanceDao()
        val payload = waitForSnapshot(dao, gameId)
        assertTrue("captured payload for $gameId failed validation", validate(payload))
        val saved = runBlocking { dao.get(gameId) }!!
        assertTrue(saved.lastCaptureSuccessAtMillis > 0)
        assertEquals(saved.lastCaptureSuccessAtMillis, saved.lastCaptureAtMillis)
        assertEquals(0L, saved.lastCaptureFailureAtMillis)
        assertEquals("hub-game-$gameId", saved.profileName)

        shell("logcat -c")
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        Thread.sleep(2_000)
        val backgroundLog = shell("logcat -d -s SessionController:D")
        assertTrue(
            "background capture missing for $gameId\n$backgroundLog",
            backgroundLog.contains("capture succeeded (background)"),
        )
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeRule.waitUntil(timeoutMillis = 20_000) {
            val sessionUi = composeRule.onAllNodesWithText("Menu").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Return to hub").fetchSemanticsNodes().isNotEmpty()
            val recovery = composeRule.onAllNodesWithText("Session Recovery Required")
                .fetchSemanticsNodes().isNotEmpty()
            sessionUi && !recovery
        }
        val afterBackground = runBlocking { dao.get(gameId) }!!
        assertTrue(
            "payload after background failed validation",
            validate(afterBackground.snapshotPayload ?: error("snapshot missing after background")),
        )

        if (composeRule.onAllNodesWithText("Return to hub").fetchSemanticsNodes().isEmpty()) {
            composeRule.onNodeWithText("Menu").performClick()
        }
        composeRule.onNodeWithText("Return to hub").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTag("resume-$gameId").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithTag("resume-$gameId").performClick()
        composeRule.waitUntil(timeoutMillis = 90_000) {
            composeRule.onAllNodesWithText("Menu").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText("Session Recovery Required").fetchSemanticsNodes().isEmpty()
        }
        val resumed = runBlocking { dao.get(gameId) }!!
        assertTrue(validate(resumed.snapshotPayload ?: error("snapshot missing after resume")))
        assertTrue(resumed.lastCaptureSuccessAtMillis >= saved.lastCaptureSuccessAtMillis)
        composeRule.onNodeWithText("Menu").performClick()
        composeRule.onNodeWithText("Return to hub").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTag("resume-$gameId").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForSnapshot(
        dao: com.example.hub.database.GameInstanceDao,
        gameId: String,
    ): String {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            val payload = runBlocking { dao.get(gameId)?.snapshotPayload }
            if (!payload.isNullOrBlank()) return payload
            Thread.sleep(500)
        }
        val error = runBlocking { dao.get(gameId)?.lastCaptureError }
        throw AssertionError("no validated snapshot for $gameId; last error=$error")
    }

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeShellCommand(command)
        return InputStreamReader(
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor),
        ).use { reader -> reader.readText() }
    }
}
