package com.example.hub.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class SiteIconTest {

    @Test
    fun sharkPageResolvesItsDeclaredPng() {
        val html = """<link rel="icon" href="textures/shark.png">"""
        assertEquals(
            listOf("https://mrredshark77.github.io/shark-incremental/textures/shark.png"),
            siteIconUrls(html, "https://mrredshark77.github.io/shark-incremental/"),
        )
    }

    @Test
    fun shortcutIconResolvesBesideThePage() {
        val html = """<link rel="shortcut icon" type="image/x-icon" href="favicon.ico">"""
        assertEquals(
            listOf("https://dan-simon.github.io/misc/fe000000/favicon.ico"),
            siteIconUrls(html, "https://dan-simon.github.io/misc/fe000000/"),
        )
    }

    @Test
    fun standardIconsOutrankAppleTouchAndSkipSvg() {
        val html = """
            <link rel="apple-touch-icon" href="apple.png" sizes="180x180">
            <link rel="icon" href="small.png" sizes="16x16">
            <link rel="icon" href="big.png" sizes="64x64">
            <link rel="mask-icon" href="mask.svg">
            <link href="vector.svg" rel="icon" type="image/svg+xml">
        """.trimIndent()
        assertEquals(
            listOf(
                "https://games.example/play/big.png",
                "https://games.example/play/small.png",
                "https://games.example/play/apple.png",
            ),
            siteIconUrls(html, "https://games.example/play/"),
        )
    }

    @Test
    fun quotedAttributesAndRootAbsoluteHrefsResolve() {
        val html = """<link href='/static/favicon.png' rel='icon'>"""
        assertEquals(
            listOf("https://games.example/static/favicon.png"),
            siteIconUrls(html, "https://games.example/play/"),
        )
    }

    @Test
    fun crossOriginIconsFallBackToSameHostFavicons() {
        val html = """<link rel="icon" href="https://cdn.example/icon.png">"""
        assertEquals(
            listOf(
                "https://games.example/play/favicon.ico",
                "https://games.example/favicon.ico",
            ),
            siteIconUrls(html, "https://games.example/play/"),
        )
    }

    @Test
    fun missingIconLinkUsesPageThenOriginFavicon() {
        assertEquals(
            listOf(
                "https://games.example/play/favicon.ico",
                "https://games.example/favicon.ico",
            ),
            siteIconUrls("<html></html>", "https://games.example/play/"),
        )
        assertEquals(
            listOf("https://games.example/favicon.ico"),
            siteIconUrls("", "https://games.example/"),
        )
    }

    @Test
    fun encodedIconPathsStayEncodedOnce() {
        val html = """<link rel="icon" href="icons/my%20icon.png">"""
        assertEquals(
            listOf("https://games.example/play/icons/my%20icon.png"),
            siteIconUrls(html, "https://games.example/play/"),
        )
    }

    @Test
    fun insecurePagesYieldNoIcon() {
        assertEquals(
            emptyList<String>(),
            siteIconUrls("""<link rel="icon" href="icon.png">""", "http://games.example/play/"),
        )
    }

    @Test
    fun icoUsesTheLargestEmbeddedPng() {
        val small = pngMarker(1)
        val large = pngMarker(2)
        val bmp = byteArrayOf(0x28, 0, 0, 0, 0, 0, 0, 0)
        val payload = imagePayload(ico(listOf(16 to small, 24 to bmp, 32 to large)))
        assertArrayEquals(large, payload)
        assertNull(imagePayload("<!DOCTYPE html>".toByteArray()))
        assertNotNull(imagePayload(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0, 0, 0, 0, 0)))
    }

    @Test
    fun embeddedPngDecodesFromIco() {
        val png = Base64.getDecoder().decode(ONE_PIXEL_PNG)
        val bitmap = decodeSiteIcon(ico(listOf(16 to png)))
        assertNotNull(bitmap)
        assertEquals(1, bitmap!!.width)
        assertEquals(1, bitmap.height)
    }

    private fun pngMarker(mark: Byte): ByteArray = byteArrayOf(
        0x89.toByte(),
        0x50,
        0x4E,
        0x47,
        0x0D,
        0x0A,
        0x1A,
        0x0A,
        mark,
    )

    private fun ico(entries: List<Pair<Int, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0)
        out.write(0)
        out.write(1)
        out.write(0)
        out.write(entries.size)
        out.write(0)
        var offset = 6 + entries.size * 16
        for ((size, png) in entries) {
            out.write(size)
            out.write(size)
            out.write(0)
            out.write(0)
            writeLe(out, 1, 2)
            writeLe(out, 32, 2)
            writeLe(out, png.size, 4)
            writeLe(out, offset, 4)
            offset += png.size
        }
        entries.forEach { (_, png) -> out.write(png) }
        return out.toByteArray()
    }

    private fun writeLe(out: ByteArrayOutputStream, value: Int, width: Int) {
        var remaining = value
        repeat(width) {
            out.write(remaining and 0xFF)
            remaining = remaining ushr 8
        }
    }

    private companion object {
        const val ONE_PIXEL_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    }
}
