package com.example.hub.ui

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.hub.games.SaveComparison
import com.example.hub.session.ForceSavePrompt
import com.example.hub.session.SaveDiagnostic
import com.example.hub.ui.chrome.HubOutlinedButton
import com.example.hub.ui.chrome.HubPrimaryButton
import com.example.hub.ui.chrome.HubWrappedText
import com.example.hub.ui.chrome.OverlaySheet
import java.text.DateFormat
import java.util.Date

private const val TAG = "RecoveryOverlay"

private fun formatWhen(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))

private fun comparisonLabel(comparison: SaveComparison): String = when (comparison) {
    SaveComparison.MATCH -> "This game matches Hub's copy."
    SaveComparison.LIVE_AHEAD -> "This game looks ahead of Hub's copy."
    SaveComparison.LIVE_BEHIND -> "This game looks behind Hub's copy."
    SaveComparison.LIVE_MISSING -> "This game has no save."
    SaveComparison.INCONCLUSIVE -> "Hub cannot tell which copy is ahead."
    SaveComparison.NO_SNAPSHOT -> "Hub has no saved copy."
}

/** A confirmation step goes back to the recovery choices. Close still leaves the session. */
internal fun recoveryBackDismissesConfirmation(
    confirmingRestore: Boolean,
    confirmingSnapshot: Boolean,
    confirmingStartOver: Boolean,
    confirmingPromote: Boolean = false,
): Boolean = confirmingRestore || confirmingSnapshot || confirmingStartOver || confirmingPromote

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
    onClose: () -> Unit,
    onExport: () -> Unit = {},
    onKeepWebView: () -> Unit = {},
    onRequestForceSave: () -> Unit = {},
    onOpenVault: () -> Unit = {},
    onInspectSnapshot: (Long) -> Unit = {},
    onExportSnapshot: (Long) -> Unit = {},
    onRestoreSnapshot: (Long) -> Unit = {},
    inspectedSequence: Long? = null,
    exportedPayload: String? = null,
    modifier: Modifier = Modifier,
) {
    Log.d(TAG, "RecoveryOverlay: shown for gameId=$gameId url=$launchUrl detail=$detail")
    var confirming by rememberSaveable(gameId) { mutableStateOf(false) }
    var confirmingUse by rememberSaveable(gameId) { mutableStateOf(-1L) }
    BackHandler {
        if (recoveryBackDismissesConfirmation(
                confirmingRestore = confirming,
                confirmingSnapshot = confirmingUse >= 0L,
                confirmingStartOver = false,
            )
        ) {
            confirming = false
            confirmingUse = -1L
        } else {
            onClose()
        }
    }
    val canKeepGame = diagnostic?.let {
        it.liveBytes > 0 && it.comparison != SaveComparison.LIVE_MISSING
    } == true
    OverlaySheet(
        onDismiss = onClose,
        dismissOnScrim = false,
        modifier = modifier,
    ) {
        Text(
            text = "This game was not opened",
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
            HubWrappedText(
                text = buildString {
                    append("Hub did not open ")
                    append(gameId)
                    append(".")
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
            )
            HubPrimaryButton(
                onClick = onRetry,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("recovery-retry"),
            ) {
                Text("Retry")
            }
        } else if (confirming) {
            Text(
                text = "Load Hub's copy",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = diagnostic.storedDetail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Replaces what is in the game with Hub's saved copy. The game reloads and is checked. If the check fails, the game is put back.",
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
                Text("Load Hub's copy")
            }
            HubOutlinedButton(
                onClick = { confirming = false },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Back")
            }
        } else if (confirmingUse >= 0L) {
            Text(
                text = "Load copy #$confirmingUse",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "Replaces what is in the game with copy #$confirmingUse. Cancel changes nothing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HubPrimaryButton(
                onClick = {
                    val sequence = confirmingUse
                    confirmingUse = -1L
                    onRestoreSnapshot(sequence)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Load this copy")
            }
            HubOutlinedButton(
                onClick = { confirmingUse = -1L },
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
                text = comparisonLabel(diagnostic.comparison),
                style = MaterialTheme.typography.titleSmall,
            )
            if (diagnostic.consequence.isNotBlank()) {
                Text(
                    text = diagnostic.consequence,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            val liveWhen = diagnostic.livePinnedAtMillis?.let { formatWhen(it) } ?: "capture time unknown"
            val roomWhen = diagnostic.storedCapturedAtMillis?.let { formatWhen(it) } ?: "capture time unknown"
            Text(
                text = "This game: ${diagnostic.liveDetail} ($liveWhen)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Hub's copy: ${diagnostic.storedDetail} ($roomWhen)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (diagnostic.history.isNotEmpty()) {
                Text(
                    text = "Snapshot history",
                    style = MaterialTheme.typography.titleSmall,
                )
                diagnostic.history.take(8).forEach { entry ->
                    val whenText = formatWhen(entry.capturedAtMillis)
                    val pin = if (entry.pinned) "pinned" else "automatic"
                    val origin = if (entry.origin.isBlank()) "" else ", ${entry.origin}"
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "#${entry.sequence} $whenText ${entry.reason} ($pin$origin, ${entry.byteLength} bytes). ${entry.detail}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(modifier = Modifier.fillMaxWidth()) {
                            HubOutlinedButton(
                                onClick = { onInspectSnapshot(entry.sequence) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Preview")
                            }
                            HubOutlinedButton(
                                onClick = { onExportSnapshot(entry.sequence) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Share")
                            }
                            HubOutlinedButton(
                                onClick = { confirmingUse = entry.sequence },
                                enabled = inspectedSequence == entry.sequence,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Load")
                            }
                        }
                    }
                }
            }
            if (!exportedPayload.isNullOrEmpty()) {
                Text(
                    text = "Sharing this copy. This does not change the game.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
            if (canKeepGame) {
                HubPrimaryButton(
                    onClick = onRequestForceSave,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Save this game")
                }
                Text(
                    text = "Keeps what is in the game and makes it Hub's current save. Hub's previous copy stays in history.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HubOutlinedButton(
                onClick = { confirming = true },
                enabled = diagnostic.recoverAllowed && !diagnostic.ambiguous,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Load Hub's copy")
            }
            Text(
                text = "Replaces what is in the game with Hub's saved copy.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HubOutlinedButton(
                onClick = onSafeLaunch,
                enabled = diagnostic.backupReady,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Play without saving")
            }
            Text(
                text = "Opens the game. Hub will not save or restore.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (diagnostic.ambiguous) {
                HubOutlinedButton(
                    onClick = onKeepWebView,
                    enabled = !diagnostic.webViewLeftUntouched,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (diagnostic.webViewLeftUntouched) "Nothing changed" else "Change nothing")
                }
            }
            HubOutlinedButton(
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Check again")
            }
        }
            HubOutlinedButton(
                onClick = onOpenVault,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("recovery-vault"),
            ) {
                Text("Vault")
            }
            HubOutlinedButton(
                onClick = onExport,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Export diagnostics")
            }
        HubOutlinedButton(
            onClick = onClose,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("recovery-close"),
        ) {
            Text("Close")
        }
    }
}

@Composable
internal fun ForceSaveConfirmation(
    prompt: ForceSavePrompt,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    BackHandler(onBack = onCancel)
    OverlaySheet(onDismiss = onCancel, dismissOnScrim = false) {
        Text(
            text = "Save this game",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "${prompt.relation} This becomes Hub's current save for ${prompt.gameId}. " +
                "The previous copy stays in history.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "This game: ${prompt.liveDetail}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Hub's copy: ${prompt.storedDetail}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HubPrimaryButton(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) {
            Text("Save this game")
        }
        HubOutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text("Cancel")
        }
    }
}
