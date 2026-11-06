package com.example.hub.games

/**
 * What Hub has verified about a game's own offline progression.
 * The default is [UNSUPPORTED]. Hub does not invent a timer or a reward.
 */
enum class OfflineCapability {
    /** No qualified calculation. Hub keeps the live session and says so. */
    UNSUPPORTED,

    /** The game calculates offline progress when its load path runs. */
    NATIVE_ON_LOAD,

    /** The game exposes the same calculation for a running page. */
    NATIVE_ON_RESUME,

    /** The running loop already applies elapsed time safely. */
    ELAPSED_TIME,
}

data class OfflineSupport(
    val capability: OfflineCapability,
    val summary: String,
) {
    companion object {
        fun unsupported(summary: String = UNSUPPORTED_SUMMARY) = OfflineSupport(
            OfflineCapability.UNSUPPORTED,
            summary,
        )

        const val UNSUPPORTED_SUMMARY =
            "This game has no verified offline calculation in Hub."
    }
}

/** Result of reading the running game without calling its save routine. */
data class OfflineInspection(
    val supported: Boolean,
    val latestTimeMillis: Long?,
    val gameNowMillis: Long?,
    val offlineEnabled: Boolean,
    val simulationActive: Boolean,
    val blocksSave: Boolean,
    val payloadSha256: String?,
) {
    val gapSeconds: Double?
        get() {
            val latest = latestTimeMillis ?: return null
            val now = gameNowMillis ?: return null
            return (now - latest) / 1000.0
        }

    companion object {
        fun unsupported() = OfflineInspection(
            supported = false,
            latestTimeMillis = null,
            gameNowMillis = null,
            offlineEnabled = false,
            simulationActive = false,
            blocksSave = false,
            payloadSha256 = null,
        )
    }
}

enum class OfflineStart {
    STARTED,
    BUSY,
    DISABLED,
    SHORT,
    UNSUPPORTED,
    FAILED,
}

enum class OfflineSettlement {
    COMPLETE,
    RUNNING,
    INTERRUPTED,
    UNSUPPORTED,
}
