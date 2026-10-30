package com.example.hub.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.hub.database.GameInstance
import com.example.hub.database.HubDatabase
import com.example.hub.database.SyncOutboxEntry
import com.example.hub.games.ProgressionClass
import com.example.hub.games.SharkIncrementalAdapter
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Base64
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
class SaveSyncIncidentTest {

    @Test
    fun rejectedCaptureAndNewLineageAreNotUploaded() = runTest {
        val db = open()
        try {
            val uploads = mutableListOf<String>()
            val api = fake(uploads = uploads, reachable = false)
            val sync = SaveSync(db.gameInstanceDao(), api, db.snapshotDao(), db.syncOutboxDao())
            sync.classify = { _, candidate, reference ->
                SharkIncrementalAdapter().classifyProgress(candidate, reference)
            }
            db.gameInstanceDao().upsert(instance("incident-save", captureIncident = "Capture incident REGRESSED"))
            sync.push(instance("incident-save", captureIncident = "Capture incident REGRESSED"))
            assertTrue(uploads.isEmpty())

            db.gameInstanceDao().upsert(instance("fresh-lineage", lineageId = "lineage-2"))
            sync.push(instance("fresh-lineage", lineageId = "lineage-2"))
            assertTrue(uploads.isEmpty())

            val accepted = instance(shark(feature = 9, prestige = 3))
            db.gameInstanceDao().upsert(accepted)
            db.syncOutboxDao().insert(outbox(accepted.snapshotPayload!!, propose = true))
            sync.push(accepted)
            assertTrue(uploads.isEmpty())
            assertTrue(db.syncOutboxDao().pending().any { it.payload == accepted.snapshotPayload })
            assertTrue(db.syncOutboxDao().pending().none { it.lineageId == "lineage-2" })
        } finally {
            db.close()
        }
    }

    @Test
    fun regressedRemoteDoesNotReplaceTheProtectedPointer() = runTest {
        val db = open()
        try {
            val local = shark(feature = 9, prestige = 2639)
            val remote = shark(feature = 0, prestige = 0)
            val proposals = mutableListOf<String>()
            val api = object : WorkstationApi {
                override fun fetchSaves(): List<RemoteSave> = listOf(remoteSave(remote, capturedAt = 9_000L))
                override fun putSave(save: RemoteSave): PutResult = error("legacy put is not used")
                override fun lookupUpdate(): UpdateCheck = UpdateCheck.Current
                override fun download(url: String, destination: File): Boolean = false
                override fun fetchPointer(gameId: String): PointerFetch = PointerFetch.Found(pointer(remote, version = 4))
                override fun fetchHistory(gameId: String): List<RemoteSnapshot> = listOf(historyRow(remote))
                override fun appendSnapshot(upload: SnapshotUpload): SnapshotUploadResult {
                    return SnapshotUploadResult.Stored(
                        created = true,
                        snapshotId = upload.snapshotId,
                        sha256 = com.example.hub.games.PayloadDigest.sha256(upload.snapshotPayload),
                    )
                }
                override fun proposePointer(proposal: PointerProposal): PointerProposalResult {
                    proposals.add(proposal.snapshotId)
                    return PointerProposalResult.Conflict("integrity", pointer(remote, version = 4))
                }
            }
            val decisions = mutableListOf<String>()
            val sync = SaveSync(db.gameInstanceDao(), api, db.snapshotDao(), db.syncOutboxDao())
            sync.onDecision = { decisions.add(it) }
            sync.classify = { _, candidate, reference ->
                SharkIncrementalAdapter().classifyProgress(candidate, reference)
            }
            db.gameInstanceDao().upsert(instance(local, capturedAt = 20L))
            db.syncOutboxDao().insert(outbox(local, propose = true))
            sync.merge()
            assertEquals(local, db.gameInstanceDao().get("shark-incremental")!!.snapshotPayload)
            assertTrue(decisions.any { it.contains("stored snapshot was kept") })
            assertEquals(listOf("local"), proposals)
            assertTrue(db.snapshotDao().history("shark-incremental").none { it.payload == remote })
        } finally {
            db.close()
        }
    }

    @Test
    fun unorderedRemoteDoesNotReplaceTheLocalSave() = runTest {
        val db = open()
        try {
            val api = object : WorkstationApi {
                override fun fetchSaves(): List<RemoteSave> = listOf(remoteSave("remote-fe", capturedAt = 50_000L))
                override fun putSave(save: RemoteSave): PutResult = error("legacy put is not used")
                override fun lookupUpdate(): UpdateCheck = UpdateCheck.Current
                override fun download(url: String, destination: File): Boolean = false
                override fun fetchPointer(gameId: String): PointerFetch = PointerFetch.Found(pointer("remote-fe", version = 2))
                override fun fetchHistory(gameId: String): List<RemoteSnapshot> = listOf(historyRow("remote-fe"))
            }
            val sync = SaveSync(db.gameInstanceDao(), api, db.snapshotDao(), db.syncOutboxDao())
            sync.classify = { _, _, _ -> ProgressionClass.UNORDERED }
            db.gameInstanceDao().upsert(instance("local-fe", capturedAt = 10L))
            sync.merge()
            assertEquals("local-fe", db.gameInstanceDao().get("shark-incremental")!!.snapshotPayload)
        } finally {
            db.close()
        }
    }

    private fun open(): HubDatabase {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val inline = Executor { it.run() }
        return Room.inMemoryDatabaseBuilder(context, HubDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(inline)
            .setTransactionExecutor(inline)
            .build()
    }

    private fun instance(
        payload: String,
        captureIncident: String? = null,
        lineageId: String = "legacy",
        capturedAt: Long = 20L,
    ): GameInstance {
        return GameInstance(
            gameId = "shark-incremental",
            profileName = "hub-game-shark-incremental",
            sessionIdentity = "session",
            lastPlayedAtMillis = capturedAt,
            lastCaptureAtMillis = capturedAt,
            snapshotPayload = payload,
            captureIncident = captureIncident,
            lineageId = lineageId,
        )
    }

    private fun outbox(payload: String, propose: Boolean): SyncOutboxEntry {
        return SyncOutboxEntry(
            idempotencyKey = "append:local",
            gameId = "shark-incremental",
            lineageId = "legacy",
            snapshotId = "local",
            payload = payload,
            contentSha256 = com.example.hub.games.PayloadDigest.sha256(payload),
            capturedAtMillis = 20L,
            reason = "cadence",
            integrity = SyncOutboxEntry.INTEGRITY_ACCEPTED,
            profileName = "hub-game-shark-incremental",
            sessionIdentity = "session",
            lastPlayedAtMillis = 20L,
            resumeToken = null,
            proposePointer = propose,
            enqueuedAtMillis = 20L,
        )
    }

    private fun fake(uploads: MutableList<String>, reachable: Boolean): WorkstationApi {
        return object : WorkstationApi {
            override fun fetchSaves(): List<RemoteSave>? = if (reachable) emptyList() else null
            override fun putSave(save: RemoteSave): PutResult {
                uploads.add(save.snapshotPayload)
                return PutResult.Stored
            }
            override fun lookupUpdate(): UpdateCheck = UpdateCheck.Unreachable
            override fun download(url: String, destination: File): Boolean = false
            override fun appendSnapshot(upload: SnapshotUpload): SnapshotUploadResult {
                uploads.add(upload.snapshotPayload)
                return SnapshotUploadResult.Stored(true, upload.snapshotId, upload.idempotencyKey)
            }
        }
    }

    private fun remoteSave(payload: String, capturedAt: Long): RemoteSave {
        return RemoteSave(
            gameId = "shark-incremental",
            snapshotPayload = payload,
            capturedAtMillis = capturedAt,
            profileName = "hub",
            sessionIdentity = "remote",
            lastPlayedAtMillis = capturedAt,
            resumeToken = null,
            recoveryState = null,
        )
    }

    private fun pointer(payload: String, version: Int): RemotePointer {
        return RemotePointer(
            gameId = "shark-incremental",
            lineageId = "legacy",
            snapshotId = "remote",
            version = version,
            snapshotPayload = payload,
            capturedAtMillis = 9_000L,
            sha256 = com.example.hub.games.PayloadDigest.sha256(payload),
            profileName = "hub",
            sessionIdentity = "remote",
            lastPlayedAtMillis = 9_000L,
            resumeToken = null,
            recoveryState = null,
        )
    }

    private fun historyRow(payload: String): RemoteSnapshot {
        return RemoteSnapshot(
            snapshotId = "remote",
            gameId = "shark-incremental",
            lineageId = "legacy",
            sha256 = com.example.hub.games.PayloadDigest.sha256(payload),
            snapshotPayload = payload,
            capturedAtMillis = 9_000L,
            integrity = "accepted",
            source = "legacy-put",
        )
    }

    private fun shark(feature: Int, prestige: Long): String {
        val json = JSONObject()
            .put("VERSION", 3)
            .put("feature", feature)
            .put("totalTimePlayed", "10")
            .put("shark_level", 1)
            .put("total_fish", JSONObject().put("s", 1).put("e", "4").put("c", org.json.JSONArray().put(1)))
            .put("prestige", JSONObject().put("times", prestige))
            .put("core", JSONObject().put("times", 0))
            .put("humanoid", JSONObject().put("times", 0))
            .put("singularity", JSONObject().put("first", false))
        return Base64.getEncoder().encodeToString(json.toString().toByteArray(Charsets.UTF_8))
    }
}
