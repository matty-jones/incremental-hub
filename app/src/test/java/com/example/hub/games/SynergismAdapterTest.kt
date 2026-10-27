package com.example.hub.games

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

class SynergismAdapterTest {
    private val adapter = SynergismAdapter()
    private val freshSave = loadFixture()

    @Test
    fun catalogueAndRealFreshSave() = runBlocking {
        val definition = File("src/main/assets/games/synergism.json").takeIf { it.isFile }
            ?: File("app/src/main/assets/games/synergism.json")
        val result = GameDefinitionValidator().validate(definition.readText())
        assertTrue(result.errors.joinToString(), result.valid)
        assertEquals("synergism-adapter", adapter.adapterId)
        assertEquals(SynergismSave.KEY, adapter.storageKey)
        assertTrue(adapter.usesSaveEnvelope)
        assertNull(adapter.qualificationFixture)
        val body = SynergismSave.body(freshSave)
        assertTrue(body != null && body.length() > 40)
        assertEquals("4.4.0 October 26, 2026: The Corrupted Update", body?.optString("version"))
        assertTrue(body?.optJSONObject("corruptions")?.opt("used") is Number)
        assertTrue(SynergismSave.acceptable(freshSave))
        val envelope = envelope(freshSave)
        assertTrue(adapter.validate(envelope))
        val summary = adapter.summarize(envelope)
        assertEquals(SaveStanding.FRESH, summary.standing)
        assertTrue(summary.detail.contains("singularity=0"))
        assertTrue(summary.detail.contains("worlds=0"))
        assertFalse(summary.detail.contains(freshSave))
        assertEquals(SaveComparison.MATCH, adapter.compare(envelope, envelope))
    }

    @Test
    fun rejectsMalformedLegacyAndFutureSaves() {
        assertFalse(SynergismSave.acceptable(freshSave.dropLast(8)))
        assertFalse(SynergismSave.acceptable("not-base64!!!"))
        assertFalse(SynergismSave.acceptable(rewrite { it.put("version", "4.5.0") }))
        assertFalse(SynergismSave.acceptable(rewrite { it.put("exporttest", false) }))
        assertFalse(
            SynergismSave.acceptable(
                rewrite { it.put("corruptions", JSONObject().put("used", JSONObject().put("viscosity", 1))) },
            ),
        )
        assertTrue(SynergismSave.acceptable(rewrite { it.put("exporttest", "YES!") }))
        assertTrue(SynergismSave.acceptable(rewrite { it.put("version", "4.3.1 September 25, 2026: The Purple") }))
    }

    @Test
    fun progressComparisonStaysConservative() = runBlocking {
        val fresh = envelope(freshSave)
        val singularity = envelope(
            rewrite {
                it.put("singularityCount", 1)
                it.put("highestSingularityCount", 1)
            },
        )
        val quarks = envelope(
            rewrite {
                it.put("worlds", 5)
                it.put("goldenQuarks", 1)
                it.put("totalQuarksEver", 5)
            },
        )
        val spent = envelope(
            rewrite {
                it.put("worlds", 2)
                it.put("goldenQuarks", 1)
                it.put("totalQuarksEver", 5)
            },
        )
        val coinsOnly = envelope(rewrite { it.put("coins", "hub-marker-only") })
        assertEquals(SaveComparison.LIVE_AHEAD, adapter.compare(singularity, fresh))
        assertEquals(SaveComparison.LIVE_BEHIND, adapter.compare(fresh, singularity))
        assertEquals(SaveComparison.LIVE_AHEAD, adapter.compare(quarks, fresh))
        assertEquals(SaveComparison.INCONCLUSIVE, adapter.compare(spent, quarks))
        assertEquals(SaveComparison.INCONCLUSIVE, adapter.compare(singularity, quarks))
        assertEquals(SaveComparison.INCONCLUSIVE, adapter.compare(coinsOnly, fresh))
        assertNull(adapter.chooseStored(listOf(fresh, coinsOnly)))
        assertEquals(fresh, adapter.chooseStored(listOf(fresh)))
        assertEquals(SaveStanding.PROGRESSED, adapter.summarize(singularity).standing)
    }

    @Test
    fun preferenceKeysStayOutOfTheEnvelope() {
        val encoded = SaveEnvelope.encode(
            listOf(
                StorageSlot(SynergismSave.KEY, freshSave),
                StorageSlot("theme", "dark"),
            ),
        )
        val decoded = SaveEnvelope.decode(encoded, adapter.envelopePolicy())
        assertTrue(decoded is EnvelopeDecode.Rejected)
    }

    private fun envelope(save: String): String =
        SaveEnvelope.encode(listOf(StorageSlot(SynergismSave.KEY, save)))

    private fun rewrite(edit: (JSONObject) -> Unit): String {
        val json = JSONObject(String(Base64.getDecoder().decode(freshSave), Charsets.UTF_8))
        edit(json)
        return Base64.getEncoder().withoutPadding().encodeToString(json.toString().toByteArray(Charsets.UTF_8))
    }

    private fun loadFixture(): String {
        val file = File("src/test/resources/synergism/fresh-4.4.0.b64").takeIf { it.isFile }
            ?: File("app/src/test/resources/synergism/fresh-4.4.0.b64")
        return file.readText().trim()
    }
}
