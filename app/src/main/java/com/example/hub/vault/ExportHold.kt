package com.example.hub.vault

/**
 * Snapshot ids selected for an in-progress export. Retention must not delete
 * them until the export finishes or is refused. The hold is process-local:
 * a cancelled export releases it, and a missing row is a refusal rather than
 * a silent omission.
 */
object ExportHold {
    private val held = LinkedHashSet<String>()

    fun hold(ids: Collection<String>) {
        if (ids.isEmpty()) return
        synchronized(held) { held.addAll(ids) }
    }

    fun release(ids: Collection<String>) {
        if (ids.isEmpty()) return
        synchronized(held) { held.removeAll(ids.toSet()) }
    }

    fun protectedIds(): Set<String> = synchronized(held) { held.toSet() }
}
