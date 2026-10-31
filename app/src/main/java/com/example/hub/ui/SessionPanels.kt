package com.example.hub.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.example.hub.BuildConfig
import java.io.File
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.hub.session.DiagnosticEvent
import com.example.hub.session.DiagnosticSeverity
import com.example.hub.ui.chrome.HubOutlinedButton
import com.example.hub.ui.chrome.HubTextButton
import com.example.hub.ui.theme.hubWarning

@Composable
fun DisplayPanel(
    folded: Boolean,
    textZoomPercent: Int,
    pageZoomPercent: Int,
    pageScaleMode: PageScaleMode,
    allowGameFit: Boolean,
    presentation: PresentationAck?,
    onTextZoom: (Int) -> Unit,
    onPageZoom: (Int) -> Unit,
    onMode: (PageScaleMode) -> Unit,
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
            text = "Display changes apply in place. They do not reload the game.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ZoomRow(
            label = "Text",
            percent = textZoomPercent,
            onChange = onTextZoom,
        )
        Text(
            text = textZoomDetail(textZoomPercent, presentation?.textZoom),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (pageScaleMode == PageScaleMode.GAME_FIT) {
            Text(
                text = "Game fit",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = gameFitDetail(presentation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            ZoomRow(
                label = "Page",
                percent = pageZoomPercent,
                onChange = onPageZoom,
            )
            Text(
                text = pageZoomDetail(pageZoomPercent, pageScaleMode, presentation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = viewportDetail(presentation?.viewportScale),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (allowGameFit) {
            if (pageScaleMode == PageScaleMode.GAME_FIT) {
                HubTextButton(onClick = { onMode(PageScaleMode.READABLE) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Use readable layout")
                }
            } else {
                Text(
                    text = readableOverrideDetail(presentation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HubTextButton(onClick = { onMode(PageScaleMode.GAME_FIT) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Use the game's fit")
                }
            }
        }
        HubTextButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
            Text("Reset to defaults")
        }
    }
}

fun textZoomDetail(requested: Int, applied: Int?): String {
    val shown = applied ?: requested
    return "Requested text $requested%. WebView text zoom $shown%."
}

fun pageZoomDetail(requested: Int, mode: PageScaleMode, ack: PresentationAck?): String {
    val applied = ack?.let { appliedZoomPercent(it) }
    val appliedText = if (applied == null) "waiting for the page" else "$applied%"
    val kind = if (mode == PageScaleMode.READABLE) "Applied body zoom" else "Applied CSS zoom"
    return "Requested page $requested%. $kind $appliedText."
}

fun gameFitDetail(ack: PresentationAck?): String {
    val ratio = ack?.bodyZoom?.toFloatOrNull()
    val percent = if (ratio == null || ratio > 2f) "waiting" else "${kotlin.math.round(ratio * 100).toInt()}%"
    return "FE000000 Fit to Width sets body zoom to $percent (window.innerWidth / 1376). Hub page zoom is not applied."
}

fun readableOverrideDetail(ack: PresentationAck?): String {
    val enabled = ack?.gameFitEnabled
    val wrote = ack?.fitRatio?.toFloat() ?: ack?.bodyZoom?.toFloatOrNull()
    val wroteText = if (wrote == null || wrote > 2f) "its own ratio" else "${kotlin.math.round(wrote * 100).toInt()}%"
    return if (enabled == false) {
        "Fit to Width is off in the game. Readable layout is holding Hub page zoom."
    } else {
        "Fit to Width is on in the game and would use $wroteText. Readable layout overrides that zoom without changing the save."
    }
}

fun viewportDetail(scale: Double?): String {
    if (scale == null) return "Viewport scale waiting for the page."
    val shown = String.format(java.util.Locale.US, "%.2f", scale)
    return if (kotlin.math.abs(scale - 1.0) > 0.02) {
        "Viewport scale $shown. Reconciling it to 1 without reloading."
    } else {
        "Viewport scale $shown."
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
        HubTextButton(onClick = { onChange(stepZoom(percent, -ZOOM_STEP)) }, enabled = percent > MIN_ZOOM) {
            Text("-")
        }
        Text(
            text = "$percent%",
            style = MaterialTheme.typography.bodyLarge,
        )
        HubTextButton(onClick = { onChange(stepZoom(percent, ZOOM_STEP)) }, enabled = percent < MAX_ZOOM) {
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
            HubOutlinedButton(onClick = { shareReport(context, report) }) {
                Text("Export")
            }
            HubTextButton(onClick = { copyReport(context, report) }) {
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
    DiagnosticSeverity.WARNING -> hubWarning()
    DiagnosticSeverity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
}

internal fun writeSnapshotExport(file: File, payload: String) {
    file.parentFile?.mkdirs()
    file.writeBytes(payload.toByteArray(Charsets.UTF_8))
}

internal fun shareSnapshotPayload(context: Context, payload: String) {
    val file = File(context.cacheDir, "snapshot-exports/snapshot.txt")
    writeSnapshotExport(file, payload)
    val uri = FileProvider.getUriForFile(
        context,
        "${BuildConfig.APPLICATION_ID}.fileprovider",
        file,
    )
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri("snapshot", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(send, "Export snapshot").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
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
