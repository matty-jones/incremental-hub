package com.example.hub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.hub.ui.BootstrapScreen

/**
 * Standalone Activity hosting the native-origin bootstrap spike screen.
 *
 * Separate from [MainActivity] so the spike can be launched independently for
 * device testing without navigating through the main app flow.
 */
class BootstrapActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BootstrapScreen(onRefresh = {})
                }
            }
        }
    }
}
