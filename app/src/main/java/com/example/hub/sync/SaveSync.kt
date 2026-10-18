package com.example.hub.sync

import android.util.Log
import com.example.hub.database.GameInstance
import com.example.hub.database.GameInstanceDao
import com.example.hub.database.SnapshotDao
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
        if (decision(existing, remote) != MergeDecision.Pull) return
        dao.upsert(remote.toGameInstance(existing))
        rememberPulled(remote)
        Log.i(TAG, "pulled ${remote.gameId} captured at ${remote.capturedAtMillis}")
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
    )
}
