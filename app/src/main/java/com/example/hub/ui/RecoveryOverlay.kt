package com.example.hub.ui

import android.util.Log
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.hub.ui.chrome.OverlaySheet

private const val TAG = "RecoveryOverlay"

/**
 * Overlay shown when the session enters [com.example.hub.session.SessionState.RecoveryRequired].
 *
 * Retry asks the caller to re-open the session. Close ends it.
 * The scrim does not dismiss, so a stray tap cannot drop the session.
 */
@Composable
fun RecoveryOverlay(
    gameId: String,
    launchUrl: String? = null,
    detail: String? = null,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Log.d(TAG, "RecoveryOverlay: shown for gameId=$gameId url=$launchUrl detail=$detail")
    OverlaySheet(
        onDismiss = onClose,
        dismissOnScrim = false,
        modifier = modifier,
    ) {
        Text(
            text = "Session Recovery Required",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        )
        Button(
            onClick = onRetry,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            shape = MaterialTheme.shapes.small,
        ) {
            Text("Retry")
        }
        Button(
            onClick = onClose,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) {
            Text("Close")
        }
    }
}
