package com.example.hub.ui

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.webkit.ProfileStore
import com.example.hub.bootstrap.BootstrapDetector
import com.example.hub.bootstrap.DetectorResult
import com.example.hub.bootstrap.PreLaunchSnapshot
import com.example.hub.bootstrap.SnapshotResult
import com.example.hub.ui.theme.hubOnSuccess
import com.example.hub.ui.theme.hubOnSuccessContainer
import com.example.hub.ui.theme.hubSuccess
import com.example.hub.ui.theme.hubSuccessContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "BootstrapScreen"

/**
 * Host composable for the native-origin bootstrap spike.
 *
 * On launch it runs [BootstrapDetector.test] and [PreLaunchSnapshot.capture] sequentially
 * on the main dispatcher, then displays the results as pass/fail badges and evidence cards.
 *
 * Callers supply [onRefresh] to trigger a re-run of both steps.
 */
@Composable
fun BootstrapScreen(onRefresh: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var detectorResult by remember {
        mutableStateOf<DetectorResult?>(null)
    }
    var snapshotResult by remember {
        mutableStateOf<SnapshotResult?>(null)
    }
    var running by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(context) {
        coroutineScope.launch {
            val result = runBootstrap(context)
            detectorResult = result.detectorResult
            snapshotResult = result.snapshotResult
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Bootstrap Origin Spike",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )

            Text(
                text = "Proves native-origin bootstrap can read localStorage before game code executes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            )

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    detectorResult?.let { result ->
                        ResultCard(
                            label = "Origin Detector",
                            passed = result.qualifiedMechanism != null,
                            message = result.message,
                        )
                    } ?: LoadingCard("Origin detector …")
                }
                item {
                    snapshotResult?.let { result ->
                        ResultCard(
                            label = "Pre-Launch Snapshot",
                            passed = result.passed,
                            message = result.message,
                        )
                    } ?: detectorResult?.let { LoadingCard("Snapshot …") }
                }
                item {
                    if (detectorResult != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Divider()
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
                item {
                    detectorResult?.let { result ->
                        EvidenceCard(
                            title = "Mechanism Evidence",
                            lines = listOfNotNull(
                                "Qualified: ${result.qualifiedMechanism ?: "none"}",
                                "WebViewAssetLoader origin: ${result.webViewAssetLoaderOrigin ?: "n/a"}",
                                "loadDataWithBaseURL origin: ${result.loadDataWithBaseURLOrigin ?: "n/a"}",
                                "WAL qualified: ${result.webViewAssetLoaderQualified}",
                                "LDWU qualified: ${result.loadDataWithBaseURLQualified}",
                                "Report: ${result.reportPath ?: "n/a"}",
                            ),
                        )
                    }
                }
                item {
                    snapshotResult?.let { result ->
                        EvidenceCard(
                            title = "Snapshot Evidence",
                            lines = listOfNotNull(
                                "SHA-256: ${result.sha256}",
                                "Bytes: ${result.uncompressedBytes}",
                                "Keys: ${result.keyCount}",
                                "Mechanism: ${result.mechanism ?: "n/a"}",
                            ),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            androidx.compose.material3.Button(
                onClick = {
                    if (running) return@Button
                    running = true
                    coroutineScope.launch {
                        val snackbarResult = snackbarHostState.showSnackbar(
                            message = "Re-running bootstrap spike …",
                            duration = SnackbarDuration.Indefinite,
                        )
                        if (snackbarResult == SnackbarResult.Dismissed ||
                            snackbarResult == SnackbarResult.ActionPerformed
                        ) {
                            val result = runBootstrap(context)
                            detectorResult = result.detectorResult
                            snapshotResult = result.snapshotResult
                            running = false
                        }
                    }
                },
                enabled = !running,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
            ) {
                Text(if (running) "Running …" else "Refresh")
            }
        }
    }
}

private suspend fun runBootstrap(context: Context): BootstrapRunResult = withContext(Dispatchers.Main) {
    Log.d(TAG, "Running bootstrap spike")
    val profileStore = ProfileStore.getInstance()

    val detectorResult = runCatching {
        BootstrapDetector(context).test(profileStore)
    }.getOrElse { e ->
        Log.e(TAG, "Detector threw", e)
        DetectorResult(
            qualifiedMechanism = null,
            webViewAssetLoaderOrigin = null,
            loadDataWithBaseURLOrigin = null,
            webViewAssetLoaderQualified = false,
            loadDataWithBaseURLQualified = false,
            reportPath = null,
            message = "Exception: ${e.message}",
        )
    }
    Log.d(TAG, "Detector complete — ${detectorResult.message}")

    val snapshotResult = runCatching {
        PreLaunchSnapshot(context).capture(profileStore, detectorResult)
    }.getOrElse { e ->
        Log.e(TAG, "Snapshot threw", e)
        SnapshotResult(
            passed = false,
            uncompressedBytes = 0L,
            sha256 = "error: ${e.message}",
            keyCount = 0,
            mechanism = null,
            timestampEpochMs = 0L,
            message = "Exception: ${e.message}",
        )
    }
    Log.d(TAG, "Snapshot complete — ${snapshotResult.message}")

    BootstrapRunResult(detectorResult, snapshotResult)
}

private data class BootstrapRunResult(
    val detectorResult: DetectorResult,
    val snapshotResult: SnapshotResult,
)

@Composable
private fun ResultCard(label: String, passed: Boolean, message: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (passed) {
                hubSuccessContainer()
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (passed) {
                        hubOnSuccessContainer()
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    },
                )
                Spacer(modifier = Modifier.width(8.dp))
                val badgeColor = if (passed) {
                    hubSuccess()
                } else {
                    MaterialTheme.colorScheme.error
                }
                Surface(
                    color = badgeColor,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = if (passed) "PASSED" else "FAILED",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (passed) {
                            hubOnSuccess()
                        } else {
                            MaterialTheme.colorScheme.onError
                        },
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = if (passed) {
                    hubOnSuccessContainer()
                } else {
                    MaterialTheme.colorScheme.onErrorContainer
                },
            )
        }
    }
}

@Composable
private fun LoadingCard(text: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EvidenceCard(title: String, lines: List<String>, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            lines.forEach { line ->
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
