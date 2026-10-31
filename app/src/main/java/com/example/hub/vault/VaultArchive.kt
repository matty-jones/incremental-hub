package com.example.hub.vault

import com.example.hub.database.SnapshotDao
import com.example.hub.database.SnapshotRecord
import com.example.hub.games.PayloadDigest
import com.example.hub.sync.HistoryPage
import com.example.hub.sync.RemoteSnapshot
import com.example.hub.sync.SnapshotMetadata
import com.example.hub.sync.WorkstationApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID

class VaultArchive(
    private val snapshots: SnapshotDao,
    private val api: WorkstationApi,
    private val publisher: IhubPublisher,
    private val exporterVersion: String,
    private val pageSize: Int = 200,
) {
    suspend fun exportSnapshot(snapshotId: String): IhubExportResult {
        val local = snapshots.getById(snapshotId)
        val draft = if (local != null) {
            local.toDraft()
        } else {
            null
        }
        if (draft != null) return exportDrafts(listOf(draft), complete = true)
        return IhubExportResult.Refused("Snapshot $snapshotId is not on this phone.", listOf(snapshotId))
    }

    suspend fun exportGame(gameId: String): IhubExportResult {
        return exportGames(listOf(gameId))
    }

    suspend fun exportAll(): IhubExportResult {
        return exportGames(snapshots.gameIds())
    }

    suspend fun import(bytes: ByteArray): IhubImportResult {
        return when (val read = IhubArchive.read(bytes)) {
            is IhubReadResult.Rejected -> IhubImportResult(0, 0, read.message)
            is IhubReadResult.Ok -> publisher.import(read.archive)
        }
    }

    private suspend fun exportGames(gameIds: List<String>): IhubExportResult {
        val drafts = mutableListOf<IhubSnapshotDraft>()
        val omissions = mutableListOf<String>()
        for (gameId in gameIds) {
            val local = snapshots.history(gameId)
            drafts.addAll(local.map { it.toDraft() })
            val remote = remoteIndex(gameId)
            if (remote == null) {
                omissions.add("$gameId workstation history")
                continue
            }
            val localIds = local.map { it.id }.toSet()
            for (meta in remote) {
                if (meta.snapshotId in localIds) continue
                val fetched = withContext(Dispatchers.IO) { api.fetchSnapshot(gameId, meta.snapshotId) }
                val draft = fetched?.toDraft(meta)
                if (draft == null) {
                    omissions.add(meta.snapshotId)
                } else {
                    drafts.add(draft)
                }
            }
        }
        if (omissions.isNotEmpty()) {
            return IhubExportResult.Refused(
                "This is not a complete archive. Missing: ${omissions.joinToString(", ")}",
                omissions,
            )
        }
        return exportDrafts(drafts, complete = true)
    }

    private fun exportDrafts(drafts: List<IhubSnapshotDraft>, complete: Boolean): IhubExportResult {
        return IhubArchive.export(
            drafts = drafts,
            archiveId = UUID.randomUUID().toString(),
            createdAt = Instant.now().toString(),
            exporterVersion = exporterVersion,
            complete = complete,
        )
    }

    private suspend fun remoteIndex(gameId: String): List<SnapshotMetadata>? {
        val rows = mutableListOf<SnapshotMetadata>()
        var afterAt = 0L
        var afterId = ""
        while (true) {
            val page = withContext(Dispatchers.IO) {
                api.fetchHistoryIndex(gameId, afterAt, afterId, pageSize)
            } ?: return null
            rows.addAll(page.entries)
            if (finished(page)) return rows
            val last = page.entries.last()
            afterAt = last.receivedAtMillis
            afterId = last.snapshotId
        }
    }

    private fun finished(page: HistoryPage): Boolean {
        return page.complete || page.entries.isEmpty() || page.entries.size < pageSize
    }
}

private fun SnapshotRecord.toDraft(): IhubSnapshotDraft {
    return IhubSnapshotDraft(
        id = id,
        gameId = gameId,
        lineageId = lineageId,
        sequence = sequence,
        capturedAtMillis = capturedAtMillis,
        reason = reason,
        origin = origin,
        pinned = pinned,
        userProtected = userProtected,
        storedSha256 = contentSha256,
        payload = payload,
    )
}

private fun RemoteSnapshot.toDraft(meta: SnapshotMetadata): IhubSnapshotDraft? {
    val sha = PayloadDigest.sha256(snapshotPayload)
    if (meta.sha256.isNotEmpty() && meta.sha256 != sha) return null
    if (sha256.isNotEmpty() && sha256 != sha) return null
    if (snapshotId != meta.snapshotId || gameId != meta.gameId || lineageId != meta.lineageId) return null
    return IhubSnapshotDraft(
        id = snapshotId,
        gameId = gameId,
        lineageId = lineageId,
        sequence = 0L,
        capturedAtMillis = capturedAtMillis,
        reason = "workstation",
        origin = "workstation-sync",
        pinned = false,
        userProtected = false,
        storedSha256 = sha,
        payload = snapshotPayload,
    )
}
