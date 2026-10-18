package com.example.hub.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Append-only snapshot. Automatic captures may be pruned. Pinned rows stay.
 */
@Entity(tableName = "snapshots")
data class SnapshotRecord(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "game_id") val gameId: String,
    val sequence: Long,
    @ColumnInfo(name = "captured_at_millis") val capturedAtMillis: Long,
    val reason: String,
    val pinned: Boolean,
    val payload: String,
    @ColumnInfo(name = "progress_json") val progressJson: String?,
)

object SnapshotReasons {
    const val MIGRATED_BASELINE = "migrated-baseline"
    const val DIAGNOSTIC_BACKUP = "diagnostic-backup"
    const val PRE_RESTORE = "pre-restore"
}
