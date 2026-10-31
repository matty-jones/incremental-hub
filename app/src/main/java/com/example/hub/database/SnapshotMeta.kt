package com.example.hub.database

import androidx.room.ColumnInfo

/**
 * Snapshot list row. The payload column is not selected.
 * [payloadBytes] is the stored character length from SQLite `length(payload)`.
 */
data class SnapshotMeta(
    val id: String,
    @ColumnInfo(name = "game_id") val gameId: String,
    val sequence: Long,
    @ColumnInfo(name = "captured_at_millis") val capturedAtMillis: Long,
    val reason: String,
    val pinned: Boolean,
    @ColumnInfo(name = "progress_json") val progressJson: String?,
    val origin: String,
    @ColumnInfo(name = "lineage_id") val lineageId: String,
    @ColumnInfo(name = "content_sha256") val contentSha256: String,
    @ColumnInfo(name = "payload_bytes") val payloadBytes: Int,
    @ColumnInfo(name = "user_protected") val userProtected: Boolean = false,
)
