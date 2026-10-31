package com.example.hub.vault

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.hub.database.GameInstance
import com.example.hub.database.HubDatabase
import com.example.hub.database.SnapshotRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class VaultRoomIndexTest {

    @Test
    fun pageMetaSkipsPayloadsForHundredsOfRows() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val inline = Executor { it.run() }
        val db = Room.inMemoryDatabaseBuilder(context, HubDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(inline)
            .setTransactionExecutor(inline)
            .build()
        try {
            db.gameInstanceDao().upsert(
                GameInstance(
                    gameId = SHARK_GAME_ID,
                    profileName = "hub",
                    sessionIdentity = "session",
                    lastPlayedAtMillis = 1L,
                    lastCaptureAtMillis = 1L,
                    snapshotPayload = "pointer",
                    highWaterSnapshotId = "row-3",
                ),
            )
            val payload = "x".repeat(4_000)
            repeat(120) { index ->
                db.snapshotDao().insert(
                    SnapshotRecord(
                        id = "row-$index",
                        gameId = SHARK_GAME_ID,
                        sequence = index.toLong(),
                        capturedAtMillis = 1_000L - index,
                        reason = "cadence",
                        pinned = false,
                        payload = payload + index,
                        progressJson = null,
                        contentSha256 = "sha-$index",
                    ),
                )
            }
            val page = db.snapshotDao().pageMeta(SHARK_GAME_ID, limit = 20, offset = 0)
            assertEquals(20, page.size)
            assertEquals(119L, page.first().sequence)
            assertEquals(100L, page.last().sequence)
            assertEquals((payload + 119).length, page.first().payloadBytes)
            assertEquals(120, db.snapshotDao().countForGame(SHARK_GAME_ID))
            val one = db.snapshotDao().payloadById("row-119")
            assertTrue(one!!.startsWith(payload))
            val index = RoomVaultIndex(db.snapshotDao(), db.gameInstanceDao())
            val catalog = VaultCatalog(index, OfflineWorkstation(), pageSize = 20)
            val browse = catalog.browse(SHARK_GAME_ID, 0, advanceRemote = true)
            assertEquals(20, browse.entries.count { it.onPhone })
            assertEquals(0, catalog.payloadReads)
            assertTrue(browse.entries.none { it.id == "row-0" })
            assertEquals("sha-119", browse.entries.first().sha256)
        } finally {
            db.close()
        }
    }
}

private class OfflineWorkstation : com.example.hub.sync.WorkstationApi {
    override fun fetchSaves(): List<com.example.hub.sync.RemoteSave>? = null
    override fun putSave(save: com.example.hub.sync.RemoteSave): com.example.hub.sync.PutResult? = null
    override fun lookupUpdate(): com.example.hub.sync.UpdateCheck = com.example.hub.sync.UpdateCheck.Unreachable
    override fun download(url: String, destination: java.io.File): Boolean = false
    override fun fetchHistoryIndex(
        gameId: String,
        afterReceivedAt: Long,
        afterSnapshotId: String,
        limit: Int,
    ): com.example.hub.sync.HistoryPage? = null
}
