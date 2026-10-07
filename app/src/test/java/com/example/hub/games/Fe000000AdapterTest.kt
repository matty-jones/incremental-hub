package com.example.hub.games

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream

/**
 * Unit tests for Fe000000Adapter format logic.
 * Tests marker stripping, gzip round-trip, and validate without a WebView.
 */
class Fe000000AdapterTest {

    private val adapter = Fe000000Adapter()

    @Test
    fun `capture payload round-trip preserves data`() = runBlocking {
        val saveData = JSONObject().apply {
            put("player", JSONObject().apply {
                put("money", 5000)
                put("level", 12)
            })
            put("version", "2.1.0")
        }
        val jsonStr = saveData.toString()
        val compressed = compressGzip(jsonStr)
        val b64 = Base64.getEncoder().encodeToString(compressed)
        val wrapped = "FE000000SaveStart" + b64 + "FE000000SaveEnd"

        val validated = adapter.validate(wrapped)
        assertTrue("valid gzip-base64 payload should pass validation", validated)

        val restored = adapter.prepareRestore(wrapped)
        val inner = stripMarkers(restored)
        assertNotNull("markers should be stripped", inner)
        val decompressed = decompressGzip(Base64.getDecoder().decode(inner!!))
        val restoredJson = JSONObject(String(decompressed, Charsets.UTF_8))
        assertEquals(5000, restoredJson.getJSONObject("player").getInt("money"))
        assertEquals(12, restoredJson.getJSONObject("player").getInt("level"))
    }

    @Test
    fun `validate rejects malformed markers`() = runBlocking {
        val bad = "WRONGStartabcWRONGEnd"
        assertFalse("wrong markers should fail validation", adapter.validate(bad))
    }

    @Test
    fun `validate rejects invalid gzip content`() = runBlocking {
        val b64 = Base64.getEncoder().encodeToString("not-gzip".toByteArray(Charsets.UTF_8))
        val wrapped = "FE000000SaveStart" + b64 + "FE000000SaveEnd"
        assertFalse("invalid gzip should fail validation", adapter.validate(wrapped))
    }

    @Test
    fun `prepareRestore handles malformed input gracefully`() = runBlocking {
        val malformed = "random-garbage"
        val result = adapter.prepareRestore(malformed)
        assertEquals("malformed input should passthrough", malformed, result)
    }

    @Test
    fun `marker stripping is precise via substring`() {
        val payload = "FE000000SaveStartabcFE000000SaveEnd"
        val stripped = stripMarkers(payload)
        assertEquals("abc", stripped)
    }

    private fun stripMarkers(input: String): String? {
        val startIdx = input.indexOf("FE000000SaveStart")
        if (startIdx < 0) return null
        val afterStart = input.substring(startIdx + "FE000000SaveStart".length)
        val endIdx = afterStart.indexOf("FE000000SaveEnd")
        if (endIdx < 0) return null
        return afterStart.substring(0, endIdx)
    }

    private fun compressGzip(data: String): ByteArray {
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gos ->
            gos.write(data.toByteArray(Charsets.UTF_8))
        }
        return baos.toByteArray()
    }

    private fun decompressGzip(data: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        java.util.zip.GZIPInputStream(ByteArrayInputStream(data)).use { gis ->
            gis.copyTo(baos)
        }
        return baos.toByteArray()
    }
}
