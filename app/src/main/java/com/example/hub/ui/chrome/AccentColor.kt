package com.example.hub.ui.chrome

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Parse a `#RRGGBB` or `#AARRGGBB` accent from a game definition.
 * Returns null when the value is missing or not hex.
 */
fun hubAccentColor(hex: String?): Color? {
    if (hex.isNullOrBlank()) return null
    val cleaned = hex.trim().removePrefix("#")
    if (cleaned.length != 6 && cleaned.length != 8) return null
    if (cleaned.any { it.digitToIntOrNull(16) == null }) return null
    val raw = cleaned.toLong(16)
    val argb = if (cleaned.length == 6) raw or 0xFF000000L else raw
    return Color(argb.toInt())
}

fun onAccentColor(accent: Color): Color =
    if (accent.luminance() > 0.5f) Color(0xFF1A1408) else Color.White
