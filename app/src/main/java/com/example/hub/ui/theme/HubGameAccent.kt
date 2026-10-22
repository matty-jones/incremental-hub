package com.example.hub.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Cool identity colour for a registered game.
 *
 * Explicit ids are fixed. Anything else uses the Java string hash of [gameId]
 * (`31 * hash + code`, then [Math.floorMod]) so the result does not depend on
 * list order, session, or `displayDefaults.colorPrimary`. Gold is never returned.
 */
object HubGameAccent {
    fun forGame(gameId: String): Color = when (gameId) {
        "fe000000" -> HubColors.Cyan
        "shark-incremental" -> HubColors.Azure
        "trimps" -> HubColors.Teal
        else -> HubColors.GameAccents[stableIndex(gameId)]
    }

    internal fun stableIndex(gameId: String): Int {
        var hash = 0
        for (char in gameId) {
            hash = 31 * hash + char.code
        }
        return Math.floorMod(hash, HubColors.GameAccents.size)
    }
}
