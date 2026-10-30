package com.example.hub.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * One cursor per game. It records how far metadata sync has read.
 * It does not store workstation payloads.
 */
@Entity(tableName = "sync_history_cursor")
data class SyncHistoryCursor(
    @PrimaryKey
    @ColumnInfo(name = "game_id")
    val gameId: String,
    @ColumnInfo(name = "received_at_millis")
    val receivedAtMillis: Long,
    @ColumnInfo(name = "snapshot_id")
    val snapshotId: String,
    @ColumnInfo(name = "seen_count")
    val seenCount: Int,
)

@Dao
interface SyncHistoryCursorDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(cursor: SyncHistoryCursor)

    @Query("SELECT * FROM sync_history_cursor WHERE game_id = :gameId LIMIT 1")
    suspend fun get(gameId: String): SyncHistoryCursor?
}
