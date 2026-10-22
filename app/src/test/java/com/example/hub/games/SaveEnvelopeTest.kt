package com.example.hub.games

import android.webkit.WebView
import com.example.hub.session.DocumentPhase
import com.example.hub.session.JournalPhase
import com.example.hub.session.RecoveryResult
import com.example.hub.session.RestoreDirection
import com.example.hub.session.RestoreInterrupted
import com.example.hub.session.RestoreJournal
import com.example.hub.session.RestoreJournalStore
import com.example.hub.session.RestorePointer
import com.example.hub.session.RestoreStorage
import com.example.hub.session.RestoreTransaction
import com.example.hub.session.StorageBootstrap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SaveEnvelopeTest {
    private val policy = EnvelopeProbeAdapter().envelopePolicy()

    @Test
    fun sharkAndFeRawBootstrapBytesStaySingleKey() {
        val shark = StorageBootstrap.document(DocumentPhase.WRITE, "shark_inc_save", "abc")
        assertEquals(
            "<!DOCTYPE html><html><head><meta charset=\"utf-8\"></head><body><script>" +
                "localStorage.setItem(\"shark_inc_save\", \"abc\");window.__hubStorageBootstrap='write';" +
                "</script></body></html>",
            shark,
        )
        val fe = StorageBootstrap.document(DocumentPhase.ROLLBACK, "fe000000-save", "FE000000SaveStartabcFE000000SaveEnd")
        assertTrue(fe.contains("localStorage.setItem(\"fe000000-save\","))
        assertFalse(fe.contains("JSON.parse"))
        assertFalse(SharkIncrementalAdapter().usesSaveEnvelope)
        assertFalse(Fe000000Adapter().usesSaveEnvelope)
        assertEquals(SharkProbe.JS, SharkIncrementalAdapter().readinessScript)
        assertFalse(Fe000000Adapter().readinessScript.contains("localStorage"))
        assertFalse(Fe000000Adapter().readinessScript.contains("shark"))
    }

    @Test
    fun oneKeyAndManyKeysRoundTripExactBytes() {
        val awkward = "a\"b\\c\n\u0001café"
        val one = SaveEnvelope.encode(listOf(StorageSlot("progress", awkward)))
        val decodedOne = SaveEnvelope.decode(one, policy) as EnvelopeDecode.Ok
        assertEquals(awkward, decodedOne.bundle.slot("progress")?.value)

        val many = SaveEnvelope.encode(
            listOf(
                StorageSlot("progress", awkward),
                StorageSlot("backup", null),
                StorageSlot("notes", ""),
                StorageSlot("slot_2", "dyn"),
            ),
        )
        val decoded = SaveEnvelope.decode(many, policy) as EnvelopeDecode.Ok
        assertEquals(awkward, decoded.bundle.slot("progress")?.value)
        assertNull(decoded.bundle.slot("backup")?.value)
        assertEquals("", decoded.bundle.slot("notes")?.value)
        assertEquals("dyn", decoded.bundle.slot("slot_2")?.value)
        assertEquals(many, SaveEnvelope.encode(decoded.bundle.slots))
    }

    @Test
    fun absentOptionalKeyIsDistinctFromEmptyString() {
        val stored = mapOf("progress" to "save", "notes" to "")
        val bundle = SaveEnvelope.collect(
            stored = stored,
            staticNames = listOf("progress", "backup", "notes"),
            discovered = listOf("slot_9", "playFabPass", "unrelated"),
            policy = policy,
        )
        assertEquals("save", bundle.slot("progress")?.value)
        assertNull(bundle.slot("backup")?.value)
        assertEquals("", bundle.slot("notes")?.value)
        assertNull(bundle.slot("slot_9"))
        assertNull(bundle.slot("playFabPass"))
        assertNull(bundle.slot("unrelated"))
        val withSlot = SaveEnvelope.collect(
            stored = stored + ("slot_9" to "slot-bytes"),
            staticNames = listOf("progress", "backup", "notes"),
            discovered = listOf("slot_9", "playFabPass", "unrelated"),
            policy = policy,
        )
        assertEquals("slot-bytes", withSlot.slot("slot_9")?.value)
        assertNull(withSlot.slot("playFabPass"))
        assertNull(withSlot.slot("unrelated"))
    }

    @Test
    fun malformedDuplicateUnexpectedAndMissingAreRejected() {
        assertEquals(EnvelopeRejection.MALFORMED, rejection("not-json"))
        assertEquals(
            EnvelopeRejection.MALFORMED,
            rejection("""{"hubEnvelope":1,"entries":[{"name":"progress","value":null}]}"""),
        )
        assertEquals(
            EnvelopeRejection.MALFORMED,
            rejection("""{"hubEnvelope":2,"entries":[]}"""),
        )
        assertEquals(
            EnvelopeRejection.DUPLICATE,
            rejection(
                """{"hubEnvelope":1,"entries":[{"name":"progress","value":"a"},{"name":"progress","value":"b"}]}""",
            ),
        )
        assertEquals(
            EnvelopeRejection.UNEXPECTED,
            rejection("""{"hubEnvelope":1,"entries":[{"name":"progress","value":"a"},{"name":"other","value":"x"}]}"""),
        )
        assertEquals(
            EnvelopeRejection.DENIED,
            rejection("""{"hubEnvelope":1,"entries":[{"name":"progress","value":"a"},{"name":"playFabPass","value":"secret"}]}"""),
        )
        assertEquals(
            EnvelopeRejection.MISSING_REQUIRED,
            rejection("""{"hubEnvelope":1,"entries":[{"name":"backup","value":"x"}]}"""),
        )
    }

    @Test
    fun deletionAndRestorationKeepUnrelatedKeys() {
        val root = File("build/envelope-journal-delete").apply {
            deleteRecursively()
            mkdirs()
        }
        val storage = MapStorage(
            mutableMapOf(
                "progress" to "old",
                "backup" to "daily",
                "notes" to "pref",
                "accountToken" to "secret",
                "unrelated" to "leave-me",
            ),
        )
        val pointer = MemoryPointer("old-pointer")
        val store = RestoreJournalStore(root)
        val intended = listOf(
            StorageSlot("progress", "new"),
            StorageSlot("backup", null),
            StorageSlot("notes", ""),
        )
        val previous = listOf(
            StorageSlot("progress", "old"),
            StorageSlot("backup", "daily"),
            StorageSlot("notes", "pref"),
        )
        val journal = sampleJournal(intended, previous, "old-pointer", SaveEnvelope.encode(intended))
        val first = RestoreTransaction(store, storage, pointer).run(journal)
        assertTrue(first is RecoveryResult.Completed)
        assertEquals("new", storage.values["progress"])
        assertFalse(storage.values.containsKey("backup"))
        assertEquals("", storage.values["notes"])
        assertEquals("secret", storage.values["accountToken"])
        assertEquals("leave-me", storage.values["unrelated"])
        assertEquals(SaveEnvelope.encode(intended), pointer.get())

        val restorePrevious = sampleJournal(
            intended = previous,
            previous = intended,
            previousPointer = pointer.get(),
            intendedPointer = "old-pointer",
        )
        val second = RestoreTransaction(store, storage, pointer).run(restorePrevious)
        assertTrue(second is RecoveryResult.Completed)
        assertEquals("daily", storage.values["backup"])
        assertEquals("pref", storage.values["notes"])
        assertEquals("secret", storage.values["accountToken"])
        assertEquals("old-pointer", pointer.get())
    }

    @Test
    fun interruptedRestoreRecoversAfterEachStage() {
        val stages = listOf("prepared", "applying", "key:0", "key:1", "applied", "verified", "pointer")
        val intended = listOf(StorageSlot("progress", "new"), StorageSlot("backup", null))
        val previous = listOf(StorageSlot("progress", "old"), StorageSlot("backup", "daily"))
        val intendedPointer = SaveEnvelope.encode(intended)
        for (stage in stages) {
            val root = File("build/envelope-journal-$stage").apply {
                deleteRecursively()
                mkdirs()
            }
            val storage = MapStorage(mutableMapOf("progress" to "old", "backup" to "daily", "accountToken" to "secret"))
            val pointer = MemoryPointer("old-pointer")
            val store = RestoreJournalStore(root)
            val fault = failOnce(stage)
            val crashed = runCatching {
                RestoreTransaction(store, storage, pointer, fault).run(
                    sampleJournal(intended, previous, "old-pointer", intendedPointer),
                )
            }
            assertTrue(stage, crashed.exceptionOrNull() is RestoreInterrupted)
            assertTrue(store.unfinished("hub-qual-envelope"))
            if (stage == "pointer") {
                assertEquals(intendedPointer, pointer.get())
            } else {
                assertEquals("old-pointer", pointer.get())
            }
            val recovered = RestoreTransaction(store, storage, pointer).recover("hub-qual-envelope")
            assertTrue(stage, recovered is RecoveryResult.Completed)
            assertTrue(recovered.allowsGameLoad)
            assertEquals("new", storage.values["progress"])
            assertFalse(storage.values.containsKey("backup"))
            assertEquals("secret", storage.values["accountToken"])
            assertEquals(intendedPointer, pointer.get())
            assertFalse(store.unfinished("hub-qual-envelope"))
        }
    }

    @Test
    fun failedIntendedWriteRollsBackToPrevious() {
        val root = File("build/envelope-journal-rollback").apply {
            deleteRecursively()
            mkdirs()
        }
        val storage = MapStorage(mutableMapOf("progress" to "old", "backup" to "daily"))
        storage.rejectWrites = true
        val pointer = MemoryPointer("old-pointer")
        val store = RestoreJournalStore(root)
        val intended = listOf(StorageSlot("progress", "new"), StorageSlot("backup", null))
        val previous = listOf(StorageSlot("progress", "old"), StorageSlot("backup", "daily"))
        val result = RestoreTransaction(store, storage, pointer).run(
            sampleJournal(intended, previous, "old-pointer", "new-pointer"),
        )
        assertTrue(result is RecoveryResult.RolledBack)
        assertEquals("old", storage.values["progress"])
        assertEquals("daily", storage.values["backup"])
        assertEquals("old-pointer", pointer.get())
        assertFalse(store.unfinished("hub-qual-envelope"))
    }

    @Test
    fun unverifiedStorageDoesNotAllowGameLoad() {
        val root = File("build/envelope-journal-blocked").apply {
            deleteRecursively()
            mkdirs()
        }
        val storage = MapStorage(mutableMapOf())
        storage.rejectWrites = true
        val pointer = MemoryPointer("old-pointer")
        val store = RestoreJournalStore(root)
        val result = RestoreTransaction(store, storage, pointer).run(
            sampleJournal(
                intended = listOf(StorageSlot("progress", "new")),
                previous = listOf(StorageSlot("progress", "old")),
                previousPointer = "old-pointer",
                intendedPointer = "new-pointer",
            ),
        )
        assertTrue(result is RecoveryResult.Blocked)
        assertFalse(result.allowsGameLoad)
        assertTrue(store.unfinished("hub-qual-envelope"))
        assertEquals("old-pointer", pointer.get())
    }

    @Test
    fun unrankableDifferencesStayInconclusive() = runBlocking {
        val adapter = RankedEnvelopeAdapter()
        val mixedLong = envelope("1,9", notes = "xxxxxxxxxxxxxxxx")
        val mixedShort = envelope("2,1", notes = null)
        assertTrue(mixedLong.length > mixedShort.length)
        assertEquals(SaveComparison.INCONCLUSIVE, adapter.compare(mixedLong, mixedShort))
        assertEquals(SaveComparison.INCONCLUSIVE, adapter.compare(envelope("unranked", null), envelope("9,9", null)))
        assertEquals(SaveComparison.LIVE_AHEAD, adapter.compare(envelope("2,3", null), envelope("1,1", null)))
        assertEquals(SaveComparison.MATCH, adapter.compare(mixedShort, mixedShort))
        val invalid = "not-an-envelope"
        assertNull(adapter.chooseStored(listOf(invalid, mixedLong)))
        assertEquals(SaveStanding.INVALID, adapter.summarize(invalid).standing)
        assertEquals(SaveComparison.INCONCLUSIVE, adapter.compare(invalid, mixedShort))
        assertFalse(adapter.summarize(mixedLong).detail.contains("xxxxxxxx"))
    }

    @Test
    fun fe000000StillTreatsUnequalValidSavesAsLiveAhead() = runBlocking {
        val adapter = Fe000000Adapter()
        val first = Fe000000Adapter.encodeSave(feJson(1))
        val second = Fe000000Adapter.encodeSave(feJson(2))
        assertTrue(adapter.validate(first))
        assertTrue(adapter.validate(second))
        assertEquals(SaveComparison.LIVE_AHEAD, adapter.compare(second, first))
        assertEquals(SaveComparison.MATCH, adapter.compare(first, first))
        assertEquals(first, adapter.chooseStored(listOf(first, second)))
    }

    @Test
    fun genericFixtureDoesNotQualifyAnotherGame() = runBlocking {
        assertNull(Fe000000Adapter().qualificationFixture)
        val sharkFixture = SharkIncrementalAdapter.QUALIFICATION_FIXTURE
        assertTrue(SharkIncrementalAdapter().validate(sharkFixture))
        assertFalse(Fe000000Adapter().validate(sharkFixture))
        assertFalse(TrimpsAdapter().validate(sharkFixture))
        assertFalse(PayloadDigest.describe("super-secret-save").contains("super-secret"))
        assertTrue(PayloadDigest.describe("super-secret-save").contains("length="))
        assertNotNull(GameAdapters.create("trimps-adapter"))
        assertNull(GameAdapters.create("ethereal-farm-adapter"))
        assertNull(GameAdapters.create("antimatter-dimensions-adapter"))
        assertNull(GameAdapters.create("antimatter-dimensions-ng3-adapter"))
        assertNull(GameAdapters.create("synergism-adapter"))
    }

    @Test
    fun readScriptIsOneEvaluationAndListsStaticNames() {
        val script = SaveEnvelope.readScript(
            staticNames = listOf("progress", "backup"),
            discoveryExpression = "[]",
            deniedNames = setOf("playFabPass"),
        )
        assertEquals(1, Regex("JSON.stringify").findAll(script).count())
        assertTrue(script.contains("progress"))
        assertTrue(script.contains("backup"))
        assertFalse(script.contains("setItem"))
    }

    private fun rejection(payload: String): EnvelopeRejection {
        val decoded = SaveEnvelope.decode(payload, policy)
        return (decoded as EnvelopeDecode.Rejected).reason
    }

    private fun envelope(progress: String, notes: String?): String {
        val slots = mutableListOf(StorageSlot("progress", progress), StorageSlot("backup", null))
        slots.add(StorageSlot("notes", notes))
        return SaveEnvelope.encode(slots)
    }

    private fun feJson(boost: Int): String {
        return org.json.JSONObject()
            .put("boost", boost)
            .put("currentTab", "main")
            .put("generators", org.json.JSONArray(listOf(1)))
            .put("lastUpdate", 10)
            .put("prestigePower", 1)
            .put("stars", "1")
            .toString()
    }

    private fun sampleJournal(
        intended: List<StorageSlot>,
        previous: List<StorageSlot>,
        previousPointer: String?,
        intendedPointer: String?,
    ) = RestoreJournal(
        profileName = "hub-qual-envelope",
        gameId = "envelope-probe",
        phase = JournalPhase.PREPARED,
        appliedKeys = 0,
        previousSlots = previous,
        intendedSlots = intended,
        previousPointer = previousPointer,
        intendedPointer = intendedPointer,
        direction = RestoreDirection.INTENDED,
    )

    private fun failOnce(stage: String): (String) -> Unit {
        var armed = true
        return { hit ->
            if (armed && hit == stage) {
                armed = false
                throw RestoreInterrupted(hit)
            }
        }
    }

    private class MapStorage(val values: MutableMap<String, String>) : RestoreStorage {
        var rejectWrites: Boolean = false
        override fun read(name: String): String? = if (values.containsKey(name)) values[name] else null
        override fun apply(slot: StorageSlot) {
            if (rejectWrites) return
            if (slot.value == null) values.remove(slot.name) else values[slot.name] = slot.value
        }
    }

    private class MemoryPointer(var value: String?) : RestorePointer {
        override fun get(): String? = value
        override fun set(value: String?) {
            this.value = value
        }
    }
}

open class EnvelopeProbeAdapter : GameAdapter {
    override val adapterId: String = "envelope-probe-adapter"
    override val adapterVersion: String = "1.0.0"
    override val storageKey: String = "progress"
    override val usesSaveEnvelope: Boolean = true
    override val storageEntries: List<StorageEntry> = listOf(
        StorageEntry("progress", required = true, role = StorageRole.PROGRESS),
        StorageEntry("backup", required = false, role = StorageRole.BACKUP),
        StorageEntry("notes", required = false, role = StorageRole.PREFERENCE),
    )
    override val deniedStorageKeys: Set<String> = setOf("playFabPass", "playFabName", "accountToken")

    override fun allowsDiscoveredKey(name: String): Boolean {
        return name.startsWith("slot_") && name.length <= 64 && name.all { it.isLetterOrDigit() || it == '_' }
    }

    override suspend fun probe(webView: WebView): Boolean = true
    override suspend fun awaitReady(webView: WebView, timeoutMs: Long): Boolean = true
    override suspend fun capture(webView: WebView, result: QualificationResult): String? = null
    override suspend fun validate(payload: String): Boolean {
        val decoded = SaveEnvelope.decode(payload, envelopePolicy())
        if (decoded !is EnvelopeDecode.Ok) return false
        val value = decoded.bundle.slot(storageKey)?.value ?: return false
        return value.isNotEmpty()
    }

    override suspend fun prepareRestore(payload: String): String = payload
    override suspend fun verifyLoaded(webView: WebView, importedPayload: String): Boolean = false
    override suspend fun peekStorage(webView: WebView): PeekOutcome = PeekOutcome.Failed
    override suspend fun confirmBootstrap(webView: WebView): Boolean = true
    override suspend fun acceptsRuntime(webView: WebView, importedPayload: String): Boolean = true

    override fun summarize(payload: String?): SaveSummary {
        if (payload.isNullOrBlank()) return SaveSummary(SaveStanding.ABSENT, null, "No save key", 0)
        return when (val decoded = SaveEnvelope.decode(payload, envelopePolicy())) {
            is EnvelopeDecode.Rejected -> SaveSummary(SaveStanding.INVALID, null, decoded.reason.name, payload.length)
            is EnvelopeDecode.Ok -> {
                val value = decoded.bundle.slot(storageKey)?.value
                when {
                    value == null -> SaveSummary(SaveStanding.ABSENT, null, "progress absent", payload.length)
                    value.isEmpty() -> SaveSummary(SaveStanding.FRESH, null, "progress empty", payload.length)
                    else -> SaveSummary(SaveStanding.PROGRESSED, null, "progress present", payload.length)
                }
            }
        }
    }
}

private class RankedEnvelopeAdapter : EnvelopeProbeAdapter() {
    override fun progressView(payload: String?): ProgressView? {
        val decoded = SaveEnvelope.decode(payload ?: return null, envelopePolicy())
        if (decoded !is EnvelopeDecode.Ok) return null
        val raw = decoded.bundle.slot(storageKey)?.value ?: return null
        if (raw == "unranked") return null
        val parts = raw.split(",")
        if (parts.size != 2) return null
        val first = parts[0].toLongOrNull() ?: return null
        val second = parts[1].toLongOrNull() ?: return null
        return ProgressView(
            listOf(
                ProgressMarker("portals", first),
                ProgressMarker("helium", second),
            ),
        )
    }
}
