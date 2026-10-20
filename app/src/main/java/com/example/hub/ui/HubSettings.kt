package com.example.hub.ui

import android.content.Context

/**
 * Hub-wide preferences. Per-game display stays in [DisplayPreferenceStore].
 */
class HubSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun debugToolsEnabled(): Boolean = prefs.getBoolean(DEBUG_TOOLS, false)

    fun setDebugToolsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(DEBUG_TOOLS, enabled).apply()
    }

    companion object {
        private const val PREFS = "hub_settings"
        private const val DEBUG_TOOLS = "debug_tools"
    }
}
