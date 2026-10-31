package com.example.hub.vault

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.example.hub.database.GameInstanceDao
import com.example.hub.database.SnapshotDao
import com.example.hub.database.SnapshotReasons
import com.example.hub.database.SnapshotRecord
import com.example.hub.database.SnapshotRetention
import com.example.hub.database.originForReason
import com.example.hub.games.PayloadDigest
import com.example.hub.sync.WorkstationApi

data class ProtectionOutcome(
    val applied: Boolean,
    val message: String,
)

/**
 * User protection is its own flag. It does not change [SnapshotRecord.pinned]
 * or a snapshot reason. A workstation-only snapshot is stored only after its
 * payload hash matches.
 */
class VaultProtection(
    private val snapshots: SnapshotDao,
    private val instances: GameInstanceDao,
    private val api: WorkstationApi,
    private val room: RoomDatabase? = null,
) {
    suspend fun protect(entry: VaultEntry): ProtectionOutcome {
        val apply = suspend {
            if (entry.residence == VaultResidence.REMOTE_ONLY) protectRemote(entry) else protectLocal(entry)
        }
        val database = room
        return if (database != null) database.withTransaction { apply() } else apply()
    }

    suspend fun unprotect(entry: VaultEntry): ProtectionOutcome {
        val row = snapshots.getById(entry.id)
            ?: return ProtectionOutcome(false, "That snapshot is not on the phone. User protection was not changed.")
        if (row.gameId != entry.gameId) {
            return ProtectionOutcome(false, "That snapshot belongs to another game. It was not changed.")
        }
        snapshots.setUserProtected(row.id, false)
        val system = systemProtected(row)
        val message = if (system) {
            "User protection was removed. This snapshot stays protected as ${row.reason}."
        } else {
            "User protection was removed."
        }
        return ProtectionOutcome(true, message)
    }

    private suspend fun protectLocal(entry: VaultEntry): ProtectionOutcome {
        val row = snapshots.getById(entry.id)
            ?: return ProtectionOutcome(false, "That snapshot is not on the phone. It was not protected.")
        if (row.gameId != entry.gameId || row.lineageId != entry.lineageId) {
            return ProtectionOutcome(false, "The snapshot id and lineage do not match. It was not protected.")
        }
        val sha = PayloadDigest.sha256(row.payload)
        if (row.contentSha256.isNotEmpty() && row.contentSha256 != sha) {
            return ProtectionOutcome(false, "The stored hash does not match the payload. It was not protected.")
        }
        if (entry.sha256.isNotEmpty() && entry.sha256 != sha) {
            return ProtectionOutcome(false, "The listed hash does not match the payload. It was not protected.")
        }
        val reason = row.reason
        val pinned = row.pinned
        val payload = row.payload
        snapshots.setUserProtected(row.id, true)
        val stored = snapshots.getById(row.id)
        if (stored == null || !stored.userProtected || stored.payload != payload ||
            stored.reason != reason || stored.pinned != pinned
        ) {
            error("Protection was not stored. The snapshot was left unchanged.")
        }
        return ProtectionOutcome(true, "Protected. Retention will keep this snapshot.")
    }

    private suspend fun protectRemote(entry: VaultEntry): ProtectionOutcome {
        val fetched = api.fetchSnapshot(entry.gameId, entry.id)
            ?: return ProtectionOutcome(
                false,
                "The workstation snapshot could not be downloaded. It is not protected.",
            )
        val sha = PayloadDigest.sha256(fetched.snapshotPayload)
        if (fetched.sha256.isEmpty() || fetched.sha256 != sha || entry.sha256 != sha) {
            return ProtectionOutcome(false, "The downloaded payload did not match its hash. It is not protected.")
        }
        if (fetched.snapshotId != entry.id || fetched.lineageId != entry.lineageId || fetched.gameId != entry.gameId) {
            return ProtectionOutcome(false, "The downloaded snapshot identity does not match. It is not protected.")
        }
        val existing = snapshots.getById(fetched.snapshotId)
        if (existing != null) {
            if (existing.payload != fetched.snapshotPayload) {
                return ProtectionOutcome(
                    false,
                    "This id is already stored with different bytes. It was not changed.",
                )
            }
            snapshots.setUserProtected(existing.id, true)
            return ProtectionOutcome(true, "Protected the copy already on the phone.")
        }
        val record = SnapshotRecord(
            id = fetched.snapshotId,
            gameId = fetched.gameId,
            sequence = snapshots.maxSequence(fetched.gameId) + 1,
            capturedAtMillis = fetched.capturedAtMillis,
            reason = SnapshotReasons.WORKSTATION,
            pinned = false,
            payload = fetched.snapshotPayload,
            progressJson = null,
            origin = originForReason(SnapshotReasons.WORKSTATION),
            lineageId = fetched.lineageId,
            contentSha256 = sha,
            userProtected = true,
        )
        snapshots.insert(record)
        val stored = snapshots.getById(record.id)
        if (stored?.payload != fetched.snapshotPayload || stored.userProtected != true) {
            error("Protection was not stored.")
        }
        return ProtectionOutcome(true, "Downloaded, verified, and protected on this phone.")
    }

    private suspend fun systemProtected(row: SnapshotRecord): Boolean {
        if (row.pinned || SnapshotRetention.isPermanentReason(row.reason)) return true
        return instances.get(row.gameId)?.highWaterSnapshotId == row.id
    }
}
