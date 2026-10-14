package com.example.hub.ui

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private const val TAG = "PlaySurfaceStub"

/**
 * S01 stub for the play surface.
 *
 * S02 will replace this with a real WebView-based [com.example.hub.ui.PlaySurface].
 * For now, it logs the received [gameId] and displays a placeholder.
 *
 * @param gameId The game identifier supplied by the navigation route.
 */
@Composable
fun PlaySurfaceStub(gameId: String) {
    Log.d(TAG, "PlaySurfaceStub: shown for gameId=$gameId (S02 placeholder)")
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Play Surface — $gameId",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
        }
    }
}
