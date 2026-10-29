package com.example.hub.sync

import android.util.Log
import com.example.hub.database.GameInstance
import com.example.hub.database.GameInstanceDao
import com.example.hub.database.SnapshotDao
import com.example.hub.database.SnapshotOrigins
import com.example.hub.database.SnapshotReasons
import com.example.hub.database.SnapshotRecord
import com.example.hub.games.SaveComparison
import com.example.hub.games.SaveStanding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Mirrors Room snapshots to the workstation. The phone keeps playing from Room
 * when the workstation does not answer. A progressed save beats a fresh one.
 * Otherwise the newer capture wins.
 *
 * [assess] reads the local payload as the live save. It may return null standings
 * when the game has no progress comparator.
 */
class SaveSync(
    private val dao: GameInstanceDao,
    private val api: WorkstationApi,
    private val snapshotDao: SnapshotDao? = null,
    private val assess: (gameId: String, localPayload: String?, remotePayload: String?) -> SaveAssessment =
        { _, _, _ -> SaveAssessment() },
) {
    var activeGameId: () -> String? = { null }
    var onDecision: (String) -> Unit = {}

    /**
     * True when [candidate] may replace [reference]. A capture incident and a
     * non-legacy lineage are never uploaded or replaced from the workstation.
     */
    var protectsLocal: (gameId: String, reference: String?, candidate: String?) -> Boolean =
        { _, _, _ -> true }

    private val mutex = Mutex()

    suspend fun push(instance: GameInstance) {
        mutex.withLock { syncLocked(fallbackPush = instance) }
    }

    suspend fun merge() {
        mutex.withLock { syncLocked(fallbackPush = null) }
    }

    private suspend fun syncLocked(fallbackPush: GameInstance?) {
        val remoteSaves = withContext(Dispatchers.IO) { api.fetchSaves() }
        if (remoteSaves == null) {
            Log.i(TAG, "workstation unreachable; keeping local saves")
            if (syncBlocked(fallbackPush)) {
                onDecision(
                    "Workstation sync skipped. The active save was kept.",
                )
                return
            }
            val remote = fallbackPush?.toRemoteSave() ?: return
            withContext(Dispatchers.IO) { api.putSave(remote) }
            return
        }
        val local = dao.getAll().associateBy { it.gameId }.toMutableMap()
        if (fallbackPush != null) local[fallbackPush.gameId] = fallbackPush
        val remoteById = remoteSaves.associateBy { it.gameId }
        for (gameId in local.keys + remoteById.keys) {
            val localRow = local[gameId]
            val remoteRow = remoteById[gameId]
            if (syncBlocked(localRow)) {
                onDecision(
                    "Workstation sync skipped for $gameId. The active save was kept.",
                )
                continue
            }
            when (decision(localRow, remoteRow)) {
                MergeDecision.Push -> pushLocked(localRow)
                MergeDecision.Pull -> remoteRow?.let { applyPull(it) }
                MergeDecision.Skip -> Unit
            }
        }
    }

    private suspend fun pushLocked(instance: GameInstance?) {
        val remote = instance?.toRemoteSave() ?: return
        val result = withContext(Dispatchers.IO) { api.putSave(remote) }
        if (result is PutResult.Stale && decision(instance, result.remote) == MergeDecision.Pull) {
            applyPull(result.remote)
        }
    }

    private fun decision(local: GameInstance?, remote: RemoteSave?): MergeDecision {
        val assessment = if (local?.gameId != null || remote != null) {
            assess(local?.gameId ?: remote!!.gameId, local?.snapshotPayload, remote?.snapshotPayload)
        } else {
            SaveAssessment()
        }
        return decideMerge(
            localCapturedAtMillis = local.capturedAtOrNull(),
            remoteCapturedAtMillis = remote?.capturedAtMillis,
            localStanding = assessment.localStanding,
            remoteStanding = assessment.remoteStanding,
            comparison = assessment.comparison,
        )
    }

    private suspend fun applyPull(remote: RemoteSave) {
        val existing = dao.get(remote.gameId)
        if (existing != null && !remoteMayReplace(existing, remote)) {
            onDecision("Rejected workstation save for ${remote.gameId}. The stored snapshot was kept.")
            return
        }
        val merge = decision(existing, remote)
        if (!replacesStoredPointer(activeGameId() == remote.gameId, merge)) {
            if (merge == MergeDecision.Pull) {
                rememberPulled(remote)
                onDecision(
                    "Archived workstation save for ${remote.gameId} without replacing the open session.",
                )
            } else {
                onDecision(
                    "Rejected workstation save for ${remote.gameId}. The stored snapshot was kept.",
                )
            }
            return
        }
        dao.upsert(remote.toGameInstance(existing))
        rememberPulled(remote)
        onDecision("Pulled workstation save for ${remote.gameId}.")
        Log.i(TAG, "pulled ${remote.gameId} captured at ${remote.capturedAtMillis}")
    }

    private suspend fun remoteMayReplace(existing: GameInstance, remote: RemoteSave): Boolean {
        if (!protectsLocal(existing.gameId, existing.snapshotPayload, remote.snapshotPayload)) return false
        val highId = existing.highWaterSnapshotId ?: return true
        val highWater = snapshotDao?.getById(highId)?.payload ?: return true
        return protectsLocal(existing.gameId, highWater, remote.snapshotPayload)
    }

    private suspend fun rememberPulled(remote: RemoteSave) {
        val snapshots = snapshotDao ?: return
        if (snapshots.history(remote.gameId).any { it.payload == remote.snapshotPayload }) return
        snapshots.insert(
            SnapshotRecord(
                id = UUID.randomUUID().toString(),
                gameId = remote.gameId,
                sequence = snapshots.maxSequence(remote.gameId) + 1,
                capturedAtMillis = remote.capturedAtMillis,
                reason = SnapshotReasons.WORKSTATION,
                pinned = true,
                payload = remote.snapshotPayload,
                progressJson = null,
                origin = SnapshotOrigins.WORKSTATION,
            ),
        )
    }

    private companion object {
        const val TAG = "SaveSync"
    }
}

data class SaveAssessment(
    val localStanding: SaveStanding? = null,
    val remoteStanding: SaveStanding? = null,
    val comparison: SaveComparison? = null,
)

private fun GameInstance?.capturedAtOrNull(): Long? {
    if (this == null || snapshotPayload.isNullOrBlank()) return null
    return lastCaptureAtMillis
}

private fun GameInstance.toRemoteSave(): RemoteSave? {
    val payload = snapshotPayload?.takeIf { it.isNotBlank() } ?: return null
    return RemoteSave(
        gameId = gameId,
        snapshotPayload = payload,
        capturedAtMillis = lastCaptureAtMillis,
        profileName = profileName,
        sessionIdentity = sessionIdentity,
        lastPlayedAtMillis = lastPlayedAtMillis,
        resumeToken = resumeToken,
        recoveryState = recoveryState,
    )
}

private fun RemoteSave.toGameInstance(existing: GameInstance?): GameInstance {
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
        lineageId = existing?.lineageId ?: "legacy",
        highWaterSnapshotId = existing?.highWaterSnapshotId,
        captureIncident = existing?.captureIncident,
        captureIncidentSha = existing?.captureIncidentSha,
        captureIncidentCount = existing?.captureIncidentCount ?: 0,
        lastSeenAtMillis = existing?.lastSeenAtMillis ?: 0L,
    )
}

private fun syncBlocked(instance: GameInstance?): Boolean {
    if (instance == null) return false
    if (instance.captureIncident != null) return true
    return instance.lineageId != "legacy"
}
