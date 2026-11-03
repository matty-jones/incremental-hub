package com.example.hub.vault

/**
 * Snapshot ids selected for an in-progress export. Retention and manual
 * deletion must not remove them until every export that selected them has
 * finished or been refused. Overlapping exports each hold a reference.
 * [beginSelection] covers the interval before [hold] is counted.
 */
object ExportHold {
    private val lock = Any()
    private val counts = HashMap<String, Int>()
    private val selecting = HashMap<String, Int>()

    fun <T> guard(block: () -> T): T = synchronized(lock) { block() }

    fun beginSelection(id: String) = guard {
        selecting[id] = (selecting[id] ?: 0) + 1
    }

    fun endSelection(id: String) = guard {
        val next = (selecting[id] ?: 0) - 1
        if (next <= 0) selecting.remove(id) else selecting[id] = next
    }

    fun hold(ids: Collection<String>) {
        if (ids.isEmpty()) return
        guard {
            for (id in ids.distinct()) {
                counts[id] = (counts[id] ?: 0) + 1
            }
        }
    }

    fun release(ids: Collection<String>) {
        if (ids.isEmpty()) return
        guard {
            for (id in ids.distinct()) {
                val next = (counts[id] ?: 0) - 1
                if (next <= 0) counts.remove(id) else counts[id] = next
            }
        }
    }

    fun protectedIds(): Set<String> = guard { (counts.keys + selecting.keys).toSet() }

    internal fun isProtectedUnlocked(id: String): Boolean {
        return id in counts || id in selecting
    }
}
