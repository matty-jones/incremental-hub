package com.example.hub.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class HubSettingsTest {

    @Test
    fun debugToolsStayOffUntilEnabled() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = HubSettingsStore(context)
        assertFalse(store.debugToolsEnabled())
        assertFalse(diagnosticLaunchActionsVisible(debugToolsEnabled = false))

        store.setDebugToolsEnabled(true)
        assertTrue(HubSettingsStore(context).debugToolsEnabled())
        assertTrue(diagnosticLaunchActionsVisible(debugToolsEnabled = true))

        store.setDebugToolsEnabled(false)
        assertFalse(store.debugToolsEnabled())
    }
}
