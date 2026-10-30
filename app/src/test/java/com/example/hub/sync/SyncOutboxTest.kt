package com.example.hub.sync

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.hub.database.GameInstance
import com.example.hub.database.HubDatabase
import com.example.hub.database.SyncOutboxEntry
import com.example.hub.games.Fe000000Adapter
import com.example.hub.games.PayloadDigest
import com.example.hub.games.SharkIncrementalAdapter
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class SyncOutboxTest {

    @Test
    fun eightDaysOfflineThenReconnectKeepsTheAdvancedSharkSave() = runTest {
        val db = memory()
        try {
            val server = MemoryWorkstation()
            server.reachable = false
            server.pointer = remotePointer(shark(feature = 0, prestige = 0), version = 3, capturedAt = 9_000_000_000L)
            val sync = SaveSync(db.gameInstanceDao(), server, db.snapshotDao(), db.syncOutboxDao())
            sync.classify = { _, candidate, reference ->
                SharkIncrementalAdapter().classifyProgress(candidate, reference)
            }
            val gameId = "shark-incremental"
            db.gameInstanceDao().upsert(row(gameId))
            var now = 1_700_000_000_000L
            val reconnectAt = now + 8L * DAY
            val repo = CaptureRepository(
                db.gameInstanceDao(),
                db.snapshotDao(),
                db,
                outboxDao = db.syncOutboxDao(),
                clock = { now },
            )
            val adapter = SharkIncrementalAdapter()
            val saved = mutableListOf<String>()
            repeat(8) { index ->
                now = reconnectAt - (8 - index) * DAY
                val payload = shark(feature = 9, prestige = index + 1L)
                saved += payload
                val commit = repo.commitIfSafe(gameId, payload, "cadence", adapter, "session", { true }, false)
                assertEquals(com.example.hub.session.CaptureDisposition.STORED, commit.disposition)
                sync.merge()
                assertEquals(index + 1, db.syncOutboxDao().pendingCount(gameId))
                assertEquals(payload, db.gameInstanceDao().get(gameId)!!.snapshotPayload)
            }
            assertTrue(server.records.isEmpty())
            server.reachable = true
            sync.merge()
            assertEquals(saved.toSet(), server.records.values.map { it.snapshotPayload }.toSet())
            assertEquals(8, server.records.size)
            assertEquals(listOf(saved.last()), server.proposals.map { proposal ->
                server.records.values.first { it.snapshotId == proposal.snapshotId }.snapshotPayload
            })
            assertEquals(saved.last(), db.gameInstanceDao().get(gameId)!!.snapshotPayload)
            assertEquals(0, db.syncOutboxDao().pendingCount(gameId))
            assertTrue(db.snapshotDao().history(gameId).none { it.payload == shark(feature = 0, prestige = 0) })
            val calls = server.appendCalls
            sync.merge()
            assertEquals(calls, server.appendCalls)
            assertEquals(saved.last(), db.gameInstanceDao().get(gameId)!!.snapshotPayload)
        } finally {
            db.close()
        }
    }

    @Test
    fun lostAcknowledgementRetriesOneHistoricalRecordAcrossProcessDeath() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "sync-outbox-death"
        context.deleteDatabase(name)
        val server = MemoryWorkstation()
        val payload = shark(feature = 9, prestige = 4)
        var now = 5_000L
        val created = openFile(name)
        try {
            created.gameInstanceDao().upsert(row("shark-incremental"))
            var failWrite = true
            val repo = CaptureRepository(
                created.gameInstanceDao(),
                created.snapshotDao(),
                created,
                outboxDao = created.syncOutboxDao(),
                clock = { now },
                fault = { stage ->
                    if (failWrite && stage == "after-pointer") error("storage error injected")
                },
            )
            val blew = runCatching {
                repo.commitIfSafe("shark-incremental", payload, "cadence", SharkIncrementalAdapter(), "session", { true }, false)
            }
            assertTrue(blew.isFailure)
            assertNull(created.gameInstanceDao().get("shark-incremental")!!.snapshotPayload)
            assertEquals(0, created.syncOutboxDao().pendingCount("shark-incremental"))
            failWrite = false
            val stored = repo.commitIfSafe(
                "shark-incremental",
                payload,
                "cadence",
                SharkIncrementalAdapter(),
                "session",
                { true },
                false,
            )
            assertEquals(com.example.hub.session.CaptureDisposition.STORED, stored.disposition)
            assertEquals(payload, created.gameInstanceDao().get("shark-incremental")!!.snapshotPayload)
            assertEquals(1, created.syncOutboxDao().pendingCount("shark-incremental"))
        } finally {
            created.close()
        }
        val beforeAck = openFile(name)
        try {
            assertEquals(payload, beforeAck.gameInstanceDao().get("shark-incremental")!!.snapshotPayload)
            assertEquals(1, beforeAck.syncOutboxDao().pendingCount("shark-incremental"))
            server.dropNextAck = true
            val sync = sync(beforeAck, server)
            sync.merge()
            assertEquals(1, server.records.size)
            assertEquals(1, beforeAck.syncOutboxDao().pendingCount("shark-incremental"))
        } finally {
            beforeAck.close()
        }
        val afterDeath = openFile(name)
        try {
            assertEquals(1, afterDeath.syncOutboxDao().pendingCount("shark-incremental"))
            val sync = sync(afterDeath, server)
            sync.merge()
            assertEquals(1, server.records.size)
            assertEquals(0, afterDeath.syncOutboxDao().pendingCount("shark-incremental"))
        } finally {
            afterDeath.close()
        }
        val settled = openFile(name)
        try {
            val before = server.appendCalls
            sync(settled, server).merge()
            assertEquals(before, server.appendCalls)
            assertEquals(1, server.records.size)
            assertEquals(payload, settled.gameInstanceDao().get("shark-incremental")!!.snapshotPayload)
        } finally {
            settled.close()
        }
    }

    @Test
    fun quarantineIsNotUploadedAndUnorderedRemoteCannotBecomeActive() = runTest {
        val db = memory()
        try {
            val server = MemoryWorkstation()
            val gameId = "fe000000"
            db.gameInstanceDao().upsert(row(gameId))
            var now = 10_000L
            val repo = CaptureRepository(
                db.gameInstanceDao(),
                db.snapshotDao(),
                db,
                outboxDao = db.syncOutboxDao(),
                clock = { now },
            )
            val adapter = Fe000000Adapter()
            val first = fe("1")
            val second = fe("2")
            assertEquals(
                com.example.hub.session.CaptureDisposition.STORED,
                repo.commitIfSafe(gameId, first, "cadence", adapter, "session", { true }, false).disposition,
            )
            now += 1_000L
            assertEquals(
                com.example.hub.games.ProgressionClass.UNORDERED,
                repo.commitIfSafe(gameId, second, "cadence", adapter, "session", { true }, false).classification,
            )
            val regressed = shark(feature = 0, prestige = 0)
            db.gameInstanceDao().upsert(row("shark-incremental", shark(feature = 9, prestige = 8)))
            val sharkRepo = CaptureRepository(
                db.gameInstanceDao(),
                db.snapshotDao(),
                db,
                outboxDao = db.syncOutboxDao(),
                clock = { now },
            )
            sharkRepo.commitIfSafe(
                "shark-incremental",
                regressed,
                "cadence",
                SharkIncrementalAdapter(),
                "session",
                { true },
                false,
            )
            assertEquals(shark(feature = 9, prestige = 8), db.gameInstanceDao().get("shark-incremental")!!.snapshotPayload)
            assertTrue(db.syncOutboxDao().pending().none { it.payload == regressed || it.reason == "quarantine" })
            server.pointer = remotePointer(fe("9"), version = 2, capturedAt = now + DAY, gameId = "fe000000")
            val sync = SaveSync(db.gameInstanceDao(), server, db.snapshotDao(), db.syncOutboxDao())
            sync.classify = { id, candidate, reference ->
                if (id == "fe000000") adapter.classifyProgress(candidate, reference)
                else SharkIncrementalAdapter().classifyProgress(candidate, reference)
            }
            sync.merge()
            assertEquals(second, db.gameInstanceDao().get(gameId)!!.snapshotPayload)
            assertEquals(shark(feature = 9, prestige = 8), db.gameInstanceDao().get("shark-incremental")!!.snapshotPayload)
            assertTrue(server.records.values.none { it.snapshotPayload == regressed })
            assertTrue(
                server.proposals.all { proposal ->
                    server.records.values.first { it.snapshotId == proposal.snapshotId }.snapshotPayload ==
                        shark(feature = 9, prestige = 8)
                },
            )
            assertTrue(server.proposals.isNotEmpty())
            assertTrue(
                server.records.values.any {
                    it.snapshotPayload == second && it.integrity == SyncOutboxEntry.INTEGRITY_ARCHIVED
                },
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun upgradedV6SaveUploadsWithoutNewGameplay() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "v6-shark-bootstrap"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name)
        path.parentFile?.mkdirs()
        val feature9 = shark(feature = 9, prestige = 2639)
        val baseline = shark(feature = 9, prestige = 1)
        val quarantined = shark(feature = 0, prestige = 0)
        val keptWorkstation = "already-\u0000-workstation"
        val sqlite = SQLiteDatabase.openOrCreateDatabase(path, null)
        sqlite.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `game_instances` (
              `gameId` TEXT NOT NULL,
              `profile_name` TEXT NOT NULL,
              `session_identity` TEXT NOT NULL,
              `last_played_at_millis` INTEGER NOT NULL,
              `resume_token` TEXT,
              `recovery_state` TEXT,
              `last_capture_at_millis` INTEGER NOT NULL,
              `snapshot_payload` TEXT,
              `last_capture_attempt_at_millis` INTEGER NOT NULL,
              `last_capture_success_at_millis` INTEGER NOT NULL,
              `last_capture_failure_at_millis` INTEGER NOT NULL,
              `last_capture_error` TEXT,
              PRIMARY KEY(`gameId`)
            )
            """.trimIndent(),
        )
        sqlite.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `snapshots` (
              `id` TEXT NOT NULL,
              `game_id` TEXT NOT NULL,
              `sequence` INTEGER NOT NULL,
              `captured_at_millis` INTEGER NOT NULL,
              `reason` TEXT NOT NULL,
              `pinned` INTEGER NOT NULL,
              `payload` TEXT NOT NULL,
              `progress_json` TEXT,
              `origin` TEXT NOT NULL DEFAULT 'local-capture',
              PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        sqlite.execSQL("ALTER TABLE game_instances ADD COLUMN lineage_id TEXT NOT NULL DEFAULT 'legacy'")
        sqlite.execSQL("ALTER TABLE game_instances ADD COLUMN high_water_snapshot_id TEXT")
        sqlite.execSQL("ALTER TABLE game_instances ADD COLUMN capture_incident TEXT")
        sqlite.execSQL("ALTER TABLE game_instances ADD COLUMN capture_incident_sha TEXT")
        sqlite.execSQL("ALTER TABLE game_instances ADD COLUMN capture_incident_count INTEGER NOT NULL DEFAULT 0")
        sqlite.execSQL("ALTER TABLE game_instances ADD COLUMN last_seen_at_millis INTEGER NOT NULL DEFAULT 0")
        sqlite.execSQL("ALTER TABLE snapshots ADD COLUMN lineage_id TEXT NOT NULL DEFAULT 'legacy'")
        sqlite.execSQL("ALTER TABLE snapshots ADD COLUMN content_sha256 TEXT NOT NULL DEFAULT ''")
        sqlite.execSQL(
            """
            INSERT INTO game_instances (
              gameId, profile_name, session_identity, last_played_at_millis, resume_token, recovery_state,
              last_capture_at_millis, snapshot_payload, last_capture_attempt_at_millis, last_capture_success_at_millis,
              last_capture_failure_at_millis, last_capture_error, lineage_id, high_water_snapshot_id,
              capture_incident, capture_incident_sha, capture_incident_count, last_seen_at_millis
            ) VALUES ('shark-incremental', 'hub-game-shark-incremental', 'kept', 40, NULL, NULL, 40, ?, 40, 40, 0, NULL, 'legacy', 'feature9', NULL, NULL, 0, 40)
            """.trimIndent(),
            arrayOf(feature9),
        )
        sqlite.execSQL(
            """
            INSERT INTO game_instances (
              gameId, profile_name, session_identity, last_played_at_millis, resume_token, recovery_state,
              last_capture_at_millis, snapshot_payload, last_capture_attempt_at_millis, last_capture_success_at_millis,
              last_capture_failure_at_millis, last_capture_error, lineage_id, high_water_snapshot_id,
              capture_incident, capture_incident_sha, capture_incident_count, last_seen_at_millis
            ) VALUES ('other-game', 'hub-game-other-game', 'other', 40, NULL, NULL, 40, 'do-not-upload', 40, 40, 0, NULL, 'fresh-lineage', NULL, NULL, NULL, 0, 40)
            """.trimIndent(),
        )
        sqlite.execSQL(
            "INSERT INTO snapshots (id, game_id, sequence, captured_at_millis, reason, pinned, payload, progress_json, origin, lineage_id, content_sha256) VALUES ('feature9', 'shark-incremental', 2, 40, 'post-restore', 1, ?, NULL, 'recovery', 'legacy', ?)",
            arrayOf(feature9, PayloadDigest.sha256(feature9)),
        )
        sqlite.execSQL(
            "INSERT INTO snapshots (id, game_id, sequence, captured_at_millis, reason, pinned, payload, progress_json, origin, lineage_id, content_sha256) VALUES ('baseline', 'shark-incremental', 1, 10, 'migrated-baseline', 1, ?, NULL, 'local-capture', 'legacy', ?)",
            arrayOf(baseline, PayloadDigest.sha256(baseline)),
        )
        sqlite.execSQL(
            "INSERT INTO snapshots (id, game_id, sequence, captured_at_millis, reason, pinned, payload, progress_json, origin, lineage_id, content_sha256) VALUES ('bad', 'shark-incremental', 3, 30, 'quarantine', 1, ?, NULL, 'recovery', 'legacy', ?)",
            arrayOf(quarantined, PayloadDigest.sha256(quarantined)),
        )
        sqlite.execSQL(
            "INSERT INTO snapshots (id, game_id, sequence, captured_at_millis, reason, pinned, payload, progress_json, origin, lineage_id, content_sha256) VALUES ('kept-remote', 'shark-incremental', 4, 20, 'workstation', 1, ?, NULL, 'workstation-sync', 'legacy', ?)",
            arrayOf(keptWorkstation, PayloadDigest.sha256(keptWorkstation)),
        )
        sqlite.version = 6
        sqlite.close()

        val server = MemoryWorkstation()
        val first = openV6(name)
        try {
            assertEquals(feature9, first.gameInstanceDao().get("shark-incremental")!!.snapshotPayload)
            assertEquals(keptWorkstation, first.snapshotDao().getById("kept-remote")!!.payload)
            assertEquals(0, first.syncOutboxDao().pendingCount("shark-incremental"))
            SyncBootstrap.enqueueExisting(first.gameInstanceDao(), first.snapshotDao(), first.syncOutboxDao(), now = 100)
            SyncBootstrap.enqueueExisting(first.gameInstanceDao(), first.snapshotDao(), first.syncOutboxDao(), now = 100)
            val pending = first.syncOutboxDao().pending()
            assertEquals(setOf(feature9, baseline), pending.map { it.payload }.toSet())
            assertTrue(pending.none { it.payload == quarantined || it.payload == "do-not-upload" || it.payload == keptWorkstation })
            assertEquals(listOf(feature9), pending.filter { it.proposePointer }.map { it.payload })
        } finally {
            first.close()
        }

        val reopened = openV6(name)
        try {
            assertEquals(feature9, reopened.snapshotDao().getById("feature9")!!.payload)
            assertEquals(keptWorkstation, reopened.snapshotDao().getById("kept-remote")!!.payload)
            SyncBootstrap.enqueueExisting(reopened.gameInstanceDao(), reopened.snapshotDao(), reopened.syncOutboxDao(), now = 200)
            assertEquals(2, reopened.syncOutboxDao().pendingCount("shark-incremental"))
            server.reachable = false
            server.pointer = remotePointer(shark(feature = 0, prestige = 0), version = 4, capturedAt = 90_000L)
            val offline = sync(reopened, server)
            offline.merge()
            assertEquals(0, server.appendCalls)
            assertEquals(2, reopened.syncOutboxDao().pendingCount("shark-incremental"))
            assertEquals(feature9, reopened.gameInstanceDao().get("shark-incremental")!!.snapshotPayload)
        } finally {
            reopened.close()
        }

        val connected = openV6(name)
        try {
            server.reachable = true
            val sync = sync(connected, server)
            sync.merge()
            assertEquals(setOf(feature9, baseline), server.records.values.map { it.snapshotPayload }.toSet())
            assertEquals(listOf(feature9), server.proposals.map { proposal ->
                server.records.values.first { it.snapshotId == proposal.snapshotId }.snapshotPayload
            })
            assertTrue(server.records.values.none { it.snapshotPayload == quarantined || it.snapshotPayload == "do-not-upload" })
            assertEquals(feature9, connected.gameInstanceDao().get("shark-incremental")!!.snapshotPayload)
            assertEquals(feature9, connected.snapshotDao().getById("feature9")!!.payload)
            assertEquals(keptWorkstation, connected.snapshotDao().getById("kept-remote")!!.payload)
            assertTrue(connected.snapshotDao().history("shark-incremental").none { it.payload == shark(feature = 0, prestige = 0) && it.id != "bad" })
            val calls = server.appendCalls
            val records = server.records.size
            val repo = CaptureRepository(
                connected.gameInstanceDao(),
                connected.snapshotDao(),
                connected,
                outboxDao = connected.syncOutboxDao(),
                clock = { 50_000L },
            )
            repo.commitIfSafe(
                "shark-incremental",
                feature9,
                "checkpoint",
                SharkIncrementalAdapter(),
                "kept",
                { true },
                true,
            )
            sync.merge()
            assertEquals(calls, server.appendCalls)
            assertEquals(records, server.records.size)
            assertEquals(feature9, connected.gameInstanceDao().get("shark-incremental")!!.snapshotPayload)
        } finally {
            connected.close()
        }
    }

    private fun openV6(name: String): HubDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val inline = Executor { it.run() }
        return Room.databaseBuilder(context, HubDatabase::class.java, name)
            .addMigrations(HubDatabase.MIGRATION_6_7, HubDatabase.MIGRATION_7_8)
            .allowMainThreadQueries()
            .setQueryExecutor(inline)
            .setTransactionExecutor(inline)
            .build()
    }

    private fun sync(db: HubDatabase, server: MemoryWorkstation): SaveSync {
        val sync = SaveSync(db.gameInstanceDao(), server, db.snapshotDao(), db.syncOutboxDao())
        sync.classify = { _, candidate, reference ->
            SharkIncrementalAdapter().classifyProgress(candidate, reference)
        }
        return sync
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

    private fun openFile(name: String): HubDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val inline = Executor { it.run() }
        return Room.databaseBuilder(context, HubDatabase::class.java, name)
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

    private fun fe(stars: String): String {
        val json = JSONObject()
            .put("boost", 4)
            .put("currentTab", "main")
            .put("generators", org.json.JSONArray(listOf(1, 2)))
            .put("lastUpdate", 99)
            .put("prestigePower", 8)
            .put("stars", stars)
            .toString()
        return Fe000000Adapter.encodeSave(json)
    }

    private fun remotePointer(
        payload: String,
        version: Int,
        capturedAt: Long,
        gameId: String = "shark-incremental",
    ): RemotePointer {
        return RemotePointer(
            gameId = gameId,
            lineageId = "legacy",
            snapshotId = "remote-regressed",
            version = version,
            snapshotPayload = payload,
            capturedAtMillis = capturedAt,
            sha256 = PayloadDigest.sha256(payload),
            profileName = "hub",
            sessionIdentity = "remote",
            lastPlayedAtMillis = capturedAt,
            resumeToken = null,
            recoveryState = null,
        )
    }

    private class MemoryWorkstation : WorkstationApi {
        val records = linkedMapOf<String, SnapshotUpload>()
        val proposals = mutableListOf<PointerProposal>()
        private val retained = mutableListOf<RemoteSnapshot>()
        var pointer: RemotePointer? = null
            set(value) {
                if (field == null && value != null) retained += value.asSnapshot()
                field = value
            }
        var reachable = true
        var dropNextAck = false
        var appendCalls = 0

        override fun fetchSaves(): List<RemoteSave>? {
            if (!reachable) return null
            val current = pointer ?: return emptyList()
            return listOf(
                RemoteSave(
                    gameId = current.gameId,
                    snapshotPayload = current.snapshotPayload,
                    capturedAtMillis = current.capturedAtMillis,
                    profileName = current.profileName,
                    sessionIdentity = current.sessionIdentity,
                    lastPlayedAtMillis = current.lastPlayedAtMillis,
                    resumeToken = current.resumeToken,
                    recoveryState = current.recoveryState,
                ),
            )
        }

        override fun putSave(save: RemoteSave): PutResult = error("legacy put is not used")

        override fun lookupUpdate(): UpdateCheck = UpdateCheck.Unreachable

        override fun download(url: String, destination: File): Boolean = false

        override fun appendSnapshot(upload: SnapshotUpload): SnapshotUploadResult? {
            if (!reachable) return null
            appendCalls += 1
            val created = records.putIfAbsent(upload.idempotencyKey, upload) == null
            if (dropNextAck) {
                dropNextAck = false
                return null
            }
            return SnapshotUploadResult.Stored(created, upload.snapshotId, PayloadDigest.sha256(upload.snapshotPayload))
        }

        override fun proposePointer(proposal: PointerProposal): PointerProposalResult? {
            if (!reachable) return null
            val upload = records.values.firstOrNull { it.snapshotId == proposal.snapshotId } ?: return null
            proposals += proposal
            val stored = RemotePointer(
                gameId = proposal.gameId,
                lineageId = proposal.lineageId,
                snapshotId = proposal.snapshotId,
                version = proposal.expectedVersion + 1,
                snapshotPayload = upload.snapshotPayload,
                capturedAtMillis = upload.capturedAtMillis,
                sha256 = PayloadDigest.sha256(upload.snapshotPayload),
                profileName = upload.profileName,
                sessionIdentity = upload.sessionIdentity,
                lastPlayedAtMillis = upload.lastPlayedAtMillis,
                resumeToken = upload.resumeToken,
                recoveryState = null,
            )
            pointer = stored
            return PointerProposalResult.Stored(stored)
        }

        override fun fetchPointer(gameId: String): PointerFetch {
            if (!reachable) return PointerFetch.Unreachable
            val current = pointer ?: return PointerFetch.Absent
            if (current.gameId != gameId) return PointerFetch.Absent
            return PointerFetch.Found(current)
        }

        override fun fetchHistory(gameId: String): List<RemoteSnapshot>? {
            if (!reachable) return null
            val rows = records.values.filter { it.gameId == gameId }.map { upload ->
                RemoteSnapshot(
                    snapshotId = upload.snapshotId,
                    gameId = upload.gameId,
                    lineageId = upload.lineageId,
                    sha256 = PayloadDigest.sha256(upload.snapshotPayload),
                    snapshotPayload = upload.snapshotPayload,
                    capturedAtMillis = upload.capturedAtMillis,
                    integrity = upload.integrity,
                    source = "phone",
                )
            }
            val prior = retained.filter { saved ->
                saved.gameId == gameId && rows.none { row -> row.snapshotPayload == saved.snapshotPayload }
            }
            return rows + prior
        }
    }

    private companion object {
        const val DAY = 86_400_000L
    }
}

private fun RemotePointer.asSnapshot(): RemoteSnapshot {
    return RemoteSnapshot(
        snapshotId = snapshotId,
        gameId = gameId,
        lineageId = lineageId,
        sha256 = sha256,
        snapshotPayload = snapshotPayload,
        capturedAtMillis = capturedAtMillis,
        integrity = "accepted",
        source = "legacy-put",
    )
}
