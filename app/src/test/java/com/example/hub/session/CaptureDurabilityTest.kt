package com.example.hub.session

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.hub.database.GameInstance
import com.example.hub.database.HubDatabase
import com.example.hub.database.SnapshotDao
import com.example.hub.database.SnapshotReasons
import com.example.hub.database.SnapshotRecord
import com.example.hub.games.PayloadDigest
import com.example.hub.games.ProgressionClass
import com.example.hub.games.SharkIncrementalAdapter
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class CaptureDurabilityTest {

    @Test
    fun oneHundredAdvancesKeepTheHighWaterPayload() = runTest {
        val db = openDatabase()
        try {
            val gameId = "shark-incremental"
            db.gameInstanceDao().upsert(row(gameId))
            var now = 1_000L
            val repo = CaptureRepository(db.gameInstanceDao(), db.snapshotDao(), db, clock = { now })
            val adapter = SharkIncrementalAdapter()
            var first = ""
            var last = ""
            repeat(100) { index ->
                val payload = shark(feature = 9, prestige = index + 1L, fish = index.toString())
                if (index == 0) first = payload
                last = payload
                now += 1_000L
                val commit = repo.commitIfSafe(gameId, payload, "cadence", adapter, "session", { true }, false)
                assertEquals(CaptureDisposition.STORED, commit.disposition)
                assertEquals(ProgressionClass.ADVANCED, commit.classification)
                val stored = db.gameInstanceDao().get(gameId)!!
                assertEquals(payload, stored.snapshotPayload)
                val anchor = db.snapshotDao().getById(stored.highWaterSnapshotId!!)
                assertEquals(payload, anchor!!.payload)
                db.snapshotDao().pruneAutomatic(gameId)
                assertEquals(payload, db.snapshotDao().getById(stored.highWaterSnapshotId!!)!!.payload)
            }
            val unpinned = db.snapshotDao().history(gameId).count { !it.pinned && it.reason == "cadence" }
            assertTrue(unpinned <= 10)
            assertTrue(db.snapshotDao().history(gameId).any { it.payload == first && it.pinned })
            val anchorId = db.gameInstanceDao().get(gameId)!!.highWaterSnapshotId!!
            db.snapshotDao().releaseAutomaticPin(anchorId)
            assertFalse(db.snapshotDao().getById(anchorId)!!.pinned)
            flood(db.snapshotDao(), gameId, 20)
            repeat(3) { db.snapshotDao().pruneAutomatic(gameId) }
            assertEquals(last, db.snapshotDao().getById(anchorId)!!.payload)
            val regressed = shark(feature = 0, prestige = 0, fish = "99")
            val quarantined = repo.commitIfSafe(gameId, regressed, "cadence", adapter, "session", { true }, false)
            assertEquals(CaptureDisposition.QUARANTINED, quarantined.disposition)
            val stored = db.gameInstanceDao().get(gameId)!!
            assertEquals(last, stored.snapshotPayload)
            assertEquals(anchorId, stored.highWaterSnapshotId)
            assertEquals(last, db.snapshotDao().getById(anchorId)!!.payload)
        } finally {
            db.close()
        }
    }

    @Test
    fun checkpointDedupedAndLineageAnchorsSurvivePruning() = runTest {
        val db = openDatabase()
        try {
            val adapter = SharkIncrementalAdapter()
            var now = 5_000L
            val repo = CaptureRepository(db.gameInstanceDao(), db.snapshotDao(), db, clock = { now })
            val checkpointGame = "checkpoint-game"
            val checkpoint = shark(feature = 9, prestige = 3, fish = "4")
            db.gameInstanceDao().upsert(row(checkpointGame))
            val saved = repo.commitIfSafe(checkpointGame, checkpoint, "request", adapter, "session", { true }, true)
            assertEquals(CaptureDisposition.STORED, saved.disposition)
            val checkpointId = db.gameInstanceDao().get(checkpointGame)!!.highWaterSnapshotId!!
            assertEquals(SnapshotReasons.CHECKPOINT, db.snapshotDao().getById(checkpointId)!!.reason)
            db.snapshotDao().releaseAutomaticPin(checkpointId)
            assertTrue(db.snapshotDao().getById(checkpointId)!!.pinned)
            flood(db.snapshotDao(), checkpointGame, 20)
            repeat(3) { db.snapshotDao().pruneAutomatic(checkpointGame) }
            assertEquals(checkpoint, db.snapshotDao().getById(checkpointId)!!.payload)

            val dedupGame = "dedup-game"
            val early = shark(feature = 9, prestige = 10, fish = "5")
            val behind = shark(feature = 4, prestige = 1, fish = "9")
            db.gameInstanceDao().upsert(row(dedupGame, behind))
            db.snapshotDao().insert(
                SnapshotRecord(
                    id = "deduped",
                    gameId = dedupGame,
                    sequence = 1,
                    capturedAtMillis = 1,
                    reason = "cadence",
                    pinned = false,
                    payload = early,
                    progressJson = null,
                    contentSha256 = PayloadDigest.sha256(early),
                ),
            )
            now += 1_000L
            val advanced = repo.commitIfSafe(dedupGame, early, "cadence", adapter, "session", { true }, false)
            assertEquals(ProgressionClass.ADVANCED, advanced.classification)
            assertEquals("deduped", db.gameInstanceDao().get(dedupGame)!!.highWaterSnapshotId)
            db.snapshotDao().releaseAutomaticPin("deduped")
            assertFalse(db.snapshotDao().getById("deduped")!!.pinned)
            flood(db.snapshotDao(), dedupGame, 20)
            repeat(3) { db.snapshotDao().pruneAutomatic(dedupGame) }
            assertEquals(early, db.snapshotDao().getById("deduped")!!.payload)
            assertEquals("deduped", db.gameInstanceDao().get(dedupGame)!!.highWaterSnapshotId)

            val lineageGame = "lineage-game"
            val good = shark(feature = 9, prestige = 20, fish = "40")
            val bad = shark(feature = 0, prestige = 0, fish = "1")
            db.gameInstanceDao().upsert(row(lineageGame))
            now += 1_000L
            repo.commitIfSafe(lineageGame, good, "request", adapter, "session", { true }, true)
            now += 1_000L
            val incident = repo.commitIfSafe(lineageGame, bad, "cadence", adapter, "session", { true }, false)
            assertEquals(CaptureDisposition.QUARANTINED, incident.disposition)
            val switched = repo.openLineage(lineageGame, confirmed = true, activate = true, adapter, "session") { true }
            assertEquals(CaptureDisposition.STORED, switched.disposition)
            val active = db.gameInstanceDao().get(lineageGame)!!
            assertEquals(bad, active.snapshotPayload)
            assertEquals(bad, db.snapshotDao().getById(active.highWaterSnapshotId!!)!!.payload)
            val kept = db.snapshotDao().history(lineageGame).first {
                it.reason == SnapshotReasons.LINEAGE_BASE && it.payload == good
            }
            db.snapshotDao().releaseAutomaticPin(kept.id)
            assertTrue(db.snapshotDao().getById(kept.id)!!.pinned)
            flood(db.snapshotDao(), lineageGame, 20)
            repeat(3) { db.snapshotDao().pruneAutomatic(lineageGame) }
            assertEquals(good, db.snapshotDao().getById(kept.id)!!.payload)
            assertEquals(bad, db.snapshotDao().getById(active.highWaterSnapshotId!!)!!.payload)
            assertNotNull(active.lineageId)
            assertTrue(active.lineageId != "legacy")
        } finally {
            db.close()
        }
    }

    @Test
    fun pruningLeavesAMissingHighWaterAlone() = runTest {
        val db = openDatabase()
        try {
            db.gameInstanceDao().upsert(row("shark-incremental"))
            repeat(12) { index ->
                db.snapshotDao().insert(
                    SnapshotRecord(
                        id = "auto-$index",
                        gameId = "shark-incremental",
                        sequence = index + 1L,
                        capturedAtMillis = index.toLong(),
                        reason = "cadence",
                        pinned = false,
                        payload = "auto-$index",
                        progressJson = null,
                    ),
                )
            }
            repeat(2) { db.snapshotDao().pruneAutomatic("shark-incremental") }
            assertNull(db.snapshotDao().getById("auto-0"))
            assertEquals(10, db.snapshotDao().history("shark-incremental").size)
        } finally {
            db.close()
        }
    }

    private fun openDatabase(): HubDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val inline = Executor { it.run() }
        return Room.inMemoryDatabaseBuilder(context, HubDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(inline)
            .setTransactionExecutor(inline)
            .build()
    }

    private fun row(gameId: String, payload: String? = null): GameInstance {
        return GameInstance(
            gameId = gameId,
            profileName = "hub-game-$gameId",
            sessionIdentity = "session",
            lastPlayedAtMillis = 1L,
            lastCaptureAtMillis = 0L,
            snapshotPayload = payload,
        )
    }

    private suspend fun flood(dao: SnapshotDao, gameId: String, count: Int) {
        val start = dao.maxSequence(gameId) + 1
        repeat(count) { index ->
            dao.insert(
                SnapshotRecord(
                    id = "$gameId-junk-$index",
                    gameId = gameId,
                    sequence = start + index,
                    capturedAtMillis = 2_000_000L + index,
                    reason = "cadence",
                    pinned = false,
                    payload = "junk-$gameId-$index",
                    progressJson = null,
                ),
            )
        }
    }

    private fun shark(feature: Int, prestige: Long, fish: String): String {
        val json = JSONObject()
            .put("VERSION", 3)
            .put("feature", feature)
            .put("totalTimePlayed", "10")
            .put("shark_level", 1)
            .put("total_fish", JSONObject().put("s", 1).put("e", fish).put("c", org.json.JSONArray().put(1)))
            .put("prestige", JSONObject().put("times", prestige))
            .put("core", JSONObject().put("times", 0))
            .put("humanoid", JSONObject().put("times", 0))
            .put("singularity", JSONObject().put("first", false))
        return Base64.getEncoder().encodeToString(json.toString().toByteArray(Charsets.UTF_8))
    }
}
