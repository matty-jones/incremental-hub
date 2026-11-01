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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun exportAllIncludesAWorkstationOnlyGame() = runTest {
        val db = open()
        try {
            val payload = "remote-only-save"
            val sha = PayloadDigest.sha256(payload)
            val api = object : WorkstationApi {
                override fun fetchSaves(): List<RemoteSave> = listOf(
                    RemoteSave(
                        gameId = "remote-only",
                        snapshotPayload = payload,
                        capturedAtMillis = 5L,
                        profileName = "hub",
                        sessionIdentity = "session",
                        lastPlayedAtMillis = 5L,
                        resumeToken = null,
                        recoveryState = null,
                    ),
                )
                override fun putSave(save: RemoteSave): PutResult? = null
                override fun lookupUpdate(): UpdateCheck = UpdateCheck.Current
                override fun download(url: String, destination: File): Boolean = false
                override fun fetchHistoryIndex(
                    gameId: String,
                    afterReceivedAt: Long,
                    afterSnapshotId: String,
                    limit: Int,
                ): HistoryPage = HistoryPage(
                    listOf(
                        SnapshotMetadata(
                            snapshotId = "remote-1",
                            gameId = gameId,
                            lineageId = "line-r",
                            sha256 = sha,
                            capturedAtMillis = 5L,
                            receivedAtMillis = 6L,
                            integrity = "accepted",
                            source = "workstation",
                        ),
                    ),
                    complete = true,
                )
                override fun fetchSnapshot(gameId: String, snapshotId: String): RemoteSnapshot = RemoteSnapshot(
                    snapshotId = snapshotId,
                    gameId = gameId,
                    lineageId = "line-r",
                    sha256 = sha,
                    snapshotPayload = payload,
                    capturedAtMillis = 5L,
                    integrity = "accepted",
                    source = "workstation",
                )
            }
            assertTrue(db.snapshotDao().gameIds().isEmpty())
            val exported = VaultArchive(
                db.snapshotDao(),
                api,
                IhubPublisher(db, db.snapshotDao(), db.gameInstanceDao()),
                "test",
            ).exportAll()
            assertTrue(exported is IhubExportResult.Ready)
            val ready = exported as IhubExportResult.Ready
            val read = IhubArchive.read(ready.bytes)
            assertTrue(read is IhubReadResult.Ok)
            val archive = (read as IhubReadResult.Ok).archive
            assertEquals(1, archive.snapshots.size)
            assertEquals("remote-only", archive.snapshots.single().gameId)
            assertEquals(payload, archive.snapshots.single().payload)
            assertTrue(archive.complete)
            ready.file.delete()
        } finally {
            db.close()
        }
    }

    @Test
    fun exportRunsOffTheCallerAndDoesNotHoldEveryPayload() = runTest {
        val caller = Thread.currentThread().name
        val source = open()
        val destination = open()
        try {
            val body = "p".repeat(80_000)
            repeat(12) { index ->
                source.snapshotDao().insert(
                    row("snap-$index", body + index).copy(sequence = index.toLong()),
                )
            }
            source.gameInstanceDao().upsert(instance("live-pointer"))
            destination.gameInstanceDao().upsert(instance("live-pointer"))
            val archive = VaultArchive(
                source.snapshotDao(),
                emptyApi(),
                IhubPublisher(source, source.snapshotDao(), source.gameInstanceDao()),
                "0.1.24",
            )
            val exported = archive.exportGame("shark-incremental")
            assertTrue(exported is IhubExportResult.Ready)
            assertTrue(archive.archiveThread!!.contains("DefaultDispatcher-worker"))
            assertTrue(archive.archiveThread != caller)
            assertTrue(IhubArchive.exportPeakPayloadBytes in 80_000L..90_000L)
            assertTrue(IhubArchive.exportPeakPayloadBytes < 80_000L * 4)
            val ready = exported as IhubExportResult.Ready
            val publisher = IhubPublisher(destination, destination.snapshotDao(), destination.gameInstanceDao())
            val importer = VaultArchive(destination.snapshotDao(), emptyApi(), publisher, "0.1.24")
            val imported = importer.import(ready.file)
            assertEquals(12, imported.imported)
            assertTrue(importer.archiveThread!!.contains("DefaultDispatcher-worker"))
            val again = VaultArchive(destination.snapshotDao(), emptyApi(), publisher, "0.1.24")
                .import(ready.file)
            assertEquals(0, again.imported)
            assertEquals(12, again.duplicates)
            assertEquals("live-pointer", destination.gameInstanceDao().get("shark-incremental")!!.snapshotPayload)
            assertEquals(body + "7", destination.snapshotDao().getById("snap-7")!!.payload)
            assertEquals("live-pointer", source.gameInstanceDao().get("shark-incremental")!!.snapshotPayload)
            ready.file.delete()
        } finally {
            source.close()
            destination.close()
        }
    }

    @Test
    fun networkLossDuringExportOrFetchWritesNoArchive() = runTest {
        val db = open()
        try {
            db.snapshotDao().insert(row("local", "kept-\u0000"))
            db.gameInstanceDao().upsert(instance("live-pointer"))
            val down = object : WorkstationApi {
                override fun fetchSaves(): List<RemoteSave>? = throw java.io.IOException("network down")
                override fun putSave(save: RemoteSave): PutResult? = null
                override fun lookupUpdate(): UpdateCheck = UpdateCheck.Unreachable
                override fun download(url: String, destination: File): Boolean = false
                override fun fetchHistoryIndex(
                    gameId: String,
                    afterReceivedAt: Long,
                    afterSnapshotId: String,
                    limit: Int,
                ): HistoryPage = throw java.io.IOException("network down")
                override fun fetchSnapshot(gameId: String, snapshotId: String): RemoteSnapshot? =
                    throw java.io.IOException("network down")
            }
            val publisher = IhubPublisher(db, db.snapshotDao(), db.gameInstanceDao())
            val archive = VaultArchive(db.snapshotDao(), down, publisher, "0.1.24")
            val game = archive.exportGame("shark-incremental")
            assertTrue(game is IhubExportResult.Refused)
            assertTrue((game as IhubExportResult.Refused).omissions.contains("shark-incremental workstation history"))
            val everything = archive.exportAll()
            assertTrue(everything is IhubExportResult.Refused)
            assertTrue((everything as IhubExportResult.Refused).message.contains("workstation game list"))
            assertEquals("kept-\u0000", db.snapshotDao().getById("local")!!.payload)
            assertEquals("live-pointer", db.gameInstanceDao().get("shark-incremental")!!.snapshotPayload)
        } finally {
            db.close()
        }
    }

    @Test
    fun interruptedImportPublishesNothing() = runTest {
        val db = open()
        try {
            db.gameInstanceDao().upsert(instance("live-pointer"))
            val exported = IhubArchive.export(
                drafts = listOf(draft("snap-a", "alpha-\u0000"), draft("snap-b", "beta")),
                archiveId = "archive",
                createdAt = "2026-10-31T17:00:00Z",
                exporterVersion = "test",
                complete = true,
            ) as IhubExportResult.Ready
            val opened = IhubArchive.open(exported.file) as IhubOpenResult.Ready
            opened.source.interruptAt = 1
            val result = IhubPublisher(db, db.snapshotDao(), db.gameInstanceDao()).import(opened.source)
            assertEquals(0, result.imported)
            assertTrue(result.message.contains("Nothing was published"))
            assertNull(db.snapshotDao().getById("snap-a"))
            assertNull(db.snapshotDao().getById("snap-b"))
            assertEquals("live-pointer", db.gameInstanceDao().get("shark-incremental")!!.snapshotPayload)
            exported.file.delete()
        } finally {
            db.close()
        }
    }

    @Test
    fun blankLegacyHashReimportsWhenBytesAndIdentityAgree() = runTest {
        val db = open()
        try {
            val payload = "migrated-legacy-\u0000"
            db.snapshotDao().insert(row("legacy-row", payload).copy(contentSha256 = ""))
            db.gameInstanceDao().upsert(instance("live-pointer"))
            val exported = IhubArchive.export(
                drafts = listOf(draft("legacy-row", payload), draft("other-row", "second")),
                archiveId = "legacy",
                createdAt = "2026-10-31T17:00:00Z",
                exporterVersion = "test",
                complete = true,
            ) as IhubExportResult.Ready
            val publisher = IhubPublisher(db, db.snapshotDao(), db.gameInstanceDao())
            val read = (IhubArchive.read(exported.bytes) as IhubReadResult.Ok).archive
            val result = publisher.import(read)
            assertEquals(1, result.imported)
            assertEquals(1, result.duplicates)
            val stored = db.snapshotDao().getById("legacy-row")!!
            assertEquals(payload, stored.payload)
            assertEquals("", stored.contentSha256)
            assertEquals("legacy", stored.lineageId)
            assertEquals("live-pointer", db.gameInstanceDao().get("shark-incremental")!!.snapshotPayload)
            exported.file.delete()
        } finally {
            db.close()
        }
    }

    @Test
    fun sameIdWithAnotherGameOrLineageRollsBack() = runTest {
        val db = open()
        try {
            val payload = "shared-bytes"
            db.snapshotDao().insert(row("shared-id", payload))
            db.gameInstanceDao().upsert(instance("live-pointer"))
            val otherGame = draft("shared-id", payload).copy(gameId = "trimps")
            val otherLineage = draft("shared-id", payload).copy(lineageId = "other-lineage")
            for (conflict in listOf(otherGame, otherLineage)) {
                val exported = IhubArchive.export(
                    drafts = listOf(draft("new-row", "brand-new"), conflict),
                    archiveId = "conflict",
                    createdAt = "2026-10-31T17:00:00Z",
                    exporterVersion = "test",
                    complete = true,
                ) as IhubExportResult.Ready
                val read = (IhubArchive.read(exported.bytes) as IhubReadResult.Ok).archive
                val result = IhubPublisher(db, db.snapshotDao(), db.gameInstanceDao()).import(read)
                assertTrue(result.message.contains("Nothing was imported"))
                assertEquals(0, result.imported)
                assertNull(db.snapshotDao().getById("new-row"))
                assertEquals(payload, db.snapshotDao().getById("shared-id")!!.payload)
                assertEquals("shark-incremental", db.snapshotDao().getById("shared-id")!!.gameId)
                assertEquals("legacy", db.snapshotDao().getById("shared-id")!!.lineageId)
                assertEquals("live-pointer", db.gameInstanceDao().get("shark-incremental")!!.snapshotPayload)
                exported.file.delete()
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun changedSelectionRefusesACompleteExport() = runTest {
        val db = open()
        try {
            db.snapshotDao().insert(row("snap-keep", "keep"))
            db.snapshotDao().insert(row("snap-drop", "drop"))
            db.gameInstanceDao().upsert(instance("live-pointer"))
            val archive = VaultArchive(
                db.snapshotDao(),
                emptyApi(),
                IhubPublisher(db, db.snapshotDao(), db.gameInstanceDao()),
                "test",
            )
            archive.afterSelection = {
                db.snapshotDao().deleteByIds("shark-incremental", listOf("snap-drop"))
            }
            val exported = archive.exportGame("shark-incremental")
            assertTrue(exported is IhubExportResult.Refused)
            val refused = exported as IhubExportResult.Refused
            assertTrue(refused.omissions.contains("snap-drop"))
            assertEquals("keep", db.snapshotDao().getById("snap-keep")!!.payload)
            assertNull(db.snapshotDao().getById("snap-drop"))
            assertEquals("live-pointer", db.gameInstanceDao().get("shark-incremental")!!.snapshotPayload)
            assertTrue(ExportHold.protectedIds().isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun largeHistoryExportKeepsOnePayloadResident() = runTest {
        val db = open()
        try {
            val body = "p".repeat(8_000)
            repeat(24) { index ->
                db.snapshotDao().insert(row("bulk-$index", body + index))
            }
            db.gameInstanceDao().upsert(instance("live-pointer"))
            val exported = VaultArchive(
                db.snapshotDao(),
                emptyApi(),
                IhubPublisher(db, db.snapshotDao(), db.gameInstanceDao()),
                "test",
            ).exportGame("shark-incremental")
            assertTrue(exported is IhubExportResult.Ready)
            val ready = exported as IhubExportResult.Ready
            assertEquals(24, ready.snapshotCount)
            assertTrue(IhubArchive.exportPeakPayloadBytes in 8_000L..20_000L)
            assertTrue(IhubArchive.exportPeakPayloadBytes < 8_000L * 4)
            assertEquals("live-pointer", db.gameInstanceDao().get("shark-incremental")!!.snapshotPayload)
            ready.file.delete()
        } finally {
            db.close()
        }
    }

    @Test
    fun overlappingExportsDoNotReleaseEachOthersHold() = runTest {
        val db = open()
        try {
            db.snapshotDao().insert(row("snap-1", "held-bytes"))
            db.gameInstanceDao().upsert(instance("live-pointer"))
            val publisher = IhubPublisher(db, db.snapshotDao(), db.gameInstanceDao())
            val first = VaultArchive(db.snapshotDao(), emptyApi(), publisher, "test")
            val second = VaultArchive(db.snapshotDao(), emptyApi(), publisher, "test")
            val firstSelected = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            var seenBySecond = emptySet<String>()
            first.afterSelection = {
                firstSelected.complete(Unit)
                releaseFirst.await()
            }
            second.afterSelection = {
                seenBySecond = ExportHold.protectedIds()
            }
            val running = async { first.exportGame("shark-incremental") }
            firstSelected.await()
            val finishedSecond = second.exportGame("shark-incremental")
            assertTrue(finishedSecond is IhubExportResult.Ready)
            assertTrue(seenBySecond.contains("snap-1"))
            assertTrue(ExportHold.protectedIds().contains("snap-1"))
            releaseFirst.complete(Unit)
            assertTrue(running.await() is IhubExportResult.Ready)
            assertTrue(ExportHold.protectedIds().isEmpty())
            (finishedSecond as IhubExportResult.Ready).file.delete()
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
