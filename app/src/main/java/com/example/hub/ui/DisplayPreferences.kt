package com.example.hub.ui

import android.content.Context
import org.json.JSONObject

data class ZoomPreference(
    val textZoomPercent: Int = DEFAULT_ZOOM,
    val pageZoomPercent: Int = DEFAULT_ZOOM,
) {
    fun clamped(): ZoomPreference = ZoomPreference(
        textZoomPercent = clampZoom(textZoomPercent),
        pageZoomPercent = clampZoom(pageZoomPercent),
    )
}

data class GameDisplayPreferences(
    val folded: ZoomPreference = ZoomPreference(),
    val unfolded: ZoomPreference = ZoomPreference(),
) {
    fun forPosture(foldedPosture: Boolean): ZoomPreference =
        if (foldedPosture) folded else unfolded

    fun withPosture(foldedPosture: Boolean, preference: ZoomPreference): GameDisplayPreferences {
        val clamped = preference.clamped()
        return if (foldedPosture) copy(folded = clamped) else copy(unfolded = clamped)
    }
}

const val MIN_ZOOM = 75
const val MAX_ZOOM = 125
const val DEFAULT_ZOOM = 100
const val ZOOM_STEP = 5

fun clampZoom(percent: Int): Int = percent.coerceIn(MIN_ZOOM, MAX_ZOOM)

fun stepZoom(percent: Int, delta: Int): Int = clampZoom(percent + delta)

/** Cover-screen widths sit under the hub's wide-window threshold. */
fun isFoldedWidth(widthDp: Float): Boolean = widthDp < 600f

fun pageZoomScript(percent: Int): String {
    val zoom = clampZoom(percent) / 100f
    val literal = String.format(java.util.Locale.US, "%.2f", zoom)
    return "(function(){ var z = '$literal'; var root = document.documentElement;" +
        " if (!root) return ''; if (root.style.zoom !== z) root.style.zoom = z; return root.style.zoom; })()"
}

/**
 * Per-game zoom for the folded and unfolded postures.
 * Stored outside the save snapshot so display changes cannot rewrite a game.
 */
class DisplayPreferenceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun read(gameId: String): GameDisplayPreferences {
        val raw = prefs.getString(gameId, null) ?: return GameDisplayPreferences()
        return runCatching { decode(raw) }.getOrDefault(GameDisplayPreferences())
    }

    fun write(gameId: String, value: GameDisplayPreferences) {
        prefs.edit().putString(gameId, encode(value)).apply()
    }

    private fun encode(value: GameDisplayPreferences): String {
        return JSONObject()
            .put("folded", encodeZoom(value.folded))
            .put("unfolded", encodeZoom(value.unfolded))
            .toString()
    }

    private fun encodeZoom(zoom: ZoomPreference): JSONObject {
        val clamped = zoom.clamped()
        return JSONObject()
            .put("text", clamped.textZoomPercent)
            .put("page", clamped.pageZoomPercent)
    }

    private fun decode(raw: String): GameDisplayPreferences {
        val json = JSONObject(raw)
        return GameDisplayPreferences(
            folded = decodeZoom(json.optJSONObject("folded")),
            unfolded = decodeZoom(json.optJSONObject("unfolded")),
        )
    }

    private fun decodeZoom(json: JSONObject?): ZoomPreference {
        if (json == null) return ZoomPreference()
        return ZoomPreference(
            textZoomPercent = json.optInt("text", DEFAULT_ZOOM),
            pageZoomPercent = json.optInt("page", DEFAULT_ZOOM),
        ).clamped()
    }

    companion object {
        private const val PREFS = "hub_display_preferences"
    }
}
