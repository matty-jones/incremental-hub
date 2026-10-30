package com.example.hub.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Append-only snapshot. Accepted automatic rows are kept by UTC time tier.
 * Pinned rows and recovery reasons stay.
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
    @ColumnInfo(name = "origin", defaultValue = "local-capture")
    val origin: String = SnapshotOrigins.LOCAL_CAPTURE,
    @ColumnInfo(name = "lineage_id", defaultValue = "legacy")
    val lineageId: String = "legacy",
    @ColumnInfo(name = "content_sha256", defaultValue = "")
    val contentSha256: String = "",
)

object SnapshotReasons {
    const val MIGRATED_BASELINE = "migrated-baseline"
    const val DIAGNOSTIC_BACKUP = "diagnostic-backup"
    const val PRE_RESTORE = "pre-restore"
    const val POST_RESTORE = "post-restore"
    const val WORKSTATION = "workstation"
    const val CHECKPOINT = "checkpoint"
    const val QUARANTINE = "quarantine"
    const val LINEAGE_BASE = "lineage-base"
    const val RETAINED_POINTER = "retained-pointer"
}

object SnapshotOrigins {
    const val LOCAL_CAPTURE = "local-capture"
    const val MANUAL_CHECKPOINT = "manual-checkpoint"
    const val RECOVERY = "recovery"
    const val WORKSTATION = "workstation-sync"
}

fun originForReason(reason: String): String = when (reason) {
    SnapshotReasons.CHECKPOINT -> SnapshotOrigins.MANUAL_CHECKPOINT
    SnapshotReasons.PRE_RESTORE,
    SnapshotReasons.POST_RESTORE,
    SnapshotReasons.QUARANTINE,
    SnapshotReasons.LINEAGE_BASE,
    -> SnapshotOrigins.RECOVERY
    SnapshotReasons.WORKSTATION -> SnapshotOrigins.WORKSTATION
    else -> SnapshotOrigins.LOCAL_CAPTURE
}
