package com.example.hub.games

import com.example.hub.games.GameDefinitionValidator
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TrimpsAdapterTest {
    private val adapter = TrimpsAdapter()

    @Test
    fun lzRoundTripAndCatalogue() {
        val text = """{"global":{"stringVersion":"5.10.1","version":4.914}}"""
        val compressed = LzString.compressToBase64(text)
        assertEquals(text, LzString.decompressFromBase64(compressed))
        val definition = File("src/main/assets/games/trimps.json").takeIf { it.isFile }
            ?: File("app/src/main/assets/games/trimps.json")
        val result = GameDefinitionValidator().validate(definition.readText())
        assertTrue(result.errors.joinToString(), result.valid)
        assertEquals("trimps-adapter", adapter.adapterId)
        assertTrue(adapter.usesSaveEnvelope)
        assertNull(adapter.qualificationFixture)
    }

    @Test
    fun envelopeAcceptsRealStructureAndRejectsArbitraryBase64() = runBlocking {
        val fresh = envelope(save())
        val progressed = envelope(save(portals = 3, zone = 20, helium = "100"))
        assertTrue(adapter.validate(fresh))
        assertEquals(SaveStanding.FRESH, adapter.summarize(fresh).standing)
        assertEquals(SaveStanding.PROGRESSED, adapter.summarize(progressed).standing)
        assertEquals(SaveComparison.LIVE_AHEAD, adapter.compare(progressed, fresh))
        assertEquals(SaveComparison.LIVE_BEHIND, adapter.compare(fresh, progressed))
        assertEquals(SaveComparison.MATCH, adapter.compare(progressed, progressed))
        assertFalse(adapter.validate(SharkIncrementalAdapter.QUALIFICATION_FIXTURE))
        assertFalse(adapter.validate(envelope("not-a-trimps-save")))
        assertFalse(adapter.validate(envelope(save().dropLast(4))))
        assertTrue(adapter.summarize(progressed).detail.contains("backup=absent"))
        assertFalse(adapter.summarize(progressed).detail.contains(progressed))
    }

    @Test
    fun absentBackupDiffersFromAPresentBackup() = runBlocking {
        val absent = envelope(save(portals = 1, zone = 5, helium = "10"), backup = null)
        val present = envelope(
            save(portals = 1, zone = 5, helium = "10"),
            backup = save(portals = 0, zone = 1, helium = "1"),
        )
        assertTrue(adapter.validate(absent))
        assertTrue(adapter.validate(present))
        assertTrue(adapter.summarize(absent).detail.contains("backup=absent"))
        assertTrue(adapter.summarize(present).detail.contains("backup=present"))
        val decoded = SaveEnvelope.decode(absent, adapter.envelopePolicy()) as EnvelopeDecode.Ok
        assertNull(decoded.bundle.slot(TrimpsSave.BACKUP)?.value)
        assertEquals(absent, adapter.prepareRestore(absent))
    }

    @Test
    fun futureOldBetaAndChallengeSaves() = runBlocking {
        assertFalse(adapter.validate(envelope(save(stringVersion = "5.11.0"))))
        assertFalse(adapter.validate(envelope(save(version = "0.07"))))
        assertFalse(adapter.validate(envelope(save(beta = true))))
        assertTrue(adapter.validate(envelope(save(stringVersion = "4.10.0", portals = 1, zone = 2, helium = "3"))))
        val challenge = envelope(save(portals = 4, zone = 30, helium = "20", challenge = "Discipline"))
        val plain = envelope(save(portals = 4, zone = 40, helium = "30"))
        assertEquals(SaveComparison.INCONCLUSIVE, adapter.compare(challenge, plain))
        val otherUniverse = envelope(save(universe = 2, portals = 9, zone = 100, helium = "1", radon = "50"))
        assertEquals(SaveComparison.INCONCLUSIVE, adapter.compare(otherUniverse, plain))
        val mixed = envelope(save(portals = 5, zone = 10, helium = "1"))
        val earlier = envelope(save(portals = 4, zone = 40, helium = "30"))
        assertEquals(SaveComparison.INCONCLUSIVE, adapter.compare(mixed, earlier))
        assertEquals(plain, adapter.chooseStored(listOf(plain, mixed)))
        val fresh = envelope(save())
        assertEquals(fresh, adapter.chooseStored(listOf(fresh, plain)))
        assertEquals(SaveComparison.MATCH, adapter.compare(fresh, fresh))
    }

    @Test
    fun deniedKeysAreRejected() {
        val encoded = SaveEnvelope.encode(
            listOf(
                StorageSlot(TrimpsSave.PRIMARY, save()),
                StorageSlot("playFabPass", "secret"),
            ),
        )
        val decoded = SaveEnvelope.decode(encoded, adapter.envelopePolicy())
        assertTrue(decoded is EnvelopeDecode.Rejected)
    }

    private fun envelope(primary: String, backup: String? = null): String =
        SaveEnvelope.encode(
            listOf(
                StorageSlot(TrimpsSave.PRIMARY, primary),
                StorageSlot(TrimpsSave.BACKUP, backup),
            ),
        )

    private fun save(
        stringVersion: String = "5.10.1",
        version: String = "4.914",
        universe: Int = 1,
        portals: Int = 0,
        zone: Int = 0,
        helium: String = "0",
        radon: String = "0",
        challenge: String = "",
        squared: Boolean = false,
        beta: Boolean = false,
    ): String {
        val global = JSONObject()
            .put("stringVersion", stringVersion)
            .put("version", BigDecimal(version))
            .put("universe", universe)
            .put("totalPortals", portals)
            .put("highestLevelCleared", if (universe == 2) 0 else zone)
            .put("highestRadonLevelCleared", if (universe == 2) zone else 0)
            .put("totalHeliumEarned", BigDecimal(helium))
            .put("totalRadonEarned", BigDecimal(radon))
            .put("challengeActive", challenge)
            .put("runningChallengeSquared", squared)
            .put("isBeta", beta)
        return LzString.compressToBase64(JSONObject().put("global", global).toString())
    }

    private fun BigDecimal(text: String) = java.math.BigDecimal(text)
}
