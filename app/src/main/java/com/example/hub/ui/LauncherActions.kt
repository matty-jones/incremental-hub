package com.example.hub.ui

/**
 * One primary action opens a game. The label records whether this profile
 * has been played before. Inspect, safe launch, and snapshot recovery stay
 * off that path unless debug tools are enabled.
 */
fun hasPlayHistory(lastPlayedAtMillis: Long?, snapshotPresent: Boolean): Boolean =
    lastPlayedAtMillis != null || snapshotPresent

fun primaryPlayLabel(hasHistory: Boolean): String = if (hasHistory) "Resume" else "Play"

/** Launcher inspect, safe launch, and snapshot recovery follow the debug toggle for every game. */
fun diagnosticLaunchActionsVisible(debugToolsEnabled: Boolean): Boolean = debugToolsEnabled
