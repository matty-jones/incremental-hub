package com.example.hub.games

/**
 * Read-only result of looking at a game's persisted save key.
 * [Failed] means the page could not be inspected. [Empty] means the key is absent.
 */
sealed class PeekOutcome {
    data class Value(val payload: String) : PeekOutcome()
    data object Empty : PeekOutcome()
    data object Failed : PeekOutcome()
}

enum class SaveStanding {
    ABSENT,
    INVALID,
    FRESH,
    PROGRESSED,
}

/**
 * Progress metadata for diagnostics. This is not the save payload.
 */
data class SaveSummary(
    val standing: SaveStanding,
    val version: Int?,
    val detail: String,
    val byteLength: Int,
)

enum class SaveComparison {
    MATCH,
    LIVE_AHEAD,
    LIVE_BEHIND,
    LIVE_MISSING,
    INCONCLUSIVE,
    NO_SNAPSHOT,
}

fun shouldResumeLive(comparison: SaveComparison): Boolean = when (comparison) {
    SaveComparison.MATCH, SaveComparison.LIVE_AHEAD, SaveComparison.NO_SNAPSHOT -> true
    SaveComparison.LIVE_BEHIND, SaveComparison.LIVE_MISSING, SaveComparison.INCONCLUSIVE -> false
}
