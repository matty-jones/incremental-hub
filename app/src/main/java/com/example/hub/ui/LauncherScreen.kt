package com.example.hub.ui

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.hub.R
import com.example.hub.database.GameInstanceDao
import com.example.hub.games.GameRegistry
import com.example.hub.games.RegisteredGame
import com.example.hub.ui.chrome.AccentCard
import com.example.hub.ui.chrome.HubScaffold
import com.example.hub.ui.chrome.HubTopBar
import com.example.hub.ui.chrome.StatusChip
import com.example.hub.ui.chrome.hubAccentColor
import com.example.hub.ui.chrome.onAccentColor

private const val TAG = "LauncherScreen"

/**
 * Launcher of registered games. Recently played games come first.
 * Color hints come from each game's display defaults.
 */
@Composable
fun LauncherScreen(
    registry: GameRegistry,
    instanceDao: GameInstanceDao,
    onLaunch: (String) -> Unit,
    onResume: (String) -> Unit,
) {
    val lastPlayedByGameId by produceState<Map<String, Long>>(initialValue = emptyMap(), instanceDao) {
        instanceDao.getAllOrdered().collect { instances ->
            value = instances.associate { it.gameId to it.lastPlayedAtMillis }
        }
    }

    val games by produceState<List<RegisteredGame>>(initialValue = emptyList(), lastPlayedByGameId, registry) {
        val ordered = registry.getGames(lastPlayedByGameId)
        Log.d(TAG, "LauncherScreen: rendering ${ordered.size} game(s) in order: ${ordered.map { it.gameId }}")
        value = ordered
    }

    HubScaffold(
        topBar = { HubTopBar(title = stringResource(R.string.app_name)) },
    ) { padding ->
        if (games.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No games registered",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp,
                    vertical = 12.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(
                    items = games,
                    key = { it.gameId },
                ) { game ->
                    GameCard(
                        game = game,
                        onLaunch = onLaunch,
                        onResume = onResume,
                    )
                }
            }
        }
    }
}

@Composable
private fun GameCard(
    game: RegisteredGame,
    onLaunch: (String) -> Unit,
    onResume: (String) -> Unit,
) {
    val hasSession = game.lastPlayedAtMillis != null
    val statusText = if (hasSession) "Resume" else "Launch"
    val accentColor = hubAccentColor(game.displayDefaults.colorPrimary)
        ?: MaterialTheme.colorScheme.primary
    val onAccent = onAccentColor(accentColor)

    AccentCard(accentColor = accentColor) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (game.displayDefaults.badgeText != null) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            color = accentColor.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(8.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = game.displayDefaults.badgeText!!,
                        style = MaterialTheme.typography.labelLarge,
                        color = accentColor,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
            }

            Text(
                text = game.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.width(8.dp))

            StatusChip(
                text = statusText,
                containerColor = if (hasSession) {
                    accentColor.copy(alpha = 0.16f)
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                },
                contentColor = if (hasSession) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = game.launchUrl,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (hasSession) {
                Button(
                    modifier = Modifier
                        .weight(1f)
                        .testTag("resume-${game.gameId}"),
                    onClick = { onResume(game.gameId) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accentColor,
                        contentColor = onAccent,
                    ),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text("Resume", fontWeight = FontWeight.Medium)
                }
                TextButton(
                    modifier = Modifier
                        .weight(1f)
                        .testTag("launch-${game.gameId}"),
                    onClick = { onLaunch(game.gameId) },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Text("Launch")
                }
            } else {
                Button(
                    modifier = Modifier
                        .weight(1f)
                        .testTag("launch-${game.gameId}"),
                    onClick = { onLaunch(game.gameId) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accentColor,
                        contentColor = onAccent,
                    ),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text("Launch", fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}
