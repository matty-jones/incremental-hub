package com.example.hub.vault

import com.example.hub.database.GameInstanceDao
import com.example.hub.database.SnapshotDao
import com.example.hub.database.SnapshotMeta
import com.example.hub.games.PayloadDigest

class RoomVaultIndex(
    private val snapshots: SnapshotDao,
    private val instances: GameInstanceDao,
) : VaultLocalIndex {
    override suspend fun count(gameId: String): Int = snapshots.countForGame(gameId)

    override suspend fun page(gameId: String, limit: Int, offset: Int): List<SnapshotMeta> {
        return snapshots.pageMeta(gameId, limit, offset)
    }

    override suspend fun existingIds(ids: List<String>): Set<String> {
        if (ids.isEmpty()) return emptySet()
        val found = mutableSetOf<String>()
        ids.chunked(400).forEach { chunk ->
            found += snapshots.existingIds(chunk)
        }
        return found
    }

    override suspend fun payload(id: String): String? = snapshots.payloadById(id)

    override suspend fun pointerSha(gameId: String): String? {
        val payload = instances.get(gameId)?.snapshotPayload?.takeIf { it.isNotEmpty() } ?: return null
        return PayloadDigest.sha256(payload)
    }

    override suspend fun highWaterId(gameId: String): String? = instances.get(gameId)?.highWaterSnapshotId
}
