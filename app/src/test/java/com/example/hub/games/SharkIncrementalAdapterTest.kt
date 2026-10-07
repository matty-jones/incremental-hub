package com.example.hub.games

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Unit tests for SharkIncrementalAdapter format logic.
 * Tests decode/encode round-trip and VERSION gate without a WebView.
 */
class SharkIncrementalAdapterTest {

    private val adapter = SharkIncrementalAdapter()

    @Test
    fun `capture payload round-trip preserves data`() = runBlocking {
        val decimalVal = JSONObject().apply {
            put("s", 1)
            put("e", 2)
            put("c", JSONArray(listOf(3)))
        }
        val player = JSONObject().apply {
            put("VERSION", 3)
            put("money", 1500)
            put("saveSlot", 1)
            put("decimalVal", decimalVal)
        }
        val raw = Base64.getEncoder().encodeToString(player.toString().toByteArray(Charsets.UTF_8))

        val validated = adapter.validate(raw)
        assertTrue("VERSION 3 should pass validation", validated)

        val restored = adapter.prepareRestore(raw)
        val decoded = String(Base64.getDecoder().decode(restored), Charsets.UTF_8)
        val restoredJson = JSONObject(decoded)
        assertEquals(3, restoredJson.getInt("VERSION"))
        assertEquals(1500, restoredJson.getInt("money"))
        assertTrue("Decimal object preserved as opaque JSON", restoredJson.has("decimalVal"))
    }

    @Test
    fun `validate rejects version below minimum`() = runBlocking {
        val player = JSONObject().apply {
            put("VERSION", 2)
            put("money", 0)
        }
        val raw = Base64.getEncoder().encodeToString(player.toString().toByteArray(Charsets.UTF_8))
        assertFalse("VERSION 2 should fail validation", adapter.validate(raw))
    }

    @Test
    fun `prepareRestore re-serializes to canonical JSON`() = runBlocking {
        val player = JSONObject().apply {
            put("VERSION", 5)
            put("money", 999)
        }
        val raw = Base64.getEncoder().encodeToString(player.toString().toByteArray(Charsets.UTF_8))
        val restored = adapter.prepareRestore(raw)
        val decoded = String(Base64.getDecoder().decode(restored), Charsets.UTF_8)
        assertTrue("restored JSON must be valid", JSONObject(decoded).length() > 0)
        assertEquals(5, JSONObject(decoded).getInt("VERSION"))
    }

    @Test
    fun `prepareRestore handles malformed input gracefully`() = runBlocking {
        val malformed = "not-base64!!!"
        val result = adapter.prepareRestore(malformed)
        assertEquals("malformed input should passthrough", malformed, result)
    }
}
