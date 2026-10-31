package com.example.hub.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.hub.ui.chrome.HubCard
import com.example.hub.ui.chrome.HubOutlinedButton
import com.example.hub.ui.chrome.HubScaffold
import com.example.hub.ui.chrome.HubTextButton
import com.example.hub.ui.chrome.HubTopBar
import com.example.hub.vault.VaultBrowse
import com.example.hub.vault.VaultCatalog
import com.example.hub.vault.VaultEntry
import com.example.hub.vault.VaultReading
import com.example.hub.vault.formatCaptureTime
import com.example.hub.vault.readSnapshot
import com.example.hub.vault.shaPrefix
import kotlinx.coroutines.launch

/**
 * The WebView key ignores this screen. [webViewIdentity] is only the game
 * and the generation, so opening the vault or a fold does not rebuild it.
 */
fun webViewIdentity(gameId: String, generation: Int): Pair<String, Int> = gameId to generation

@Composable
fun VaultHost(
    gameId: String,
    catalog: VaultCatalog,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var offset by rememberSaveable(gameId) { mutableIntStateOf(0) }
    var browse by remember(gameId) { mutableStateOf<VaultBrowse?>(null) }
    var reading by remember(gameId) { mutableStateOf<VaultReading?>(null) }
    var notice by remember(gameId) { mutableStateOf<String?>(null) }

    suspend fun load(nextOffset: Int, advanceRemote: Boolean) {
        offset = nextOffset
        browse = catalog.browse(gameId, nextOffset, advanceRemote)
        notice = null
    }

    LaunchedEffect(gameId) {
        load(0, advanceRemote = true)
    }

    BackHandler { onBack() }

    VaultScreen(
        gameId = gameId,
        browse = browse,
        reading = reading,
        notice = notice,
        onBack = onBack,
        onOlder = {
            val page = browse?.pageSize ?: catalog.pageSize
            scope.launch { load(offset + page, advanceRemote = false) }
        },
        onNewer = {
            val page = browse?.pageSize ?: catalog.pageSize
            scope.launch { load((offset - page).coerceAtLeast(0), advanceRemote = false) }
        },
        onMoreRemote = {
            scope.launch { load(offset, advanceRemote = true) }
        },
        onSelect = { entry ->
            scope.launch {
                val payload = catalog.readPayload(entry)
                reading = readSnapshot(gameId, entry, payload)
            }
        },
        onCloseDetail = { reading = null },
        modifier = modifier,
    )
}

@Composable
fun VaultScreen(
    gameId: String,
    browse: VaultBrowse?,
    reading: VaultReading?,
    notice: String?,
    onBack: () -> Unit,
    onOlder: () -> Unit,
    onNewer: () -> Unit,
    onMoreRemote: () -> Unit,
    onSelect: (VaultEntry) -> Unit,
    onCloseDetail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val detail = reading
    HubScaffold(
        modifier = modifier.testTag("vault-screen"),
        topBar = {
            HubTopBar(
                title = "Vault",
                subtitle = gameId,
                navigationIcon = {
                    HubTextButton(onClick = if (detail == null) onBack else onCloseDetail) {
                        Text(if (detail == null) "Back" else "Timeline")
                    }
                },
            )
        },
    ) { padding ->
        if (detail != null) {
            VaultDetail(detail, Modifier.padding(padding))
        } else {
            VaultTimeline(
                browse = browse,
                notice = notice,
                onOlder = onOlder,
                onNewer = onNewer,
                onMoreRemote = onMoreRemote,
                onSelect = onSelect,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun VaultTimeline(
    browse: VaultBrowse?,
    notice: String?,
    onOlder: () -> Unit,
    onNewer: () -> Unit,
    onMoreRemote: () -> Unit,
    onSelect: (VaultEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("vault-timeline"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            val status = when {
                browse == null -> "Loading snapshot history"
                !browse.workstationReachable ->
                    "Workstation history is unavailable. Local snapshots stay on this phone."
                browse.remoteComplete -> "Workstation index is included for the pages already requested."
                else -> "Workstation history loads one page at a time. Payloads stay on the workstation until opened."
            }
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!notice.isNullOrBlank()) {
                Text(
                    text = notice,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (browse != null) {
                Text(
                    text = "${browse.localCount} local snapshots. Showing ${browse.localOffset + 1} onward.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        val groups = browse?.groups.orEmpty()
        if (browse != null && groups.isEmpty()) {
            item {
                Text(
                    text = "No snapshots for this game yet.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        groups.forEach { group ->
            item(key = "day-${group.title}-${group.entries.first().id}") {
                Text(
                    text = group.title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(group.entries, key = { it.id }) { entry ->
                VaultRow(entry, onSelect)
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HubOutlinedButton(
                    onClick = onNewer,
                    enabled = browse?.hasNewerLocal == true,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Newer")
                }
                HubOutlinedButton(
                    onClick = onOlder,
                    enabled = browse?.hasOlderLocal == true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("vault-older"),
                ) {
                    Text("Older")
                }
            }
            HubOutlinedButton(
                onClick = onMoreRemote,
                enabled = browse?.hasMoreRemote == true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .testTag("vault-more-remote"),
            ) {
                Text(
                    if (browse?.workstationReachable == false) {
                        "Retry workstation history"
                    } else {
                        "More workstation history"
                    },
                )
            }
        }
    }
}

@Composable
private fun VaultRow(entry: VaultEntry, onSelect: (VaultEntry) -> Unit) {
    HubCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("vault-entry-${entry.id}")
            .clickable { onSelect(entry) },
    ) {
        Text(
            text = entry.labels.joinToString(" · "),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = formatCaptureTime(entry.capturedAtMillis),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = buildString {
                if (entry.sequence > 0L) append("#${entry.sequence} ")
                append(entry.reason)
                append(" · ")
                append(entry.origin)
                append(" · ")
                append(entry.lineageId)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "${entry.payloadBytes} characters · ${entry.integrity} · ${shaPrefix(entry.sha256)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val detail = entry.storedDetail
        if (!detail.isNullOrBlank()) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun VaultDetail(reading: VaultReading, modifier: Modifier = Modifier) {
    val entry = reading.entry
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag("vault-detail"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(entry.labels.joinToString(" · "), style = MaterialTheme.typography.titleMedium)
        Text("Captured ${formatCaptureTime(entry.capturedAtMillis)}")
        if (entry.sequence > 0L) Text("Sequence ${entry.sequence}")
        Text("Reason ${entry.reason}")
        Text("Source ${entry.origin}")
        Text("Lineage ${entry.lineageId}")
        Text("Integrity ${entry.integrity}")
        val hash = reading.computedSha ?: entry.sha256.ifBlank { "not recorded" }
        Text("SHA-256 $hash")
        val match = reading.hashMatches
        if (match != null) {
            Text(if (match) "Hash matches the stored payload." else "Hash does not match the stored payload.")
        }
        val size = reading.utf8Bytes
        Text(
            if (size != null) {
                "$size UTF-8 bytes, ${entry.payloadBytes} stored characters"
            } else {
                "${entry.payloadBytes} stored characters"
            },
        )
        val shark = reading.shark
        if (shark != null) {
            Text("Feature ${shark.feature}")
            Text("Prestige ${shark.prestige}")
            Text("Core ${shark.core}")
            Text("Humanoid ${shark.humanoid}")
            Text("Singularity ${shark.singularity}")
        }
        val recomputed = reading.recomputedDetail
        if (!recomputed.isNullOrBlank() && shark == null) {
            Text(recomputed)
        }
        val missing = reading.unavailableReason
        if (!missing.isNullOrBlank()) {
            Text(missing, color = MaterialTheme.colorScheme.error)
        }
    }
}
