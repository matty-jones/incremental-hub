package com.example.hub

import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.hub.database.HubDatabase
import com.example.hub.games.GameRegistry
import com.example.hub.navigation.HubNavigation
import com.example.hub.session.SessionRuntime
import com.example.hub.ui.ColdStartSplash
import com.example.hub.ui.ColdStartSplashHost
import com.example.hub.ui.theme.HubTheme
import com.example.hub.ui.theme.enableHubEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val freshLaunch = savedInstanceState == null && ColdStartSplash.arm()
        if (freshLaunch) {
            ColdStartSplash.preload(applicationContext)
        } else {
            Log.i(TAG, "skipping splash intro")
        }
        enableHubEdgeToEdge()
        val registry = GameRegistry(applicationContext)
        val database = HubDatabase.getInstance(applicationContext)
        setContent {
            HubTheme {
                ColdStartSplashHost(armed = freshLaunch) {
                    HubNavigation(
                        context = applicationContext,
                        gameRegistry = registry,
                        database = database,
                    )
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.i(TAG, "Configuration changed; resizing the existing WebView")
        SessionRuntime.controller?.noteConfigurationChange()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onRetainCustomNonConfigurationInstance(): Any? {
        return SessionRuntime.controller?.retainForConfigurationChange()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
