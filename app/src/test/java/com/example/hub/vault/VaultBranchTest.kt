package com.example.hub.vault

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.hub.database.GameInstance
import com.example.hub.database.HubDatabase
import com.example.hub.database.SnapshotReasons
import com.example.hub.database.SnapshotRecord
import com.example.hub.games.PayloadDigest
import com.example.hub.games.SharkIncrementalAdapter
import com.example.hub.session.CaptureDisposition
import com.example.hub.session.CaptureRepository
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class VaultBranchTest {

    @Test
    fun confirmedBranchKeepsThePreviousHighWaterAndSkipsSync() = runTest {
        val db = open()
        try {
            val current = shark(feature = 9, prestige = 2639)
            val older = shark(feature = 4, prestige = 2)
            db.snapshotDao().insert(row("current", 2, current))
            db.snapshotDao().insert(row("older", 1, older))
            db.gameInstanceDao().upsert(instance(current, "current"))
            val commit = repository(db).branchToSnapshot(
                gameId = "shark-incremental",
                snapshotId = "older",
                lineageId = "legacy",
                confirmed = true,
                adapter = SharkIncrementalAdapter(),
                sessionId = "session",
                stillCurrent = { true },
            )
            assertEquals(CaptureDisposition.STORED, commit.disposition)
            assertTrue(commit.sync.not())
            val saved = db.gameInstanceDao().get("shark-incremental")!!
            assertEquals(older, saved.snapshotPayload)
            assertNotEquals("legacy", saved.lineageId)
            assertNotEquals("current", saved.highWaterSnapshotId)
            val anchor = db.snapshotDao().getById("current")!!
            assertEquals(current, anchor.payload)
            assertTrue(anchor.pinned)
            val original = db.snapshotDao().getById("older")!!
            assertEquals(older, original.payload)
            assertEquals("legacy", original.lineageId)
            assertTrue(db.syncOutboxDao().pending().isEmpty())
            assertTrue(
                db.snapshotDao().history("shark-incremental").any {
                    it.lineageId == "legacy" &&
                        it.reason == SnapshotReasons.LINEAGE_BASE &&
                        it.payload == current
                },
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun unconfirmedBranchDoesNotWrite() = runTest {
        val db = open()
        try {
            val current = shark(feature = 9, prestige = 2639)
            db.snapshotDao().insert(row("current", 1, current))
            db.gameInstanceDao().upsert(instance(current, "current"))
            val commit = repository(db).branchToSnapshot(
                gameId = "shark-incremental",
                snapshotId = "current",
                lineageId = "legacy",
                confirmed = false,
                adapter = SharkIncrementalAdapter(),
                sessionId = "session",
                stillCurrent = { true },
            )
            assertEquals(CaptureDisposition.REJECTED, commit.disposition)
            assertEquals(current, db.gameInstanceDao().get("shark-incremental")!!.snapshotPayload)
            assertEquals(1, db.snapshotDao().countForGame("shark-incremental"))
        } finally {
            db.close()
        }
    }

    @Test
    fun interruptedBranchRollsBackThePointer() = runTest {
        val db = open()
        try {
            val current = shark(feature = 9, prestige = 2639)
            val older = shark(feature = 4, prestige = 2)
            db.snapshotDao().insert(row("current", 2, current, pinned = false))
            db.snapshotDao().insert(row("older", 1, older))
            db.gameInstanceDao().upsert(instance(current, "current"))
            val repo = repository(db) { stage ->
                if (stage == "branch-before-pointer") error("process died")
            }
            var failed = false
            try {
                repo.branchToSnapshot(
                    gameId = "shark-incremental",
                    snapshotId = "older",
                    lineageId = "legacy",
                    confirmed = true,
                    adapter = SharkIncrementalAdapter(),
                    sessionId = "session",
                    stillCurrent = { true },
                )
            } catch (e: IllegalStateException) {
                failed = e.message == "process died"
            }
            assertTrue(failed)
            val saved = db.gameInstanceDao().get("shark-incremental")!!
            assertEquals(current, saved.snapshotPayload)
            assertEquals("legacy", saved.lineageId)
            assertEquals("current", saved.highWaterSnapshotId)
            assertEquals(false, db.snapshotDao().getById("current")!!.pinned)
            assertEquals(older, db.snapshotDao().getById("older")!!.payload)
            assertEquals(2, db.snapshotDao().countForGame("shark-incremental"))
            assertTrue(db.syncOutboxDao().pending().isEmpty())
        } finally {
            db.close()
        }
    }

    private fun repository(db: HubDatabase, fault: (suspend (String) -> Unit)? = null): CaptureRepository {
        return CaptureRepository(
            instanceDao = db.gameInstanceDao(),
            snapshotDao = db.snapshotDao(),
            roomDatabase = db,
            outboxDao = db.syncOutboxDao(),
            clock = { 1_700_000_000_000L },
            fault = fault,
        )
    }

    private fun open(): HubDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val inline = Executor { it.run() }
        return Room.inMemoryDatabaseBuilder(context, HubDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(inline)
            .setTransactionExecutor(inline)
            .build()
    }

    private fun instance(payload: String, highWater: String): GameInstance {
        return GameInstance(
            gameId = "shark-incremental",
            profileName = "hub",
            sessionIdentity = "session",
            lastPlayedAtMillis = 20L,
            lastCaptureAtMillis = 20L,
            snapshotPayload = payload,
            lineageId = "legacy",
            highWaterSnapshotId = highWater,
        )
    }

    private fun row(id: String, sequence: Long, payload: String, pinned: Boolean = true): SnapshotRecord {
        return SnapshotRecord(
            id = id,
            gameId = "shark-incremental",
            sequence = sequence,
            capturedAtMillis = sequence,
            reason = "cadence",
            pinned = pinned,
            payload = payload,
            progressJson = null,
            lineageId = "legacy",
            contentSha256 = PayloadDigest.sha256(payload),
        )
    }

    private fun shark(feature: Int, prestige: Long): String {
        val json = JSONObject()
            .put("VERSION", 3)
            .put("feature", feature)
            .put("totalTimePlayed", "100")
            .put("shark_level", JSONObject().put("s", 1).put("e", 20).put("c", JSONArray().put(1)))
            .put("total_fish", JSONObject().put("s", 1).put("e", "4").put("c", JSONArray().put(1)))
            .put("prestige", JSONObject().put("times", prestige))
            .put("core", JSONObject().put("times", 1))
            .put("humanoid", JSONObject().put("times", 0))
            .put("singularity", JSONObject().put("first", false))
        return Base64.getEncoder().encodeToString(json.toString().toByteArray(Charsets.UTF_8))
    }
}
