package com.example.hub.games

import org.json.JSONArray
import org.json.JSONObject

/**
 * Unwrap a value returned by [android.webkit.WebView.evaluateJavascript].
 * The platform JSON-encodes the result, so string payloads arrive quoted.
 */
internal fun unwrapJsResult(raw: String?): String? {
    if (raw.isNullOrBlank() || raw == "null") return null
    return try {
        val value = JSONArray("[$raw]").opt(0)
        when (value) {
            null, JSONObject.NULL -> null
            is String -> value.takeIf { it.isNotEmpty() }
            else -> value.toString().takeIf { it.isNotEmpty() && it != "null" }
        }
    } catch (e: Exception) {
        raw.removeSurrounding("\"").takeIf { it.isNotEmpty() && it != "null" }
    }
}
