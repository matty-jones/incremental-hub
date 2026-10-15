package com.example.hub.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.example.hub.ui.chrome.hubAccentColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HubThemeTest {

    @Test
    fun defaultSchemeIsDark() {
        val scheme = hubColorScheme()
        assertTrue(scheme.background.luminance() < 0.2f)
        assertTrue(scheme.onBackground.luminance() > 0.7f)
        assertTrue(scheme.background.luminance() < scheme.onBackground.luminance())
        assertEquals(Color(0xFF0E1116).toArgb(), scheme.background.toArgb())
        assertEquals(Color(0xFFE6B450).toArgb(), scheme.primary.toArgb())
    }

    @Test
    fun lightSchemeStaysAvailable() {
        val scheme = hubColorScheme(darkTheme = false)
        assertTrue(scheme.background.luminance() > scheme.onBackground.luminance())
    }

    @Test
    fun hubAccentColorAcceptsRgbHex() {
        val color = hubAccentColor("#1565C0")
        assertEquals(Color(0xFF1565C0).toArgb(), color!!.toArgb())
    }

    @Test
    fun hubAccentColorRejectsJunk() {
        assertNull(hubAccentColor(null))
        assertNull(hubAccentColor(""))
        assertNull(hubAccentColor("nope"))
        assertNull(hubAccentColor("#GGGGGG"))
        assertNull(hubAccentColor("#1565C"))
    }
}
