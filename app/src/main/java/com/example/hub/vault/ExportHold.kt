package com.example.hub.vault

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Snapshot ids selected for an in-progress export. Retention and manual
 * deletion must not remove them until every export that selected them has
 * finished or been refused. Overlapping exports each hold a reference.
 * [beginSelection] covers the interval before [hold] is counted.
 *
 * Callers take [guard] before a Room write that removes a snapshot and do
 * not take it again from inside that write. Export reads the payload only
 * after releasing [guard], so a reservation and a delete cannot pass each
 * other, and neither side waits on the database while holding the mutex
 * the other side needs.
 */
object ExportHold {
    private val mutex = Mutex()
    private val counts = HashMap<String, Int>()
    private val selecting = HashMap<String, Int>()

    suspend fun <T> guard(block: suspend () -> T): T = mutex.withLock { block() }

    suspend fun beginSelection(id: String) = guard { beginSelectionUnlocked(id) }

    suspend fun endSelection(id: String) = guard { endSelectionUnlocked(id) }

    suspend fun hold(ids: Collection<String>) {
        if (ids.isEmpty()) return
        guard { holdUnlocked(ids) }
    }

    suspend fun release(ids: Collection<String>) {
        if (ids.isEmpty()) return
        guard { releaseUnlocked(ids) }
    }

    internal fun beginSelectionUnlocked(id: String) {
        selecting[id] = (selecting[id] ?: 0) + 1
    }

    internal fun endSelectionUnlocked(id: String) {
        val next = (selecting[id] ?: 0) - 1
        if (next <= 0) selecting.remove(id) else selecting[id] = next
    }

    internal fun holdUnlocked(ids: Collection<String>) {
        for (id in ids.distinct()) {
            counts[id] = (counts[id] ?: 0) + 1
        }
    }

    internal fun releaseUnlocked(ids: Collection<String>) {
        for (id in ids.distinct()) {
            val next = (counts[id] ?: 0) - 1
            if (next <= 0) counts.remove(id) else counts[id] = next
        }
    }

    suspend fun protectedIds(): Set<String> = guard { protectedIdsUnlocked() }

    internal fun protectedIdsUnlocked(): Set<String> = (counts.keys + selecting.keys).toSet()

    internal fun isProtectedUnlocked(id: String): Boolean {
        return id in counts || id in selecting
    }
}
