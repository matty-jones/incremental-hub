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
    var archiveThread: String? = null
        private set

    /** Test hook. Runs after a row is reserved and before that reservation becomes a hold. */
    internal var beforeHold: (suspend () -> Unit)? = null

    /** Test hook. Runs after the export set is held and before those rows are written. */
    internal var afterSelection: (suspend () -> Unit)? = null

    suspend fun exportSnapshot(snapshotId: String): IhubExportResult = IhubArchive.runOffMain {
        noteThread()
        val selected = takeForExport(snapshotId)
            ?: return@runOffMain IhubExportResult.Refused(
                "Snapshot $snapshotId is not on this phone.",
                listOf(snapshotId),
            )
        try {
            val writer = IhubArchive.beginExport(
                archiveId = UUID.randomUUID().toString(),
                createdAt = Instant.now().toString(),
                exporterVersion = exporterVersion,
            )
            try {
                afterSelection?.invoke()
            val row = snapshots.getById(snapshotId)
            if (!selected.matches(row)) {
                return@runOffMain writer.refuse(
                    "This is not a complete archive. Missing: $snapshotId",
                    listOf(snapshotId),
                )
            }
            val failure = writer.add(row!!.toDraft())
            if (failure != null) return@runOffMain failure
            writer.finish(complete = true, omissions = emptyList())
            } catch (cancelled: CancellationException) {
                writer.abort()
                throw cancelled
            }
        } finally {
            ExportHold.release(listOf(snapshotId))
        }
    }

    suspend fun exportGame(gameId: String): IhubExportResult = IhubArchive.runOffMain {
        noteThread()
        exportGames(listOf(gameId), emptyMap())
    }

    suspend fun exportAll(): IhubExportResult = IhubArchive.runOffMain {
        noteThread()
        val index = loadExportIndex()
            ?: return@runOffMain IhubExportResult.Refused(
                "The workstation game list could not be read. This is not a complete archive.",
                listOf("workstation games"),
            )
        exportGames(index.first, index.second)
    }

    suspend fun import(file: File): IhubImportResult = IhubArchive.runOffMain {
        noteThread()
        when (val opened = IhubArchive.open(file)) {
            is IhubOpenResult.Rejected -> IhubImportResult(0, 0, opened.message)
            is IhubOpenResult.Ready -> publisher.import(opened.source)
        }
    }

    suspend fun import(bytes: ByteArray): IhubImportResult = IhubArchive.runOffMain {
        noteThread()
        if (bytes.size.toLong() > IhubArchive.MAX_COMPRESSED_BYTES) {
            return@runOffMain IhubImportResult(0, 0, "The archive is larger than the compressed limit.")
        }
        val file = File.createTempFile("ihub-import", ".zip")
        try {
            file.outputStream().use { it.write(bytes) }
            when (val opened = IhubArchive.open(file)) {
                is IhubOpenResult.Rejected -> IhubImportResult(0, 0, opened.message)
                is IhubOpenResult.Ready -> publisher.import(opened.source)
            }
        } finally {
            file.delete()
        }
    }

    private suspend fun loadExportIndex(): Pair<List<String>, Map<String, String>>? {
        val remote = try {
            api.fetchSaves()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return null
        val ids = (snapshots.gameIds() + remote.map { it.gameId }).distinct().sorted()
        val pointerSha = buildMap {
            for (save in remote) {
                if (save.snapshotPayload.isNotEmpty()) {
                    put(save.gameId, PayloadDigest.sha256(save.snapshotPayload))
                }
            }
        }
        return ids to pointerSha
    }

    private suspend fun exportGames(
        gameIds: List<String>,
        pointerShaByGame: Map<String, String>,
    ): IhubExportResult {
        val writer = IhubArchive.beginExport(
            archiveId = UUID.randomUUID().toString(),
            createdAt = Instant.now().toString(),
            exporterVersion = exporterVersion,
        )
        val omissions = mutableListOf<String>()
        val held = mutableListOf<String>()
        try {
            val selectedByGame = LinkedHashMap<String, List<SelectedSnapshot>>()
            for (gameId in gameIds) {
                val selected = ArrayList<SelectedSnapshot>()
                for (id in snapshots.idsForGame(gameId)) {
                    val selectedRow = takeForExport(id)
                    if (selectedRow == null) {
                        omissions.add(id)
                    } else {
                        selected += selectedRow
                        held += id
                    }
                }
                selectedByGame[gameId] = selected
            }
            afterSelection?.invoke()
            for (gameId in gameIds) {
                val localIds = mutableSetOf<String>()
                for (selected in selectedByGame.getValue(gameId)) {
                    val row = snapshots.getById(selected.id)
                    if (row == null || !selected.matches(row)) {
                        omissions.add(selected.id)
                        continue
                    }
                    localIds += row.id
                    val failure = writer.add(row.toDraft())
                    if (failure != null) return failure
                }
                val remote = remoteIndex(gameId)
                if (remote == null) {
                    omissions.add("$gameId workstation history")
                    continue
                }
                for (meta in remote) {
                    if (meta.snapshotId in localIds) continue
                    val fetched = fetchRemote(gameId, meta.snapshotId)
                    val draft = fetched?.toDraft(meta)
                    if (draft == null) {
                        omissions.add(meta.snapshotId)
                    } else {
                        val failure = writer.add(draft)
                        if (failure != null) return failure
                    }
                }
                val pointer = pointerShaByGame[gameId]
                if (pointer != null && !writer.contains(gameId, pointer)) {
                    omissions.add("$gameId workstation pointer")
                }
            }
            if (omissions.isNotEmpty()) {
                return writer.refuse(
                    "This is not a complete archive. Missing: ${omissions.joinToString(", ")}",
                    omissions,
                )
            }
            return writer.finish(complete = true, omissions = emptyList())
        } catch (cancelled: CancellationException) {
            writer.abort()
            throw cancelled
        } catch (e: Exception) {
            writer.abort()
            return IhubExportResult.Refused(
                e.message ?: "The archive could not be written.",
                omissions,
            )
        } finally {
            ExportHold.release(held)
        }
    }

    private fun fetchRemote(gameId: String, snapshotId: String): RemoteSnapshot? {
        return try {
            api.fetchSnapshot(gameId, snapshotId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private fun remoteIndex(gameId: String): List<SnapshotMetadata>? {
        val rows = mutableListOf<SnapshotMetadata>()
        var afterAt = 0L
        var afterId = ""
        while (true) {
            val page = try {
                api.fetchHistoryIndex(gameId, afterAt, afterId, pageSize)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return null
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

    private suspend fun takeForExport(id: String): SelectedSnapshot? {
        ExportHold.beginSelection(id)
        var transferred = false
        try {
            val row = snapshots.getById(id) ?: return null
            beforeHold?.invoke()
            val selected = SelectedSnapshot(
                id = row.id,
                payloadSha256 = PayloadDigest.sha256(row.payload),
                storedSha256 = row.contentSha256,
                payloadBytes = row.payload.length,
            )
            ExportHold.guard {
                ExportHold.holdUnlocked(listOf(id))
                ExportHold.endSelectionUnlocked(id)
                transferred = true
            }
            return selected
        } finally {
            if (!transferred) ExportHold.endSelection(id)
        }
    }

    private fun noteThread() {
        archiveThread = IhubArchive.lastIoThread
    }
}

private data class SelectedSnapshot(
    val id: String,
    val payloadSha256: String,
    val storedSha256: String,
    val payloadBytes: Int,
) {
    fun matches(row: SnapshotRecord?): Boolean {
        if (row == null || row.id != id) return false
        if (row.contentSha256 != storedSha256 || row.payload.length != payloadBytes) return false
        return PayloadDigest.sha256(row.payload) == payloadSha256
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
