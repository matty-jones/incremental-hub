package com.example.hub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import com.example.hub.database.HubDatabase
import com.example.hub.games.GameRegistry
import com.example.hub.navigation.HubNavigation

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val registry = GameRegistry(applicationContext)
        val database = HubDatabase.getInstance(applicationContext)
        setContent {
            MaterialTheme {
                HubNavigation(
                    context = applicationContext,
                    gameRegistry = registry,
                    database = database,
                )
            }
        }
    }
}
