package com.example.hub.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SnapshotDao {

    @Insert
    suspend fun insert(record: SnapshotRecord)

    @Query("SELECT * FROM snapshots WHERE game_id = :gameId ORDER BY sequence ASC")
    suspend fun history(gameId: String): List<SnapshotRecord>

    @Query("SELECT COALESCE(MAX(sequence), 0) FROM snapshots WHERE game_id = :gameId")
    suspend fun maxSequence(gameId: String): Long

    @Query(
        "SELECT COUNT(*) FROM snapshots WHERE game_id = :gameId AND reason = :reason AND pinned = 1",
    )
    suspend fun countPinned(gameId: String, reason: String): Int

    @Query(
        "SELECT * FROM snapshots WHERE game_id = :gameId AND reason = :reason AND payload = :payload AND pinned = 1 LIMIT 1",
    )
    suspend fun findPinnedPayload(gameId: String, reason: String, payload: String): SnapshotRecord?

    @Query(
        """
        DELETE FROM snapshots
        WHERE game_id = :gameId
          AND pinned = 0
          AND reason NOT IN ('migrated-baseline', 'diagnostic-backup', 'pre-restore', 'post-restore', 'workstation', 'checkpoint')
          AND id NOT IN (
            SELECT id FROM snapshots
            WHERE game_id = :gameId
              AND pinned = 0
              AND reason NOT IN ('migrated-baseline', 'diagnostic-backup', 'pre-restore', 'post-restore', 'workstation', 'checkpoint')
            ORDER BY sequence DESC
            LIMIT 10
          )
        """,
    )
    suspend fun pruneAutomatic(gameId: String)
}
