package com.example.hub.ui

import com.example.hub.session.DocumentPhase
import com.example.hub.session.SessionState
import com.example.hub.session.StorageBootstrap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the WebView host rules that decide whether a view is created and
 * whether navigation is allowed. Navigation uses the registry launch URL.
 */
class WebViewHostTest {

    @Test
    fun profileMustBeBoundBeforeNavigation() {
        assertFalse(shouldNavigate(profileName = null, launchUrl = SHARK_URL))
        assertFalse(shouldNavigate(profileName = "hub-game-shark-incremental", launchUrl = null))
        assertFalse(shouldNavigate(profileName = "", launchUrl = SHARK_URL))
        assertTrue(shouldNavigate("hub-game-shark-incremental", SHARK_URL))
        assertTrue(shouldNavigate("hub-game-fe000000", FE_URL))
    }

    @Test
    fun navigationUsesRegistryLaunchUrl() {
        assertFalse(SHARK_URL.contains("hub.local"))
        assertFalse(FE_URL.contains("hub.local"))
        assertEquals("https://mrredshark77.github.io/shark-incremental/", SHARK_URL)
        assertEquals("https://dan-simon.github.io/misc/fe000000/", FE_URL)
    }

    @Test
    fun webViewIsCreatedForOpeningAndPlayingOnly() {
        assertTrue(shouldCreateWebView(SessionState.Opening))
        assertTrue(shouldCreateWebView(SessionState.Playing))
        assertTrue(shouldCreateWebView(SessionState.Capturing))
        assertTrue(shouldCreateWebView(SessionState.Background))
        assertFalse(shouldCreateWebView(SessionState.Closed))
        assertFalse(shouldCreateWebView(SessionState.Closing))
        assertFalse(shouldCreateWebView(SessionState.RecoveryRequired))
    }

    @Test
    fun bootstrapServesOnlyTheMainFrameBeforeTheGame() {
        val launch = "https://mrredshark77.github.io/shark-incremental/"
        assertTrue(StorageBootstrap.shouldServe(DocumentPhase.PEEK, launch, launch, mainFrame = true))
        assertFalse(StorageBootstrap.shouldServe(DocumentPhase.PEEK, launch, launch, mainFrame = false))
        assertFalse(StorageBootstrap.shouldServe(DocumentPhase.GAME, launch, launch, mainFrame = true))
        val page = StorageBootstrap.document(DocumentPhase.PEEK, "shark_inc_save", null)
        assertTrue(page.contains("__hubStorageBootstrap"))
        assertFalse(page.contains("shark-incremental/js"))
        val write = StorageBootstrap.document(DocumentPhase.WRITE, "shark_inc_save", "abc")
        assertTrue(write.contains("localStorage.setItem"))
        assertTrue(write.contains("abc"))
    }

    @Test
    fun mainFrameFlagIsTheSessionFailureGate() {
        assertTrue(isMainFrame(true))
        assertFalse(isMainFrame(false))
    }

    private fun isMainFrame(isForMainFrame: Boolean): Boolean = isForMainFrame

    companion object {
        private const val SHARK_URL = "https://mrredshark77.github.io/shark-incremental/"
        private const val FE_URL = "https://dan-simon.github.io/misc/fe000000/"
    }
}
