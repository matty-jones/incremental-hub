package com.example.hub.games

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Fe000000Adapter format logic.
 * Tests marker stripping, gzip round-trip, and validate without a WebView.
 */
class Fe000000AdapterTest {

    private val adapter = Fe000000Adapter()

    @Test
    fun `live zlib save round-trip preserves required fields`() = runBlocking {
        val json = JSONObject()
            .put("boost", 4)
            .put("currentTab", "main")
            .put("generators", org.json.JSONArray(listOf(1, 2)))
            .put("lastUpdate", 99)
            .put("prestigePower", 8)
            .put("stars", "1.5e3")
            .toString()
        val wrapped = Fe000000Adapter.encodeSave(json)
        assertTrue(adapter.validate(wrapped))
        assertEquals(wrapped, adapter.prepareRestore(wrapped))
        val decoded = JSONObject(Fe000000Adapter.decodeSave(wrapped))
        assertEquals(4, decoded.getInt("boost"))
        assertEquals("1.5e3", decoded.getString("stars"))
    }

    @Test
    fun `python zlib fixture matches pako deflate alphabet`() = runBlocking {
        val fixture = "FE000000SaveStarteJwVyzEOQEAQBdC70cHoKS7enUFCJYjARCbubmRGFuDv6925MOZsjBsJ8qkryjidEHLwlEFZJouxZDXEII2Fn874s7PKdilBUzLdV2nyJItYEc0c41Gjwv2TAfBQFE000000SaveEnd"
        assertTrue(adapter.validate(fixture))
        val decoded = JSONObject(Fe000000Adapter.decodeSave(fixture))
        assertEquals(1, decoded.getInt("boost"))
        assertEquals("main", decoded.getString("currentTab"))
        assertEquals("3", decoded.getString("stars"))
    }

    @Test
    fun `scripts are invoked functions and quote payload characters`() {
        assertFalse(Fe000000Adapter.JS_PLAYER_READY.trimStart().startsWith("return"))
        assertFalse(Fe000000Adapter.JS_FLUSH_AND_READ.trimStart().startsWith("return"))
        assertTrue(Fe000000Adapter.JS_FLUSH_AND_READ.contains("Saving.saveGame(true, false)"))
        assertFalse(Fe000000Adapter.JS_PEEK_SAVE.contains("saveGame"))
        assertFalse(Fe000000Adapter.JS_RUNTIME_LAST_UPDATE.contains("saveGame"))
        assertTrue(Fe000000Adapter.JS_FLUSH_AND_READ.contains("fe000000-save"))
        val nasty = "a\"b\\c\n\u2028</script>"
        val script = Fe000000Adapter.setSaveScript(nasty)
        assertTrue(script.startsWith("(function()"))
        assertTrue(script.contains(JSONObject.quote(nasty)))
        assertFalse(script.contains("arguments[0]"))
        assertFalse(script.contains("\n"))
    }

    @Test
    fun `validate rejects malformed markers`() = runBlocking {
        val bad = "WRONGStartabcWRONGEnd"
        assertFalse("wrong markers should fail validation", adapter.validate(bad))
    }

    @Test
    fun `validate rejects payload that is not zlib deflate`() = runBlocking {
        val wrapped = "FE000000SaveStartnot-a-saveFE000000SaveEnd"
        assertFalse(adapter.validate(wrapped))
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
}
