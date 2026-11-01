package com.example.hub.vault

/**
 * Snapshot ids selected for an in-progress export. Retention must not delete
 * them until every export that selected them has finished or been refused.
 * Overlapping exports each hold a reference. One finishing does not drop a
 * row another export still needs.
 */
object ExportHold {
    private val counts = HashMap<String, Int>()

    fun hold(ids: Collection<String>) {
        if (ids.isEmpty()) return
        synchronized(counts) {
            for (id in ids.distinct()) {
                counts[id] = (counts[id] ?: 0) + 1
            }
        }
    }

    fun release(ids: Collection<String>) {
        if (ids.isEmpty()) return
        synchronized(counts) {
            for (id in ids.distinct()) {
                val next = (counts[id] ?: 0) - 1
                if (next <= 0) counts.remove(id) else counts[id] = next
            }
        }
    }

    fun protectedIds(): Set<String> = synchronized(counts) { counts.keys.toSet() }
}
