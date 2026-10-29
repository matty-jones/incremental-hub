package com.example.hub.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
        snapshots.pruneAutomatic("shark-incremental")
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
        db.snapshotDao().pruneAutomatic("shark-incremental")
        assertEquals(payload, db.snapshotDao().history("shark-incremental").single().payload)
        db.close()
    }
}
