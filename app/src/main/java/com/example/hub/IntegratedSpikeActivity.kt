package com.example.hub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.hub.ui.IntegratedSpikeScreen
import com.example.hub.ui.theme.HubTheme
import com.example.hub.ui.theme.enableHubEdgeToEdge

/**
 * Standalone Activity hosting the integrated play-capture-restore spike screen.
 *
 * Separate from [MainActivity] so the integrated spike can be launched
 * independently for device testing without navigating through the main app flow.
 */
class IntegratedSpikeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableHubEdgeToEdge()
        setContent {
            HubTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    IntegratedSpikeScreen(onRefresh = {})
                }
            }
        }
    }
}
