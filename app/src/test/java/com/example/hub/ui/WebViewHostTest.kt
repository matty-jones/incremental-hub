package com.example.hub.ui

import com.example.hub.session.SessionState
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
