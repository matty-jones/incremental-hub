package com.example.hub.session

/**
 * Correlated load timeline for blank-screen diagnosis.
 * Entries never include save payloads or storage contents.
 */
data class LoadTraceEvent(
    val elapsedMs: Long,
    val navId: Int,
    val loadToken: Int,
    val phase: String,
    val state: String,
    val category: String,
    val message: String,
)

class LoadTimeline {
    private val startedAt = System.currentTimeMillis()
    private val events = ArrayDeque<LoadTraceEvent>()
    @Volatile var navId: Int = 0
        private set
    @Volatile var frozenReport: String? = null
        private set

    @Synchronized
    fun beginNavigation(reason: String): Int {
        navId += 1
        append(
            loadToken = -1,
            phase = "nav",
            state = reason,
            category = "navigation",
            message = "beginNavigation reason=$reason navId=$navId",
        )
        return navId
    }

    @Synchronized
    fun append(
        loadToken: Int,
        phase: String,
        state: String,
        category: String,
        message: String,
    ) {
        if (events.size >= LIMIT) events.removeFirst()
        events.addLast(
            LoadTraceEvent(
                elapsedMs = System.currentTimeMillis() - startedAt,
                navId = navId,
                loadToken = loadToken,
                phase = phase,
                state = state,
                category = category,
                message = message.take(MESSAGE_LIMIT),
            ),
        )
    }

    @Synchronized
    fun snapshot(): List<LoadTraceEvent> = events.toList()

    @Synchronized
    fun freeze(report: String) {
        if (frozenReport == null) frozenReport = report
    }

    @Synchronized
    fun clearFrozen() {
        frozenReport = null
    }

    @Synchronized
    fun reset() {
        events.clear()
        frozenReport = null
        navId = 0
    }

    companion object {
        private const val LIMIT = 200
        private const val MESSAGE_LIMIT = 500
    }
}
