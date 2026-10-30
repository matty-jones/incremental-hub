package com.example.hub.sync

import com.example.hub.database.GameInstance
import com.example.hub.database.GameInstanceDao
import com.example.hub.database.SnapshotDao
import com.example.hub.database.SnapshotReasons
import com.example.hub.database.SnapshotRecord
import com.example.hub.database.SyncOutboxDao
import com.example.hub.database.SyncOutboxEntry
import com.example.hub.database.enqueueAcceptedSnapshot
import com.example.hub.games.PayloadDigest

/**
 * Queues saves that were already in Room before the outbox existed.
 * The same call is safe on every startup: an existing sha is left as it is.
 */
object SyncBootstrap {
    suspend fun enqueueExisting(
        instances: GameInstanceDao,
        snapshots: SnapshotDao,
        outbox: SyncOutboxDao,
        now: Long = System.currentTimeMillis(),
    ) {
        for (instance in instances.getAll()) {
            if (instance.lineageId != "legacy") continue
            val payload = instance.snapshotPayload?.takeIf { it.isNotBlank() } ?: continue
            val rows = snapshots.history(instance.gameId)
            if (!payloadIsQuarantineOnly(rows, payload)) {
                val pointer = rows.lastOrNull { it.payload == payload && it.reason != SnapshotReasons.QUARANTINE }
                enqueue(
                    outbox = outbox,
                    instance = instance,
                    payload = payload,
                    snapshotId = pointer?.id ?: "bootstrap:${instance.gameId}:${PayloadDigest.sha256(payload)}",
                    reason = pointer?.reason ?: "bootstrap",
                    capturedAtMillis = pointer?.capturedAtMillis ?: instance.lastCaptureAtMillis,
                    proposePointer = true,
                    now = now,
                )
            }
            val highWater = instance.highWaterSnapshotId?.let { snapshots.getById(it) }
            if (highWater != null && highWater.payload != payload) {
                enqueueRow(outbox, instance, highWater, proposePointer = false, now = now)
            }
            val recovery = recoveryBaseline(rows, payload)
            if (recovery != null && recovery.id != highWater?.id) {
                enqueueRow(outbox, instance, recovery, proposePointer = false, now = now)
            }
        }
    }

    private fun payloadIsQuarantineOnly(rows: List<SnapshotRecord>, payload: String): Boolean {
        val matches = rows.filter { it.payload == payload }
        return matches.isNotEmpty() && matches.all { it.reason == SnapshotReasons.QUARANTINE }
    }

    private fun recoveryBaseline(rows: List<SnapshotRecord>, pointerPayload: String): SnapshotRecord? {
        val reasons = listOf(
            SnapshotReasons.POST_RESTORE,
            SnapshotReasons.MIGRATED_BASELINE,
            SnapshotReasons.PRE_RESTORE,
        )
        for (reason in reasons) {
            val row = rows.lastOrNull { it.reason == reason && it.payload.isNotBlank() && it.payload != pointerPayload }
            if (row != null) return row
        }
        return null
    }

    private suspend fun enqueueRow(
        outbox: SyncOutboxDao,
        instance: GameInstance,
        row: SnapshotRecord,
        proposePointer: Boolean,
        now: Long,
    ) {
        enqueue(
            outbox = outbox,
            instance = instance,
            payload = row.payload,
            snapshotId = row.id,
            reason = row.reason,
            capturedAtMillis = row.capturedAtMillis,
            proposePointer = proposePointer,
            now = now,
        )
    }

    private suspend fun enqueue(
        outbox: SyncOutboxDao,
        instance: GameInstance,
        payload: String,
        snapshotId: String,
        reason: String,
        capturedAtMillis: Long,
        proposePointer: Boolean,
        now: Long,
    ) {
        if (payload.isBlank() || reason == SnapshotReasons.QUARANTINE) return
        val sha = PayloadDigest.sha256(payload)
        if (outbox.findBySha(instance.gameId, sha) != null) return
        enqueueAcceptedSnapshot(
            dao = outbox,
            instance = instance,
            snapshotId = snapshotId,
            payload = payload,
            sha = sha,
            capturedAtMillis = capturedAtMillis,
            reason = reason,
            integrity = SyncOutboxEntry.INTEGRITY_ACCEPTED,
            proposePointer = proposePointer,
            enqueuedAtMillis = now,
        )
    }
}
