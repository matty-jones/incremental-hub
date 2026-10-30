package com.example.hub.session

import com.example.hub.database.SnapshotOrigins
import com.example.hub.games.SaveComparison

data class HistoryEntry(
    val sequence: Long,
    val reason: String,
    val pinned: Boolean,
    val capturedAtMillis: Long,
    val byteLength: Int,
    val detail: String,
    val origin: String = SnapshotOrigins.LOCAL_CAPTURE,
    val snapshotId: String = "",
)

/**
 * Native view of storage and startup evidence. Save bytes are not included.
 */
data class SaveDiagnostic(
    val comparison: SaveComparison,
    val liveDetail: String,
    val liveBytes: Int,
    val liveHash: String = "",
    val storedDetail: String,
    val storedBytes: Int,
    val storedHash: String = "",
    val history: List<HistoryEntry>,
    val note: String,
    val backupReady: Boolean,
    val recoverAllowed: Boolean,
    val ambiguous: Boolean = false,
    val consequence: String = "",
    val storedCapturedAtMillis: Long? = null,
    val livePinnedAtMillis: Long? = null,
)
