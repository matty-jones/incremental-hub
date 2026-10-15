package com.example.hub.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

/**
 * Single-database singleton for the Hub app.
 *
 * Currently holds only the [GameInstance] table (R002 scope).
 * Additional tables are added here as the schema evolves.
 */
@Database(
    entities = [GameInstance::class],
    version = 3,
    exportSchema = true,
)
abstract class HubDatabase : RoomDatabase() {

    abstract fun gameInstanceDao(): GameInstanceDao

    companion object {
        private const val DATABASE_NAME = "hub_database"

        @Volatile
        private var INSTANCE: HubDatabase? = null

        fun getInstance(context: Context): HubDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE game_instances ADD COLUMN recovery_state TEXT"
                )
                db.execSQL(
                    "ALTER TABLE game_instances ADD COLUMN last_capture_at_millis INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE game_instances ADD COLUMN snapshot_payload TEXT")
                db.execSQL(
                    "ALTER TABLE game_instances ADD COLUMN last_capture_attempt_at_millis INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE game_instances ADD COLUMN last_capture_success_at_millis INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE game_instances ADD COLUMN last_capture_failure_at_millis INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL("ALTER TABLE game_instances ADD COLUMN last_capture_error TEXT")
            }
        }

        private fun buildDatabase(context: Context) = Room.databaseBuilder(
            context.applicationContext,
            HubDatabase::class.java,
            DATABASE_NAME,
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()
    }
}
