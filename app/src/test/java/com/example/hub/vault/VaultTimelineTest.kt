package com.example.hub.vault

import com.example.hub.database.SnapshotMeta
import com.example.hub.games.PayloadDigest
import com.example.hub.ui.webViewIdentity
import com.example.hub.sync.HistoryPage
import com.example.hub.sync.PutResult
import com.example.hub.sync.RemoteSave
import com.example.hub.sync.RemoteSnapshot
import com.example.hub.sync.SnapshotMetadata
import com.example.hub.sync.UpdateCheck
import com.example.hub.sync.WorkstationApi
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

class VaultTimelineTest {

    @Test
    fun sequenceOrdersPhoneRowsWhenTheClockMovesBackwards() {
        val olderClock = entry("later", sequence = 20, capturedAt = 1_000)
        val newerClock = entry("earlier", sequence = 10, capturedAt = 9_000)
        val ordered = orderedTimeline(listOf(newerClock, olderClock))
        assertEquals(listOf("later", "earlier"), ordered.map { it.id })
    }

    @Test
    fun equalTimestampsUseSequenceThenId() {
        val low = entry("b", sequence = 4, capturedAt = 50)
        val high = entry("a", sequence = 5, capturedAt = 50)
        val tie = entry("c", sequence = 5, capturedAt = 50)
        val ordered = orderedTimeline(listOf(low, tie, high))
        assertEquals(listOf("a", "c", "b"), ordered.map { it.id })
    }

    @Test
    fun workstationOnlyRowsStayInReceiveOrderAfterPhoneRows() {
        val phone = entry("phone", sequence = 3, capturedAt = 10)
        val olderRemote = remote("old", receivedAt = 100, capturedAt = 9_000)
        val newerRemote = remote("new", receivedAt = 200, capturedAt = 1)
        val ordered = orderedTimeline(listOf(olderRemote, phone, newerRemote))
        assertEquals(listOf("phone", "new", "old"), ordered.map { it.id })
        val groups = groupTimeline(ordered)
        assertTrue(groups.first().title.endsWith("UTC"))
        assertFalse(groups.first().title.startsWith("Workstation"))
        assertTrue(groups.last().title.startsWith("Workstation only"))
    }

    @Test
    fun labelsDistinguishTheSnapshotRoles() {
        val active = entry("live", sequence = 2, capturedAt = 10, reason = "checkpoint", pinned = true, active = true)
        assertTrue(active.labels.contains("Active"))
        assertTrue(active.labels.contains("Manual"))
        assertTrue(active.labels.contains("Protected"))
        assertFalse(active.labels.contains("Historical"))
        val quarantine = entry("bad", sequence = 1, capturedAt = 9, reason = "quarantine", pinned = true)
        assertTrue(quarantine.labels.contains("Quarantined"))
        assertTrue(quarantine.labels.contains("Historical"))
        val remoteOnly = remote("away", receivedAt = 1, capturedAt = 1).copy(
            availability = VaultAvailability.WORKSTATION_UNAVAILABLE,
        )
        assertTrue(remoteOnly.labels.contains("Remote only"))
        assertTrue(remoteOnly.labels.contains("Unavailable"))
    }

    @Test
    fun sharkMilestonesIgnoreHugeCurrency() {
        val fish = JSONObject().put("s", 1).put("e", "999999999999999999999").put("c", JSONArray().put(1))
        val payload = shark(feature = 9, prestige = 2639, core = 2026, humanoid = 4, fish = fish)
        val milestones = sharkMilestones(payload)
        assertEquals(9, milestones?.feature)
        assertEquals(2639L, milestones?.prestige)
        assertEquals(2026L, milestones?.core)
        assertEquals(4L, milestones?.humanoid)
        assertEquals(false, milestones?.singularity)
        val reading = readSnapshot(SHARK_GAME_ID, entry("s", 1, 1, sha = PayloadDigest.sha256(payload)), payload)
        assertEquals("feature 9, prestige 2639, core 2026, humanoid 4, singularity false", reading.recomputedDetail)
        assertTrue(reading.hashMatches == true)
        assertFalse(reading.recomputedDetail!!.contains("Infinity"))
    }

    @Test
    fun webViewIdentityIgnoresVaultAndPosture() {
        assertEquals("shark-incremental" to 7, webViewIdentity("shark-incremental", 7))
        assertEquals(
            webViewIdentity("shark-incremental", 7),
            webViewIdentity("shark-incremental", 7),
        )
    }

    private fun entry(
        id: String,
        sequence: Long,
        capturedAt: Long,
        reason: String = "cadence",
        pinned: Boolean = false,
        active: Boolean = false,
        sha: String = "abc",
    ): VaultEntry {
        return VaultEntry(
            id = id,
            gameId = SHARK_GAME_ID,
            lineageId = "legacy",
            sequence = sequence,
            capturedAtMillis = capturedAt,
            receivedAtMillis = 0L,
            reason = reason,
            origin = "local-capture",
            pinned = pinned,
            userProtected = false,
            sha256 = sha,
            payloadBytes = 12,
            integrity = "hash recorded",
            residence = VaultResidence.LOCAL,
            availability = VaultAvailability.PRESENT,
            active = active,
            highWater = false,
            storedDetail = null,
        )
    }

    private fun remote(id: String, receivedAt: Long, capturedAt: Long): VaultEntry {
        return entry(id, sequence = 0, capturedAt = capturedAt).copy(
            residence = VaultResidence.REMOTE_ONLY,
            receivedAtMillis = receivedAt,
            reason = "workstation",
            origin = "workstation-sync",
        )
    }
}

class VaultCatalogTest {

    @Test
    fun pagingHundredsDoesNotReadPayloads() = runTest {
        val rows = (1..400).map { index ->
            meta(id = "row-$index", sequence = index.toLong(), bytes = 8_000 + index)
        }
        val local = MemoryIndex(rows)
        val api = FakeWorkstation()
        val catalog = VaultCatalog(local, api, pageSize = 25)
        val first = catalog.browse(SHARK_GAME_ID, localOffset = 0, advanceRemote = false)
        assertEquals(25, first.entries.size)
        assertEquals(400L, first.entries.first().sequence)
        assertEquals(376L, first.entries.last().sequence)
        assertEquals(0, local.payloadCalls)
        assertEquals(0, catalog.payloadReads)
        val second = catalog.browse(SHARK_GAME_ID, localOffset = 25, advanceRemote = false)
        assertEquals(375L, second.entries.first().sequence)
        assertTrue(second.hasOlderLocal)
        assertEquals(0, local.payloadCalls)
        val opened = first.entries.first()
        local.payloads[opened.id] = "payload"
        val text = catalog.readPayload(opened)
        assertEquals("payload", text)
        assertEquals(1, local.payloadCalls)
        assertEquals(1, catalog.payloadReads)
    }

    @Test
    fun phoneOnlyHistoryStaysWhenTheWorkstationIsDown() = runTest {
        val local = MemoryIndex(listOf(meta("local", 4, 20)))
        val api = FakeWorkstation(index = null)
        val catalog = VaultCatalog(local, api, pageSize = 10)
        val browse = catalog.browse(SHARK_GAME_ID, 0, advanceRemote = true)
        assertEquals(listOf("local"), browse.entries.map { it.id })
        assertTrue(browse.entries.single().labels.contains("Local"))
        assertFalse(browse.workstationReachable)
        assertEquals(0, api.snapshotCalls)
        assertEquals(0, local.payloadCalls)
    }

    @Test
    fun workstationOnlyRowsAreMetadataUntilOpened() = runTest {
        val remote = SnapshotMetadata(
            snapshotId = "remote-1",
            gameId = SHARK_GAME_ID,
            lineageId = "legacy",
            sha256 = "abc",
            capturedAtMillis = 20,
            receivedAtMillis = 30,
            integrity = "accepted",
            source = "phone",
        )
        val api = FakeWorkstation(index = HistoryPage(listOf(remote), complete = true))
        val catalog = VaultCatalog(MemoryIndex(emptyList()), api, pageSize = 10)
        val browse = catalog.browse(SHARK_GAME_ID, 0, advanceRemote = true)
        val entry = browse.entries.single()
        assertEquals(VaultResidence.REMOTE_ONLY, entry.residence)
        assertEquals(0, api.snapshotCalls)
        assertEquals(0, entry.payloadBytes)
        api.snapshot = RemoteSnapshot(
            snapshotId = "remote-1",
            gameId = SHARK_GAME_ID,
            lineageId = "legacy",
            sha256 = "abc",
            snapshotPayload = "remote-bytes",
            capturedAtMillis = 20,
            integrity = "accepted",
            source = "phone",
        )
        assertNull(catalog.readPayload(entry))
        api.snapshot = api.snapshot?.copy(sha256 = PayloadDigest.sha256("remote-bytes"))
        val entryWithHash = entry.copy(sha256 = PayloadDigest.sha256("remote-bytes"))
        assertEquals("remote-bytes", catalog.readPayload(entryWithHash))
        assertEquals(2, api.snapshotCalls)
    }

    @Test
    fun aFailedRefreshMarksFetchedWorkstationRowsUnavailable() = runTest {
        val remote = SnapshotMetadata(
            snapshotId = "remote-1",
            gameId = SHARK_GAME_ID,
            lineageId = "legacy",
            sha256 = "abc",
            capturedAtMillis = 20,
            receivedAtMillis = 30,
            integrity = "accepted",
            source = "phone",
        )
        val api = FakeWorkstation(index = HistoryPage(listOf(remote), complete = false))
        val local = MemoryIndex(listOf(meta("local", 2, 10)))
        val catalog = VaultCatalog(local, api, pageSize = 1)
        val online = catalog.browse(SHARK_GAME_ID, 0, advanceRemote = true)
        assertTrue(online.entries.any { it.id == "remote-1" && it.availability == VaultAvailability.PRESENT })
        api.index = null
        val offline = catalog.browse(SHARK_GAME_ID, 0, advanceRemote = true)
        val remoteRow = offline.entries.single { it.id == "remote-1" }
        assertEquals(VaultAvailability.WORKSTATION_UNAVAILABLE, remoteRow.availability)
        assertTrue(offline.entries.any { it.id == "local" })
        assertNull(catalog.readPayload(remoteRow))
        assertEquals(0, api.snapshotCalls)
    }

    private fun meta(id: String, sequence: Long, bytes: Int): SnapshotMeta {
        return SnapshotMeta(
            id = id,
            gameId = SHARK_GAME_ID,
            sequence = sequence,
            capturedAtMillis = sequence,
            reason = "cadence",
            pinned = false,
            progressJson = null,
            origin = "local-capture",
            lineageId = "legacy",
            contentSha256 = "sha-$id",
            payloadBytes = bytes,
        )
    }
}

private class MemoryIndex(
    private val rows: List<SnapshotMeta>,
) : VaultLocalIndex {
    val payloads = mutableMapOf<String, String>()
    var payloadCalls = 0

    override suspend fun count(gameId: String): Int = rows.count { it.gameId == gameId }

    override suspend fun page(gameId: String, limit: Int, offset: Int): List<SnapshotMeta> {
        return rows.filter { it.gameId == gameId }
            .sortedWith(compareByDescending<SnapshotMeta> { it.sequence }.thenBy { it.id })
            .drop(offset)
            .take(limit)
    }

    override suspend fun existingIds(ids: List<String>): Set<String> {
        val known = rows.map { it.id }.toSet()
        return ids.filter { it in known }.toSet()
    }

    override suspend fun payload(id: String): String? {
        payloadCalls += 1
        return payloads[id]
    }

    override suspend fun pointerSha(gameId: String): String? = null

    override suspend fun highWaterId(gameId: String): String? = null
}

private class FakeWorkstation(
    var index: HistoryPage? = HistoryPage(emptyList(), complete = true),
    var snapshot: RemoteSnapshot? = null,
) : WorkstationApi {
    var snapshotCalls = 0

    override fun fetchSaves(): List<RemoteSave>? = null

    override fun putSave(save: RemoteSave): PutResult? = null

    override fun lookupUpdate(): UpdateCheck = UpdateCheck.Unreachable

    override fun download(url: String, destination: File): Boolean = false

    override fun fetchHistoryIndex(
        gameId: String,
        afterReceivedAt: Long,
        afterSnapshotId: String,
        limit: Int,
    ): HistoryPage? = index

    override fun fetchSnapshot(gameId: String, snapshotId: String): RemoteSnapshot? {
        snapshotCalls += 1
        return snapshot
    }
}

private fun shark(
    feature: Int,
    prestige: Long,
    core: Long,
    humanoid: Long,
    fish: Any,
): String {
    val json = JSONObject()
        .put("VERSION", 3)
        .put("feature", feature)
        .put("totalTimePlayed", "345600")
        .put("shark_level", JSONObject().put("s", 1).put("e", 20).put("c", JSONArray().put(1)))
        .put("total_fish", fish)
        .put("prestige", JSONObject().put("times", prestige))
        .put("core", JSONObject().put("times", core))
        .put("humanoid", JSONObject().put("times", humanoid))
        .put("singularity", JSONObject().put("first", false))
    return Base64.getEncoder().encodeToString(json.toString().toByteArray(Charsets.UTF_8))
}
