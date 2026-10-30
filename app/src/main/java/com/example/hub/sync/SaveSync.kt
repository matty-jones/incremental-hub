package com.example.hub.sync

import android.util.Log
import com.example.hub.database.GameInstance
import com.example.hub.database.GameInstanceDao
import com.example.hub.database.SnapshotDao
import com.example.hub.database.SnapshotOrigins
import com.example.hub.database.SnapshotReasons
import com.example.hub.database.SnapshotRecord
import com.example.hub.database.SyncHistoryCursor
import com.example.hub.database.SyncHistoryCursorDao
import com.example.hub.database.SyncOutboxDao
import com.example.hub.database.SyncOutboxEntry
import com.example.hub.games.PayloadDigest
import com.example.hub.games.ProgressionClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Uploads the local outbox after a commit and reads workstation history as
 * metadata. A full payload is stored only when the phone adopts an empty slot
 * or the caller asks for one snapshot. The phone keeps playing from Room when
 * the workstation does not answer. A wall-clock timestamp is not a reason to
 * replace either side.
 */
class SaveSync(
    private val dao: GameInstanceDao,
    private val api: WorkstationApi,
    private val snapshotDao: SnapshotDao? = null,
    private val outboxDao: SyncOutboxDao? = null,
    private val historyCursorDao: SyncHistoryCursorDao? = null,
) {
    var activeGameId: () -> String? = { null }
    var onDecision: (String) -> Unit = {}

    /**
     * Compares [candidate] with [reference] using the game adapter.
     * [ProgressionClass.UNORDERED] is the safe default when the caller has
     * not supplied an adapter.
     */
    var classify: (gameId: String, candidate: String?, reference: String?) -> ProgressionClass =
        { _, _, _ -> ProgressionClass.UNORDERED }

    private val mutex = Mutex()

    suspend fun push(instance: GameInstance) {
        mutex.withLock {
            Log.i(TAG, "sync after local commit ${instance.gameId}")
            syncLocked()
        }
    }

    suspend fun merge() {
        mutex.withLock { syncLocked() }
    }

    /**
     * Fetches one historical payload and keeps it as a local workstation snapshot.
     * A second request for the same bytes does not insert another row.
     */
    suspend fun recoverSnapshot(gameId: String, snapshotId: String): Boolean {
        return mutex.withLock {
            val remote = withContext(Dispatchers.IO) { api.fetchSnapshot(gameId, snapshotId) } ?: return false
            rememberWorkstationPayload(
                gameId = remote.gameId,
                lineageId = remote.lineageId,
                payload = remote.snapshotPayload,
                capturedAtMillis = remote.capturedAtMillis,
            )
        }
    }

    private suspend fun syncLocked() {
        bootstrapExisting()
        val remoteSaves = withContext(Dispatchers.IO) { api.fetchSaves() }
        if (remoteSaves == null) {
            Log.i(TAG, "workstation unreachable; keeping local saves")
            onDecision("Saved locally; workstation sync pending.")
            return
        }
        if (!uploadPending()) return
        reconcile(remoteSaves)
    }

    private suspend fun uploadPending(): Boolean {
        val outbox = outboxDao ?: return true
        val pending = outbox.pending()
        val proposeKeys = pending
            .filter { it.proposePointer && it.integrity == SyncOutboxEntry.INTEGRITY_ACCEPTED }
            .groupBy { it.gameId }
            .mapValues { (_, rows) -> rows.last().idempotencyKey }
        for (entry in pending) {
            if (entry.reason == SnapshotReasons.QUARANTINE || entry.integrity == "quarantine") {
                outbox.markBlocked(entry.idempotencyKey)
                continue
            }
            val uploaded = withContext(Dispatchers.IO) { api.appendSnapshot(entry.toUpload()) }
            when (uploaded) {
                null -> {
                    outbox.noteAttempt(entry.idempotencyKey)
                    onDecision("Saved locally; workstation sync pending.")
                    return false
                }
                SnapshotUploadResult.Rejected -> {
                    outbox.markBlocked(entry.idempotencyKey)
                    onDecision("Workstation sync kept the local save for ${entry.gameId}.")
                    continue
                }
                is SnapshotUploadResult.Stored -> {
                    if (uploaded.sha256 != entry.contentSha256) {
                        outbox.noteAttempt(entry.idempotencyKey)
                        onDecision("Saved locally; workstation sync pending.")
                        return false
                    }
                    val propose = proposeKeys[entry.gameId] == entry.idempotencyKey
                    if (propose && !proposePointer(entry, uploaded.snapshotId)) return false
                    outbox.markAcked(entry.idempotencyKey, uploaded.snapshotId)
                }
            }
        }
        return true
    }

    private suspend fun proposePointer(entry: SyncOutboxEntry, remoteSnapshotId: String): Boolean {
        val local = dao.get(entry.gameId)
        if (local == null || local.lineageId != entry.lineageId) {
            onDecision("Workstation sync kept ${entry.gameId} on its current lineage.")
            return true
        }
        if (local.snapshotPayload != entry.payload) return true
        if (!clearsHighWater(local, entry.payload)) {
            onDecision("Rejected workstation pointer move for ${entry.gameId}. The stored snapshot was kept.")
            return true
        }
        val fetched = withContext(Dispatchers.IO) { api.fetchPointer(entry.gameId) }
        val expected = when (fetched) {
            PointerFetch.Unreachable -> {
                onDecision("Saved locally; workstation sync pending.")
                return false
            }
            PointerFetch.Absent -> 0
            is PointerFetch.Found -> {
                if (fetched.pointer.lineageId != entry.lineageId) {
                    onDecision("Workstation sync kept ${entry.gameId} on its current lineage.")
                    return true
                }
                if (fetched.pointer.snapshotPayload == entry.payload) return true
                val relation = classify(entry.gameId, entry.payload, fetched.pointer.snapshotPayload)
                if (!mayPublish(relation)) {
                    onDecision(
                        "Rejected workstation pointer move for ${entry.gameId}. The stored snapshot was kept.",
                    )
                    return true
                }
                fetched.pointer.version
            }
        }
        val proposed = withContext(Dispatchers.IO) {
            api.proposePointer(
                PointerProposal(
                    gameId = entry.gameId,
                    expectedVersion = expected,
                    snapshotId = remoteSnapshotId,
                    lineageId = entry.lineageId,
                ),
            )
        }
        return when (proposed) {
            null -> {
                onDecision("Saved locally; workstation sync pending.")
                false
            }
            is PointerProposalResult.Stored -> true
            is PointerProposalResult.Conflict -> {
                if (proposed.conflict == "version" && proposed.pointer != null) {
                    retryPropose(entry, remoteSnapshotId, proposed.pointer)
                } else {
                    onDecision(
                        "Workstation pointer for ${entry.gameId} was not moved. The stored snapshot was kept.",
                    )
                    true
                }
            }
        }
    }

    private suspend fun retryPropose(
        entry: SyncOutboxEntry,
        remoteSnapshotId: String,
        current: RemotePointer,
    ): Boolean {
        if (current.lineageId != entry.lineageId) {
            onDecision("Workstation sync kept ${entry.gameId} on its current lineage.")
            return true
        }
        val relation = classify(entry.gameId, entry.payload, current.snapshotPayload)
        if (current.snapshotPayload == entry.payload || !mayPublish(relation)) {
            onDecision("Rejected workstation pointer move for ${entry.gameId}. The stored snapshot was kept.")
            return true
        }
        val proposed = withContext(Dispatchers.IO) {
            api.proposePointer(
                PointerProposal(
                    gameId = entry.gameId,
                    expectedVersion = current.version,
                    snapshotId = remoteSnapshotId,
                    lineageId = entry.lineageId,
                ),
            )
        }
        return when (proposed) {
            is PointerProposalResult.Stored -> true
            null -> {
                onDecision("Saved locally; workstation sync pending.")
                false
            }
            is PointerProposalResult.Conflict -> {
                onDecision("Workstation pointer for ${entry.gameId} was not moved. The stored snapshot was kept.")
                true
            }
        }
    }

    private suspend fun bootstrapExisting() {
        val snapshots = snapshotDao ?: return
        val outbox = outboxDao ?: return
        SyncBootstrap.enqueueExisting(dao, snapshots, outbox)
    }

    private suspend fun reconcile(remoteSaves: List<RemoteSave>) {
        val local = dao.getAll().associateBy { it.gameId }
        val gameIds = local.keys + remoteSaves.map { it.gameId }
        for (gameId in gameIds) {
            if (!syncHistoryIndex(gameId)) {
                onDecision("Saved locally; workstation sync pending.")
                return
            }
            when (val pointer = withContext(Dispatchers.IO) { api.fetchPointer(gameId) }) {
                PointerFetch.Unreachable -> {
                    onDecision("Saved locally; workstation sync pending.")
                    return
                }
                PointerFetch.Absent -> archiveLegacy(remoteSaves.firstOrNull { it.gameId == gameId })
                is PointerFetch.Found -> considerRemote(pointer.pointer)
            }
        }
    }

    private suspend fun considerRemote(pointer: RemotePointer) {
        val existing = dao.get(pointer.gameId)
        val localPayload = existing?.snapshotPayload?.takeIf { it.isNotBlank() }
        if (localPayload != null) {
            if (localPayload != pointer.snapshotPayload) {
                onDecision(
                    "Rejected workstation save for ${pointer.gameId}. The stored snapshot was kept.",
                )
            }
            return
        }
        if (activeGameId() == pointer.gameId) {
            onDecision("Archived workstation save for ${pointer.gameId} without replacing the open session.")
            return
        }
        if (existing != null && existing.lineageId != pointer.lineageId) {
            onDecision("Workstation sync kept ${pointer.gameId} on its current lineage.")
            return
        }
        val relation = classify(pointer.gameId, pointer.snapshotPayload, null)
        if (relation == ProgressionClass.INVALID || relation == ProgressionClass.INCOMPARABLE) {
            onDecision("Rejected workstation save for ${pointer.gameId}. The stored snapshot was kept.")
            return
        }
        dao.upsert(pointer.toGameInstance(existing))
        rememberWorkstationPayload(
            gameId = pointer.gameId,
            lineageId = pointer.lineageId,
            payload = pointer.snapshotPayload,
            capturedAtMillis = pointer.capturedAtMillis,
        )
        onDecision("Pulled workstation save for ${pointer.gameId}.")
    }

    private suspend fun archiveLegacy(remote: RemoteSave?) {
        if (remote == null) return
        val existing = dao.get(remote.gameId)
        if (!existing?.snapshotPayload.isNullOrBlank() && existing?.snapshotPayload != remote.snapshotPayload) {
            onDecision("Rejected workstation save for ${remote.gameId}. The stored snapshot was kept.")
        }
    }

    private suspend fun syncHistoryIndex(gameId: String): Boolean {
        val cursors = historyCursorDao ?: return true
        val cursor = cursors.get(gameId)
        var afterAt = cursor?.receivedAtMillis ?: 0L
        var afterId = cursor?.snapshotId.orEmpty()
        var seen = cursor?.seenCount ?: 0
        while (true) {
            val page = withContext(Dispatchers.IO) {
                api.fetchHistoryIndex(gameId, afterAt, afterId, HISTORY_PAGE)
            } ?: return false
            if (page.entries.isEmpty()) return true
            val last = page.entries.last()
            seen += page.entries.size
            cursors.upsert(
                SyncHistoryCursor(
                    gameId = gameId,
                    receivedAtMillis = last.receivedAtMillis,
                    snapshotId = last.snapshotId,
                    seenCount = seen,
                ),
            )
            afterAt = last.receivedAtMillis
            afterId = last.snapshotId
            if (page.complete || page.entries.size < HISTORY_PAGE) return true
        }
    }

    private suspend fun rememberWorkstationPayload(
        gameId: String,
        lineageId: String,
        payload: String,
        capturedAtMillis: Long,
    ): Boolean {
        val snapshots = snapshotDao ?: return false
        if (payload.isBlank()) return false
        val sha = PayloadDigest.sha256(payload)
        if (snapshots.findBySha(gameId, sha)?.payload == payload) return true
        if (snapshots.history(gameId).any { it.payload == payload }) return true
        snapshots.insert(
            SnapshotRecord(
                id = UUID.randomUUID().toString(),
                gameId = gameId,
                sequence = snapshots.maxSequence(gameId) + 1,
                capturedAtMillis = capturedAtMillis,
                reason = SnapshotReasons.WORKSTATION,
                pinned = true,
                payload = payload,
                progressJson = null,
                origin = SnapshotOrigins.WORKSTATION,
                lineageId = lineageId,
                contentSha256 = sha,
            ),
        )
        return true
    }

    private suspend fun clearsHighWater(existing: GameInstance, payload: String): Boolean {
        val highId = existing.highWaterSnapshotId ?: return true
        val highWater = snapshotDao?.getById(highId)?.payload?.takeIf { it.isNotBlank() } ?: return true
        return mayPublish(classify(existing.gameId, payload, highWater))
    }

    private companion object {
        const val TAG = "SaveSync"
        const val HISTORY_PAGE = 200
    }
}

private fun mayPublish(relation: ProgressionClass): Boolean = when (relation) {
    ProgressionClass.ADVANCED, ProgressionClass.EQUAL, ProgressionClass.RESET_APPROVED -> true
    ProgressionClass.UNORDERED,
    ProgressionClass.REGRESSED,
    ProgressionClass.INCOMPARABLE,
    ProgressionClass.INVALID,
    -> false
}

private fun SyncOutboxEntry.toUpload(): SnapshotUpload {
    return SnapshotUpload(
        gameId = gameId,
        snapshotId = snapshotId,
        lineageId = lineageId,
        snapshotPayload = payload,
        capturedAtMillis = capturedAtMillis,
        idempotencyKey = idempotencyKey,
        integrity = integrity,
        reason = reason,
        profileName = profileName,
        sessionIdentity = sessionIdentity,
        lastPlayedAtMillis = lastPlayedAtMillis,
        resumeToken = resumeToken,
    )
}

private fun RemotePointer.toGameInstance(existing: GameInstance?): GameInstance {
    return GameInstance(
        gameId = gameId,
        profileName = profileName.ifBlank { existing?.profileName ?: "hub-game-$gameId" },
        sessionIdentity = sessionIdentity.ifBlank { existing?.sessionIdentity ?: gameId },
        lastPlayedAtMillis = maxOf(lastPlayedAtMillis, existing?.lastPlayedAtMillis ?: 0L),
        resumeToken = resumeToken ?: existing?.resumeToken,
        recoveryState = existing?.recoveryState ?: recoveryState,
        lastCaptureAtMillis = capturedAtMillis,
        snapshotPayload = snapshotPayload,
        lastCaptureAttemptAtMillis = existing?.lastCaptureAttemptAtMillis ?: capturedAtMillis,
        lastCaptureSuccessAtMillis = capturedAtMillis,
        lastCaptureFailureAtMillis = existing?.lastCaptureFailureAtMillis ?: 0L,
        lastCaptureError = null,
        lineageId = existing?.lineageId ?: lineageId,
        highWaterSnapshotId = existing?.highWaterSnapshotId,
        captureIncident = existing?.captureIncident,
        captureIncidentSha = existing?.captureIncidentSha,
        captureIncidentCount = existing?.captureIncidentCount ?: 0,
        lastSeenAtMillis = existing?.lastSeenAtMillis ?: 0L,
    )
}
