package com.example.hub.ui

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.example.hub.ui.chrome.HubTextButton
import com.example.hub.ui.theme.HubColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.hub.session.SessionState
import com.example.hub.ui.chrome.OverlaySheet

private const val TAG = "NativeMenu"

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
    data object Diagnostics : MenuAction()
    data object ReturnToHub : MenuAction()
}

internal val MenuAction.label: String
    get() = when (this) {
        is MenuAction.Resume -> "Resume"
        is MenuAction.SnapshotNow -> "Snapshot now"
        is MenuAction.Checkpoint -> "Checkpoint"
        is MenuAction.Vault -> "Vault"
        is MenuAction.Reload -> "Reload"
        is MenuAction.Display -> "Display"
        is MenuAction.Diagnostics -> "Diagnostics"
        is MenuAction.ReturnToHub -> "Return to hub"
    }

/**
 * Session state does not force the menu open. Visibility is owned by the caller.
 */
internal fun sessionStateForcesMenuOpen(state: SessionState): Boolean = false

/**
 * In-session menu drawn in an [OverlaySheet] beside the WebView, not around it.
 * Dismissing the sheet only hides it.
 */
@Composable
fun NativeMenu(
    state: SessionState,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onAction: (MenuAction) -> Unit,
    modifier: Modifier = Modifier,
    showDisplay: Boolean = false,
    showDiagnostics: Boolean = false,
    onPanelBack: () -> Unit = {},
    display: @Composable () -> Unit = {},
    diagnostics: @Composable () -> Unit = {},
) {
    if (sessionStateForcesMenuOpen(state)) {
        onExpandedChange(true)
    }

    BackHandler(enabled = expanded) {
        Log.d(TAG, "NativeMenu: BackHandler closing menu (state=$state)")
        onExpandedChange(false)
    }

    if (!expanded) return

    val dismiss = {
        Log.d(TAG, "NativeMenu: close requested (state=$state)")
        onExpandedChange(false)
    }

    OverlaySheet(
        onDismiss = dismiss,
        modifier = modifier,
    ) {
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
                onClick = dismiss,
                modifier = Modifier
                    .size(MinTouchTarget)
                    .semantics { contentDescription = "Close menu" },
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Close",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (showDisplay || showDiagnostics) {
            HubTextButton(onClick = onPanelBack) {
                Text("Back")
            }
            if (showDisplay) display() else diagnostics()
        } else {
            MenuItems(
                state = state,
                onAction = onAction,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

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
        MenuAction.Diagnostics,
        MenuAction.ReturnToHub,
    )

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        actions.forEach { action ->
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

internal fun isActionEnabled(action: MenuAction, state: SessionState): Boolean {
    return when (action) {
        is MenuAction.Resume -> state is SessionState.Playing ||
            state is SessionState.Background ||
            state is SessionState.Degraded
        is MenuAction.SnapshotNow -> state is SessionState.Playing
        is MenuAction.Checkpoint -> state is SessionState.Playing
        is MenuAction.Vault -> false
        is MenuAction.Reload -> state is SessionState.Playing
        is MenuAction.Display -> state !is SessionState.Closed && state !is SessionState.Closing
        is MenuAction.Diagnostics -> state !is SessionState.Closed && state !is SessionState.Closing
        is MenuAction.ReturnToHub -> true
    }
}

@Composable
private fun MenuItemRow(
    action: MenuAction,
    enabled: Boolean,
    onTrigger: () -> Unit,
) {
    val icon = when (action) {
        is MenuAction.Resume -> Icons.Default.PlayArrow
        is MenuAction.SnapshotNow -> Icons.Default.Screenshot
        is MenuAction.Checkpoint -> Icons.Default.Save
        is MenuAction.Vault -> Icons.Default.Cloud
        is MenuAction.Reload -> Icons.Default.Refresh
        is MenuAction.Display -> Icons.Default.Settings
        is MenuAction.Diagnostics -> Icons.Default.Info
        is MenuAction.ReturnToHub -> Icons.AutoMirrored.Default.ArrowBack
    }
    val label = action.label
    val principal = action is MenuAction.Resume
    val protective = action is MenuAction.SnapshotNow || action is MenuAction.Checkpoint
    val container = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant
        principal -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        principal -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val iconTint = when {
        enabled && protective -> HubColors.Gold
        else -> tint
    }

    Surface(
        onClick = onTrigger,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(MinTouchTarget)
            .semantics {
                contentDescription = if (enabled) label else "$label (disabled)"
            },
        color = container,
        shape = MaterialTheme.shapes.extraSmall,
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
                tint = iconTint,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = tint,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
