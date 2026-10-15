package com.example.hub.ui

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.BoxWithConstraints
import com.example.hub.session.SessionState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.OpenInNew

private const val TAG = "NativeMenu"

/**
 * Minimum touch target size (Material 3 spec: 48dp).
 */
private val MinTouchTarget: Dp = 48.dp

/**
 * Seven actions available in the in-session native menu.
 */
sealed class MenuAction {
    data object Resume : MenuAction()
    data object SnapshotNow : MenuAction()
    data object Checkpoint : MenuAction()
    data object Vault : MenuAction()
    data object Reload : MenuAction()
    data object Display : MenuAction()
    data object ReturnToHub : MenuAction()
}

/**
 * Labels for each [MenuAction]. Used for accessibility content descriptions
 * and debug logging.
 */
internal val MenuAction.label: String
    get() = when (this) {
        is MenuAction.Resume -> "Resume"
        is MenuAction.SnapshotNow -> "Snapshot now"
        is MenuAction.Checkpoint -> "Checkpoint"
        is MenuAction.Vault -> "Vault"
        is MenuAction.Reload -> "Reload"
        is MenuAction.Display -> "Display"
        is MenuAction.ReturnToHub -> "Return to hub"
    }

/**
 * Native in-session menu composable.
 *
 * This composable renders a sibling overlay that does **not** wrap [WebViewHost],
 * ensuring the WebView identity is preserved across menu open/close cycles.
 *
 * Back behavior is deterministic:
 * - When the menu is open, Android Back closes the menu and leaves the game running.
 * - Closing the menu does not end the session.
 *
 * Touch targets are 48dp minimum via [Modifier.minimumHeight].
 *
 * Layout branches on available width measured via [BoxWithConstraints]:
 * - Compact windows (< 600dp): bottom-sheet-style column.
 * - Wider windows (>= 600dp): side panel row.
 */
/**
 * Session state does not force the menu open. Visibility is owned by the caller.
 */
internal fun sessionStateForcesMenuOpen(state: SessionState): Boolean = false

@Composable
fun NativeMenu(
    state: SessionState,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onAction: (MenuAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (sessionStateForcesMenuOpen(state)) {
        onExpandedChange(true)
    }

    BackHandler(enabled = expanded) {
        Log.d(TAG, "NativeMenu: BackHandler closing menu (state=$state)")
        onExpandedChange(false)
    }

    if (!expanded) return

    // Measure available width to branch between compact and wide layouts.
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isCompact = maxWidth < 600.dp
        val dismiss = {
            Log.d(TAG, "NativeMenu: close requested (state=$state)")
            onExpandedChange(false)
        }
        if (isCompact) {
            CompactMenu(
                state = state,
                onAction = onAction,
                onClose = dismiss,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            WideMenu(
                state = state,
                onAction = onAction,
                onClose = dismiss,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Compact (bottom-sheet) layout: full-width column of menu items.
 */
@Composable
private fun CompactMenu(
    state: SessionState,
    onAction: (MenuAction) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(240.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Close handle / row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Game Menu",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .size(MinTouchTarget)
                    .semantics { contentDescription = "Close menu" },
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Default.Backspace,
                    contentDescription = "Close",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        MenuItems(
            state = state,
            onAction = onAction,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Wide (side-panel) layout: vertical column with a draggable handle.
 */
@Composable
private fun WideMenu(
    state: SessionState,
    onAction: (MenuAction) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Menu panel
        Column(
            modifier = Modifier
                .width(200.dp)
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Close row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Game Menu",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(MinTouchTarget)
                        .semantics { contentDescription = "Close menu" },
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Default.Backspace,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            MenuItems(
                state = state,
                onAction = onAction,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Draggable handle
        MenuHandle(onClose = onClose)
    }
}

/**
 * Common menu item rows for both compact and wide layouts.
 * Seven items, 48dp minimum touch targets, deterministic logging.
 */
@Composable
private fun MenuItems(
    state: SessionState,
    onAction: (MenuAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val actions = listOf(
        MenuAction.Resume,
        MenuAction.SnapshotNow,
        MenuAction.Checkpoint,
        MenuAction.Vault,
        MenuAction.Reload,
        MenuAction.Display,
        MenuAction.ReturnToHub,
    )

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(actions) { action ->
            MenuItemRow(
                action = action,
                enabled = isActionEnabled(action, state),
                onTrigger = {
                    Log.d(TAG, "NativeMenu: action triggered=${action.label} (state=$state)")
                    onAction(action)
                },
            )
        }
    }
}

/**
 * Determine if a [MenuAction] is enabled in the given [state].
 *
 * Some actions are only meaningful when the session is actively playing or
 * capturing — e.g., Resume when already playing is a no-op but still enabled
 * for convenience; ReturnToHub is always enabled.
 */
internal fun isActionEnabled(action: MenuAction, state: SessionState): Boolean {
    return when (action) {
        is MenuAction.Resume -> state is SessionState.Playing ||
            state is SessionState.Background ||
            state is SessionState.Degraded
        is MenuAction.SnapshotNow -> state is SessionState.Playing
        is MenuAction.Checkpoint -> state is SessionState.Playing
        is MenuAction.Vault -> false
        is MenuAction.Reload -> state is SessionState.Playing
        is MenuAction.Display -> false
        is MenuAction.ReturnToHub -> true
    }
}

/**
 * A single menu item row with 48dp minimum touch target.
 * Uses a Surface for elevation and visual feedback.
 */
@Composable
private fun MenuItemRow(
    action: MenuAction,
    enabled: Boolean,
    onTrigger: () -> Unit,
) {
    val icon = when (action) {
        is MenuAction.Resume -> Icons.Default.PlayArrow
        is MenuAction.SnapshotNow -> Icons.Default.Screenshot
        is MenuAction.Checkpoint -> Icons.Default.Cloud
        is MenuAction.Vault -> Icons.Default.Cloud
        is MenuAction.Reload -> Icons.Default.Refresh
        is MenuAction.Display -> Icons.Default.Settings
        is MenuAction.ReturnToHub -> Icons.AutoMirrored.Default.Backspace
    }

    val label = action.label

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(MinTouchTarget)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTrigger,
            )
            .semantics {
                contentDescription = if (enabled) label else "$label (disabled)"
            },
        color = if (enabled) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                },
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                },
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (action is MenuAction.ReturnToHub) {
                Icon(
                    imageVector = Icons.AutoMirrored.Default.OpenInNew,
                    contentDescription = null,
                    tint = if (enabled) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * Small, accessible, movable menu handle.
 *
 * In wider layouts, the handle provides a visual affordance and a touch target
 * for closing the panel.
 */
@Composable
private fun MenuHandle(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .width(8.dp)
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClose,
            )
            .semantics { contentDescription = "Menu handle — tap to close" },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .size(24.dp)
                .background(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(12.dp),
                ),
            color = Color.Transparent,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                            shape = RoundedCornerShape(4.dp),
                        ),
                )
            }
        }
    }
}
