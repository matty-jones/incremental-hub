package com.example.hub.vault

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.example.hub.database.GameInstanceDao
import com.example.hub.database.SnapshotDao
import com.example.hub.database.SnapshotReasons
import com.example.hub.database.SnapshotRecord
import com.example.hub.database.SnapshotRetention
import com.example.hub.database.SyncOutboxDao
import com.example.hub.games.PayloadDigest
import com.example.hub.session.RestoreJournalStore

enum class DeletionRefusal {
    NOT_ON_PHONE,
    WRONG_GAME,
    STALE,
    ACTIVE_POINTER,
    HIGH_WATER,
    PINNED,
    USER_PROTECTED,
    MIGRATED_BASELINE,
    CHECKPOINT,
    DIAGNOSTIC_BACKUP,
    PRE_RESTORE,
    POST_RESTORE,
    QUARANTINE,
    WORKSTATION,
    LINEAGE_BASE,
    RETAINED_POINTER,
    PENDING_SYNC,
    ACTIVE_EXPORT,
    ACTIVE_RESTORE,
    RECOVERY_INCIDENT,
    RETENTION,
    LAST_LINEAGE_COPY,
    WORKSTATION_ONLY,
}

data class SnapshotFingerprint(
    val snapshotId: String,
    val gameId: String,
    val pinned: Boolean,
    val userProtected: Boolean,
    val reason: String,
    val sequence: Long,
    val lineageId: String,
    val contentSha256: String,
    val identityHash: String,
) {
    fun matches(row: SnapshotRecord): Boolean {
        return row.id == snapshotId &&
            row.gameId == gameId &&
            row.pinned == pinned &&
            row.userProtected == userProtected &&
            row.reason == reason &&
            row.sequence == sequence &&
            row.lineageId == lineageId &&
            row.contentSha256 == contentSha256 &&
            snapshotIdentity(row) == identityHash
    }
}

data class DeletionFacts(
    val gameId: String,
    val snapshotId: String,
    val row: SnapshotRecord?,
    val rows: List<SnapshotRecord>,
    val livePayload: String?,
    val highWaterSnapshotId: String?,
    val captureIncident: String?,
    val pendingSyncIds: Set<String>,
    val exportProtected: Boolean,
    val restoreConflict: Boolean,
    val workstationOnly: Boolean,
    val nowMillis: Long,
    val fingerprint: SnapshotFingerprint?,
)

data class DeletionDecision(
    val snapshotId: String,
    val gameId: String,
    val eligible: Boolean,
    val refusal: DeletionRefusal?,
    val message: String,
    val fingerprint: SnapshotFingerprint?,
)

data class DeletionResult(
    val snapshotId: String,
    val gameId: String,
    val deleted: Boolean,
    val refusal: DeletionRefusal?,
    val message: String,
)

/**
 * Manual deletion of one local snapshot. Eligibility is a pure function of
 * [DeletionFacts]. The repository holds the export mutex, re-reads those facts
 * inside the delete transaction, and deletes only when the same fingerprint
 * still matches. A snapshot [SnapshotRetention.keepIds] currently selects is
 * refused, including one of the ten newest distinct rows in a full bucket.
 */
object SnapshotDeletionPolicy {
    fun decide(facts: DeletionFacts): DeletionDecision {
        if (facts.workstationOnly) {
            return refuse(facts, DeletionRefusal.WORKSTATION_ONLY, "exists only on the workstation")
        }
        val row = facts.row ?: return refuse(facts, DeletionRefusal.NOT_ON_PHONE, "is not on this phone")
        if (row.gameId != facts.gameId) {
            return refuse(facts, DeletionRefusal.WRONG_GAME, "belongs to another game")
        }
        val expected = facts.fingerprint
        if (expected != null && !expected.matches(row)) {
            return refuse(facts, DeletionRefusal.STALE, "changed after it was selected")
        }
        if (facts.highWaterSnapshotId == row.id) {
            return refuse(facts, DeletionRefusal.HIGH_WATER, "is the high-water snapshot")
        }
        if (soleLiveCopy(row, facts.rows, facts.livePayload)) {
            return refuse(facts, DeletionRefusal.ACTIVE_POINTER, "is the only local copy of the current save")
        }
        reasonRefusal(row.reason)?.let { refusal ->
            return refuse(facts, refusal, "is kept as ${row.reason}")
        }
        if (row.reason == SnapshotReasons.RETAINED_POINTER) {
            return refuse(facts, DeletionRefusal.RETAINED_POINTER, "is a retained pointer")
        }
        if (row.pinned) {
            return refuse(facts, DeletionRefusal.PINNED, "is pinned")
        }
        if (row.userProtected) {
            return refuse(facts, DeletionRefusal.USER_PROTECTED, "is user-protected")
        }
        if (row.id in facts.pendingSyncIds) {
            return refuse(facts, DeletionRefusal.PENDING_SYNC, "is still waiting to sync")
        }
        if (facts.exportProtected) {
            return refuse(facts, DeletionRefusal.ACTIVE_EXPORT, "is selected for export")
        }
        if (facts.restoreConflict) {
            return refuse(facts, DeletionRefusal.ACTIVE_RESTORE, "is part of a restore that has not finished")
        }
        if (!facts.captureIncident.isNullOrBlank()) {
            return refuse(facts, DeletionRefusal.RECOVERY_INCIDENT, "is blocked by an unresolved capture incident")
        }
        val lineageCount = facts.rows.count { it.lineageId == row.lineageId }
        if (lineageCount <= 1) {
            return refuse(facts, DeletionRefusal.LAST_LINEAGE_COPY, "is the last snapshot for its lineage")
        }
        val selected = SnapshotRetention.keepIds(
            facts.rows,
            facts.nowMillis,
            protectedIds = setOfNotNull(facts.highWaterSnapshotId),
        )
        if (row.id in selected) {
            return refuse(facts, DeletionRefusal.RETENTION, "is kept by retention")
        }
        return DeletionDecision(
            snapshotId = facts.snapshotId,
            gameId = facts.gameId,
            eligible = true,
            refusal = null,
            message = "Snapshot ${facts.snapshotId} can be deleted from local history.",
            fingerprint = fingerprintOf(row),
        )
    }

    private fun reasonRefusal(reason: String): DeletionRefusal? = when (reason) {
        SnapshotReasons.MIGRATED_BASELINE -> DeletionRefusal.MIGRATED_BASELINE
        SnapshotReasons.CHECKPOINT -> DeletionRefusal.CHECKPOINT
        SnapshotReasons.DIAGNOSTIC_BACKUP -> DeletionRefusal.DIAGNOSTIC_BACKUP
        SnapshotReasons.PRE_RESTORE -> DeletionRefusal.PRE_RESTORE
        SnapshotReasons.POST_RESTORE -> DeletionRefusal.POST_RESTORE
        SnapshotReasons.QUARANTINE -> DeletionRefusal.QUARANTINE
        SnapshotReasons.WORKSTATION -> DeletionRefusal.WORKSTATION
        SnapshotReasons.LINEAGE_BASE -> DeletionRefusal.LINEAGE_BASE
        else -> null
    }

    private fun refuse(facts: DeletionFacts, refusal: DeletionRefusal, detail: String): DeletionDecision {
        return DeletionDecision(
            snapshotId = facts.snapshotId,
            gameId = facts.gameId,
            eligible = false,
            refusal = refusal,
            message = "Snapshot ${facts.snapshotId} $detail. Nothing was deleted.",
            fingerprint = facts.row?.let { fingerprintOf(it) },
        )
    }
}

class SnapshotDeletion(
    private val snapshots: SnapshotDao,
    private val instances: GameInstanceDao,
    private val outbox: SyncOutboxDao,
    private val room: RoomDatabase? = null,
    private val journals: RestoreJournalStore? = null,
) {
    /** Fixed in tests so retention windows do not follow the device clock. */
    var clock: () -> Long = { System.currentTimeMillis() }

    /** Throws inside the delete transaction so the Room write rolls back. */
    internal var beforeCommit: (suspend () -> Unit)? = null

    suspend fun preview(gameId: String, snapshotId: String, workstationOnly: Boolean): DeletionDecision {
        if (workstationOnly) {
            return SnapshotDeletionPolicy.decide(
                DeletionFacts(
                    gameId = gameId,
                    snapshotId = snapshotId,
                    row = null,
                    rows = emptyList(),
                    livePayload = null,
                    highWaterSnapshotId = null,
                    captureIncident = null,
                    pendingSyncIds = emptySet(),
                    exportProtected = false,
                    restoreConflict = false,
                    workstationOnly = true,
                    nowMillis = clock(),
                    fingerprint = null,
                ),
            )
        }
        return ExportHold.guard { transaction { decide(gameId, snapshotId, fingerprint = null) } }
    }

    suspend fun delete(fingerprint: SnapshotFingerprint): DeletionResult {
        return ExportHold.guard { transaction { deleteLocked(fingerprint) } }
    }

    private suspend fun deleteLocked(fingerprint: SnapshotFingerprint): DeletionResult {
        val decision = decide(fingerprint.gameId, fingerprint.snapshotId, fingerprint)
        if (!decision.eligible) {
            return result(fingerprint, deleted = false, decision.refusal, decision.message)
        }
        val removed = when {
            ExportHold.isProtectedUnlocked(fingerprint.snapshotId) -> -1
            ActiveRestore.isHeldUnlocked(fingerprint.snapshotId) -> -2
            else -> snapshots.deleteOneIfUnchanged(
                id = fingerprint.snapshotId,
                gameId = fingerprint.gameId,
                pinned = if (fingerprint.pinned) 1 else 0,
                userProtected = if (fingerprint.userProtected) 1 else 0,
                reason = fingerprint.reason,
                sequence = fingerprint.sequence,
                lineageId = fingerprint.lineageId,
                contentSha256 = fingerprint.contentSha256,
            )
        }
        return when (removed) {
            1 -> {
                beforeCommit?.invoke()
                result(
                    fingerprint,
                    deleted = true,
                    refusal = null,
                    message = "Deleted local snapshot ${fingerprint.snapshotId}. " +
                        "Only that historical row was removed. The live save and other snapshots were kept.",
                )
            }
            0 -> result(
                fingerprint,
                deleted = false,
                DeletionRefusal.STALE,
                "Snapshot ${fingerprint.snapshotId} changed after it was selected. Nothing was deleted.",
            )
            -1 -> result(
                fingerprint,
                deleted = false,
                DeletionRefusal.ACTIVE_EXPORT,
                "Snapshot ${fingerprint.snapshotId} is selected for export. Nothing was deleted.",
            )
            -2 -> result(
                fingerprint,
                deleted = false,
                DeletionRefusal.ACTIVE_RESTORE,
                "Snapshot ${fingerprint.snapshotId} is part of a restore that has not finished. Nothing was deleted.",
            )
            else -> error("Snapshot delete affected $removed rows")
        }
    }

    private suspend fun decide(
        gameId: String,
        snapshotId: String,
        fingerprint: SnapshotFingerprint?,
    ): DeletionDecision {
        val row = snapshots.getById(snapshotId)
        val rows = snapshots.history(gameId)
        val instance = instances.get(gameId)
        val facts = DeletionFacts(
            gameId = gameId,
            snapshotId = snapshotId,
            row = row,
            rows = rows,
            livePayload = instance?.snapshotPayload,
            highWaterSnapshotId = instance?.highWaterSnapshotId,
            captureIncident = instance?.captureIncident,
            pendingSyncIds = outbox.unackedSnapshotIds(gameId).toSet(),
            exportProtected = ExportHold.isProtectedUnlocked(snapshotId),
            restoreConflict = ActiveRestore.isHeldUnlocked(snapshotId) ||
                journals?.blocksDeletion(gameId) == true,
            workstationOnly = false,
            nowMillis = clock(),
            fingerprint = fingerprint,
        )
        return SnapshotDeletionPolicy.decide(facts)
    }

    private suspend fun <T> transaction(block: suspend () -> T): T {
        val database = room
        return if (database != null) database.withTransaction { block() } else block()
    }

    private fun result(
        fingerprint: SnapshotFingerprint,
        deleted: Boolean,
        refusal: DeletionRefusal?,
        message: String,
    ): DeletionResult {
        return DeletionResult(
            snapshotId = fingerprint.snapshotId,
            gameId = fingerprint.gameId,
            deleted = deleted,
            refusal = refusal,
            message = message,
        )
    }
}

internal fun fingerprintOf(row: SnapshotRecord): SnapshotFingerprint {
    return SnapshotFingerprint(
        snapshotId = row.id,
        gameId = row.gameId,
        pinned = row.pinned,
        userProtected = row.userProtected,
        reason = row.reason,
        sequence = row.sequence,
        lineageId = row.lineageId,
        contentSha256 = row.contentSha256,
        identityHash = snapshotIdentity(row),
    )
}

internal fun snapshotIdentity(row: SnapshotRecord): String {
    if (row.contentSha256.isNotEmpty()) return row.contentSha256
    return PayloadDigest.sha256(row.payload)
}

private fun soleLiveCopy(row: SnapshotRecord, rows: List<SnapshotRecord>, live: String?): Boolean {
    if (!preservesLive(row, live)) return false
    return rows.none { other -> other.id != row.id && preservesLive(other, live) }
}

private fun preservesLive(row: SnapshotRecord, live: String?): Boolean {
    if (live.isNullOrBlank()) return false
    if (row.payload == live) return true
    if (row.contentSha256.isEmpty()) return false
    return row.contentSha256 == PayloadDigest.sha256(live)
}
