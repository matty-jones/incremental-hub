package com.example.hub

import android.content.Context
import com.example.hub.games.SharkLayout
import com.example.hub.session.Continuity
import com.example.hub.session.DiagnosticSeverity
import com.example.hub.session.DisplayContinuity
import com.example.hub.session.classifyResourceFailure
import com.example.hub.session.continuityFor
import com.example.hub.sync.MergeDecision
import com.example.hub.sync.replacesStoredPointer
import com.example.hub.ui.DisplayPreferenceStore
import com.example.hub.ui.PageScaleMode
import com.example.hub.ui.GameDisplayPreferences
import com.example.hub.ui.ZoomPreference
import com.example.hub.ui.clampZoom
import com.example.hub.ui.hasPlayHistory
import com.example.hub.ui.isFoldedPosture
import com.example.hub.ui.isFoldedWidth
import com.example.hub.ui.pageZoomScript
import com.example.hub.ui.primaryPlayLabel
import com.example.hub.ui.stepZoom
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class GameplayReliabilityTest {

    @Test
    fun faviconIsInformationalAndMainFrameIsFatal() {
        assertEquals(
            DiagnosticSeverity.INFO,
            classifyResourceFailure("https://mrredshark77.github.io/favicon.ico", 404, mainFrame = false),
        )
        assertEquals(
            DiagnosticSeverity.WARNING,
            classifyResourceFailure("https://mrredshark77.github.io/shark-incremental/js/main.js", 404, mainFrame = false),
        )
        assertEquals(
            DiagnosticSeverity.FATAL,
            classifyResourceFailure("https://mrredshark77.github.io/shark-incremental/", 500, mainFrame = true),
        )
    }

    @Test
    fun foldDoesNotRequestNavigation() {
        assertFalse(DisplayContinuity.requestsNavigation())
        assertEquals(Continuity.LIVE_VIEW_RESIZED, continuityFor(false, false, false))
        assertEquals(Continuity.RETAINED_ACROSS_RECREATION, continuityFor(true, true, false))
        assertEquals(Continuity.RESTORED_FROM_CAPTURE, continuityFor(true, false, true))
        assertEquals(Continuity.UNSAVED_PROGRESS_LOST, continuityFor(true, false, false))
    }

    @Test
    fun onePrimaryActionChangesOnlyItsLabel() {
        assertFalse(hasPlayHistory(lastPlayedAtMillis = null, snapshotPresent = false))
        assertTrue(hasPlayHistory(lastPlayedAtMillis = 10L, snapshotPresent = false))
        assertTrue(hasPlayHistory(lastPlayedAtMillis = null, snapshotPresent = true))
        assertEquals("Play", primaryPlayLabel(hasHistory = false))
        assertEquals("Resume", primaryPlayLabel(hasHistory = true))
    }

    @Test
    fun foldedAndUnfoldedZoomPersistSeparately() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = DisplayPreferenceStore(context)
        val written = GameDisplayPreferences(
            folded = ZoomPreference(textZoomPercent = 80, pageZoomPercent = 90),
            unfolded = ZoomPreference(textZoomPercent = 110, pageZoomPercent = 100),
        )
        store.write("shark-incremental", written)
        val read = store.read("shark-incremental")
        assertEquals(80, read.folded.textZoomPercent)
        assertEquals(90, read.folded.pageZoomPercent)
        assertEquals(110, read.unfolded.textZoomPercent)
        assertEquals(100, read.unfolded.pageZoomPercent)
        assertTrue(isFoldedWidth(400f))
        assertFalse(isFoldedWidth(700f))
        assertTrue(isFoldedPosture(411))
        assertTrue(isFoldedPosture(599))
        assertFalse(isFoldedPosture(600))
        assertFalse(isFoldedPosture(701))
        assertEquals(75, clampZoom(10))
        assertEquals(125, clampZoom(400))
        assertEquals(100, stepZoom(100, 0))
        assertEquals(125, stepZoom(125, 10))
        val script = pageZoomScript(110)
        assertTrue(script.contains("1.10"))
        assertFalse(script.contains("loadUrl"))
        assertFalse(script.contains("save("))
        assertFalse(script.contains("localStorage"))
        assertEquals(PageScaleMode.READABLE, store.read("fe000000").pageScaleMode)
        assertEquals(PageScaleMode.HUB, store.read("shark-incremental").pageScaleMode)
        store.write("fe000000", written.copy(pageScaleMode = PageScaleMode.GAME_FIT))
        assertEquals(PageScaleMode.GAME_FIT, store.read("fe000000").pageScaleMode)
        store.write("shark-incremental", written.copy(pageScaleMode = PageScaleMode.READABLE))
        assertEquals(PageScaleMode.HUB, store.read("shark-incremental").pageScaleMode)
    }

    @Test
    fun legacyDisplayPrefsUseTheGameDefaultMode() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("hub_display_preferences", Context.MODE_PRIVATE)
            .edit()
            .putString(
                "fe000000",
                """{"folded":{"text":90,"page":80},"unfolded":{"text":100,"page":110}}""",
            )
            .commit()
        val read = DisplayPreferenceStore(context).read("fe000000")
        assertEquals(90, read.folded.textZoomPercent)
        assertEquals(80, read.folded.pageZoomPercent)
        assertEquals(110, read.unfolded.pageZoomPercent)
        assertEquals(PageScaleMode.READABLE, read.pageScaleMode)
    }

    @Test
    fun sharkLayoutOverrideDoesNotTouchTheSave() {
        val script = SharkLayout.injectionScript()
        assertTrue(script.contains(SharkLayout.STYLE_ID))
        assertTrue(script.contains("shark-upgrade-div"))
        assertEquals(4, SharkLayout.VERSION)
        assertTrue(script.contains(".explore-desc"))
        assertTrue(script.contains("flex-flow: column nowrap"))
        assertTrue(script.contains(".explore-cost"))
        assertTrue(script.contains("min-width: 0"))
        assertTrue(script.contains("position: fixed"))
        assertTrue(script.contains("offline-done"))
        assertTrue(script.contains("#offline"))
        assertFalse(script.contains("save("))
        assertFalse(script.contains("localStorage"))
        assertFalse(script.contains("location.reload"))
    }

    @Test
    fun openSessionRejectsARegressedWorkstationSave() {
        assertFalse(replacesStoredPointer(sessionOpen = true, decision = MergeDecision.Pull))
        assertFalse(replacesStoredPointer(sessionOpen = false, decision = MergeDecision.Push))
        assertTrue(replacesStoredPointer(sessionOpen = false, decision = MergeDecision.Pull))
    }
}
