package com.example.hub.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One accepted local commit waiting for the workstation. The row is inserted
 * in the same transaction as the snapshot it names. Quarantine is not queued.
 */
@Entity(tableName = "sync_outbox")
data class SyncOutboxEntry(
    @PrimaryKey
    @ColumnInfo(name = "idempotency_key")
    val idempotencyKey: String,
    @ColumnInfo(name = "game_id") val gameId: String,
    @ColumnInfo(name = "lineage_id") val lineageId: String,
    @ColumnInfo(name = "snapshot_id") val snapshotId: String,
    val payload: String,
    @ColumnInfo(name = "content_sha256") val contentSha256: String,
    @ColumnInfo(name = "captured_at_millis") val capturedAtMillis: Long,
    val reason: String,
    val integrity: String,
    @ColumnInfo(name = "profile_name") val profileName: String,
    @ColumnInfo(name = "session_identity") val sessionIdentity: String,
    @ColumnInfo(name = "last_played_at_millis") val lastPlayedAtMillis: Long,
    @ColumnInfo(name = "resume_token") val resumeToken: String?,
    @ColumnInfo(name = "propose_pointer") val proposePointer: Boolean,
    @ColumnInfo(name = "enqueued_at_millis") val enqueuedAtMillis: Long,
    @ColumnInfo(name = "attempt_count") val attemptCount: Int = 0,
    val state: String = STATE_PENDING,
    @ColumnInfo(name = "remote_snapshot_id") val remoteSnapshotId: String? = null,
) {
    companion object {
        const val STATE_PENDING = "pending"
        const val STATE_ACKED = "acked"
        const val STATE_BLOCKED = "blocked"
        const val INTEGRITY_ACCEPTED = "accepted"
        const val INTEGRITY_ARCHIVED = "archived"

        fun keyFor(snapshotId: String): String = "append:$snapshotId"
    }
}
