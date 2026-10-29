package com.example.hub.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Minimal Room entity tracking a single GameInstance row.
 *
 * Deliberately narrow per R002 — stores only the metadata needed to
 * order and resume sessions. No archive, vault, or history schemas.
 *
 * @param gameId            Primary key — the stable identifier from the game definition.
 * @param profileName       User-assigned or auto-generated profile label.
 * @param sessionIdentity   Opaque token identifying this specific play session.
 * @param lastPlayedAtMillis Epoch millis of the most recent play; used for ordering.
 * @param resumeToken          Nullable token used to restore a session. Null when no resume is available.
 * @param recoveryState        Nullable string tracking the session's recovery state (e.g. "Degraded", "RecoveryRequired"). Null when no recovery is pending.
 * @param lastCaptureAtMillis  Epoch millis of the most recent successful capture. Failure must not change this.
 * @param snapshotPayload Validated capture payload. Failed captures must not replace it.
 * @param lastCaptureAttemptAtMillis Epoch millis of the latest capture attempt, successful or not.
 * @param lastCaptureSuccessAtMillis Epoch millis of the latest validated snapshot write.
 * @param lastCaptureFailureAtMillis Epoch millis of the latest failed capture attempt.
 * @param lastCaptureError Human-readable reason for the latest failed capture, or null after success.
 */
@Entity(tableName = "game_instances")
data class GameInstance(
    @PrimaryKey val gameId: String,
    @ColumnInfo(name = "profile_name") val profileName: String,
    @ColumnInfo(name = "session_identity") val sessionIdentity: String,
    @ColumnInfo(name = "last_played_at_millis") val lastPlayedAtMillis: Long,
    @ColumnInfo(name = "resume_token") val resumeToken: String? = null,
    @ColumnInfo(name = "recovery_state") val recoveryState: String? = null,
    @ColumnInfo(name = "last_capture_at_millis") val lastCaptureAtMillis: Long,
    @ColumnInfo(name = "snapshot_payload") val snapshotPayload: String? = null,
    @ColumnInfo(name = "last_capture_attempt_at_millis") val lastCaptureAttemptAtMillis: Long = 0L,
    @ColumnInfo(name = "last_capture_success_at_millis") val lastCaptureSuccessAtMillis: Long = 0L,
    @ColumnInfo(name = "last_capture_failure_at_millis") val lastCaptureFailureAtMillis: Long = 0L,
    @ColumnInfo(name = "last_capture_error") val lastCaptureError: String? = null,
    @ColumnInfo(name = "lineage_id", defaultValue = "legacy") val lineageId: String = "legacy",
    @ColumnInfo(name = "high_water_snapshot_id") val highWaterSnapshotId: String? = null,
    @ColumnInfo(name = "capture_incident") val captureIncident: String? = null,
    @ColumnInfo(name = "capture_incident_sha") val captureIncidentSha: String? = null,
    @ColumnInfo(name = "capture_incident_count", defaultValue = "0") val captureIncidentCount: Int = 0,
    @ColumnInfo(name = "last_seen_at_millis", defaultValue = "0") val lastSeenAtMillis: Long = 0L,
)
