package com.example.hub.ui

import android.content.Context
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil

/**
 * Learned block heights, outside the game save and outside backup.
 * A value for the same viewport, type size, and element structure only grows.
 */
class LayoutReservationStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()

    fun read(gameId: String): String {
        if (gameId.isBlank()) return "{}"
        synchronized(lock) {
            return configs(document(gameId)).toString()
        }
    }

    fun merge(gameId: String, payload: String): Boolean {
        if (gameId.isBlank() || payload.length > MAX_PAYLOAD) return false
        val incoming = runCatching { JSONObject(payload) }.getOrNull() ?: return false
        val config = incoming.optString("config")
        if (!CONFIG.matches(config)) return false
        val entries = incoming.optJSONObject("entries") ?: return false
        synchronized(lock) {
            val root = document(gameId)
            val stored = configs(root)
            val order = order(root)
            val created = !stored.has(config)
            val bucket = stored.optJSONObject(config) ?: JSONObject().also { stored.put(config, it) }
            val keys = entries.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val px = ceil(entries.optDouble(key, Double.NaN))
                if (key.length > MAX_KEY || !key.contains('\n') || key.substringBefore('\n').isBlank()) continue
                if (px.isNaN() || px <= 0.0 || px > MAX_PX) continue
                val next = px.toInt()
                if (bucket.has(key)) {
                    if (next > bucket.optInt(key)) bucket.put(key, next)
                    continue
                }
                if (identityCount(stored) >= MAX_IDENTITIES) continue
                bucket.put(key, next)
            }
            if (bucket.length() == 0) {
                if (created) stored.remove(config)
                return true
            }
            touch(order, config)
            while (order.length() > MAX_CONFIGS) {
                val oldest = (0 until order.length()).firstOrNull { order.optString(it) != config } ?: break
                stored.remove(order.optString(oldest))
                order.remove(oldest)
            }
            prefs.edit().putString(gameId, root.toString()).apply()
        }
        return true
    }

    private fun document(gameId: String): JSONObject {
        val raw = prefs.getString(gameId, null) ?: return JSONObject()
        return runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
    }

    private fun configs(root: JSONObject): JSONObject =
        root.optJSONObject("configs") ?: JSONObject().also { root.put("configs", it) }

    private fun order(root: JSONObject): JSONArray =
        root.optJSONArray("order") ?: JSONArray().also { root.put("order", it) }

    private fun touch(order: JSONArray, config: String) {
        val kept = JSONArray()
        for (index in 0 until order.length()) {
            val name = order.optString(index)
            if (name.isNotEmpty() && name != config) kept.put(name)
        }
        kept.put(config)
        while (order.length() > 0) order.remove(0)
        for (index in 0 until kept.length()) order.put(kept.optString(index))
    }

    private fun identityCount(configs: JSONObject): Int {
        var count = 0
        val names = configs.keys()
        while (names.hasNext()) count += configs.optJSONObject(names.next())?.length() ?: 0
        return count
    }

    companion object {
        const val PREFS = "hub_layout_reservations"
        const val MAX_CONFIGS = 8
        const val MAX_IDENTITIES = 200
        const val MAX_PX = 320.0
        private const val MAX_PAYLOAD = 100_000
        private const val MAX_KEY = 500
        private val CONFIG = Regex("""^[0-9]{1,5}x[0-9]{1,3}x[0-9]+(?:\.[0-9]+)?$""")
    }
}

/** Accepts one reservation merge from the document. It cannot read or write a save. */
class LayoutReservationBridge(private val store: LayoutReservationStore) {
    @Volatile
    var gameId: String = ""

    @JavascriptInterface
    fun remember(payload: String) {
        store.merge(gameId, payload)
    }
}
