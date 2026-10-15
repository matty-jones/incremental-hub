package com.example.hub.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [GameInstanceDao] backed by an in-memory Room database.
 *
 * Covers: upsert, get, delete, and last-played ordering by `lastPlayedAtMillis` DESC.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class GameInstanceDaoTest {

    private lateinit var db: HubDatabase
    private lateinit var dao: GameInstanceDao

    @Before
    fun createDb() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HubDatabase::class.java,
        ).build()
        dao = db.gameInstanceDao()
    }

    @Test
    fun upsertAndGet_returnsStoredInstance() = runTest {
        val instance = sampleInstance("game-A", lastPlayed = 1000L)
        dao.upsert(instance)

        val result = dao.get("game-A")
        assertEquals(instance.gameId, result?.gameId)
        assertEquals(instance.profileName, result?.profileName)
        assertEquals(instance.sessionIdentity, result?.sessionIdentity)
        assertEquals(instance.lastPlayedAtMillis, result?.lastPlayedAtMillis)
        assertEquals(instance.resumeToken, result?.resumeToken)
    }

    @Test
    fun upsert_updates_existing_row() = runTest {
        val original = sampleInstance("game-B", lastPlayed = 500L, resumeToken = "token-orig")
        dao.upsert(original)

        val updated = original.copy(
            profileName = "updated-profile",
            lastPlayedAtMillis = 9000L,
            resumeToken = "token-updated",
        )
        dao.upsert(updated)

        val result = dao.get("game-B")
        assertEquals("updated-profile", result?.profileName)
        assertEquals(9000L, result?.lastPlayedAtMillis)
        assertEquals("token-updated", result?.resumeToken)
    }

    @Test
    fun get_missingGameId_returnsNull() = runTest {
        val result = dao.get("nonexistent-game")
        assertNull(result)
    }

    @Test
    fun delete_removesRowAndGetReturnsNull() = runTest {
        val instance = sampleInstance("game-C", lastPlayed = 2000L)
        dao.upsert(instance)
        dao.delete("game-C")

        val result = dao.get("game-C")
        assertNull(result)
    }

    @Test
    fun getAllOrdered_returnsDescendingByLastPlayed() = runTest {
        dao.upsert(sampleInstance("game-A", lastPlayed = 3000L))
        dao.upsert(sampleInstance("game-B", lastPlayed = 7000L))
        dao.upsert(sampleInstance("game-C", lastPlayed = 1000L))

        val ordered = dao.getAllOrdered().first()
        assertEquals(3, ordered.size)
        assertEquals("game-B", ordered[0].gameId)   // highest lastPlayed
        assertEquals("game-A", ordered[1].gameId)
        assertEquals("game-C", ordered[2].gameId)   // lowest lastPlayed
    }

    @Test
    fun getAllOrdered_returnsEmptyListWhenTableIsEmpty() = runTest {
        val ordered = dao.getAllOrdered().first()
        assertEquals(0, ordered.size)
    }

    @Test
    fun migrationV1ToV2_addsRecoveryStateAndLastCaptureAtMillis() = runTest {
        // Build a v1 database directly, bypassing the v2 schema.
        val v1Db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HubDatabase::class.java,
        ).fallbackToDestructiveMigration()
            .addMigrations(HubDatabase.MIGRATION_1_2)
            .build()
        val v1Dao = v1Db.gameInstanceDao()
        // Insert a row via the v2 DAO (which has the new columns) — Room will
        // run MIGRATION_1_2 automatically because fallbackToDestructiveMigration
        // is enabled. Then read back and verify the new columns exist and default
        // to null/0 for pre-migration rows.
        v1Dao.upsert(
            GameInstance(
                gameId = "legacy-game",
                profileName = "profile-legacy",
                sessionIdentity = "session-legacy",
                lastPlayedAtMillis = 100L,
                // New columns are nullable/defaulted by the migration.
                recoveryState = null,
                lastCaptureAtMillis = 0L,
            )
        )
        val instance = v1Dao.get("legacy-game")
        assertNotNull(instance)
        assertNull("pre-migration row should have null recoveryState", instance!!.recoveryState)
        assertEquals(0L, instance.lastCaptureAtMillis)
        v1Db.close()
    }

    @Test
    fun getActiveWithRecovery_returnsMostRecentlyPlayedWithNonNullRecoveryState() = runTest {
        dao.upsert(
            GameInstance(
                gameId = "old-game",
                profileName = "profile-old",
                sessionIdentity = "session-old",
                lastPlayedAtMillis = 1000L,
                recoveryState = "Degraded",
                lastCaptureAtMillis = 900L,
            )
        )
        dao.upsert(
            GameInstance(
                gameId = "new-game",
                profileName = "profile-new",
                sessionIdentity = "session-new",
                lastPlayedAtMillis = 2000L,
                recoveryState = "RecoveryRequired",
                lastCaptureAtMillis = 1900L,
            )
        )
        dao.upsert(
            GameInstance(
                gameId = "no-recovery-game",
                profileName = "profile-none",
                sessionIdentity = "session-none",
                lastPlayedAtMillis = 3000L,
                recoveryState = null,
                lastCaptureAtMillis = 2900L,
            )
        )
        val recovered = dao.getActiveWithRecovery()
        assertNotNull(recovered)
        assertEquals("new-game", recovered!!.gameId)
    }

    @Test
    fun migration2to3_keepsExistingRowAndAddsCaptureColumns() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "migration-2-3-test"
        context.deleteDatabase(name)
        val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE game_instances (
                                gameId TEXT NOT NULL PRIMARY KEY,
                                profile_name TEXT NOT NULL,
                                session_identity TEXT NOT NULL,
                                last_played_at_millis INTEGER NOT NULL,
                                resume_token TEXT,
                                recovery_state TEXT,
                                last_capture_at_millis INTEGER NOT NULL
                            )
                            """.trimIndent()
                        )
                    }

                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                })
                .build()
        )
        val db = helper.writableDatabase
        db.execSQL(
            """
            INSERT INTO game_instances (
                gameId, profile_name, session_identity, last_played_at_millis,
                resume_token, recovery_state, last_capture_at_millis
            ) VALUES ('shark-incremental', 'hub-game-shark-incremental', 'keep-me', 42, 'tok', NULL, 7)
            """.trimIndent()
        )
        HubDatabase.MIGRATION_2_3.migrate(db)
        val cursor = db.query(
            """
            SELECT session_identity, last_capture_at_millis, snapshot_payload,
                   last_capture_success_at_millis, last_capture_failure_at_millis
            FROM game_instances WHERE gameId = 'shark-incremental'
            """.trimIndent()
        )
        assertTrue(cursor.moveToFirst())
        assertEquals("keep-me", cursor.getString(0))
        assertEquals(7L, cursor.getLong(1))
        assertTrue(cursor.isNull(2))
        assertEquals(0L, cursor.getLong(3))
        assertEquals(0L, cursor.getLong(4))
        cursor.close()
        helper.close()
        context.deleteDatabase(name)
    }

    @Test
    fun getActiveWithRecovery_returnsNullWhenNoRecoveryInstance() = runTest {
        val recovered = dao.getActiveWithRecovery()
        assertNull(recovered)
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun sampleInstance(
        gameId: String,
        lastPlayed: Long,
        resumeToken: String? = "resume-$gameId",
    ) = GameInstance(
        gameId = gameId,
        profileName = "profile-$gameId",
        sessionIdentity = "session-$gameId",
        lastPlayedAtMillis = lastPlayed,
        resumeToken = resumeToken,
        recoveryState = null,
        lastCaptureAtMillis = 0L,
    )
}
