package com.example.hub.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.hub.ui.theme.HubTheme
import com.example.hub.ui.theme.enableHubEdgeToEdge

/**
 * Standalone Activity hosting the candidate-game qualification screen.
 *
 * Separate from [com.example.hub.MainActivity] so the qualification spike
 * can be launched independently for device testing without navigating
 * through the main app flow.
 */
class GameQualificationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableHubEdgeToEdge()
        setContent {
            HubTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    GameQualificationScreen(onRefresh = {})
                }
            }
        }
    }
}
