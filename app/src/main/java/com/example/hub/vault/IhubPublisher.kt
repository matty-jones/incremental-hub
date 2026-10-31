package com.example.hub.vault

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.example.hub.database.GameInstanceDao
import com.example.hub.database.SnapshotDao
import com.example.hub.database.SnapshotRecord

data class IhubImportResult(
    val imported: Int,
    val duplicates: Int,
    val message: String,
)

class IhubConflict(message: String) : IllegalStateException(message)

/**
 * Publishes archive snapshots as historical rows. The live pointer, the
 * WebView, and the workstation pointer are not part of this write.
 */
class IhubPublisher(
    private val room: RoomDatabase,
    private val snapshots: SnapshotDao,
    private val instances: GameInstanceDao,
) {
    suspend fun import(archive: IhubContents): IhubImportResult {
        if (archive.encrypted || archive.formatVersion != IhubArchive.FORMAT_VERSION) {
            return IhubImportResult(0, 0, "This archive format cannot be imported.")
        }
        return writeSnapshots(archive.snapshots.size) { archive.snapshots[it] }
    }

    suspend fun import(source: IhubSource): IhubImportResult {
        if (source.encrypted || source.formatVersion != IhubArchive.FORMAT_VERSION) {
            return IhubImportResult(0, 0, "This archive format cannot be imported.")
        }
        return writeSnapshots(source.entries.size) { source.snapshot(it) }
    }

    private suspend fun writeSnapshots(count: Int, load: (Int) -> IhubSnapshotFile): IhubImportResult {
        val before = instances.getAll().associate { it.gameId to it.snapshotPayload }
        var imported = 0
        var duplicates = 0
        try {
            room.withTransaction {
                for (index in 0 until count) {
                    val file = load(index)
                    val existing = snapshots.getById(file.id)
                    if (existing != null) {
                        if (existing.payload != file.payload || existing.contentSha256 != file.contentSha256) {
                            throw IhubConflict(
                                "Snapshot ${file.id} is already stored with different bytes. Nothing was imported.",
                            )
                        }
                        duplicates += 1
                        continue
                    }
                    snapshots.insert(
                        SnapshotRecord(
                            id = file.id,
                            gameId = file.gameId,
                            sequence = file.sequence,
                            capturedAtMillis = file.capturedAtMillis,
                            reason = file.reason,
                            pinned = file.pinned,
                            payload = file.payload,
                            progressJson = null,
                            origin = file.origin,
                            lineageId = file.lineageId,
                            contentSha256 = file.contentSha256,
                            userProtected = file.userProtected,
                        ),
                    )
                    imported += 1
                }
            }
        } catch (e: IhubConflict) {
            return IhubImportResult(0, 0, e.message ?: "The archive conflicts with a stored snapshot.")
        }
        val after = instances.getAll().associate { it.gameId to it.snapshotPayload }
        if (before != after) {
            error("Import changed a live save pointer.")
        }
        return IhubImportResult(
            imported = imported,
            duplicates = duplicates,
            message = "Imported $imported snapshots. $duplicates already matched. The live save was not changed.",
        )
    }
}
