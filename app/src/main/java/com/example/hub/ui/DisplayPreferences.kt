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

enum class PageScaleMode {
    /** Hub sets documentElement zoom. Shark uses this. */
    HUB,

    /** FE000000's Fit to Width owns body zoom. Hub page zoom is not applied. */
    GAME_FIT,

    /** Reflow FE000000 and hold body zoom at the Hub page percent. */
    READABLE,
    ;

    fun stored(): String = when (this) {
        HUB -> "hub"
        GAME_FIT -> "game-fit"
        READABLE -> "readable"
    }

    companion object {
        fun fromStored(raw: String?): PageScaleMode = when (raw) {
            "game-fit" -> GAME_FIT
            "readable" -> READABLE
            else -> HUB
        }
    }
}

data class GameDisplayPreferences(
    val folded: ZoomPreference = ZoomPreference(),
    val unfolded: ZoomPreference = ZoomPreference(),
    val pageScaleMode: PageScaleMode = PageScaleMode.HUB,
) {
    fun forPosture(foldedPosture: Boolean): ZoomPreference =
        if (foldedPosture) folded else unfolded

    fun withPosture(foldedPosture: Boolean, preference: ZoomPreference): GameDisplayPreferences {
        val clamped = preference.clamped()
        return if (foldedPosture) copy(folded = clamped) else copy(unfolded = clamped)
    }
}

fun defaultPageScaleMode(gameId: String): PageScaleMode =
    if (gameId == FE000000_GAME_ID) PageScaleMode.READABLE else PageScaleMode.HUB

/** FE000000 never uses root zoom. Other games stay on the Hub's page zoom. */
fun pageScaleModeFor(gameId: String, stored: PageScaleMode): PageScaleMode = when {
    gameId == FE000000_GAME_ID && stored == PageScaleMode.GAME_FIT -> PageScaleMode.GAME_FIT
    gameId == FE000000_GAME_ID -> PageScaleMode.READABLE
    else -> PageScaleMode.HUB
}

fun defaultDisplayPreferences(gameId: String): GameDisplayPreferences =
    GameDisplayPreferences(pageScaleMode = defaultPageScaleMode(gameId))

const val FE000000_GAME_ID = "fe000000"

const val MIN_ZOOM = 75
const val MAX_ZOOM = 125
const val DEFAULT_ZOOM = 100
const val ZOOM_STEP = 5

fun clampZoom(percent: Int): Int = percent.coerceIn(MIN_ZOOM, MAX_ZOOM)

fun stepZoom(percent: Int, delta: Int): Int = clampZoom(percent + delta)

/** Cover-screen widths sit under the hub's wide-window threshold. */
fun isFoldedWidth(widthDp: Float): Boolean = widthDp < 600f

fun zoomLiteral(percent: Int): String =
    String.format(java.util.Locale.US, "%.2f", clampZoom(percent) / 100f)

/** Hub page zoom for callers that only need the Shark path. */
fun pageZoomScript(percent: Int): String =
    presentationScript(PageScaleMode.HUB, percent, flush = false)

/**
 * Per-game zoom for the folded and unfolded postures.
 * Stored outside the save snapshot so display changes cannot rewrite a game.
 */
class DisplayPreferenceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun read(gameId: String): GameDisplayPreferences {
        val raw = prefs.getString(gameId, null) ?: return defaultDisplayPreferences(gameId)
        return runCatching { decode(gameId, raw) }.getOrDefault(defaultDisplayPreferences(gameId))
    }

    fun write(gameId: String, value: GameDisplayPreferences) {
        prefs.edit().putString(gameId, encode(value)).apply()
    }

    private fun encode(value: GameDisplayPreferences): String {
        return JSONObject()
            .put("folded", encodeZoom(value.folded))
            .put("unfolded", encodeZoom(value.unfolded))
            .put("mode", value.pageScaleMode.stored())
            .toString()
    }

    private fun encodeZoom(zoom: ZoomPreference): JSONObject {
        val clamped = zoom.clamped()
        return JSONObject()
            .put("text", clamped.textZoomPercent)
            .put("page", clamped.pageZoomPercent)
    }

    private fun decode(gameId: String, raw: String): GameDisplayPreferences {
        val json = JSONObject(raw)
        val stored = if (json.has("mode")) {
            PageScaleMode.fromStored(json.optString("mode"))
        } else {
            defaultPageScaleMode(gameId)
        }
        return GameDisplayPreferences(
            folded = decodeZoom(json.optJSONObject("folded")),
            unfolded = decodeZoom(json.optJSONObject("unfolded")),
            pageScaleMode = pageScaleModeFor(gameId, stored),
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
