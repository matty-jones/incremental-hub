package com.example.hub.sync

import android.util.Log
import com.example.hub.database.GameInstance
import com.example.hub.database.GameInstanceDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Mirrors Room snapshots to the workstation. The phone keeps playing from Room
 * when the workstation does not answer. A newer [GameInstance.lastCaptureAtMillis]
 * wins in either direction.
 */
class SaveSync(
    private val dao: GameInstanceDao,
    private val api: WorkstationApi,
) {
    private val mutex = Mutex()

    suspend fun push(instance: GameInstance) {
        val remote = instance.toRemoteSave() ?: return
        mutex.withLock {
            val result = withContext(Dispatchers.IO) { api.putSave(remote) }
            if (result is PutResult.Stale) {
                applyIfNewer(result.remote)
            }
        }
    }

    suspend fun merge() {
        mutex.withLock {
            val remoteSaves = withContext(Dispatchers.IO) { api.fetchSaves() }
            if (remoteSaves == null) {
                Log.i(TAG, "workstation unreachable; keeping local saves")
                return
            }
            val local = dao.getAll().associateBy { it.gameId }
            val remoteById = remoteSaves.associateBy { it.gameId }
            for (gameId in local.keys + remoteById.keys) {
                val localRow = local[gameId]
                val remoteRow = remoteById[gameId]
                when (decideMerge(localRow.capturedAtOrNull(), remoteRow?.capturedAtMillis)) {
                    MergeDecision.Push -> pushLocked(localRow)
                    MergeDecision.Pull -> remoteRow?.let { applyIfNewer(it) }
                    MergeDecision.Skip -> Unit
                }
            }
        }
    }

    private suspend fun pushLocked(instance: GameInstance?) {
        val remote = instance?.toRemoteSave() ?: return
        val result = withContext(Dispatchers.IO) { api.putSave(remote) }
        if (result is PutResult.Stale) {
            applyIfNewer(result.remote)
        }
    }

    private suspend fun applyIfNewer(remote: RemoteSave) {
        val existing = dao.get(remote.gameId)
        val existingAt = existing.capturedAtOrNull()
        if (existingAt != null && existingAt >= remote.capturedAtMillis) return
        dao.upsert(remote.toGameInstance(existing))
        Log.i(TAG, "pulled ${remote.gameId} captured at ${remote.capturedAtMillis}")
    }

    private companion object {
        const val TAG = "SaveSync"
    }
}

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
        resumeToken = resumeToken,
        recoveryState = recoveryState,
        lastCaptureAtMillis = capturedAtMillis,
        snapshotPayload = snapshotPayload,
        lastCaptureAttemptAtMillis = existing?.lastCaptureAttemptAtMillis ?: capturedAtMillis,
        lastCaptureSuccessAtMillis = capturedAtMillis,
        lastCaptureFailureAtMillis = existing?.lastCaptureFailureAtMillis ?: 0L,
        lastCaptureError = null,
    )
}
