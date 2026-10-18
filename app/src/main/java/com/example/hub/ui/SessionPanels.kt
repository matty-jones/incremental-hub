package com.example.hub.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.hub.session.DiagnosticEvent
import com.example.hub.session.DiagnosticSeverity

@Composable
fun DisplayPanel(
    folded: Boolean,
    textZoomPercent: Int,
    pageZoomPercent: Int,
    onTextZoom: (Int) -> Unit,
    onPageZoom: (Int) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = if (folded) "Folded display" else "Unfolded display",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "Zoom changes the page in place. It does not reload the game.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ZoomRow(
            label = "Text",
            percent = textZoomPercent,
            onChange = onTextZoom,
        )
        ZoomRow(
            label = "Page",
            percent = pageZoomPercent,
            onChange = onPageZoom,
        )
        TextButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
            Text("Reset to defaults")
        }
    }
}

@Composable
private fun ZoomRow(
    label: String,
    percent: Int,
    onChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        TextButton(onClick = { onChange(stepZoom(percent, -ZOOM_STEP)) }, enabled = percent > MIN_ZOOM) {
            Text("-")
        }
        Text(
            text = "$percent%",
            style = MaterialTheme.typography.bodyLarge,
        )
        TextButton(onClick = { onChange(stepZoom(percent, ZOOM_STEP)) }, enabled = percent < MAX_ZOOM) {
            Text("+")
        }
    }
}

@Composable
fun DiagnosticsPanel(
    report: String,
    events: List<DiagnosticEvent>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = "Diagnostics", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Fatal errors interrupt play. Warnings and informational notes stay here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { shareReport(context, report) }) {
                Text("Export")
            }
            TextButton(onClick = { copyReport(context, report) }) {
                Text("Copy")
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (events.isEmpty()) {
                Text(
                    text = "No session events yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            events.asReversed().take(40).forEach { event ->
                Text(
                    text = "${event.severity.name.lowercase()} · ${event.category}: ${event.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = severityColor(event.severity),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun severityColor(severity: DiagnosticSeverity) = when (severity) {
    DiagnosticSeverity.FATAL -> MaterialTheme.colorScheme.error
    DiagnosticSeverity.WARNING -> MaterialTheme.colorScheme.tertiary
    DiagnosticSeverity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
}

internal fun shareReport(context: Context, report: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Incremental Hub diagnostics")
        putExtra(Intent.EXTRA_TEXT, report)
    }
    context.startActivity(
        Intent.createChooser(send, "Export diagnostics").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

internal fun copyReport(context: Context, report: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Incremental Hub diagnostics", report))
}
