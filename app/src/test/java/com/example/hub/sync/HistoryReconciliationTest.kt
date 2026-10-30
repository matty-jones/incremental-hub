package com.example.hub.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.hub.database.HubDatabase
import com.example.hub.database.SnapshotOrigins
import com.example.hub.database.SnapshotReasons
import com.example.hub.database.SnapshotRecord
import com.example.hub.games.PayloadDigest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class HistoryReconciliationTest {

    @Test
    fun tenThousandRemoteSnapshotsStayOnTheServerUntilOneIsRecovered() = runTest {
        val db = memory()
        try {
            val gameId = "shark-incremental"
            val kept = "already-workstation"
            db.snapshotDao().insert(
                SnapshotRecord(
                    id = "kept-local",
                    gameId = gameId,
                    sequence = 1,
                    capturedAtMillis = 1,
                    reason = SnapshotReasons.WORKSTATION,
                    pinned = true,
                    payload = kept,
                    progressJson = null,
                    origin = SnapshotOrigins.WORKSTATION,
                    contentSha256 = PayloadDigest.sha256(kept),
                ),
            )
            val remote = RemoteHistory(10_000)
            remote.failOnCall = 2
            val sync = SaveSync(
                dao = db.gameInstanceDao(),
                api = remote,
                snapshotDao = db.snapshotDao(),
                outboxDao = db.syncOutboxDao(),
                historyCursorDao = db.syncHistoryCursorDao(),
            )
            sync.merge()
            assertEquals(200, db.syncHistoryCursorDao().get(gameId)!!.seenCount)
            assertEquals(listOf("kept-local"), db.snapshotDao().history(gameId).map { it.id })
            assertEquals(0, remote.payloadFetches)

            remote.failOnCall = 0
            sync.merge()
            assertEquals(10_000, db.syncHistoryCursorDao().get(gameId)!!.seenCount)
            assertEquals(kept, db.snapshotDao().getById("kept-local")!!.payload)
            assertEquals(1, db.snapshotDao().history(gameId).size)
            assertEquals(0, remote.payloadFetches)

            val calls = remote.indexCalls
            sync.merge()
            assertEquals(calls + 1, remote.indexCalls)
            assertEquals(10_000, db.syncHistoryCursorDao().get(gameId)!!.seenCount)
            assertEquals(1, db.snapshotDao().history(gameId).size)

            val selected = remote.payloads[4_321]
            assertTrue(sync.recoverSnapshot(gameId, remote.idOf(4_321)))
            val history = db.snapshotDao().history(gameId)
            assertEquals(2, history.size)
            assertEquals(kept, history.first { it.id == "kept-local" }.payload)
            assertTrue(history.any { it.payload == selected && it.pinned && it.reason == SnapshotReasons.WORKSTATION })
            assertEquals(1, remote.payloadFetches)
            assertTrue(sync.recoverSnapshot(gameId, remote.idOf(4_321)))
            assertEquals(2, db.snapshotDao().history(gameId).size)
            assertEquals(2, remote.payloadFetches)
        } finally {
            db.close()
        }
    }

    private fun memory(): HubDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val inline = Executor { it.run() }
        return Room.inMemoryDatabaseBuilder(context, HubDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(inline)
            .setTransactionExecutor(inline)
            .build()
    }

    private class RemoteHistory(count: Int) : WorkstationApi {
        val payloads = List(count) { index -> "remote-payload-$index" }
        var indexCalls = 0
        var payloadFetches = 0
        var failOnCall = 0

        fun idOf(index: Int): String = "s-${index.toString().padStart(5, '0')}"

        override fun fetchSaves(): List<RemoteSave> {
            return listOf(
                RemoteSave(
                    gameId = "shark-incremental",
                    snapshotPayload = "active-pointer",
                    capturedAtMillis = 1,
                    profileName = "hub",
                    sessionIdentity = "remote",
                    lastPlayedAtMillis = 1,
                    resumeToken = null,
                    recoveryState = null,
                ),
            )
        }

        override fun putSave(save: RemoteSave): PutResult = error("legacy put is not used")

        override fun lookupUpdate(): UpdateCheck = UpdateCheck.Current

        override fun download(url: String, destination: File): Boolean = false

        override fun fetchPointer(gameId: String): PointerFetch = PointerFetch.Absent

        override fun fetchHistoryIndex(
            gameId: String,
            afterReceivedAt: Long,
            afterSnapshotId: String,
            limit: Int,
        ): HistoryPage? {
            indexCalls += 1
            if (indexCalls == failOnCall) return null
            val start = payloads.indices.firstOrNull { index ->
                val received = index + 1L
                val id = idOf(index)
                received > afterReceivedAt || (received == afterReceivedAt && id > afterSnapshotId)
            } ?: payloads.size
            val end = minOf(start + limit, payloads.size)
            val entries = (start until end).map { index ->
                SnapshotMetadata(
                    snapshotId = idOf(index),
                    gameId = gameId,
                    lineageId = "legacy",
                    sha256 = "sha-$index",
                    capturedAtMillis = index + 1L,
                    receivedAtMillis = index + 1L,
                    integrity = "accepted",
                    source = "phone",
                )
            }
            return HistoryPage(entries, complete = entries.size < limit)
        }

        override fun fetchSnapshot(gameId: String, snapshotId: String): RemoteSnapshot {
            payloadFetches += 1
            val index = snapshotId.removePrefix("s-").toInt()
            return RemoteSnapshot(
                snapshotId = snapshotId,
                gameId = gameId,
                lineageId = "legacy",
                sha256 = PayloadDigest.sha256(payloads[index]),
                snapshotPayload = payloads[index],
                capturedAtMillis = index + 1L,
                integrity = "accepted",
                source = "phone",
            )
        }
    }
}
