package com.example.hub.vault

/**
 * Snapshot ids named by a staged or in-progress vault restore.
 * Counts share [ExportHold]'s mutex so a deletion check and a restore
 * reservation cannot pass each other.
 */
object ActiveRestore {
    private val counts = HashMap<String, Int>()

    suspend fun hold(snapshotId: String) = ExportHold.guard { holdUnlocked(snapshotId) }

    suspend fun release(snapshotId: String) = ExportHold.guard { releaseUnlocked(snapshotId) }

    suspend fun snapshotIds(): Set<String> = ExportHold.guard { snapshotIdsUnlocked() }

    internal fun snapshotIdsUnlocked(): Set<String> = counts.keys.toSet()

    internal fun holdUnlocked(snapshotId: String) {
        counts[snapshotId] = (counts[snapshotId] ?: 0) + 1
    }

    internal fun releaseUnlocked(snapshotId: String) {
        val next = (counts[snapshotId] ?: 0) - 1
        if (next <= 0) counts.remove(snapshotId) else counts[snapshotId] = next
    }

    internal fun isHeldUnlocked(snapshotId: String): Boolean = (counts[snapshotId] ?: 0) > 0
}
