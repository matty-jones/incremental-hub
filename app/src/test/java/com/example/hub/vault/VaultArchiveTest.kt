package com.example.hub.vault

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.hub.database.GameInstance
import com.example.hub.database.HubDatabase
import com.example.hub.database.SnapshotRecord
import com.example.hub.games.PayloadDigest
import com.example.hub.sync.HistoryPage
import com.example.hub.sync.PutResult
import com.example.hub.sync.RemoteSave
import com.example.hub.sync.RemoteSnapshot
import com.example.hub.sync.SnapshotMetadata
import com.example.hub.sync.UpdateCheck
import com.example.hub.sync.WorkstationApi
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
class VaultArchiveTest {

    @Test
    fun exportAndImportKeepBytesWithoutTouchingTheLivePointer() = runTest {
        val source = open()
        val destination = open()
        try {
            val payload = "exact-save"
            source.snapshotDao().insert(row("snap-1", payload))
            source.gameInstanceDao().upsert(instance("live-save"))
            destination.gameInstanceDao().upsert(instance("live-save"))
            val archive = VaultArchive(
                source.snapshotDao(),
                emptyApi(),
                IhubPublisher(source, source.snapshotDao(), source.gameInstanceDao()),
                "0.1.23",
            )
            val exported = archive.exportGame("shark-incremental")
            assertTrue(exported is IhubExportResult.Ready)
            val bytes = (exported as IhubExportResult.Ready).bytes
            val imported = VaultArchive(
                destination.snapshotDao(),
                emptyApi(),
                IhubPublisher(destination, destination.snapshotDao(), destination.gameInstanceDao()),
                "0.1.23",
            ).import(bytes)
            assertEquals(1, imported.imported)
            val stored = destination.snapshotDao().getById("snap-1")!!
            assertEquals(payload, stored.payload)
            assertEquals(PayloadDigest.sha256(payload), stored.contentSha256)
            assertEquals("legacy", stored.lineageId)
            assertEquals(4L, stored.sequence)
            assertEquals("live-save", destination.gameInstanceDao().get("shark-incremental")!!.snapshotPayload)
            val again = VaultArchive(
                destination.snapshotDao(),
                emptyApi(),
                IhubPublisher(destination, destination.snapshotDao(), destination.gameInstanceDao()),
                "0.1.23",
            ).import(bytes)
            assertEquals(0, again.imported)
            assertEquals(1, again.duplicates)
            assertEquals(1, destination.snapshotDao().countForGame("shark-incremental"))
        } finally {
            source.close()
            destination.close()
        }
    }

    @Test
    fun aConflictingIdRollsBackAndAMissingWorkstationRefusesCompletion() = runTest {
        val db = open()
        try {
            db.snapshotDao().insert(row("snap-1", "original"))
            db.gameInstanceDao().upsert(instance("live-save"))
            val payload = "different"
            val conflict = IhubArchive.export(
                drafts = listOf(
                    draft("snap-1", payload),
                    draft("snap-2", "new"),
                ),
                archiveId = "archive",
                createdAt = "2026-10-31T17:00:00Z",
                exporterVersion = "test",
                complete = true,
            ) as IhubExportResult.Ready
            val publisher = IhubPublisher(db, db.snapshotDao(), db.gameInstanceDao())
            val read = (IhubArchive.read(conflict.bytes) as IhubReadResult.Ok).archive
            val result = publisher.import(read)
            assertTrue(result.message.contains("different bytes"))
            assertEquals("original", db.snapshotDao().getById("snap-1")!!.payload)
            assertEquals(null, db.snapshotDao().getById("snap-2"))
            assertEquals("live-save", db.gameInstanceDao().get("shark-incremental")!!.snapshotPayload)

            val remote = VaultArchive(
                db.snapshotDao(),
                missingApi(),
                publisher,
                "test",
            ).exportAll()
            assertTrue(remote is IhubExportResult.Refused)
            assertTrue((remote as IhubExportResult.Refused).omissions.isNotEmpty())
        } finally {
            db.close()
        }
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

    private fun instance(payload: String): GameInstance {
        return GameInstance(
            gameId = "shark-incremental",
            profileName = "hub",
            sessionIdentity = "session",
            lastPlayedAtMillis = 1L,
            lastCaptureAtMillis = 1L,
            snapshotPayload = payload,
            lineageId = "legacy",
        )
    }

    private fun row(id: String, payload: String): SnapshotRecord {
        return SnapshotRecord(
            id = id,
            gameId = "shark-incremental",
            sequence = 4,
            capturedAtMillis = 1_700_000_000_000L,
            reason = "cadence",
            pinned = false,
            payload = payload,
            progressJson = null,
            lineageId = "legacy",
            contentSha256 = PayloadDigest.sha256(payload),
        )
    }

    private fun draft(id: String, payload: String): IhubSnapshotDraft {
        return IhubSnapshotDraft(
            id = id,
            gameId = "shark-incremental",
            lineageId = "legacy",
            sequence = 4,
            capturedAtMillis = 1_700_000_000_000L,
            reason = "cadence",
            origin = "local-capture",
            pinned = false,
            userProtected = false,
            storedSha256 = PayloadDigest.sha256(payload),
            payload = payload,
        )
    }

    private fun emptyApi(): WorkstationApi = object : WorkstationApi {
        override fun fetchSaves(): List<RemoteSave>? = emptyList()
        override fun putSave(save: RemoteSave): PutResult? = null
        override fun lookupUpdate(): UpdateCheck = UpdateCheck.Current
        override fun download(url: String, destination: File): Boolean = false
        override fun fetchHistoryIndex(
            gameId: String,
            afterReceivedAt: Long,
            afterSnapshotId: String,
            limit: Int,
        ): HistoryPage = HistoryPage(emptyList(), complete = true)
    }

    private fun missingApi(): WorkstationApi = object : WorkstationApi {
        override fun fetchSaves(): List<RemoteSave>? = null
        override fun putSave(save: RemoteSave): PutResult? = null
        override fun lookupUpdate(): UpdateCheck = UpdateCheck.Unreachable
        override fun download(url: String, destination: File): Boolean = false
        override fun fetchHistoryIndex(
            gameId: String,
            afterReceivedAt: Long,
            afterSnapshotId: String,
            limit: Int,
        ): HistoryPage = HistoryPage(
            listOf(
                SnapshotMetadata(
                    snapshotId = "remote-only",
                    gameId = gameId,
                    lineageId = "legacy",
                    sha256 = "abc",
                    capturedAtMillis = 1L,
                    receivedAtMillis = 1L,
                    integrity = "accepted",
                    source = "workstation",
                ),
            ),
            complete = true,
        )
        override fun fetchSnapshot(gameId: String, snapshotId: String): RemoteSnapshot? = null
    }
}
