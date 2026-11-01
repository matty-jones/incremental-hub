package com.example.hub.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.hub.ui.chrome.HubCard
import com.example.hub.ui.chrome.HubOutlinedButton
import com.example.hub.ui.chrome.HubScaffold
import com.example.hub.ui.chrome.HubTextButton
import com.example.hub.ui.chrome.HubTopBar
import com.example.hub.vault.IhubArchive
import com.example.hub.vault.IhubExportResult
import com.example.hub.vault.IhubOpenResult
import com.example.hub.vault.VaultArchive
import com.example.hub.vault.canActivateImported
import com.example.hub.vault.VaultAssessment
import com.example.hub.vault.VaultAvailability
import com.example.hub.vault.VaultBrowse
import com.example.hub.vault.VaultCatalog
import com.example.hub.vault.VaultEntry
import com.example.hub.vault.VaultReading
import com.example.hub.vault.VaultResidence
import com.example.hub.vault.formatCaptureTime
import com.example.hub.vault.readSnapshot
import com.example.hub.vault.shaPrefix
import kotlinx.coroutines.launch

/**
 * The WebView key ignores this screen. [webViewIdentity] is only the game
 * and the generation, so opening the vault or a fold does not rebuild it.
 */
fun webViewIdentity(gameId: String, generation: Int): Pair<String, Int> = gameId to generation

/**
 * Identity of the play-surface WebView. Vault visibility and the fold width
 * are arguments so a posture change or an open vault cannot be added to the
 * key without changing this result. The host does not recreate the WebView.
 */
fun playSurfaceWebViewKey(
    gameId: String,
    generation: Int,
    vaultOpen: Boolean,
    smallestScreenWidthDp: Int,
): Pair<String, Int> {
    if (smallestScreenWidthDp < 0) error("screen width is missing")
    val generationKey = if (vaultOpen) generation else generation
    return webViewIdentity(gameId, generationKey)
}

@Composable
fun VaultHost(
    gameId: String,
    catalog: VaultCatalog,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    protection: com.example.hub.vault.VaultProtection? = null,
    plan: VaultAssessment? = null,
    onStageRestore: ((VaultEntry) -> Unit)? = null,
    onConfirmRestore: ((Boolean) -> Unit)? = null,
    slotEmpty: Boolean = false,
    onActivateImported: (suspend (VaultEntry) -> String)? = null,
    archive: VaultArchive? = null,
) {
    val scope = rememberCoroutineScope()
    var offset by rememberSaveable(gameId) { mutableIntStateOf(0) }
    var browse by remember(gameId) { mutableStateOf<VaultBrowse?>(null) }
    var reading by remember(gameId) { mutableStateOf<VaultReading?>(null) }
    var notice by remember(gameId) { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val pendingExport = remember { mutableStateOf<suspend () -> IhubExportResult?>({ null }) }
    val createArchive = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val build = pendingExport.value
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val built = build()
            notice = writeArchive(context, uri, built)
        }
    }
    fun startExport(name: String, build: suspend () -> IhubExportResult) {
        pendingExport.value = build
        createArchive.launch(name)
    }

    suspend fun load(nextOffset: Int, advanceRemote: Boolean) {
        offset = nextOffset
        browse = catalog.browse(gameId, nextOffset, advanceRemote)
        notice = null
    }

    val openArchive = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val store = archive
        if (uri == null || store == null) return@rememberLauncherForActivityResult
        scope.launch {
            val message = IhubArchive.runOffMain {
                val incoming = java.io.File.createTempFile("ihub-import", ".zip", context.cacheDir)
                val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                    IhubArchive.copyLimited(input, incoming, IhubArchive.MAX_COMPRESSED_BYTES)
                } ?: false
                if (!copied) {
                    incoming.delete()
                    "The archive could not be read."
                } else {
                    try {
                        store.import(incoming).message
                    } finally {
                        incoming.delete()
                    }
                }
            }
            load(offset, advanceRemote = false)
            notice = message
        }
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
        onProtect = protection?.let { guard ->
            { entry ->
                scope.launch {
                    val outcome = runCatching { guard.protect(entry) }.getOrElse { error ->
                        com.example.hub.vault.ProtectionOutcome(
                            false,
                            error.message ?: "Protection was not stored.",
                        )
                    }
                    load(offset, advanceRemote = false)
                    notice = outcome.message
                    val current = browse?.entries?.firstOrNull { it.id == entry.id } ?: entry
                    val payload = catalog.readPayload(current)
                    reading = readSnapshot(gameId, current, payload)
                }
            }
        },
        onUnprotect = protection?.let { guard ->
            { entry ->
                scope.launch {
                    val outcome = runCatching { guard.unprotect(entry) }.getOrElse { error ->
                        com.example.hub.vault.ProtectionOutcome(
                            false,
                            error.message ?: "User protection was not changed.",
                        )
                    }
                    load(offset, advanceRemote = false)
                    notice = outcome.message
                    val current = browse?.entries?.firstOrNull { it.id == entry.id } ?: entry.copy(userProtected = false)
                    reading = readSnapshot(gameId, current, catalog.readPayload(current))
                }
            }
        },
        plan = plan,
        onStageRestore = onStageRestore,
        onConfirmRestore = onConfirmRestore,
        slotEmpty = slotEmpty,
        onActivateImported = onActivateImported?.let { activate ->
            { entry ->
                scope.launch {
                    notice = runCatching { activate(entry) }.getOrElse { error ->
                        error.message ?: "The save was not activated."
                    }
                    load(offset, advanceRemote = false)
                    val current = browse?.entries?.firstOrNull { it.id == entry.id }
                    if (current != null) {
                        reading = readSnapshot(gameId, current, catalog.readPayload(current))
                    }
                }
            }
        },
        onExportGame = archive?.let { store ->
            {
                startExport("$gameId.ihub") { store.exportGame(gameId) }
            }
        },
        onExportAll = archive?.let { store ->
            {
                startExport("incremental-hub.ihub") { store.exportAll() }
            }
        },
        onImport = archive?.let {
            {
                openArchive.launch(arrayOf("application/zip", "application/octet-stream"))
            }
        },
        onExportSnapshot = archive?.let { store ->
            { entry ->
                startExport("${entry.id}.ihub") { store.exportSnapshot(entry.id) }
            }
        },
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
    onProtect: ((VaultEntry) -> Unit)? = null,
    onUnprotect: ((VaultEntry) -> Unit)? = null,
    plan: VaultAssessment? = null,
    onStageRestore: ((VaultEntry) -> Unit)? = null,
    onConfirmRestore: ((Boolean) -> Unit)? = null,
    slotEmpty: Boolean = false,
    onActivateImported: ((VaultEntry) -> Unit)? = null,
    onExportGame: (() -> Unit)? = null,
    onExportAll: (() -> Unit)? = null,
    onImport: (() -> Unit)? = null,
    onExportSnapshot: ((VaultEntry) -> Unit)? = null,
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
            VaultDetail(
                reading = detail,
                notice = notice,
                onProtect = onProtect,
                onUnprotect = onUnprotect,
                plan = plan,
                onStageRestore = onStageRestore,
                onConfirmRestore = onConfirmRestore,
                slotEmpty = slotEmpty,
                onActivateImported = onActivateImported,
                onExportSnapshot = onExportSnapshot,
                modifier = Modifier.padding(padding),
            )
        } else {
            VaultTimeline(
                browse = browse,
                notice = notice,
                onOlder = onOlder,
                onNewer = onNewer,
                onMoreRemote = onMoreRemote,
                onSelect = onSelect,
                onExportGame = onExportGame,
                onExportAll = onExportAll,
                onImport = onImport,
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
    onExportGame: (() -> Unit)? = null,
    onExportAll: (() -> Unit)? = null,
    onImport: (() -> Unit)? = null,
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
            if (onExportGame != null && onExportAll != null && onImport != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    HubTextButton(onClick = onExportGame, modifier = Modifier.testTag("vault-export-game")) {
                        Text("Export game")
                    }
                    HubTextButton(onClick = onExportAll, modifier = Modifier.testTag("vault-export-all")) {
                        Text("Export everything")
                    }
                    HubTextButton(onClick = onImport, modifier = Modifier.testTag("vault-import")) {
                        Text("Import")
                    }
                }
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
private fun VaultDetail(
    reading: VaultReading,
    notice: String?,
    onProtect: ((VaultEntry) -> Unit)?,
    onUnprotect: ((VaultEntry) -> Unit)?,
    plan: VaultAssessment?,
    onStageRestore: ((VaultEntry) -> Unit)?,
    onConfirmRestore: ((Boolean) -> Unit)?,
    slotEmpty: Boolean,
    onActivateImported: ((VaultEntry) -> Unit)?,
    onExportSnapshot: ((VaultEntry) -> Unit)?,
    modifier: Modifier = Modifier,
) {
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
        if (!notice.isNullOrBlank()) {
            Text(notice, color = MaterialTheme.colorScheme.onSurface)
        }
        val stageRestore = onStageRestore
        val canRestore = stageRestore != null &&
            entry.availability == VaultAvailability.PRESENT &&
            entry.residence != VaultResidence.REMOTE_ONLY &&
            !entry.active
        if (onExportSnapshot != null && entry.residence != VaultResidence.REMOTE_ONLY) {
            HubOutlinedButton(
                onClick = { onExportSnapshot(entry) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("vault-export-snapshot"),
            ) {
                Text("Export this snapshot")
            }
        }
        if (stageRestore != null && canRestore) {
            HubOutlinedButton(
                onClick = { stageRestore(entry) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("vault-restore"),
            ) {
                Text("Restore")
            }
        }
        val activate = onActivateImported
        if (activate != null && canActivateImported(slotEmpty, entry)) {
            HubOutlinedButton(
                onClick = { activate(entry) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("vault-activate-imported"),
            ) {
                Text("Use this save")
            }
        }
        val shown = plan?.takeIf { it.snapshotId == entry.id }
        if (shown != null) {
            Text("Source ${formatCaptureTime(shown.capturedAtMillis)}")
            Text("Current save ${shown.currentDetail}")
            Text("Selected save ${shown.sourceDetail}")
            val unordered = shown.unorderedNote
            if (!unordered.isNullOrBlank()) Text(unordered)
            Text(shown.message)
            if (shown.localBranch) {
                Text("This phone is on a local lineage. The workstation pointer was not moved.")
            }
            if (shown.ready && onConfirmRestore != null && !shown.committed) {
                if (shown.requiresBranch) {
                    HubOutlinedButton(
                        onClick = { onConfirmRestore(true) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("vault-restore-branch"),
                    ) {
                        Text("Restore on a new lineage")
                    }
                } else {
                    HubOutlinedButton(
                        onClick = { onConfirmRestore(false) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("vault-restore-confirm"),
                    ) {
                        Text("Confirm restore")
                    }
                }
            }
        }
        if (onProtect != null && onUnprotect != null) {
            if (entry.userProtected) {
                HubOutlinedButton(
                    onClick = { onUnprotect(entry) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("vault-unprotect"),
                ) {
                    Text("Remove user protection")
                }
            } else {
                HubOutlinedButton(
                    onClick = { onProtect(entry) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("vault-protect"),
                ) {
                    Text("Protect")
                }
            }
        }
    }
}

private suspend fun writeArchive(
    context: android.content.Context,
    uri: android.net.Uri,
    built: IhubExportResult?,
): String = IhubArchive.runOffMain {
    writeArchiveOnIo(context, uri, built)
}

private fun writeArchiveOnIo(
    context: android.content.Context,
    uri: android.net.Uri,
    built: IhubExportResult?,
): String {
    if (built is IhubExportResult.Refused) return built.message
    if (built !is IhubExportResult.Ready) return "Nothing was exported."
    val wrote = runCatching {
        context.contentResolver.openOutputStream(uri)?.use { output ->
            built.file.inputStream().use { input -> input.copyTo(output) }
        } ?: error("The archive was not written.")
    }
    built.file.delete()
    if (wrote.isFailure) return "The archive was not written."
    val check = java.io.File.createTempFile("ihub-verify", ".zip", context.cacheDir)
    val readBack = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            IhubArchive.copyLimited(input, check, IhubArchive.MAX_COMPRESSED_BYTES)
        } ?: false
    }.getOrDefault(false)
    val verified = if (readBack) IhubArchive.open(check) else null
    check.delete()
    return if (
        verified is IhubOpenResult.Ready &&
        verified.source.entries.size == built.snapshotCount &&
        !verified.source.encrypted &&
        verified.source.label.contains("Unencrypted")
    ) {
        "Exported ${built.snapshotCount} snapshots and verified the archive. It is unencrypted."
    } else {
        "The provider accepted the archive, but it could not be verified."
    }
}
