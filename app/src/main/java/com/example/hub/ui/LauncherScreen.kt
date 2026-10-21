package com.example.hub.ui

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.hub.R
import com.example.hub.database.GameInstanceDao
import com.example.hub.games.GameRegistry
import com.example.hub.games.RegisteredGame
import com.example.hub.ui.chrome.HubCard
import com.example.hub.ui.chrome.HubChipKind
import com.example.hub.ui.chrome.HubPrimaryButton
import com.example.hub.ui.chrome.HubScaffold
import com.example.hub.ui.chrome.HubTextButton
import com.example.hub.ui.chrome.HubTopBar
import com.example.hub.ui.chrome.StatusChip
import com.example.hub.ui.theme.HubGameAccent

private const val TAG = "LauncherScreen"

/**
 * Launcher of registered games. Recently played games come first.
 * Game identity uses [HubGameAccent], not the definition's colorPrimary.
 */
@Composable
fun LauncherScreen(
    registry: GameRegistry,
    instanceDao: GameInstanceDao,
    syncSettled: Boolean,
    onPlay: (String) -> Unit,
    debugTools: Boolean = false,
    onOpenSettings: () -> Unit = {},
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
        topBar = {
            HubTopBar(
                title = stringResource(R.string.app_name),
                branded = true,
                actions = {
                    if (debugTools) {
                        StatusChip(text = "Debug", kind = HubChipKind.Warning)
                    }
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier
                            .testTag("open-settings")
                            .semantics { contentDescription = "Settings" },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Settings",
                        )
                    }
                },
            )
        },
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                            debugTools = debugTools,
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
    debugTools: Boolean,
    onPlay: (String) -> Unit,
    onInspect: (String) -> Unit,
    onSafeLaunch: (String) -> Unit,
    onRecover: (String) -> Unit,
) {
    val hasSession = hasPlayHistory(game.lastPlayedAtMillis, hasSnapshot)
    val statusText = primaryPlayLabel(hasSession)
    val accentColor = HubGameAccent.forGame(game.gameId)

    HubCard(identityAccent = accentColor) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GameMark(
                badgeText = game.displayDefaults.badgeText,
                launchUrl = game.launchUrl,
                accentColor = accentColor,
            )

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
                kind = if (hasSession) HubChipKind.Ready else HubChipKind.New,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = game.launchUrl,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HubPrimaryButton(
                modifier = Modifier
                    .weight(1f)
                    .testTag("play-${game.gameId}"),
                enabled = !actionsHeld,
                onClick = { onPlay(game.gameId) },
            ) {
                Text(primaryPlayLabel(hasSession))
            }
        }
        if (diagnosticLaunchActionsVisible(debugTools)) {
            Spacer(modifier = Modifier.height(4.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                HubTextButton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("inspect-${game.gameId}"),
                    onClick = { onInspect(game.gameId) },
                ) {
                    Text("Inspect Save")
                }
                HubTextButton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("safe-${game.gameId}"),
                    onClick = { onSafeLaunch(game.gameId) },
                ) {
                    Text("Safe Launch")
                }
                HubTextButton(
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

@Composable
private fun GameMark(
    badgeText: String?,
    launchUrl: String,
    accentColor: Color,
) {
    val icon by produceState<Bitmap?>(initialValue = null, launchUrl) {
        value = SiteIconLoader.load(launchUrl)
    }
    if (icon == null && badgeText == null) return
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(accentColor.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        val loaded = icon
        if (loaded != null) {
            Image(
                bitmap = loaded.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(3.dp),
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.Medium,
            )
        } else if (badgeText != null) {
            Text(
                text = badgeText,
                style = MaterialTheme.typography.labelLarge,
                color = accentColor,
                fontWeight = FontWeight.Bold,
            )
        }
    }
    Spacer(modifier = Modifier.width(12.dp))
}
