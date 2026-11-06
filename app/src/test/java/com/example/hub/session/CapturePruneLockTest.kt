package com.example.hub.session

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.hub.database.GameInstance
import com.example.hub.database.HubDatabase
import com.example.hub.database.SnapshotRecord
import com.example.hub.database.SnapshotRetention
import com.example.hub.games.SharkIncrementalAdapter
import com.example.hub.sync.HistoryPage
import com.example.hub.sync.RemoteSave
import com.example.hub.sync.UpdateCheck
import com.example.hub.sync.WorkstationApi
import com.example.hub.vault.IhubExportResult
import com.example.hub.vault.IhubPublisher
import com.example.hub.vault.SnapshotDeletion
import com.example.hub.vault.VaultArchive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Base64
import java.util.concurrent.Executor

/**
 * Capture used to hold the Room transaction and then take [com.example.hub.vault.ExportHold].
 * Deletion and export take that mutex and then enter Room. These tests time out on that inversion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CapturePruneLockTest {

    @After
    fun clearHook() {
        SnapshotRetention.beforeExclusion = null
    }

    @Test
    fun capturePruneExportAndDeletionFinishWithoutADeadlock() = runTest(timeout = 20.seconds) {
        val db = open()
        try {
            seed(db)
            val deletion = deletion(db)
            val archive = archive(db)
            val repo = repository(db)
            SnapshotRetention.beforeExclusion = {
                withContext(Dispatchers.Default.limitedParallelism(1)) {
                    withTimeout(8_000) {
                        coroutineScope {
                            val exported = async { archive.exportSnapshot("old-0") }
                            val removed = async {
                                val fingerprint = deletion.preview(GAME, "old-1", workstationOnly = false).fingerprint
                                assertNotNull(fingerprint)
                                deletion.delete(fingerprint!!)
                            }
                            val exportResult = exported.await()
                            val deleteResult = removed.await()
                            assertTrue(exportResult is IhubExportResult.Ready)
                            (exportResult as IhubExportResult.Ready).file.delete()
                            assertTrue(deleteResult.deleted || deleteResult.refusal != null)
                        }
                    }
                }
            }
            val advanced = shark(feature = 2)
            val commit = repo.commitIfSafe(
                GAME,
                advanced,
                "cadence",
                SharkIncrementalAdapter(),
                "session",
                { true },
                false,
            )
            assertEquals(CaptureDisposition.STORED, commit.disposition)
            val stored = db.gameInstanceDao().get(GAME)!!
            assertEquals(advanced, stored.snapshotPayload)
            val anchor = db.snapshotDao().getById(stored.highWaterSnapshotId!!)
            assertEquals(advanced, anchor!!.payload)
            assertTrue(db.syncOutboxDao().pendingCount(GAME) >= 1)
        } finally {
            db.close()
        }
    }

    @Test
    fun pruningFailureLeavesTheCommittedSaveAndOutbox() = runTest {
        val db = open()
        try {
            seed(db)
            val repo = repository(db)
            val advanced = shark(feature = 2)
            SnapshotRetention.beforeExclusion = { error("prune failed") }
            val commit = repo.commitIfSafe(
                GAME,
                advanced,
                "cadence",
                SharkIncrementalAdapter(),
                "session",
                { true },
                false,
            )
            assertEquals(CaptureDisposition.STORED, commit.disposition)
            val stored = db.gameInstanceDao().get(GAME)!!
            assertEquals(advanced, stored.snapshotPayload)
            assertEquals(advanced, db.snapshotDao().getById(stored.highWaterSnapshotId!!)!!.payload)
            assertTrue(db.syncOutboxDao().pendingCount(GAME) >= 1)
            assertNotNull(db.snapshotDao().getById("hw"))
        } finally {
            db.close()
        }
    }

    private fun repository(db: HubDatabase): CaptureRepository {
        return CaptureRepository(
            db.gameInstanceDao(),
            db.snapshotDao(),
            db,
            db.syncOutboxDao(),
            clock = { NOW },
        )
    }

    private fun deletion(db: HubDatabase): SnapshotDeletion {
        return SnapshotDeletion(
            snapshots = db.snapshotDao(),
            instances = db.gameInstanceDao(),
            outbox = db.syncOutboxDao(),
            room = db,
        ).also { it.clock = { NOW } }
    }

    private fun archive(db: HubDatabase): VaultArchive {
        return VaultArchive(
            db.snapshotDao(),
            object : WorkstationApi {
                override fun fetchSaves(): List<RemoteSave>? = emptyList()
                override fun putSave(save: RemoteSave) = null
                override fun lookupUpdate(): UpdateCheck = UpdateCheck.Current
                override fun download(url: String, destination: File) = false
                override fun fetchHistoryIndex(
                    gameId: String,
                    afterReceivedAt: Long,
                    afterSnapshotId: String,
                    limit: Int,
                ): HistoryPage = HistoryPage(emptyList(), complete = true)
            },
            IhubPublisher(db, db.snapshotDao(), db.gameInstanceDao()),
            "test",
        )
    }

    private suspend fun seed(db: HubDatabase) {
        val live = shark(feature = 1)
        db.gameInstanceDao().upsert(
            GameInstance(
                gameId = GAME,
                profileName = "hub-game-$GAME",
                sessionIdentity = "session",
                lastPlayedAtMillis = NOW,
                lastCaptureAtMillis = NOW,
                snapshotPayload = live,
                lineageId = "legacy",
                highWaterSnapshotId = "hw",
            ),
        )
        db.snapshotDao().insert(snapshot("hw", live, 100, NOW, pinned = true))
        repeat(12) { index ->
            db.snapshotDao().insert(
                snapshot("old-$index", "old-$index", index.toLong(), ANCIENT + index, pinned = false),
            )
        }
    }

    private fun snapshot(
        id: String,
        payload: String,
        sequence: Long,
        capturedAt: Long,
        pinned: Boolean,
    ): SnapshotRecord {
        return SnapshotRecord(
            id = id,
            gameId = GAME,
            sequence = sequence,
            capturedAtMillis = capturedAt,
            reason = if (pinned) "checkpoint" else "cadence",
            pinned = pinned,
            payload = payload,
            progressJson = null,
            lineageId = "legacy",
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

    private fun shark(feature: Int): String {
        val json = JSONObject()
            .put("VERSION", 3)
            .put("feature", feature)
            .put("totalTimePlayed", "10")
            .put("shark_level", 1)
            .put("total_fish", JSONObject().put("s", 1).put("e", "1").put("c", JSONArray().put(1)))
            .put("prestige", JSONObject().put("times", 1))
            .put("core", JSONObject().put("times", 0))
            .put("humanoid", JSONObject().put("times", 0))
            .put("singularity", JSONObject().put("first", false))
        return Base64.getEncoder().encodeToString(json.toString().toByteArray(Charsets.UTF_8))
    }

    private companion object {
        const val GAME = "shark-incremental"
        const val NOW = 1_800_000_000_000L
        const val ANCIENT = 1_500_000_000_000L
    }
}
