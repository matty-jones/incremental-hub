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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hub.games.DisplayDefaults
import com.example.hub.games.GameRegistry
import com.example.hub.games.RegisteredGame

private const val TAG = "LauncherScreen"

/**
 * Material 3 launcher screen showing registered games as native cards.
 *
 * Games are ordered with recently-played-first (by [RegisteredGame.lastPlayedAtMillis]
 * DESC), then alphabetical by [RegisteredGame.gameId]. Each card shows the game's
 * [RegisteredGame.displayName], a status label ("Resume" when [lastPlayedAtMillis]
 * is non-null, "Launch" otherwise), and action buttons.
 *
 * Color hints are pulled from [RegisteredGame.displayDefaults]. The composable reads
 * the DAO via [remember] + [produceState] — no ViewModel per the S01 constraint.
 *
 * @param onLaunch callback fired when the user taps "Launch" (no prior session).
 * @param onResume callback fired when the user taps "Resume" (prior session exists).
 */
@Composable
fun LauncherScreen(
    registry: GameRegistry,
    instanceDao: com.example.hub.database.GameInstanceDao,
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

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
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

/**
 * A single game card rendered with color hints from [RegisteredGame.displayDefaults].
 *
 * Shows [RegisteredGame.displayName], a status label ("Resume" / "Launch"),
 * and action buttons. When [DisplayDefaults.colorPrimary] is non-null, the card
 * uses it as an accent stripe.
 */
@Composable
private fun GameCard(
    game: RegisteredGame,
    onLaunch: (String) -> Unit,
    onResume: (String) -> Unit,
) {
    val hasSession = game.lastPlayedAtMillis != null
    val statusText = if (hasSession) "Resume" else "Launch"
    val accentColor = parseColorOrNull(game.displayDefaults.colorPrimary)
        ?: MaterialTheme.colorScheme.primary

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = accentColor.copy(alpha = 0.06f),
        ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        // Accent stripe at top
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(accentColor),
        )

        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
        ) {
            // Header row: badge + displayName + status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Badge (if configured)
                if (game.displayDefaults.badgeText != null) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(
                                color = accentColor.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(8.dp),
                            )
                            .padding(4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = game.displayDefaults.badgeText!!,
                            style = MaterialTheme.typography.labelLarge.copy(
                                color = accentColor,
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                }

                // Display name (primary)
                Text(
                    text = game.displayName,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(modifier = Modifier.width(8.dp))

                // Status chip
                Surface(
                    color = if (hasSession) accentColor.copy(alpha = 0.12f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = if (hasSession) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium,
                        ),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Subtle URL hint
            Text(
                text = game.launchUrl,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (hasSession) {
                    TextButton(
                        modifier = Modifier
                            .weight(1f)
                            .testTag("resume-${game.gameId}"),
                        onClick = { onResume(game.gameId) },
                        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                            contentColor = accentColor,
                        ),
                    ) {
                        Text("Resume", fontWeight = FontWeight.Medium)
                    }
                }
                TextButton(
                    modifier = Modifier
                        .weight(if (hasSession) 1f else 2f)
                        .testTag("launch-${game.gameId}"),
                    onClick = { onLaunch(game.gameId) },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = if (hasSession) MaterialTheme.colorScheme.onSurfaceVariant else accentColor,
                    ),
                ) {
                    Text("Launch", fontWeight = if (hasSession) FontWeight.Normal else FontWeight.Medium)
                }
                if (hasSession) {
                    Spacer(modifier = Modifier.width(8.dp))
                }
            }
        }
    }
}

/**
 * Parse a hex color string (e.g. `#1565C0`) to a [Color].
 * Returns null when the input is null or malformed.
 */
private fun parseColorOrNull(hex: String?): Color? {
    if (hex == null) return null
    return runCatching {
        val cleaned = hex.removePrefix("#")
        val int = cleaned.toLong(16)
        when {
            cleaned.length <= 6 -> Color(int + 0xFF000000L)
            else -> Color(int)
        }
    }.getOrNull()
}
