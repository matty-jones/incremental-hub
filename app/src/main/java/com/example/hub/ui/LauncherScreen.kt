package com.example.hub.ui

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
    syncSettled: Boolean,
    onPlay: (String) -> Unit,
    onInspect: (String) -> Unit = {},
    onSafeLaunch: (String) -> Unit = {},
    onRecover: (String) -> Unit = {},
) {
    val instances by produceState(initialValue = LauncherInstances(), instanceDao) {
        instanceDao.getAllOrdered().collect { rows ->
            value = LauncherInstances(
                lastPlayed = rows.associate { it.gameId to it.lastPlayedAtMillis },
                snapshotIds = rows.mapNotNull { row ->
                    row.gameId.takeIf { !row.snapshotPayload.isNullOrBlank() }
                }.toSet(),
            )
        }
    }
    val lastPlayedByGameId = instances.lastPlayed
    val snapshotIds = instances.snapshotIds

    val games by produceState<List<RegisteredGame>>(initialValue = emptyList(), lastPlayedByGameId, registry) {
        val ordered = registry.getGames(lastPlayedByGameId)
        Log.d(TAG, "LauncherScreen: rendering ${ordered.size} game(s) in order: ${ordered.map { it.gameId }}")
        value = ordered
    }

    HubScaffold(
        topBar = { HubTopBar(title = stringResource(R.string.app_name)) },
    ) { padding ->
        val checkingWorkstation = !syncSettled && games.any { it.gameId !in snapshotIds }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (checkingWorkstation) {
                Text(
                    text = "Checking the workstation for saves",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (games.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize(),
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
                        .weight(1f)
                        .fillMaxSize(),
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
                            actionsHeld = !syncSettled && game.gameId !in snapshotIds,
                            hasSnapshot = game.gameId in snapshotIds,
                            onPlay = onPlay,
                            onInspect = onInspect,
                            onSafeLaunch = onSafeLaunch,
                            onRecover = onRecover,
                        )
                    }
                }
            }
        }
    }
}

private data class LauncherInstances(
    val lastPlayed: Map<String, Long> = emptyMap(),
    val snapshotIds: Set<String> = emptySet(),
)

@Composable
private fun GameCard(
    game: RegisteredGame,
    actionsHeld: Boolean,
    hasSnapshot: Boolean,
    onPlay: (String) -> Unit,
    onInspect: (String) -> Unit,
    onSafeLaunch: (String) -> Unit,
    onRecover: (String) -> Unit,
) {
    val hasSession = hasPlayHistory(game.lastPlayedAtMillis, hasSnapshot)
    val statusText = primaryPlayLabel(hasSession)
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
            Button(
                modifier = Modifier
                    .weight(1f)
                    .testTag("play-${game.gameId}"),
                enabled = !actionsHeld,
                onClick = { onPlay(game.gameId) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = accentColor,
                    contentColor = onAccent,
                ),
                shape = MaterialTheme.shapes.small,
            ) {
                Text(primaryPlayLabel(hasSession), fontWeight = FontWeight.Medium)
            }
        }
        if (game.gameId == "shark-incremental") {
            Spacer(modifier = Modifier.height(4.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("inspect-${game.gameId}"),
                    onClick = { onInspect(game.gameId) },
                ) {
                    Text("Inspect Save")
                }
                TextButton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("safe-${game.gameId}"),
                    onClick = { onSafeLaunch(game.gameId) },
                ) {
                    Text("Safe Launch")
                }
                TextButton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("recover-${game.gameId}"),
                    onClick = { onRecover(game.gameId) },
                ) {
                    Text("Recover from Snapshot")
                }
            }
        }
    }
}
