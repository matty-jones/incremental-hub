package com.example.hub.games

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SharkProgressTest {

    @Test
    fun freshVersionThreeSaveIsNotProgress() {
        val summary = SharkProgress.summarize(encode(fresh()))
        assertEquals(SaveStanding.FRESH, summary.standing)
        assertTrue(SharkProgress.parsePayload(encode(fresh()))!!.fresh)
    }

    @Test
    fun progressedSaveRanksAheadOfFreshSave() {
        val freshPayload = encode(fresh())
        val progressedPayload = encode(progressed())
        assertEquals(SaveComparison.LIVE_BEHIND, SharkProgress.compare(freshPayload, progressedPayload))
        assertEquals(SaveComparison.LIVE_AHEAD, SharkProgress.compare(progressedPayload, freshPayload))
        assertEquals(SaveComparison.MATCH, SharkProgress.compare(progressedPayload, progressedPayload))
        assertEquals(progressedPayload, SharkProgress.chooseStored(listOf(freshPayload, progressedPayload)))
    }

    @Test
    fun runtimeMustKeepProgressMarkers() {
        val snapshot = SharkProgress.parsePayload(encode(progressed()))!!
        val same = SharkProgress.parsePayload(encode(progressed()))!!
        val fresh = SharkProgress.parsePayload(encode(fresh()))!!
        assertTrue(SharkProgress.acceptsRuntime(snapshot, same))
        assertFalse(SharkProgress.acceptsRuntime(snapshot, fresh))
    }

    @Test
    fun decimalRankIgnoresCoefficientAndKeepsSign() {
        val high = SharkProgress.parseDecimal(JSONObject().put("s", 1).put("e", 20))!!
        val higher = SharkProgress.parseDecimal(JSONObject().put("s", 1).put("e", 21))!!
        val negative = SharkProgress.parseDecimal(JSONObject().put("s", -1).put("e", 3))!!
        val zero = SharkProgress.parseDecimal(0)!!
        assertTrue(higher > high)
        assertTrue(negative < zero)
        assertEquals(0, zero.sign)
    }

    @Test
    fun giganticScientificExponentsRemainValidAndWinRecoverySelection() {
        val saved = progressed()
            .put("feature", 9)
            .put("shark_level", "2126")
            .put("total_fish", "1e3689787472063838")
            .put("totalTimePlayed", 1159470.6309996273)
        val regressed = progressed()
            .put("feature", 0)
            .put("shark_level", "15")
            .put("total_fish", "1.9450546477309658e27")
            .put("totalTimePlayed", 1188149.1980000373)
            .put("prestige", JSONObject().put("times", 0))
            .put("core", JSONObject().put("times", 0))
        val savedPayload = encode(saved)
        val regressedPayload = encode(regressed)
        assertEquals(SaveStanding.PROGRESSED, SharkProgress.summarize(savedPayload).standing)
        assertEquals("3689787472063838", SharkProgress.parsePayload(savedPayload)!!.totalFish.exponent.toString())
        assertEquals(SaveComparison.LIVE_BEHIND, SharkProgress.compare(regressedPayload, savedPayload))
        assertEquals(savedPayload, SharkProgress.chooseStored(listOf(regressedPayload, savedPayload)))
        assertTrue(SharkProgress.acceptsRuntime(
            SharkProgress.parsePayload(savedPayload)!!,
            SharkProgress.parsePayload(savedPayload)!!,
        ))
    }

    private fun fresh(): JSONObject = player(feature = 0, played = 0, sharkExponent = null, fishExponent = null, prestige = 0)

    private fun progressed(): JSONObject = player(feature = 4, played = 345_600, sharkExponent = 20, fishExponent = 15, prestige = 2)

    private fun player(
        feature: Int,
        played: Int,
        sharkExponent: Int?,
        fishExponent: Int?,
        prestige: Int,
    ): JSONObject {
        return JSONObject()
            .put("VERSION", 3)
            .put("feature", feature)
            .put("totalTimePlayed", played)
            .put("shark_level", decimal(sharkExponent))
            .put("total_fish", decimal(fishExponent))
            .put("prestige", JSONObject().put("times", prestige))
            .put("core", JSONObject().put("times", if (feature > 0) 1 else 0))
            .put("humanoid", JSONObject().put("times", 0))
            .put("singularity", JSONObject().put("first", false))
    }

    private fun decimal(exponent: Int?): Any {
        if (exponent == null) return 0
        return JSONObject().put("s", 1).put("e", exponent).put("c", JSONArray().put(1))
    }

    private fun encode(json: JSONObject): String =
        Base64.getEncoder().encodeToString(json.toString().toByteArray(Charsets.UTF_8))
}
