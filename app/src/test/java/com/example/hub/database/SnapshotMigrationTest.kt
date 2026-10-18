package com.example.hub.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
            .addMigrations(HubDatabase.MIGRATION_1_2, HubDatabase.MIGRATION_2_3, HubDatabase.MIGRATION_3_4)
            .allowMainThreadQueries()
            .build()
        val history = db.snapshotDao().history("shark-incremental")
        assertEquals(1, history.size)
        assertEquals(SnapshotReasons.MIGRATED_BASELINE, history.single().reason)
        assertTrue(history.single().pinned)
        assertEquals("progressed-payload", history.single().payload)
        assertEquals("progressed-payload", db.gameInstanceDao().get("shark-incremental")?.snapshotPayload)
        db.close()
    }
}
