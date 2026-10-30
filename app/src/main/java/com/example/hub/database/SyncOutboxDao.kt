package com.example.hub.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SyncOutboxDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: SyncOutboxEntry)

    @Query(
        """
        SELECT * FROM sync_outbox
        WHERE state = 'pending'
        ORDER BY enqueued_at_millis ASC, idempotency_key ASC
        """,
    )
    suspend fun pending(): List<SyncOutboxEntry>

    @Query("SELECT * FROM sync_outbox WHERE idempotency_key = :key LIMIT 1")
    suspend fun find(key: String): SyncOutboxEntry?

    @Query(
        """
        SELECT * FROM sync_outbox
        WHERE game_id = :gameId AND content_sha256 = :sha
        LIMIT 1
        """,
    )
    suspend fun findBySha(gameId: String, sha: String): SyncOutboxEntry?

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE game_id = :gameId AND state = 'pending'")
    suspend fun pendingCount(gameId: String): Int

    @Query(
        """
        UPDATE sync_outbox
        SET state = 'acked', remote_snapshot_id = :remoteId, attempt_count = attempt_count + 1
        WHERE idempotency_key = :key
        """,
    )
    suspend fun markAcked(key: String, remoteId: String)

    @Query(
        """
        UPDATE sync_outbox
        SET state = 'blocked', attempt_count = attempt_count + 1
        WHERE idempotency_key = :key
        """,
    )
    suspend fun markBlocked(key: String)

    @Query("UPDATE sync_outbox SET attempt_count = attempt_count + 1 WHERE idempotency_key = :key")
    suspend fun noteAttempt(key: String)
}

suspend fun enqueueAcceptedSnapshot(
    dao: SyncOutboxDao,
    instance: GameInstance,
    snapshotId: String,
    payload: String,
    sha: String,
    capturedAtMillis: Long,
    reason: String,
    integrity: String,
    proposePointer: Boolean,
    enqueuedAtMillis: Long,
) {
    if (payload.isBlank() || reason == SnapshotReasons.QUARANTINE) return
    if (integrity != SyncOutboxEntry.INTEGRITY_ACCEPTED && integrity != SyncOutboxEntry.INTEGRITY_ARCHIVED) return
    dao.insert(
        SyncOutboxEntry(
            idempotencyKey = SyncOutboxEntry.keyFor(snapshotId),
            gameId = instance.gameId,
            lineageId = instance.lineageId,
            snapshotId = snapshotId,
            payload = payload,
            contentSha256 = sha,
            capturedAtMillis = capturedAtMillis,
            reason = reason,
            integrity = integrity,
            profileName = instance.profileName,
            sessionIdentity = instance.sessionIdentity,
            lastPlayedAtMillis = instance.lastPlayedAtMillis,
            resumeToken = instance.resumeToken,
            proposePointer = proposePointer && integrity == SyncOutboxEntry.INTEGRITY_ACCEPTED,
            enqueuedAtMillis = enqueuedAtMillis,
        ),
    )
}
