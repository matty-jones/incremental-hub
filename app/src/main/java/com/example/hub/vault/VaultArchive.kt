package com.example.hub.vault

import com.example.hub.database.SnapshotDao
import com.example.hub.database.SnapshotRecord
import com.example.hub.games.PayloadDigest
import com.example.hub.sync.HistoryPage
import com.example.hub.sync.RemoteSave
import com.example.hub.sync.RemoteSnapshot
import com.example.hub.sync.SnapshotMetadata
import com.example.hub.sync.WorkstationApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
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
        val remote = try {
            withContext(Dispatchers.IO) { api.fetchSaves() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return IhubExportResult.Refused(
            "The workstation game list could not be read. This is not a complete archive.",
            listOf("workstation games"),
        )
        val ids = (snapshots.gameIds() + remote.map { it.gameId }).distinct().sorted()
        return exportGames(ids, remote)
    }

    suspend fun import(file: File): IhubImportResult {
        return when (val opened = IhubArchive.open(file)) {
            is IhubOpenResult.Rejected -> IhubImportResult(0, 0, opened.message)
            is IhubOpenResult.Ready -> publisher.import(opened.source)
        }
    }

    suspend fun import(bytes: ByteArray): IhubImportResult {
        if (bytes.size.toLong() > IhubArchive.MAX_COMPRESSED_BYTES) {
            return IhubImportResult(0, 0, "The archive is larger than the compressed limit.")
        }
        val file = File.createTempFile("ihub-import", ".zip")
        return try {
            file.outputStream().use { it.write(bytes) }
            import(file)
        } finally {
            file.delete()
        }
    }

    private suspend fun exportGames(
        gameIds: List<String>,
        remoteSaves: List<RemoteSave> = emptyList(),
    ): IhubExportResult {
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
            val save = remoteSaves.firstOrNull { it.gameId == gameId }
            if (save != null && save.snapshotPayload.isNotEmpty() &&
                drafts.none { it.gameId == gameId && it.payload == save.snapshotPayload }
            ) {
                omissions.add("$gameId workstation pointer")
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
