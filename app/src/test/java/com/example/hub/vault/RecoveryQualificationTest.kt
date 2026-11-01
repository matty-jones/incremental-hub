package com.example.hub.vault

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.hub.database.GameInstance
import com.example.hub.database.HubDatabase
import com.example.hub.database.SnapshotRecord
import com.example.hub.games.PayloadDigest
import com.example.hub.games.ProgressionClass
import com.example.hub.games.SharkIncrementalAdapter
import com.example.hub.session.CaptureDisposition
import com.example.hub.session.CaptureRepository
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Base64
import java.util.concurrent.Executor
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class RecoveryQualificationTest {

    @Test
    fun cleanInstallRestoresChosenSharkBytesLineageAndProgression() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("s5b-source")
        context.deleteDatabase("s5b-clean")
        val source = openNamed("s5b-source")
        val chosen = shark(feature = 9, prestige = 2639)
        val older = shark(feature = 4, prestige = 2)
        try {
            source.snapshotDao().insert(sharkRow("older", 1, older, "lineage-shark"))
            source.snapshotDao().insert(sharkRow("chosen", 2, chosen, "lineage-shark"))
            source.gameInstanceDao().upsert(instance(chosen, "chosen", "lineage-shark"))
            val exported = VaultArchive(
                source.snapshotDao(),
                emptyApi(),
                IhubPublisher(source, source.snapshotDao(), source.gameInstanceDao()),
                "s5b",
            ).exportGame("shark-incremental")
            assertTrue(exported is IhubExportResult.Ready)
            val file = (exported as IhubExportResult.Ready).file
            val clean = openNamed("s5b-clean")
            try {
                val imported = VaultArchive(
                    clean.snapshotDao(),
                    emptyApi(),
                    IhubPublisher(clean, clean.snapshotDao(), clean.gameInstanceDao()),
                    "s5b",
                ).import(file)
                assertEquals(2, imported.imported)
                assertNull(clean.gameInstanceDao().get("shark-incremental")?.snapshotPayload)
                val commit = repository(clean).restoreImportedPointer("shark-incremental", "chosen")
                assertEquals(CaptureDisposition.STORED, commit.disposition)
                val saved = clean.gameInstanceDao().get("shark-incremental")!!
                assertEquals(chosen, saved.snapshotPayload)
                assertEquals("lineage-shark", saved.lineageId)
                assertEquals("chosen", saved.highWaterSnapshotId)
                val stored = clean.snapshotDao().getById("chosen")!!
                assertEquals(chosen, stored.payload)
                assertEquals("lineage-shark", stored.lineageId)
                assertEquals(PayloadDigest.sha256(chosen), stored.contentSha256)
                assertEquals(older, clean.snapshotDao().getById("older")!!.payload)
                val adapter = SharkIncrementalAdapter()
                assertEquals(ProgressionClass.EQUAL, adapter.classifyProgress(saved.snapshotPayload, chosen))
                assertEquals(ProgressionClass.ADVANCED, adapter.classifyProgress(chosen, older))
                val again = repository(clean).restoreImportedPointer("shark-incremental", "older")
                assertEquals(CaptureDisposition.REJECTED, again.disposition)
                assertEquals(chosen, clean.gameInstanceDao().get("shark-incremental")!!.snapshotPayload)
            } finally {
                clean.close()
            }
            file.delete()
        } finally {
            source.close()
        }
        val reopened = openNamed("s5b-clean")
        try {
            val saved = reopened.gameInstanceDao().get("shark-incremental")!!
            assertEquals(chosen, saved.snapshotPayload)
            assertEquals("lineage-shark", saved.lineageId)
            assertEquals(chosen, reopened.snapshotDao().getById("chosen")!!.payload)
            assertEquals(older, reopened.snapshotDao().getById("older")!!.payload)
        } finally {
            reopened.close()
            context.deleteDatabase("s5b-source")
            context.deleteDatabase("s5b-clean")
        }
    }

    @Test
    fun corruptArchivesLeaveTheStoredRow() = runTest {
        val db = memory()
        try {
            val payload = "keeper-\u0000"
            db.snapshotDao().insert(sharkRow("keeper", 1, payload, "legacy").copy(contentSha256 = PayloadDigest.sha256(payload)))
            db.gameInstanceDao().upsert(instance(payload, "keeper", "legacy"))
            val archive = VaultArchive(
                db.snapshotDao(),
                emptyApi(),
                IhubPublisher(db, db.snapshotDao(), db.gameInstanceDao()),
                "s5b",
            )
            val good = IhubArchive.export(
                drafts = listOf(
                    IhubSnapshotDraft(
                        id = "incoming",
                        gameId = "shark-incremental",
                        lineageId = "legacy",
                        sequence = 2,
                        capturedAtMillis = 2L,
                        reason = "cadence",
                        origin = "local-capture",
                        pinned = false,
                        userProtected = false,
                        storedSha256 = PayloadDigest.sha256("incoming"),
                        payload = "incoming",
                    ),
                ),
                archiveId = "good",
                createdAt = "2026-11-01T00:00:00Z",
                exporterVersion = "s5b",
                complete = true,
            ) as IhubExportResult.Ready
            val cases = listOf(
                good.bytes.copyOf(12),
                good.bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0xff).toByte() },
                malformed(),
            )
            for (bytes in cases) {
                val result = archive.import(bytes)
                assertEquals(0, result.imported)
                assertEquals(payload, db.snapshotDao().getById("keeper")!!.payload)
                assertNull(db.snapshotDao().getById("incoming"))
                assertEquals(payload, db.gameInstanceDao().get("shark-incremental")!!.snapshotPayload)
            }
            good.file.delete()
        } finally {
            db.close()
        }
    }

    private fun malformed(): ByteArray {
        val file = File.createTempFile("ihub-malformed", ".zip")
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write("not-json".toByteArray())
                zip.closeEntry()
            }
            return file.readBytes()
        } finally {
            file.delete()
        }
    }

    @Test
    fun invalidPayloadAndOccupiedSlotAreNotActivated() = runTest {
        val db = memory()
        try {
            val adapter = SharkIncrementalAdapter()
            db.snapshotDao().insert(sharkRow("bad", 1, "not-a-shark-save", "lineage-shark"))
            val invalid = repository(db).restoreImportedPointer("shark-incremental", "bad", adapter)
            assertEquals(CaptureDisposition.REJECTED, invalid.disposition)
            assertNull(db.gameInstanceDao().get("shark-incremental")?.snapshotPayload)
            assertEquals("not-a-shark-save", db.snapshotDao().getById("bad")!!.payload)
            val payload = shark(feature = 9, prestige = 10)
            db.snapshotDao().insert(sharkRow("good", 2, payload, "lineage-shark"))
            val adopted = repository(db).restoreImportedPointer("shark-incremental", "good", adapter)
            assertEquals(CaptureDisposition.STORED, adopted.disposition)
            val occupied = repository(db).restoreImportedPointer("shark-incremental", "good", adapter)
            assertEquals(CaptureDisposition.REJECTED, occupied.disposition)
            val saved = db.gameInstanceDao().get("shark-incremental")!!
            assertEquals(payload, saved.snapshotPayload)
            assertEquals("lineage-shark", saved.lineageId)
            assertEquals("good", saved.highWaterSnapshotId)
            assertEquals(payload, db.snapshotDao().getById("good")!!.payload)
        } finally {
            db.close()
        }
    }

    private fun repository(db: HubDatabase): CaptureRepository {
        return CaptureRepository(
            instanceDao = db.gameInstanceDao(),
            snapshotDao = db.snapshotDao(),
            roomDatabase = db,
            outboxDao = db.syncOutboxDao(),
            clock = { 1_700_000_000_000L },
        )
    }

    private fun openNamed(name: String): HubDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val inline = Executor { it.run() }
        return Room.databaseBuilder(context, HubDatabase::class.java, name)
            .allowMainThreadQueries()
            .setQueryExecutor(inline)
            .setTransactionExecutor(inline)
            .build()
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

    private fun instance(payload: String, highWater: String, lineage: String): GameInstance {
        return GameInstance(
            gameId = "shark-incremental",
            profileName = "hub",
            sessionIdentity = "session",
            lastPlayedAtMillis = 20L,
            lastCaptureAtMillis = 20L,
            snapshotPayload = payload,
            lineageId = lineage,
            highWaterSnapshotId = highWater,
        )
    }

    private fun sharkRow(id: String, sequence: Long, payload: String, lineage: String): SnapshotRecord {
        return SnapshotRecord(
            id = id,
            gameId = "shark-incremental",
            sequence = sequence,
            capturedAtMillis = sequence,
            reason = "cadence",
            pinned = false,
            payload = payload,
            progressJson = null,
            lineageId = lineage,
            contentSha256 = PayloadDigest.sha256(payload),
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

    private fun emptyApi() = object : com.example.hub.sync.WorkstationApi {
        override fun fetchSaves(): List<com.example.hub.sync.RemoteSave>? = emptyList()
        override fun putSave(save: com.example.hub.sync.RemoteSave) = null
        override fun lookupUpdate() = com.example.hub.sync.UpdateCheck.Current
        override fun download(url: String, destination: File) = false
        override fun fetchHistoryIndex(
            gameId: String,
            afterReceivedAt: Long,
            afterSnapshotId: String,
            limit: Int,
        ) = com.example.hub.sync.HistoryPage(emptyList(), complete = true)
    }
}
