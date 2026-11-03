package com.example.hub.vault

/**
 * Snapshot ids named by a staged or in-progress vault restore.
 * Counts share [ExportHold]'s lock so a deletion check and a restore
 * reservation cannot pass each other.
 */
object ActiveRestore {
    private val counts = HashMap<String, Int>()

    fun hold(snapshotId: String) = ExportHold.guard { holdUnlocked(snapshotId) }

    fun release(snapshotId: String) = ExportHold.guard { releaseUnlocked(snapshotId) }

    fun snapshotIds(): Set<String> = ExportHold.guard { counts.keys.toSet() }

    internal fun holdUnlocked(snapshotId: String) {
        counts[snapshotId] = (counts[snapshotId] ?: 0) + 1
    }

    internal fun releaseUnlocked(snapshotId: String) {
        val next = (counts[snapshotId] ?: 0) - 1
        if (next <= 0) counts.remove(snapshotId) else counts[snapshotId] = next
    }

    internal fun isHeldUnlocked(snapshotId: String): Boolean = (counts[snapshotId] ?: 0) > 0
}
