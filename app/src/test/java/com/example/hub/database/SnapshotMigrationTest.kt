package com.example.hub.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class SnapshotMigrationTest {

    @Test
    fun migrationCopiesExistingSnapshotIntoPinnedHistory() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "snapshot-migration"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name)
        path.parentFile?.mkdirs()
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
            INSERT INTO game_instances (
              gameId, profile_name, session_identity, last_played_at_millis, resume_token,
              recovery_state, last_capture_at_millis, snapshot_payload,
              last_capture_attempt_at_millis, last_capture_success_at_millis,
              last_capture_failure_at_millis, last_capture_error
            ) VALUES (
              'shark-incremental', 'hub-game-shark-incremental', 'kept', 40, NULL,
              NULL, 40, 'progressed-payload',
              40, 40, 0, NULL
            )
            """.trimIndent(),
        )
        sqlite.version = 3
        sqlite.close()

        val db = Room.databaseBuilder(context, HubDatabase::class.java, name)
            .addMigrations(
                HubDatabase.MIGRATION_1_2,
                HubDatabase.MIGRATION_2_3,
                HubDatabase.MIGRATION_3_4,
                HubDatabase.MIGRATION_4_5,
                HubDatabase.MIGRATION_5_6,
            )
            .allowMainThreadQueries()
            .build()
        val history = db.snapshotDao().history("shark-incremental")
        assertEquals(1, history.size)
        assertEquals(SnapshotReasons.MIGRATED_BASELINE, history.single().reason)
        assertTrue(history.single().pinned)
        assertEquals("progressed-payload", history.single().payload)
        assertEquals("progressed-payload", db.gameInstanceDao().get("shark-incremental")?.snapshotPayload)
        assertEquals("local-capture", history.single().origin)
        db.close()
    }

    @Test
    fun migrationKeepsPinnedHistoryAndRecordsOrigin() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "snapshot-origin"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name)
        path.parentFile?.mkdirs()
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
              PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        sqlite.execSQL(
            """
            INSERT INTO snapshots (
              id, game_id, sequence, captured_at_millis, reason, pinned, payload, progress_json
            ) VALUES (
              'pre', 'shark-incremental', 2, 50, 'pre-restore', 1, 'before', NULL
            )
            """.trimIndent(),
        )
        sqlite.execSQL(
            """
            INSERT INTO game_instances (
              gameId, profile_name, session_identity, last_played_at_millis, resume_token,
              recovery_state, last_capture_at_millis, snapshot_payload,
              last_capture_attempt_at_millis, last_capture_success_at_millis,
              last_capture_failure_at_millis, last_capture_error
            ) VALUES (
              'shark-incremental', 'hub-game-shark-incremental', 'kept', 50, NULL,
              NULL, 50, 'before',
              50, 50, 0, NULL
            )
            """.trimIndent(),
        )
        sqlite.version = 4
        sqlite.close()

        val db = Room.databaseBuilder(context, HubDatabase::class.java, name)
            .addMigrations(HubDatabase.MIGRATION_4_5, HubDatabase.MIGRATION_5_6)
            .allowMainThreadQueries()
            .build()
        val history = db.snapshotDao().history("shark-incremental")
        assertEquals(1, history.size)
        assertEquals("pre-restore", history.single().reason)
        assertEquals("recovery", history.single().origin)
        assertTrue(history.single().pinned)
        assertEquals("before", history.single().payload)
        db.close()
    }

    @Test
    fun migratedHistoryKeepsPostRestoreAndHistoricalReasons() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "snapshot-post-restore"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name)
        path.parentFile?.mkdirs()
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
              PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        sqlite.execSQL(
            """
            INSERT INTO snapshots (
              id, game_id, sequence, captured_at_millis, reason, pinned, payload, progress_json
            ) VALUES (
              'pre', 'shark-incremental', 2, 50, 'pre-restore', 1, 'before', NULL
            )
            """.trimIndent(),
        )
        sqlite.execSQL(
            """
            INSERT INTO game_instances (
              gameId, profile_name, session_identity, last_played_at_millis, resume_token,
              recovery_state, last_capture_at_millis, snapshot_payload,
              last_capture_attempt_at_millis, last_capture_success_at_millis,
              last_capture_failure_at_millis, last_capture_error
            ) VALUES (
              'shark-incremental', 'hub-game-shark-incremental', 'kept', 50, NULL,
              NULL, 50, 'before',
              50, 50, 0, NULL
            )
            """.trimIndent(),
        )
        sqlite.version = 4
        sqlite.close()

        val db = Room.databaseBuilder(context, HubDatabase::class.java, name)
            .addMigrations(HubDatabase.MIGRATION_4_5, HubDatabase.MIGRATION_5_6)
            .allowMainThreadQueries()
            .build()
        val snapshots = db.snapshotDao()
        snapshots.insert(
            com.example.hub.database.SnapshotRecord(
                id = "post",
                gameId = "shark-incremental",
                sequence = 3,
                capturedAtMillis = 60L,
                reason = SnapshotReasons.POST_RESTORE,
                pinned = true,
                payload = "restored",
                progressJson = null,
                origin = "recovery",
            ),
        )
        repeat(12) { index ->
            snapshots.insert(
                com.example.hub.database.SnapshotRecord(
                    id = "auto-$index",
                    gameId = "shark-incremental",
                    sequence = 10L + index,
                    capturedAtMillis = 100L + index,
                    reason = "cadence",
                    pinned = false,
                    payload = "auto-$index",
                    progressJson = null,
                ),
            )
        }
        com.example.hub.database.SnapshotRetention.prune(
            snapshots,
            db.gameInstanceDao(),
            "shark-incremental",
            java.time.Instant.parse("2026-06-15T12:00:00Z").toEpochMilli(),
        )
        val history = snapshots.history("shark-incremental")
        val pre = history.single { it.reason == "pre-restore" }
        assertEquals("before", pre.payload)
        assertEquals("recovery", pre.origin)
        assertTrue(pre.pinned)
        val post = history.single { it.reason == SnapshotReasons.POST_RESTORE }
        assertEquals("restored", post.payload)
        assertTrue(post.pinned)
        assertTrue(history.count { it.reason == "cadence" } <= 10)
        assertEquals("before", db.gameInstanceDao().get("shark-incremental")?.snapshotPayload)
        db.close()
    }

    @Test
    fun migrationV5ToV6KeepsPayloadBytesAndAddsLineageColumns() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "snapshot-v5-lineage"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name)
        path.parentFile?.mkdirs()
        val payload = "feature-9-bytes-\u0000-kept"
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
        sqlite.execSQL(
            "INSERT INTO game_instances (gameId, profile_name, session_identity, last_played_at_millis, resume_token, recovery_state, last_capture_at_millis, snapshot_payload, last_capture_attempt_at_millis, last_capture_success_at_millis, last_capture_failure_at_millis, last_capture_error) VALUES ('shark-incremental', 'hub-game-shark-incremental', 'kept', 50, NULL, NULL, 50, ?, 50, 50, 0, NULL)",
            arrayOf(payload),
        )
        sqlite.execSQL(
            "INSERT INTO snapshots (id, game_id, sequence, captured_at_millis, reason, pinned, payload, progress_json, origin) VALUES ('pre', 'shark-incremental', 2, 50, 'pre-restore', 1, ?, NULL, 'recovery')",
            arrayOf(payload),
        )
        sqlite.version = 5
        sqlite.close()

        val db = Room.databaseBuilder(context, HubDatabase::class.java, name)
            .addMigrations(HubDatabase.MIGRATION_5_6)
            .allowMainThreadQueries()
            .build()
        val instance = db.gameInstanceDao().get("shark-incremental")
        assertEquals(payload, instance?.snapshotPayload)
        assertEquals("legacy", instance?.lineageId)
        assertNull(instance?.captureIncident)
        assertEquals(0, instance?.captureIncidentCount)
        assertNull(instance?.highWaterSnapshotId)
        val history = db.snapshotDao().history("shark-incremental")
        assertEquals(payload, history.single().payload)
        assertEquals("recovery", history.single().origin)
        assertEquals("legacy", history.single().lineageId)
        assertEquals("", history.single().contentSha256)
        com.example.hub.database.SnapshotRetention.prune(
            db.snapshotDao(),
            db.gameInstanceDao(),
            "shark-incremental",
            java.time.Instant.parse("2026-06-15T12:00:00Z").toEpochMilli(),
        )
        assertEquals(payload, db.snapshotDao().history("shark-incremental").single().payload)
        val anchor = SnapshotRecord(
            id = "anchor",
            gameId = "shark-incremental",
            sequence = 10,
            capturedAtMillis = 60,
            reason = "cadence",
            pinned = false,
            payload = "unpinned-high-water",
            progressJson = null,
        )
        db.snapshotDao().insert(anchor)
        repeat(15) { index ->
            db.snapshotDao().insert(
                anchor.copy(
                    id = "newer-$index",
                    sequence = 11L + index,
                    capturedAtMillis = 70L + index,
                    payload = "newer-$index",
                ),
            )
        }
        db.gameInstanceDao().upsert(instance!!.copy(highWaterSnapshotId = "anchor"))
        val pruneAt = java.time.Instant.parse("2026-06-15T12:00:00Z").toEpochMilli()
        repeat(3) {
            com.example.hub.database.SnapshotRetention.prune(
                db.snapshotDao(),
                db.gameInstanceDao(),
                "shark-incremental",
                pruneAt,
            )
        }
        assertEquals("unpinned-high-water", db.snapshotDao().getById("anchor")!!.payload)
        assertEquals("anchor", db.gameInstanceDao().get("shark-incremental")!!.highWaterSnapshotId)
        assertEquals(payload, db.snapshotDao().getById("pre")!!.payload)
        assertNull(db.snapshotDao().getById("newer-0"))
        assertEquals("newer-14", db.snapshotDao().getById("newer-14")!!.payload)
        db.close()
    }

    @Test
    fun migrationV5KeepsHistoricalReasonsThroughTierPrune() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "snapshot-v5-tiers"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name)
        path.parentFile?.mkdirs()
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
        sqlite.execSQL(
            "INSERT INTO game_instances (gameId, profile_name, session_identity, last_played_at_millis, resume_token, recovery_state, last_capture_at_millis, snapshot_payload, last_capture_attempt_at_millis, last_capture_success_at_millis, last_capture_failure_at_millis, last_capture_error) VALUES ('shark-incremental', 'hub-game-shark-incremental', 'kept', 50, NULL, NULL, 50, ?, 50, 50, 0, NULL)",
            arrayOf("pointer-\u0000"),
        )
        fun insertHistorical(
            id: String,
            sequence: Long,
            reason: String,
            pinned: Int,
            payload: String,
            origin: String,
        ) {
            sqlite.execSQL(
                "INSERT INTO snapshots (id, game_id, sequence, captured_at_millis, reason, pinned, payload, progress_json, origin) VALUES (?, 'shark-incremental', ?, 40, ?, ?, ?, NULL, ?)",
                arrayOf<Any>(id, sequence, reason, pinned, payload, origin),
            )
        }
        insertHistorical("base", 1L, "migrated-baseline", 1, "base-\u0000", "local-capture")
        insertHistorical("pre", 2L, "pre-restore", 1, "pre-\u0000", "recovery")
        insertHistorical("post", 3L, "post-restore", 1, "post-\u0000", "recovery")
        insertHistorical("diag", 4L, "diagnostic-backup", 1, "diag-\u0000", "local-capture")
        insertHistorical("check", 5L, "checkpoint", 1, "check-\u0000", "manual-checkpoint")
        insertHistorical("remote", 6L, "workstation", 0, "remote-\u0000", "workstation-sync")
        sqlite.version = 5
        sqlite.close()

        val db = Room.databaseBuilder(context, HubDatabase::class.java, name)
            .addMigrations(HubDatabase.MIGRATION_5_6)
            .allowMainThreadQueries()
            .build()
        repeat(40) { index ->
            db.snapshotDao().insert(
                SnapshotRecord(
                    id = "cadence-$index",
                    gameId = "shark-incremental",
                    sequence = 100L + index,
                    capturedAtMillis = 1_000L + index,
                    reason = "cadence",
                    pinned = false,
                    payload = "cadence-$index",
                    progressJson = null,
                ),
            )
        }
        val instance = db.gameInstanceDao().get("shark-incremental")!!
        db.gameInstanceDao().upsert(instance.copy(highWaterSnapshotId = "cadence-0"))
        val pruneAt = java.time.Instant.parse("2026-06-15T12:00:00Z").toEpochMilli()
        SnapshotRetention.prune(db.snapshotDao(), db.gameInstanceDao(), "shark-incremental", pruneAt)
        val first = db.snapshotDao().history("shark-incremental").map { it.id }.toSet()
        SnapshotRetention.prune(db.snapshotDao(), db.gameInstanceDao(), "shark-incremental", pruneAt)
        val second = db.snapshotDao().history("shark-incremental").map { it.id }.toSet()
        assertEquals(first, second)
        assertEquals("pointer-\u0000", db.gameInstanceDao().get("shark-incremental")!!.snapshotPayload)
        assertEquals("base-\u0000", db.snapshotDao().getById("base")!!.payload)
        assertEquals("pre-\u0000", db.snapshotDao().getById("pre")!!.payload)
        assertEquals("recovery", db.snapshotDao().getById("pre")!!.origin)
        assertEquals("post-\u0000", db.snapshotDao().getById("post")!!.payload)
        assertEquals("diag-\u0000", db.snapshotDao().getById("diag")!!.payload)
        assertEquals("check-\u0000", db.snapshotDao().getById("check")!!.payload)
        assertEquals("manual-checkpoint", db.snapshotDao().getById("check")!!.origin)
        assertEquals("remote-\u0000", db.snapshotDao().getById("remote")!!.payload)
        assertEquals("workstation-sync", db.snapshotDao().getById("remote")!!.origin)
        assertFalse(db.snapshotDao().getById("remote")!!.pinned)
        assertEquals("cadence-0", db.snapshotDao().getById("cadence-0")!!.payload)
        assertEquals("cadence-39", db.snapshotDao().getById("cadence-39")!!.payload)
        assertNull(db.snapshotDao().getById("cadence-1"))
        assertTrue(db.snapshotDao().history("shark-incremental").count { it.reason == "cadence" } <= 11)
        db.close()
    }
}
