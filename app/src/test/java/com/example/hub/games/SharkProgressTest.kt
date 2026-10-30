package com.example.hub.games

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
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

    @Test
    fun captureClassIgnoresTimeCurrencyAndHugeExponents() {
        val advanced = encode(player(feature = 9, played = 10, sharkExponent = 20, fishExponent = 40, prestige = 2639))
        val huge = JSONObject(String(Base64.getDecoder().decode(advanced)))
        huge.getJSONObject("total_fish").put("e", "3689787472063838")
        val feature9 = encode(huge)
        val regressed = encode(player(feature = 0, played = 999_999_999, sharkExponent = 80, fishExponent = 80, prestige = 9_000))
        assertEquals(ProgressionClass.REGRESSED, SharkProgress.classifyCapture(regressed, feature9))
        val sameFeatureLessPrestige = encode(
            player(feature = 9, played = 999_999_999, sharkExponent = 80, fishExponent = 80, prestige = 10),
        )
        assertEquals(ProgressionClass.REGRESSED, SharkProgress.classifyCapture(sameFeatureLessPrestige, feature9))
        assertEquals(SaveComparison.LIVE_AHEAD, SharkProgress.compare(sameFeatureLessPrestige, feature9))
        val prestiged = encode(player(feature = 9, played = 1, sharkExponent = 1, fishExponent = 1, prestige = 2640))
        assertEquals(ProgressionClass.ADVANCED, SharkProgress.classifyCapture(prestiged, feature9))
        val sameMilestones = encode(player(feature = 9, played = 11, sharkExponent = 21, fishExponent = 41, prestige = 2639))
        assertEquals(ProgressionClass.EQUAL, SharkProgress.classifyCapture(sameMilestones, feature9))
        assertEquals(ProgressionClass.EQUAL, SharkProgress.classifyCapture(feature9, feature9))
        assertEquals(ProgressionClass.INVALID, SharkProgress.classifyCapture("not-a-save", feature9))
        assertEquals(ProgressionClass.ADVANCED, SharkProgress.classifyCapture(feature9, null))
    }

    @Test
    fun diagnoseSeparatesEncodingJsonFieldsDecimalsAndGameData() {
        assertEquals(SharkParseKind.BASE64, SharkProgress.diagnosePayload("not-a-save").kind)
        assertEquals(
            SharkParseKind.JSON,
            SharkProgress.diagnosePayload(Base64.getEncoder().encodeToString("{".toByteArray())).kind,
        )
        val missing = JSONObject(fresh().toString())
        missing.remove("feature")
        val missingReport = SharkProgress.diagnosePayload(encode(missing))
        assertEquals(SharkParseKind.MISSING_FIELD, missingReport.kind)
        assertEquals("feature", missingReport.field)
        val weird = JSONObject(progressed().toString()).put("total_fish", JSONObject().put("mant", "x"))
        val decimalReport = SharkProgress.diagnosePayload(encode(weird))
        assertEquals(SharkParseKind.DECIMAL, decimalReport.kind)
        assertEquals("total_fish", decimalReport.field)
        val nan = JSONObject(progressed().toString()).put("shark_level", "NaN")
        val nanReport = SharkProgress.diagnosePayload(encode(nan))
        assertEquals(SharkParseKind.INVALID_GAME, nanReport.kind)
        assertEquals(ProgressionClass.INCOMPARABLE, SharkProgress.classifyCapture(encode(nan), encode(progressed())))
        assertEquals(ProgressionClass.INVALID, SharkProgress.classifyCapture("not-a-save", encode(progressed())))
        assertEquals(ProgressionClass.REGRESSED, SharkProgress.classifyCapture(encode(fresh()), encode(progressed())))
    }

    @Test
    fun nineThousandByteWebViewSaveParsesWhenFishUsesBreakEternityToString() {
        val roomFish = JSONObject().put("s", 1).put("e", 20).put("c", JSONArray().put(1))
        val room = sizedIncident(9072, roomFish)
        val web = sizedIncident(9156, "1e3.689787472063838e+15")
        assertEquals(9072, room.length)
        assertEquals(9156, web.length)
        val report = SharkProgress.diagnosePayload(web)
        assertEquals(SharkParseKind.OK, report.kind)
        assertEquals(11, report.progress!!.feature)
        assertEquals(2791L, report.progress.prestigeTimes)
        assertEquals(2901L, report.progress.coreTimes)
        assertEquals(2L, report.progress.humanoidTimes)
        assertEquals(ProgressionClass.EQUAL, SharkProgress.classifyCapture(web, room))
        val spent = sizedIncident(9100, JSONObject().put("s", 1).put("e", 10).put("c", JSONArray().put(1)))
        assertEquals(ProgressionClass.EQUAL, SharkProgress.classifyCapture(spent, room))
        val offline = encode(milestoneSave("1e3.689787472063838e+15", played = 900000))
        assertEquals(ProgressionClass.EQUAL, SharkProgress.classifyCapture(offline, room))
        val upgraded = encode(milestoneSave(roomFish, extra = "core-slot"))
        assertEquals(ProgressionClass.EQUAL, SharkProgress.classifyCapture(upgraded, room))
        val newer = encode(milestoneSave(roomFish, feature = 12))
        assertEquals(ProgressionClass.ADVANCED, SharkProgress.classifyCapture(newer, room))
        assertEquals(2, SharkProgress.parseDecimal("ee10")!!.tower)
        assertEquals(3, SharkProgress.parseDecimal("(e^3)10")!!.tower)
        val nested = JSONObject()
            .put("s", 1)
            .put("e", JSONObject().put("s", 1).put("e", 15).put("c", JSONArray().put(3)))
        assertTrue(SharkProgress.parseDecimal(nested)!!.exponent > BigInteger.ZERO)
        val nanWeb = sizedIncident(9156, "NaN")
        assertEquals(SharkParseKind.INVALID_GAME, SharkProgress.diagnosePayload(nanWeb).kind)
        assertEquals(ProgressionClass.INCOMPARABLE, SharkProgress.classifyCapture(nanWeb, room))
    }

    @Test
    fun liveSaveFunctionOutputClassifiesAndAdvances() {
        val preserved = resource("room.b64")
        val resumed = resource("save-fn.b64")
        val fresh = resource("fresh-save.b64")
        val preservedReport = SharkProgress.diagnosePayload(preserved)
        assertEquals(SharkParseKind.OK, preservedReport.kind)
        assertEquals(5, preservedReport.progress!!.feature)
        assertEquals(1409L, preservedReport.progress.prestigeTimes)
        val resumedReport = SharkProgress.diagnosePayload(resumed)
        assertEquals(resumedReport.detail, SharkParseKind.OK, resumedReport.kind)
        assertEquals(11, resumedReport.progress!!.feature)
        assertEquals(2791L, resumedReport.progress.prestigeTimes)
        assertEquals(2901L, resumedReport.progress.coreTimes)
        assertEquals(2L, resumedReport.progress.humanoidTimes)
        val decoded = JSONObject(String(java.util.Base64.getDecoder().decode(resumed), Charsets.UTF_8))
        assertEquals("ee21", decoded.getString("total_fish"))
        assertEquals("ee100", decoded.getString("shark_level"))
        assertEquals("ee10000000000", decoded.getString("fish"))
        assertEquals(ProgressionClass.ADVANCED, SharkProgress.classifyCapture(resumed, preserved))
        val freshReport = SharkProgress.diagnosePayload(fresh)
        assertEquals(freshReport.detail, SharkParseKind.OK, freshReport.kind)
        assertTrue(freshReport.progress!!.fresh || freshReport.progress.totalFish.sign != 0)
        val rejected = mutableListOf<String>()
        resource("decimal-strings.txt").lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { literal ->
            if (literal == "NaN") return@forEach
            val rank = SharkProgress.parseDecimal(literal)
            if (rank == null) rejected += literal
        }
        assertEquals(emptyList<String>(), rejected)
    }

    private fun resource(name: String): String {
        val stream = javaClass.classLoader!!.getResourceAsStream("shark/$name")
            ?: error("Missing fixture shark/$name")
        return stream.bufferedReader().readText().trim()
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

    private fun milestoneSave(
        fish: Any,
        played: Any = 400000,
        feature: Int = 11,
        extra: String? = null,
    ): JSONObject {
        val json = JSONObject()
            .put("VERSION", 3)
            .put("feature", feature)
            .put("totalTimePlayed", played)
            .put("shark_level", JSONObject().put("s", 1).put("e", 20).put("c", JSONArray().put(1)))
            .put("total_fish", fish)
            .put("prestige", JSONObject().put("times", 2791))
            .put("core", JSONObject().put("times", 2901))
            .put("humanoid", JSONObject().put("times", 2))
            .put("singularity", JSONObject().put("first", false))
        if (extra != null) json.put("upgrade", extra)
        return json
    }

    private fun sizedIncident(targetBase64: Int, fish: Any): String {
        var pad = 0
        while (pad < 20_000) {
            val json = milestoneSave(fish)
            if (pad > 0) json.put("pad", "x".repeat(pad))
            val encoded = encode(json)
            if (encoded.length == targetBase64) return encoded
            if (encoded.length > targetBase64) break
            pad += 1
        }
        error("Could not pad a Shark save to $targetBase64 base64 characters")
    }
}
