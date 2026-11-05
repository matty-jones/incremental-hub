package com.example.hub.vault

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.hub.database.GameInstance
import com.example.hub.database.HubDatabase
import com.example.hub.database.SnapshotReasons
import com.example.hub.database.SnapshotRecord
import com.example.hub.database.SnapshotRetention
import com.example.hub.database.SyncOutboxEntry
import com.example.hub.games.PayloadDigest
import com.example.hub.session.JournalPhase
import com.example.hub.session.RestoreDirection
import com.example.hub.session.RestoreJournal
import com.example.hub.session.RestoreJournalStore
import com.example.hub.sync.HistoryPage
import com.example.hub.sync.PutResult
import com.example.hub.sync.RemoteSave
import com.example.hub.sync.UpdateCheck
import com.example.hub.sync.WorkstationApi
import java.io.File
import java.time.Instant
import java.util.concurrent.Executor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class SnapshotDeletionTest {
    @Test
    fun deletesOneRedundantAutomaticSnapshot() = runTest {
        val db = open()
        try {
            seedPair(db)
            val service = service(db)
            val before = db.gameInstanceDao().get(GAME)!!
            val kept = db.snapshotDao().getById("hw")!!
            val decision = service.preview(GAME, "copy", workstationOnly = false)
            assertTrue(decision.eligible)
            assertEquals("copy", decision.fingerprint!!.snapshotId)
            val result = service.delete(decision.fingerprint!!)
            assertTrue(result.deleted)
            assertNull(result.refusal)
            assertTrue(result.message.contains("copy"))
            assertNull(db.snapshotDao().getById("copy"))
            assertEquals(kept, db.snapshotDao().getById("hw"))
            assertEquals(before, db.gameInstanceDao().get(GAME))
        } finally {
            db.close()
        }
    }

    @Test
    fun refusesEachProtectedReasonAndPin() = runTest {
        val db = open()
        try {
            seedPair(db)
            val cases = listOf(
                protected("migrated", SnapshotReasons.MIGRATED_BASELINE, pinned = true) to DeletionRefusal.MIGRATED_BASELINE,
                protected("checkpoint", SnapshotReasons.CHECKPOINT, pinned = true) to DeletionRefusal.CHECKPOINT,
                protected("diagnostic", SnapshotReasons.DIAGNOSTIC_BACKUP, pinned = true) to DeletionRefusal.DIAGNOSTIC_BACKUP,
                protected("pre", SnapshotReasons.PRE_RESTORE, pinned = true) to DeletionRefusal.PRE_RESTORE,
                protected("post", SnapshotReasons.POST_RESTORE, pinned = true) to DeletionRefusal.POST_RESTORE,
                protected("quarantine", SnapshotReasons.QUARANTINE, pinned = true) to DeletionRefusal.QUARANTINE,
                protected("workstation", SnapshotReasons.WORKSTATION, pinned = false) to DeletionRefusal.WORKSTATION,
                protected("lineage", SnapshotReasons.LINEAGE_BASE, pinned = true) to DeletionRefusal.LINEAGE_BASE,
                row("pinned", "pinned-bytes", 30, NOW, pinned = true) to DeletionRefusal.PINNED,
                row("user", "user-bytes", 31, NOW, userProtected = true) to DeletionRefusal.USER_PROTECTED,
                row("retained", LIVE, 32, NOW, reason = SnapshotReasons.RETAINED_POINTER) to DeletionRefusal.RETAINED_POINTER,
                row("retained-pin", "retained-pin", 33, NOW, reason = SnapshotReasons.RETAINED_POINTER, pinned = true) to
                    DeletionRefusal.RETAINED_POINTER,
            )
            for ((record, _) in cases) db.snapshotDao().insert(record)
            val before = db.snapshotDao().history(GAME)
            val service = service(db)
            for ((record, refusal) in cases) {
                val decision = service.preview(GAME, record.id, workstationOnly = false)
                assertFalse(record.id, decision.eligible)
                assertEquals(record.id, refusal, decision.refusal)
                val result = service.delete(decision.fingerprint!!)
                assertFalse(result.deleted)
                assertEquals(refusal, result.refusal)
                assertTrue(result.message.contains("Nothing was deleted."))
            }
            val remote = service.preview(GAME, "remote-only", workstationOnly = true)
            assertEquals(DeletionRefusal.WORKSTATION_ONLY, remote.refusal)
            assertNull(remote.fingerprint)
            db.snapshotDao().insert(row("other-game", "other", 1, NOW, gameId = "fe000000"))
            val wrong = service.preview(GAME, "other-game", workstationOnly = false)
            assertEquals(DeletionRefusal.WRONG_GAME, wrong.refusal)
            assertEquals("other", db.snapshotDao().getById("other-game")!!.payload)
            val missing = service.preview(GAME, "missing", workstationOnly = false)
            assertEquals(DeletionRefusal.NOT_ON_PHONE, missing.refusal)
            assertEquals(before, db.snapshotDao().history(GAME))
        } finally {
            db.close()
        }
    }

    @Test
    fun protectsHighWaterAndTheSoleLivePayload() = runTest {
        val db = open()
        try {
            seedPair(db)
            val service = service(db)
            val copy = service.preview(GAME, "copy", workstationOnly = false)
            assertTrue(copy.eligible)
            val highWater = service.delete(service.preview(GAME, "hw", workstationOnly = false).fingerprint!!)
            assertEquals(DeletionRefusal.HIGH_WATER, highWater.refusal)
            assertEquals(LIVE, db.snapshotDao().getById("hw")!!.payload)
            assertEquals(LIVE, db.snapshotDao().getById("copy")!!.payload)
            db.snapshotDao().insert(row("mirror", "current-payload", 40, NOW))
            val moved = db.gameInstanceDao().get(GAME)!!.copy(snapshotPayload = "current-payload")
            db.gameInstanceDao().upsert(moved)
            val sole = service.delete(service.preview(GAME, "mirror", workstationOnly = false).fingerprint!!)
            assertEquals(DeletionRefusal.ACTIVE_POINTER, sole.refusal)
            assertEquals("current-payload", db.snapshotDao().getById("mirror")!!.payload)
            assertEquals("hw", db.gameInstanceDao().get(GAME)!!.highWaterSnapshotId)
            assertEquals(LIVE, db.snapshotDao().getById("copy")!!.payload)
        } finally {
            db.close()
        }
    }

    @Test
    fun refusesRetainedPointersAndTheLastLineageCopy() = runTest {
        val db = open()
        try {
            seedPair(db)
            db.snapshotDao().insert(
                row("kept-retained", LIVE, 8, NOW, reason = SnapshotReasons.RETAINED_POINTER),
            )
            db.snapshotDao().insert(
                row("last-line", "only-line-payload", 1, NOW, lineage = "other-line"),
            )
            val service = service(db)
            val retained = service.preview(GAME, "kept-retained", workstationOnly = false)
            assertEquals(DeletionRefusal.RETAINED_POINTER, retained.refusal)
            val last = service.preview(GAME, "last-line", workstationOnly = false)
            assertEquals(DeletionRefusal.LAST_LINEAGE_COPY, last.refusal)
            assertEquals("only-line-payload", db.snapshotDao().getById("last-line")!!.payload)
            assertEquals("other-line", db.snapshotDao().getById("last-line")!!.lineageId)
        } finally {
            db.close()
        }
    }

    @Test
    fun refusesSyncExportAndRestoreConflicts() = runTest {
        val db = open()
        try {
            seedPair(db)
            db.syncOutboxDao().insert(outbox("hw", LIVE))
            val service = service(db)
            assertTrue(service.preview(GAME, "copy", workstationOnly = false).eligible)
            db.syncOutboxDao().insert(outbox("copy", LIVE))
            val pending = service.preview(GAME, "copy", workstationOnly = false)
            assertEquals(DeletionRefusal.PENDING_SYNC, pending.refusal)
            assertNotNull(db.syncOutboxDao().find(SyncOutboxEntry.keyFor("copy")))
            db.syncOutboxDao().markAcked(SyncOutboxEntry.keyFor("copy"), "remote")
            assertTrue(service.preview(GAME, "copy", workstationOnly = false).eligible)
            ExportHold.hold(listOf("copy"))
            try {
                assertEquals(
                    DeletionRefusal.ACTIVE_EXPORT,
                    service.preview(GAME, "copy", workstationOnly = false).refusal,
                )
            } finally {
                ExportHold.release(listOf("copy"))
            }
            ActiveRestore.hold("copy")
            try {
                assertEquals(
                    DeletionRefusal.ACTIVE_RESTORE,
                    service.preview(GAME, "copy", workstationOnly = false).refusal,
                )
            } finally {
                ActiveRestore.release("copy")
            }
            val incident = db.gameInstanceDao().get(GAME)!!.copy(captureIncident = "open")
            db.gameInstanceDao().upsert(incident)
            assertEquals(
                DeletionRefusal.RECOVERY_INCIDENT,
                service.preview(GAME, "copy", workstationOnly = false).refusal,
            )
            db.gameInstanceDao().upsert(incident.copy(captureIncident = null))
            val directory = File.createTempFile("journals", "").apply {
                delete()
                mkdirs()
            }
            val journals = RestoreJournalStore(directory)
            journals.write(
                RestoreJournal(
                    profileName = "hub-game-shark-incremental",
                    gameId = GAME,
                    phase = JournalPhase.PREPARED,
                    appliedKeys = 0,
                    previousSlots = emptyList(),
                    intendedSlots = emptyList(),
                    previousPointer = LIVE,
                    intendedPointer = LIVE,
                    direction = RestoreDirection.INTENDED,
                ),
            )
            val watched = service(db, journals)
            assertEquals(
                DeletionRefusal.ACTIVE_RESTORE,
                watched.preview(GAME, "copy", workstationOnly = false).refusal,
            )
            assertNotNull(journals.read("hub-game-shark-incremental"))
            assertEquals(LIVE, db.snapshotDao().getById("copy")!!.payload)
            directory.deleteRecursively()
        } finally {
            ExportHold.release(listOf("copy"))
            ActiveRestore.release("copy")
            db.close()
        }
    }

    @Test
    fun oneOfTwoSimultaneousDeletesWins() = runTest {
        val db = open()
        try {
            seedPair(db)
            val service = service(db)
            val fingerprint = service.preview(GAME, "copy", workstationOnly = false).fingerprint!!
            val results = listOf(
                async { service.delete(fingerprint) },
                async { service.delete(fingerprint) },
            ).map { it.await() }
            assertEquals(1, results.count { it.deleted })
            assertEquals(1, results.count { !it.deleted })
            assertNull(db.snapshotDao().getById("copy"))
            assertEquals(LIVE, db.snapshotDao().getById("hw")!!.payload)
            assertEquals(LIVE, db.gameInstanceDao().get(GAME)!!.snapshotPayload)
        } finally {
            db.close()
        }
    }

    @Test
    fun protectionChangeBetweenPreviewAndConfirmIsStale() = runTest {
        val db = open()
        try {
            seedPair(db)
            val service = service(db)
            val preview = service.preview(GAME, "copy", workstationOnly = false)
            assertTrue(preview.eligible)
            db.snapshotDao().setUserProtected("copy", true)
            val result = service.delete(preview.fingerprint!!)
            assertFalse(result.deleted)
            assertEquals(DeletionRefusal.STALE, result.refusal)
            val kept = db.snapshotDao().getById("copy")!!
            assertTrue(kept.userProtected)
            assertEquals(LIVE, kept.payload)
            assertEquals("hw", db.gameInstanceDao().get(GAME)!!.highWaterSnapshotId)
        } finally {
            db.close()
        }
    }

    @Test
    fun exportSelectionBlocksDeletionBeforeTheHold() = runTest {
        val db = open()
        try {
            seedPair(db)
            val service = service(db)
            val fingerprint = service.preview(GAME, "copy", workstationOnly = false).fingerprint!!
            val archive = VaultArchive(
                db.snapshotDao(),
                emptyApi(),
                IhubPublisher(db, db.snapshotDao(), db.gameInstanceDao()),
                "test",
            )
            var sawSelection = false
            archive.beforeHold = {
                if ("copy" in ExportHold.protectedIds()) {
                    sawSelection = true
                    val result = service.delete(fingerprint)
                    assertFalse(result.deleted)
                    assertEquals(DeletionRefusal.ACTIVE_EXPORT, result.refusal)
                }
            }
            archive.afterSelection = {
                val result = service.delete(fingerprint)
                assertFalse(result.deleted)
                assertEquals(DeletionRefusal.ACTIVE_EXPORT, result.refusal)
                assertTrue("copy" in ExportHold.protectedIds())
            }
            val exported = archive.exportGame(GAME)
            assertTrue(exported is IhubExportResult.Ready)
            assertTrue(sawSelection)
            assertEquals(LIVE, db.snapshotDao().getById("copy")!!.payload)
            assertTrue(ExportHold.protectedIds().isEmpty())
            (exported as IhubExportResult.Ready).file.delete()

            db.snapshotDao().insert(row("ancient-dup", LIVE, 0, ANCIENT))
            ExportHold.beginSelection("ancient-dup")
            try {
                SnapshotRetention.prune(db.snapshotDao(), db.gameInstanceDao(), GAME, NOW)
                assertEquals(LIVE, db.snapshotDao().getById("ancient-dup")!!.payload)
            } finally {
                ExportHold.endSelection("ancient-dup")
            }
            SnapshotRetention.prune(db.snapshotDao(), db.gameInstanceDao(), GAME, NOW)
            assertNull(db.snapshotDao().getById("ancient-dup"))
            assertEquals(LIVE, db.snapshotDao().getById("hw")!!.payload)
        } finally {
            db.close()
        }
    }

    @Test
    fun retentionCoverageHoldsAfterDeletion() = runTest {
        val db = open()
        try {
            seedPair(db)
            repeat(9) { index ->
                db.snapshotDao().insert(row("recent-$index", "recent-$index", 100L + index, NOW))
            }
            db.snapshotDao().insert(row("extra", "extra-payload", 0, NOW))
            val day = Instant.parse("2026-06-01T08:00:00Z").toEpochMilli()
            db.snapshotDao().insert(row("day-only", "day-payload", 4, day))
            val service = service(db)
            val beforeRows = db.snapshotDao().history(GAME)
            val beforeKeep = SnapshotRetention.keepIds(
                beforeRows,
                NOW,
                protectedIds = setOf("hw"),
            )
            assertFalse("extra" in beforeKeep)
            assertTrue("day-only" in beforeKeep)
            val deleted = service.delete(service.preview(GAME, "extra", workstationOnly = false).fingerprint!!)
            assertTrue(deleted.deleted)
            val afterRows = db.snapshotDao().history(GAME)
            assertNull(afterRows.find { it.id == "extra" })
            val afterKeep = SnapshotRetention.keepIds(afterRows, NOW, protectedIds = setOf("hw"))
            assertEquals(beforeKeep, afterKeep)
            for (row in beforeRows.filter { it.id != "extra" }) {
                assertEquals(row, afterRows.single { it.id == row.id })
            }
            val dayDecision = service.preview(GAME, "day-only", workstationOnly = false)
            assertEquals(DeletionRefusal.RETENTION, dayDecision.refusal)
            assertEquals("day-payload", db.snapshotDao().getById("day-only")!!.payload)
            assertEquals("legacy", db.snapshotDao().getById("day-only")!!.lineageId)
        } finally {
            db.close()
        }
    }

    @Test
    fun eligibleDeletionLeavesEveryRetentionSelection() = runTest {
        val db = open()
        try {
            db.gameInstanceDao().upsert(instance(LIVE).copy(highWaterSnapshotId = "hw"))
            db.snapshotDao().insert(row("hw", LIVE, 10_000, NOW))
            repeat(12) { index ->
                db.snapshotDao().insert(row("hour-$index", "payload-$index", index.toLong(), NOW))
            }
            val service = service(db)
            val beforeRows = db.snapshotDao().history(GAME)
            val beforeKeep = SnapshotRetention.keepIds(beforeRows, NOW, protectedIds = setOf("hw"))
            assertTrue("hour-3" in beforeKeep)
            assertFalse("hour-2" in beforeKeep)
            assertEquals(DeletionRefusal.HIGH_WATER, service.preview(GAME, "hw", workstationOnly = false).refusal)
            for (index in 3..11) {
                val decision = service.preview(GAME, "hour-$index", workstationOnly = false)
                assertEquals("hour-$index", DeletionRefusal.RETENTION, decision.refusal)
                assertEquals("payload-$index", db.snapshotDao().getById("hour-$index")!!.payload)
            }
            val deleted = service.delete(service.preview(GAME, "hour-2", workstationOnly = false).fingerprint!!)
            assertTrue(deleted.deleted)
            val afterRows = db.snapshotDao().history(GAME)
            assertNull(afterRows.find { it.id == "hour-2" })
            for (kept in beforeRows.filter { it.id in beforeKeep }) {
                assertEquals(kept, afterRows.single { it.id == kept.id })
            }
            assertEquals(LIVE, db.gameInstanceDao().get(GAME)!!.snapshotPayload)
            assertEquals("hw", db.gameInstanceDao().get(GAME)!!.highWaterSnapshotId)
        } finally {
            db.close()
        }
    }

    @Test
    fun pruningThatStartsFirstStillKeepsAnExportReservation() = runTest {
        val db = open()
        val entered = CompletableDeferred<Unit>()
        val reserved = CompletableDeferred<Unit>()
        try {
            seedAncient(db)
            SnapshotRetention.beforeExclusion = {
                entered.complete(Unit)
                reserved.await()
            }
            val pruning = async { SnapshotRetention.prune(db.snapshotDao(), db.gameInstanceDao(), GAME, NOW) }
            entered.await()
            val archive = archive(db)
            archive.beforeHold = {
                reserved.complete(Unit)
                pruning.await()
                assertEquals("old-0", db.snapshotDao().getById("old-0")!!.payload)
                assertTrue("old-0" in ExportHold.protectedIds())
                assertNull(db.snapshotDao().getById("old-1"))
            }
            val exported = archive.exportSnapshot("old-0")
            assertTrue(exported is IhubExportResult.Ready)
            assertEquals("old-0", db.snapshotDao().getById("old-0")!!.payload)
            assertEquals(LIVE, db.snapshotDao().getById("hw")!!.payload)
            assertTrue(ExportHold.protectedIds().isEmpty())
            (exported as IhubExportResult.Ready).file.delete()
        } finally {
            SnapshotRetention.beforeExclusion = null
            if (!reserved.isCompleted) reserved.complete(Unit)
            db.close()
        }
    }

    @Test
    fun exportReservationBeforePruningKeepsThePayload() = runTest {
        val db = open()
        try {
            seedAncient(db)
            val archive = archive(db)
            archive.beforeHold = {
                SnapshotRetention.prune(db.snapshotDao(), db.gameInstanceDao(), GAME, NOW)
                assertEquals("old-0", db.snapshotDao().getById("old-0")!!.payload)
                assertTrue("old-0" in ExportHold.protectedIds())
                assertNull(db.snapshotDao().getById("old-1"))
                assertNull(db.snapshotDao().getById("old-2"))
            }
            val exported = archive.exportSnapshot("old-0")
            assertTrue(exported is IhubExportResult.Ready)
            assertEquals("old-0", db.snapshotDao().getById("old-0")!!.payload)
            assertEquals(LIVE, db.gameInstanceDao().get(GAME)!!.snapshotPayload)
            assertTrue(ExportHold.protectedIds().isEmpty())
            (exported as IhubExportResult.Ready).file.delete()
        } finally {
            db.close()
        }
    }

    @Test
    fun overlappingExportsStayIntactWhilePruning() = runTest {
        val db = open()
        val firstReady = CompletableDeferred<Unit>()
        val secondReady = CompletableDeferred<Unit>()
        val pruned = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            seedAncient(db)
            val first = archive(db)
            val second = archive(db)
            first.beforeHold = {
                firstReady.complete(Unit)
                secondReady.await()
                SnapshotRetention.prune(db.snapshotDao(), db.gameInstanceDao(), GAME, NOW)
                assertEquals("old-0", db.snapshotDao().getById("old-0")!!.payload)
                assertTrue("old-0" in ExportHold.protectedIds())
                assertNull(db.snapshotDao().getById("old-1"))
                pruned.complete(Unit)
                release.await()
            }
            second.beforeHold = {
                secondReady.complete(Unit)
                release.await()
            }
            val firstExport = async { first.exportSnapshot("old-0") }
            firstReady.await()
            val secondExport = async { second.exportSnapshot("old-0") }
            secondReady.await()
            pruned.await()
            release.complete(Unit)
            val firstResult = firstExport.await()
            val secondResult = secondExport.await()
            assertTrue(firstResult is IhubExportResult.Ready)
            assertTrue(secondResult is IhubExportResult.Ready)
            assertEquals("old-0", db.snapshotDao().getById("old-0")!!.payload)
            assertEquals(LIVE, db.snapshotDao().getById("hw")!!.payload)
            assertTrue(ExportHold.protectedIds().isEmpty())
            (firstResult as IhubExportResult.Ready).file.delete()
            (secondResult as IhubExportResult.Ready).file.delete()
        } finally {
            if (!secondReady.isCompleted) secondReady.complete(Unit)
            if (!pruned.isCompleted) pruned.complete(Unit)
            if (!release.isCompleted) release.complete(Unit)
            db.close()
        }
    }

    @Test
    fun manualDeletionExportAndPruningLeaveTheReservedPayload() = runTest {
        val db = open()
        try {
            seedAncient(db)
            val service = service(db)
            val reserved = service.preview(GAME, "old-0", workstationOnly = false).fingerprint!!
            val other = service.preview(GAME, "old-1", workstationOnly = false).fingerprint!!
            assertTrue(service.preview(GAME, "old-0", workstationOnly = false).eligible)
            val archive = archive(db)
            archive.beforeHold = {
                coroutineScope {
                    val selectedDelete = async { service.delete(reserved) }
                    val otherDelete = async { service.delete(other) }
                    val pruning = async {
                        SnapshotRetention.prune(db.snapshotDao(), db.gameInstanceDao(), GAME, NOW)
                    }
                    val selectedResult = selectedDelete.await()
                    val otherResult = otherDelete.await()
                    pruning.await()
                    assertFalse(selectedResult.deleted)
                    assertEquals(DeletionRefusal.ACTIVE_EXPORT, selectedResult.refusal)
                    assertTrue(otherResult.deleted || otherResult.refusal == DeletionRefusal.NOT_ON_PHONE)
                    assertEquals("old-0", db.snapshotDao().getById("old-0")!!.payload)
                    assertNull(db.snapshotDao().getById("old-1"))
                    assertNull(db.snapshotDao().getById("old-2"))
                    assertTrue("old-0" in ExportHold.protectedIds())
                }
            }
            val exported = archive.exportSnapshot("old-0")
            assertTrue(exported is IhubExportResult.Ready)
            assertEquals("old-0", db.snapshotDao().getById("old-0")!!.payload)
            assertEquals(LIVE, db.snapshotDao().getById("hw")!!.payload)
            assertEquals(LIVE, db.gameInstanceDao().get(GAME)!!.snapshotPayload)
            assertEquals("hw", db.gameInstanceDao().get(GAME)!!.highWaterSnapshotId)
            assertTrue(ExportHold.protectedIds().isEmpty())
            (exported as IhubExportResult.Ready).file.delete()
        } finally {
            db.close()
        }
    }

    @Test
    fun reimportRestoresADeletedHistoricalRow() = runTest {
        val db = open()
        try {
            seedPair(db)
            val archive = VaultArchive(
                db.snapshotDao(),
                emptyApi(),
                IhubPublisher(db, db.snapshotDao(), db.gameInstanceDao()),
                "test",
            )
            val exported = archive.exportGame(GAME) as IhubExportResult.Ready
            val service = service(db)
            val deleted = service.delete(service.preview(GAME, "copy", workstationOnly = false).fingerprint!!)
            assertTrue(deleted.deleted)
            assertNull(db.snapshotDao().getById("copy"))
            archive.import(exported.file)
            exported.file.delete()
            val restored = db.snapshotDao().getById("copy")!!
            assertEquals(LIVE, restored.payload)
            assertEquals(GAME, restored.gameId)
            assertEquals("legacy", restored.lineageId)
            assertEquals("copy", restored.id)
            assertEquals(LIVE, db.snapshotDao().getById("hw")!!.payload)
            val instance = db.gameInstanceDao().get(GAME)!!
            assertEquals(LIVE, instance.snapshotPayload)
            assertEquals("hw", instance.highWaterSnapshotId)
        } finally {
            db.close()
        }
    }

    @Test
    fun injectedFailureRollsTheDeletionBack() = runTest {
        val db = open()
        try {
            seedPair(db)
            val service = service(db)
            val before = db.snapshotDao().history(GAME)
            val instance = db.gameInstanceDao().get(GAME)!!
            service.beforeCommit = { error("injected failure") }
            val fingerprint = service.preview(GAME, "copy", workstationOnly = false).fingerprint!!
            val thrown = runCatching { service.delete(fingerprint) }
            assertEquals("injected failure", thrown.exceptionOrNull()?.message)
            assertEquals(before, db.snapshotDao().history(GAME))
            assertEquals(instance, db.gameInstanceDao().get(GAME))
        } finally {
            db.close()
        }
    }

    private fun service(db: HubDatabase, journals: RestoreJournalStore? = null): SnapshotDeletion {
        return SnapshotDeletion(
            snapshots = db.snapshotDao(),
            instances = db.gameInstanceDao(),
            outbox = db.syncOutboxDao(),
            room = db,
            journals = journals,
        ).also { it.clock = { NOW } }
    }

    private suspend fun seedPair(db: HubDatabase) {
        db.gameInstanceDao().upsert(instance(LIVE).copy(highWaterSnapshotId = "hw"))
        db.snapshotDao().insert(row("hw", LIVE, 2, NOW))
        db.snapshotDao().insert(row("copy", LIVE, 1, NOW))
    }

    private suspend fun seedAncient(db: HubDatabase) {
        db.gameInstanceDao().upsert(instance(LIVE).copy(highWaterSnapshotId = "hw"))
        db.snapshotDao().insert(row("hw", LIVE, 10_000, NOW))
        repeat(12) { index ->
            db.snapshotDao().insert(row("old-$index", "old-$index", index.toLong(), ANCIENT + index))
        }
    }

    private fun archive(db: HubDatabase): VaultArchive {
        return VaultArchive(
            db.snapshotDao(),
            emptyApi(),
            IhubPublisher(db, db.snapshotDao(), db.gameInstanceDao()),
            "test",
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

    private fun instance(payload: String): GameInstance {
        return GameInstance(
            gameId = GAME,
            profileName = "hub",
            sessionIdentity = "session",
            lastPlayedAtMillis = 1L,
            lastCaptureAtMillis = 1L,
            snapshotPayload = payload,
            lineageId = "legacy",
        )
    }

    private fun row(
        id: String,
        payload: String,
        sequence: Long,
        at: Long,
        reason: String = "cadence",
        pinned: Boolean = false,
        userProtected: Boolean = false,
        lineage: String = "legacy",
        gameId: String = GAME,
    ): SnapshotRecord {
        return SnapshotRecord(
            id = id,
            gameId = gameId,
            sequence = sequence,
            capturedAtMillis = at,
            reason = reason,
            pinned = pinned,
            payload = payload,
            progressJson = null,
            lineageId = lineage,
            contentSha256 = PayloadDigest.sha256(payload),
            userProtected = userProtected,
        )
    }

    private fun protected(id: String, reason: String, pinned: Boolean): SnapshotRecord {
        return row(id, "$id-payload", 20, ANCIENT, reason = reason, pinned = pinned)
    }

    private fun outbox(snapshotId: String, payload: String): SyncOutboxEntry {
        return SyncOutboxEntry(
            idempotencyKey = SyncOutboxEntry.keyFor(snapshotId),
            gameId = GAME,
            lineageId = "legacy",
            snapshotId = snapshotId,
            payload = payload,
            contentSha256 = PayloadDigest.sha256(payload),
            capturedAtMillis = NOW,
            reason = "cadence",
            integrity = SyncOutboxEntry.INTEGRITY_ACCEPTED,
            profileName = "hub",
            sessionIdentity = "session",
            lastPlayedAtMillis = 1L,
            resumeToken = null,
            proposePointer = false,
            enqueuedAtMillis = NOW,
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

    private companion object {
        const val GAME = "shark-incremental"
        const val LIVE = "live-payload"
        val NOW: Long = Instant.parse("2026-06-15T12:00:00Z").toEpochMilli()
        val ANCIENT: Long = Instant.parse("2020-01-01T00:00:00Z").toEpochMilli()
    }
}
