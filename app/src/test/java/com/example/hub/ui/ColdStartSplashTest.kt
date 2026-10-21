package com.example.hub.ui

import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ColdStartSplashTest {

    @After
    fun resetProcessGate() {
        ColdStartSplash.resetForTest()
    }

    @Test
    fun introArmsOncePerProcess() {
        assertTrue(ColdStartSplash.arm())
        assertFalse(ColdStartSplash.isFinished)
        assertFalse(ColdStartSplash.arm())
        ColdStartSplash.finish()
        assertTrue(ColdStartSplash.isFinished)
        assertFalse(ColdStartSplash.arm())
    }

    @Test
    fun introTimingStaysTunableInOnePlace() {
        assertEquals(600L, ColdStartSplash.HOLD_MS)
        assertEquals(180, ColdStartSplash.FADE_MS)
    }

    @Test
    fun splashArtMatchesApprovedMasterBytes() {
        val bytes = resourceFile("drawable-nodpi/ihub_splash_art.png").readBytes()
        assertEquals(
            "d15d96a2eb9272cceb7cf11ea7a398760c9f053e2e9167cc6ae18369fb9f4d54",
            sha256(bytes),
        )
        assertEquals(853 to 1844, pngSize(bytes))
    }

    @Test
    fun adaptiveIconUsesLayersAndMonochrome() {
        val icon = resourceFile("mipmap-anydpi-v26/ic_launcher.xml").readText()
        val round = resourceFile("mipmap-anydpi-v26/ic_launcher_round.xml").readText()
        assertEquals(icon, round)
        assertTrue(icon.contains("@drawable/ic_launcher_background"))
        assertTrue(icon.contains("@drawable/ic_launcher_foreground"))
        assertTrue(icon.contains("@drawable/ic_launcher_monochrome"))
        assertTrue(resourceFile("drawable/ic_launcher_monochrome.xml").readText().contains("android:pathData"))
    }

    @Test
    fun legacyAndLayerIconsKeepApprovedPixelSizes() {
        val legacy = mapOf(
            "mipmap-mdpi" to 48,
            "mipmap-hdpi" to 72,
            "mipmap-xhdpi" to 96,
            "mipmap-xxhdpi" to 144,
            "mipmap-xxxhdpi" to 192,
        )
        legacy.forEach { (dir, pixels) ->
            val folder = resourceFile(dir)
            assertTrue(File(folder, "ic_launcher.png").isFile)
            assertTrue(File(folder, "ic_launcher_round.png").isFile)
            assertFalse(File(folder, "ic_launcher.xml").exists())
            assertFalse(File(folder, "ic_launcher_round.xml").exists())
            assertEquals(pixels to pixels, pngSize(File(folder, "ic_launcher.png").readBytes()))
            assertEquals(pixels to pixels, pngSize(File(folder, "ic_launcher_round.png").readBytes()))
        }
        assertEquals(432 to 432, pngSize(resourceFile("drawable-nodpi/ic_launcher_foreground.png").readBytes()))
        assertEquals(432 to 432, pngSize(resourceFile("drawable-nodpi/ic_launcher_background.png").readBytes()))
    }

    private fun resourceFile(relative: String): File {
        val candidates = listOf(
            File("src/main/res/$relative"),
            File("app/src/main/res/$relative"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("missing $relative from ${File(".").absolutePath}")
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun pngSize(bytes: ByteArray): Pair<Int, Int> {
        assertTrue(bytes.size > 24)
        assertEquals("IHDR", String(bytes, 12, 4, Charsets.US_ASCII))
        val width = ByteBuffer.wrap(bytes, 16, 4).int
        val height = ByteBuffer.wrap(bytes, 20, 4).int
        return width to height
    }
}
