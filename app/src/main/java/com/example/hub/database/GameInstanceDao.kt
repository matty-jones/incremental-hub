package com.example.hub.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Data-access object for [GameInstance] rows.
 *
 * Exposes a Flow-based query for last-played-first ordering and
 * suspend functions for point mutations.
 */
@Dao
interface GameInstanceDao {

    /**
     * Returns all instances ordered by [GameInstance.lastPlayedAtMillis] descending.
     * Emits a fresh list whenever the table changes.
     */
    @Query("SELECT * FROM game_instances ORDER BY last_played_at_millis DESC")
    fun getAllOrdered(): Flow<List<GameInstance>>

    /**
     * Returns the instance with the given [gameId], or null if absent.
     */
    @Query("SELECT * FROM game_instances WHERE gameId = :gameId LIMIT 1")
    suspend fun get(gameId: String): GameInstance?

    /**
     * Returns the most-recently-played instance that has a non-null [GameInstance.recoveryState].
     * Used for cold-start recovery: the app reads this on attach to restore the last session.
     */
    @Query("SELECT * FROM game_instances WHERE recovery_state IS NOT NULL ORDER BY last_played_at_millis DESC LIMIT 1")
    suspend fun getActiveWithRecovery(): GameInstance?

    /**
     * Inserts a new row or replaces an existing one with the same [GameInstance.gameId].
     */
    @Upsert
    suspend fun upsert(instance: GameInstance)

    /**
     * Deletes the instance with the given [gameId].
     */
    @Query("DELETE FROM game_instances WHERE gameId = :gameId")
    suspend fun delete(gameId: String)
}
