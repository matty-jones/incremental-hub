package com.example.hub.games

/**
 * Resolves the production [GameAdapter] for a registered [adapterId].
 */
object GameAdapters {
    fun create(adapterId: String): GameAdapter? = when (adapterId) {
        "shark-incremental-adapter" -> SharkIncrementalAdapter()
        "fe000000-adapter" -> Fe000000Adapter()
        else -> null
    }
}
