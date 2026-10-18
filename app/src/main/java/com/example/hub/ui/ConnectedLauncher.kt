package com.example.hub.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.hub.database.GameInstanceDao
import com.example.hub.games.GameRegistry
import com.example.hub.sync.SaveSync
import com.example.hub.sync.UpdateInfo
import com.example.hub.sync.WorkstationApi
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
    onLaunch: (String) -> Unit,
    onResume: (String) -> Unit,
    onInspect: (String) -> Unit = {},
    onSafeLaunch: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val installer = remember(context, api) { UpdateInstaller(context, api) }
    val scope = rememberCoroutineScope()
    var syncSettled by remember { mutableStateOf(false) }
    var networkTick by remember { mutableIntStateOf(0) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    var dismissedVersion by remember { mutableIntStateOf(0) }
    var installing by remember { mutableStateOf(false) }
    var installError by remember { mutableStateOf<String?>(null) }

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
        LauncherScreen(
            registry = registry,
            instanceDao = instanceDao,
            syncSettled = syncSettled,
            onLaunch = onLaunch,
            onResume = onResume,
            onInspect = onInspect,
            onSafeLaunch = onSafeLaunch,
        )
        val available = update
        if (available != null) {
            AlertDialog(
                onDismissRequest = {
                    if (!installing) {
                        dismissedVersion = available.versionCode
                        update = null
                    }
                },
                title = { Text("Update available") },
                text = {
                    Text(
                        installError
                            ?: "Version ${available.versionName} is on the workstation.",
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = !installing,
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
                    TextButton(
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
