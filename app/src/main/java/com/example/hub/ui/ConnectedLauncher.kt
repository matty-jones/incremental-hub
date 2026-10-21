package com.example.hub.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.hub.database.GameInstanceDao
import com.example.hub.games.GameRegistry
import com.example.hub.sync.SaveSync
import com.example.hub.sync.UpdateCheck
import com.example.hub.sync.UpdateInfo
import com.example.hub.sync.WorkstationApi
import com.example.hub.sync.updateCheckStatus
import com.example.hub.ui.chrome.HubTextButton
import com.example.hub.update.UpdateInstaller
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "ConnectedLauncher"
private const val SYNC_HOLD_MS = 2_000L

/**
 * Launcher plus the workstation check. Save merge and update lookup fail open
 * when 192.168.86.3 does not answer. Buttons for games with no local snapshot
 * stay disabled until that first merge finishes or [SYNC_HOLD_MS] passes.
 */
@Composable
fun ConnectedLauncher(
    context: Context,
    registry: GameRegistry,
    instanceDao: GameInstanceDao,
    saveSync: SaveSync,
    api: WorkstationApi,
    onPlay: (String) -> Unit,
    onInspect: (String) -> Unit = {},
    onSafeLaunch: (String) -> Unit = {},
    onRecover: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val installer = remember(context, api) { UpdateInstaller(context, api) }
    val settingsStore = remember(context) { HubSettingsStore(context) }
    val scope = rememberCoroutineScope()
    var syncSettled by remember { mutableStateOf(false) }
    var networkTick by remember { mutableIntStateOf(0) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    var dismissedVersion by remember { mutableIntStateOf(0) }
    var installing by remember { mutableStateOf(false) }
    var installError by remember { mutableStateOf<String?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var debugTools by remember { mutableStateOf(settingsStore.debugToolsEnabled()) }
    var checkingForUpdate by remember { mutableStateOf(false) }
    var updateStatus by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = showSettings && update == null) {
        showSettings = false
    }

    DisposableEffect(context, saveSync) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch {
                    saveSync.merge()
                    networkTick += 1
                }
            }
        }
        val registered = runCatching { manager.registerDefaultNetworkCallback(callback) }.isSuccess
        onDispose {
            if (registered) {
                runCatching { manager.unregisterNetworkCallback(callback) }
            }
        }
    }

    LaunchedEffect(Unit) {
        val merge = launch { saveSync.merge() }
        withTimeoutOrNull(SYNC_HOLD_MS) { merge.join() }
        syncSettled = true
    }

    LaunchedEffect(networkTick) {
        val info = installer.check()
        if (info != null && info.versionCode != dismissedVersion && update?.versionCode != info.versionCode) {
            installError = null
            update = info
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (showSettings) {
            SettingsScreen(
                debugTools = debugTools,
                checkingForUpdate = checkingForUpdate,
                updateStatus = updateStatus,
                onDebugToolsChange = { enabled ->
                    debugTools = enabled
                    settingsStore.setDebugToolsEnabled(enabled)
                },
                onCheckForUpdate = {
                    checkingForUpdate = true
                    updateStatus = null
                    scope.launch {
                        val result = installer.lookup()
                        checkingForUpdate = false
                        when (result) {
                            is UpdateCheck.Available -> {
                                installError = null
                                update = result.info
                                updateStatus = null
                            }
                            else -> updateStatus = updateCheckStatus(result)
                        }
                    }
                },
                onBack = { showSettings = false },
            )
        } else {
            LauncherScreen(
                registry = registry,
                instanceDao = instanceDao,
                syncSettled = syncSettled,
                debugTools = debugTools,
                onOpenSettings = { showSettings = true },
                onPlay = onPlay,
                onInspect = onInspect,
                onSafeLaunch = onSafeLaunch,
                onRecover = onRecover,
            )
        }
        val available = update
        if (available != null) {
            AlertDialog(
                onDismissRequest = {
                    if (!installing) {
                        dismissedVersion = available.versionCode
                        update = null
                    }
                },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                title = { Text("Update available") },
                text = {
                    Text(
                        text = installError
                            ?: "Version ${available.versionName} is on the workstation.",
                        color = if (installError != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = !installing,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary,
                        ),
                        onClick = {
                            installing = true
                            installError = null
                            scope.launch {
                                val started = installer.downloadAndInstall(available)
                                installing = false
                                if (started) {
                                    update = null
                                } else {
                                    installError = "The download did not finish. You can keep playing and try again later."
                                    Log.w(TAG, "update download failed for ${available.versionName}")
                                }
                            }
                        },
                    ) {
                        Text(if (installing) "Downloading" else "Update")
                    }
                },
                dismissButton = {
                    HubTextButton(
                        enabled = !installing,
                        onClick = {
                            dismissedVersion = available.versionCode
                            update = null
                        },
                    ) {
                        Text("Later")
                    }
                },
            )
        }
    }
}
