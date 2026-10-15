package com.example.hub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.hub.database.HubDatabase
import com.example.hub.games.GameRegistry
import com.example.hub.navigation.HubNavigation
import com.example.hub.ui.theme.HubTheme
import com.example.hub.ui.theme.enableHubEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableHubEdgeToEdge()
        val registry = GameRegistry(applicationContext)
        val database = HubDatabase.getInstance(applicationContext)
        setContent {
            HubTheme {
                HubNavigation(
                    context = applicationContext,
                    gameRegistry = registry,
                    database = database,
                )
            }
        }
    }
}
