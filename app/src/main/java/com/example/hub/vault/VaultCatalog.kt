package com.example.hub.vault

import com.example.hub.database.SnapshotMeta
import com.example.hub.database.SnapshotOrigins
import com.example.hub.database.SnapshotReasons
import com.example.hub.games.PayloadDigest
import com.example.hub.sync.SnapshotMetadata
import com.example.hub.sync.WorkstationApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Local snapshot index. Implementations must not return payload text from
 * [page] or [count].
 */
interface VaultLocalIndex {
    suspend fun count(gameId: String): Int
    suspend fun page(gameId: String, limit: Int, offset: Int): List<SnapshotMeta>
    suspend fun existingIds(ids: List<String>): Set<String>
    suspend fun payload(id: String): String?
    suspend fun pointerSha(gameId: String): String?
    suspend fun highWaterId(gameId: String): String?
}

/**
 * Pages phone metadata and workstation index rows. Payload bytes are read
 * only from [readPayload], one snapshot at a time. A failed workstation
 * call leaves phone rows in place and marks workstation-only rows unavailable.
 */
class VaultCatalog(
    private val local: VaultLocalIndex,
    private val api: WorkstationApi,
    val pageSize: Int = DEFAULT_PAGE_SIZE,
) {
    private val remote = mutableMapOf<String, RemoteWalk>()
    var payloadReads: Int = 0
        private set

    suspend fun browse(gameId: String, localOffset: Int, advanceRemote: Boolean): VaultBrowse {
        val count = local.count(gameId)
        val offset = localOffset.coerceAtLeast(0)
        val metas = local.page(gameId, pageSize, offset)
        val pointerSha = local.pointerSha(gameId)
        val highWaterId = local.highWaterId(gameId)
        val walk = remote.getOrPut(gameId) { RemoteWalk() }
        if (advanceRemote && !walk.complete) {
            val page = workstation { api.fetchHistoryIndex(gameId, walk.afterAt, walk.afterId, pageSize) }
            if (page == null) {
                walk.reachable = false
            } else {
                walk.reachable = true
                walk.rows += page.entries
                if (page.entries.isNotEmpty()) {
                    val last = page.entries.last()
                    walk.afterAt = last.receivedAtMillis
                    walk.afterId = last.snapshotId
                }
                if (page.complete || page.entries.isEmpty() || page.entries.size < pageSize) {
                    walk.complete = true
                }
            }
        }
        val pageIds = metas.map { it.id }.toSet()
        val remoteIds = walk.rows.map { it.snapshotId }.filter { it !in pageIds }
        val known = if (remoteIds.isEmpty()) emptySet() else local.existingIds(remoteIds)
        val phone = metas.map { meta ->
            phoneEntry(meta, pointerSha, highWaterId)
        }
        val remoteOnly = walk.rows.mapNotNull { row ->
            if (row.snapshotId in pageIds || row.snapshotId in known) return@mapNotNull null
            remoteEntry(row, reachable = walk.reachable)
        }
        val entries = orderedTimeline(phone + remoteOnly)
        return VaultBrowse(
            gameId = gameId,
            entries = entries,
            groups = groupTimeline(entries),
            localCount = count,
            localOffset = offset,
            pageSize = pageSize,
            remoteFetched = walk.rows.size,
            remoteComplete = walk.complete,
            workstationReachable = walk.reachable,
        )
    }

    suspend fun readPayload(entry: VaultEntry): String? {
        payloadReads += 1
        if (entry.residence != VaultResidence.REMOTE_ONLY) {
            return local.payload(entry.id)
        }
        val walk = remote[entry.gameId]
        if (walk == null || !walk.reachable) return null
        val fetched = workstation { api.fetchSnapshot(entry.gameId, entry.id) } ?: run {
            walk.reachable = false
            return null
        }
        if (entry.sha256.isNotEmpty() && fetched.sha256 != entry.sha256) return null
        if (fetched.sha256.isNotEmpty() && PayloadDigest.sha256(fetched.snapshotPayload) != fetched.sha256) {
            return null
        }
        return fetched.snapshotPayload
    }

    private fun phoneEntry(meta: SnapshotMeta, pointerSha: String?, highWaterId: String?): VaultEntry {
        val active = pointerSha != null && meta.contentSha256.isNotEmpty() && meta.contentSha256 == pointerSha
        return VaultEntry(
            id = meta.id,
            gameId = meta.gameId,
            lineageId = meta.lineageId,
            sequence = meta.sequence,
            capturedAtMillis = meta.capturedAtMillis,
            receivedAtMillis = 0L,
            reason = meta.reason,
            origin = meta.origin,
            pinned = meta.pinned,
            userProtected = meta.userProtected,
            sha256 = meta.contentSha256,
            payloadBytes = meta.payloadBytes,
            integrity = if (meta.contentSha256.isBlank()) "hash not recorded" else "hash recorded",
            residence = VaultResidence.LOCAL,
            availability = VaultAvailability.PRESENT,
            active = active,
            highWater = meta.id == highWaterId,
            storedDetail = storedProgressDetail(meta.progressJson),
        )
    }

    private fun remoteEntry(row: SnapshotMetadata, reachable: Boolean): VaultEntry {
        return VaultEntry(
            id = row.snapshotId,
            gameId = row.gameId,
            lineageId = row.lineageId,
            sequence = 0L,
            capturedAtMillis = row.capturedAtMillis,
            receivedAtMillis = row.receivedAtMillis,
            reason = SnapshotReasons.WORKSTATION,
            origin = SnapshotOrigins.WORKSTATION,
            pinned = false,
            userProtected = false,
            sha256 = row.sha256,
            payloadBytes = 0,
            integrity = row.integrity.ifBlank { "remote" },
            residence = VaultResidence.REMOTE_ONLY,
            availability = if (reachable) {
                VaultAvailability.PRESENT
            } else {
                VaultAvailability.WORKSTATION_UNAVAILABLE
            },
            active = false,
            highWater = false,
            storedDetail = null,
        )
    }

    private suspend fun <T> workstation(block: () -> T): T? {
        return try {
            withContext(Dispatchers.IO) { block() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private class RemoteWalk {
        val rows = mutableListOf<SnapshotMetadata>()
        var afterAt: Long = 0L
        var afterId: String = ""
        var complete: Boolean = false
        var reachable: Boolean = true
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 40
    }
}
