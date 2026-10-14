package com.example.hub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.hub.database.HubDatabase
import com.example.hub.games.GameRegistry
import com.example.hub.navigation.HubNavigation

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Create shared dependencies in Activity.onCreate so they survive
        // recomposition and are available for SessionController creation.
        val registry = GameRegistry(applicationContext)
        val database = HubDatabase.getInstance(applicationContext)
        setContent {
            MaterialTheme {
                HubNavigation(context = applicationContext)
            }
        }
    }
}
