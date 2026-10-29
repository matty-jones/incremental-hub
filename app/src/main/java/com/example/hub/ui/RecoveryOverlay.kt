package com.example.hub.ui

import android.util.Log
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.hub.session.SaveDiagnostic
import com.example.hub.ui.chrome.HubOutlinedButton
import com.example.hub.ui.chrome.HubPrimaryButton
import com.example.hub.ui.chrome.OverlaySheet
import java.text.DateFormat
import java.util.Date

private const val TAG = "RecoveryOverlay"

/**
 * Overlay shown when the session enters [com.example.hub.session.SessionState.RecoveryRequired].
 *
 * When [diagnostic] is present, the actions inspect storage, launch without capture,
 * or restore a snapshot after confirmation. Retry remains for failures that have
 * no storage report. The scrim does not dismiss.
 */
@Composable
fun RecoveryOverlay(
    gameId: String,
    launchUrl: String? = null,
    detail: String? = null,
    diagnostic: SaveDiagnostic? = null,
    consoleLines: List<String> = emptyList(),
    onRetry: () -> Unit,
    onSafeLaunch: () -> Unit = {},
    onRecover: () -> Unit = {},
    onStartOver: (() -> Unit)? = null,
    onClose: () -> Unit,
    onExport: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Log.d(TAG, "RecoveryOverlay: shown for gameId=$gameId url=$launchUrl detail=$detail")
    var confirming by rememberSaveable(gameId) { mutableStateOf(false) }
    var confirmingStartOver by rememberSaveable(gameId) { mutableStateOf(false) }
    val incident = detail?.contains("Capture incident") == true ||
        diagnostic?.note?.contains("Capture incident") == true
    OverlaySheet(
        onDismiss = onClose,
        dismissOnScrim = false,
        modifier = modifier,
    ) {
        Text(
            text = if (diagnostic == null) "Session Recovery Required" else "Save diagnostics",
            style = MaterialTheme.typography.headlineSmall,
            color = if (diagnostic == null) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        if (diagnostic == null) {
            Text(
                text = buildString {
                    append("The session for ")
                    append(gameId)
                    append(" needs recovery.")
                    if (!launchUrl.isNullOrBlank()) {
                        append("\n")
                        append(launchUrl)
                    }
                    if (!detail.isNullOrBlank()) {
                        append("\n")
                        append(detail)
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            HubPrimaryButton(
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Retry")
            }
        } else if (confirming) {
            Text(
                text = "Recover from snapshot",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = diagnostic.storedDetail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "This writes the snapshot into the current profile, reloads the game once, and checks the running progress. If that check fails, the previous storage value is written back.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HubPrimaryButton(
                onClick = {
                    confirming = false
                    onRecover()
                },
                enabled = diagnostic.recoverAllowed,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Restore this snapshot")
            }
            HubOutlinedButton(
                onClick = { confirming = false },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Back")
            }
        } else {
            Text(
                text = diagnostic.note,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Comparison: ${diagnostic.comparison.name}",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "WebView key: ${diagnostic.liveBytes} bytes. ${diagnostic.liveDetail}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Room snapshot: ${diagnostic.storedBytes} bytes. ${diagnostic.storedDetail}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (diagnostic.history.isNotEmpty()) {
                Text(
                    text = "Snapshot history",
                    style = MaterialTheme.typography.titleSmall,
                )
                diagnostic.history.take(8).forEach { entry ->
                    val whenText = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(Date(entry.capturedAtMillis))
                    val pin = if (entry.pinned) "pinned" else "automatic"
                    val origin = if (entry.origin.isBlank()) "" else ", ${entry.origin}"
                    Text(
                        text = "#${entry.sequence} $whenText ${entry.reason} ($pin$origin, ${entry.byteLength} bytes). ${entry.detail}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (consoleLines.isNotEmpty()) {
                Text(
                    text = "Console",
                    style = MaterialTheme.typography.titleSmall,
                )
                consoleLines.takeLast(8).forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!detail.isNullOrBlank() && detail != diagnostic.note) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "Safe Launch loads the game without a Hub restore or snapshot. The game can still autosave.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HubOutlinedButton(
                onClick = onSafeLaunch,
                enabled = diagnostic.backupReady,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Safe Launch")
            }
            if (incident && onStartOver != null && confirmingStartOver) {
                Text(
                    text = "Start over keeps the current save in history and makes the quarantined save the active one. It is not uploaded.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HubPrimaryButton(
                    onClick = {
                        confirmingStartOver = false
                        onStartOver()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Confirm new lineage")
                }
                HubOutlinedButton(
                    onClick = { confirmingStartOver = false },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Back")
                }
            } else if (incident && onStartOver != null) {
                HubOutlinedButton(
                    onClick = { confirmingStartOver = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Start over on a new lineage")
                }
            }
            HubPrimaryButton(
                onClick = { confirming = true },
                enabled = diagnostic.recoverAllowed,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Recover from snapshot")
            }
            HubOutlinedButton(
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Check storage again")
            }
        }
        HubOutlinedButton(
            onClick = onExport,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Export diagnostics")
        }
        HubOutlinedButton(
            onClick = onClose,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Close")
        }
    }
}
