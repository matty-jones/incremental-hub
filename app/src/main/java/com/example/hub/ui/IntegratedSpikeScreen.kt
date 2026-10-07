package com.example.hub.ui

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
import androidx.webkit.WebViewFeature
import com.example.hub.integrated.IntegratedSpike
import com.example.hub.integrated.IntegratedSpikeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val TAG = "IntegratedSpikeScreen"

/**
 * Host composable for the integrated play-capture-restore spike (S04).
 *
 * On launch it runs [IntegratedSpike.runSpike], then displays the result
 * as per-stage PASS/FAIL cards with evidence lines, profile metadata,
 * a blocker banner, and a critical destroy→survive invariant evidence row.
 *
 * Callers supply [onRefresh] to trigger a re-run of the spike.
 */
@Composable
fun IntegratedSpikeScreen(onRefresh: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var result by remember { mutableStateOf<IntegratedSpikeResult?>(null) }
    var reportPath by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }

    LaunchedEffect(context) {
        coroutineScope.launch {
            val spikeResult = runSpike(context)
            result = spikeResult
            reportPath = writeReport(context, spikeResult)
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
                text = "Integrated Spike (S04)",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )

            Text(
                text = "Proves WebView.destroy() preserves profile localStorage and adapter import round-trip on-device.",
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
                // Profile metadata card
                item {
                    if (result != null) {
                        ProfileMetaCard(
                            sourceProfileName = result!!.sourceProfileName,
                            capturedSaveLength = result!!.capturedSaveLength,
                            freshProfileName = result!!.freshProfileName,
                        )
                    } else {
                        LoadingCard("Integrated Spike …")
                    }
                }

                // Per-stage cards
                item {
                    if (result != null) {
                        StageResultCard(result = result!!)
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Divider()
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Blocker banner
                item {
                    if (result?.blocker != null) {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                            ),
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "BLOCKER",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = result!!.blocker!!,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                    }
                }

                item {
                    if (result != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Divider()
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                // Critical destroy→survive invariant evidence row
                item {
                    if (result != null) {
                        DestroySurviveCard(
                            destroyedWebView = result!!.destroyedWebView,
                            profileDataSurvived = result!!.profileDataSurvived,
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Divider()
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Report path card
                item {
                    reportPath?.let { path ->
                        EvidenceCard(
                            title = "Report",
                            lines = listOf("Path: $path"),
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
                            message = "Re-running integrated spike …",
                            duration = SnackbarDuration.Indefinite,
                        )
                        if (snackbarResult == SnackbarResult.Dismissed ||
                            snackbarResult == SnackbarResult.ActionPerformed
                        ) {
                            val spikeResult = runSpike(context)
                            result = spikeResult
                            reportPath = writeReport(context, spikeResult)
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

/**
 * Run the integrated spike on [Dispatchers.Main].
 */
private suspend fun runSpike(context: android.content.Context): IntegratedSpikeResult =
    withContext(Dispatchers.Main) {
        runCatching {
            check(WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                "WebView MULTI_PROFILE is not supported on this runtime"
            }

            val profileStore = ProfileStore.getInstance()
            val spike = IntegratedSpike(context)
            spike.runSpike(profileStore)
        }.getOrElse { e ->
            IntegratedSpikeResult(
                sourceProfileName = "N/A",
                capturedSaveLength = 0,
                destroyedWebView = false,
                profileDataSurvived = false,
                freshProfileName = "N/A",
                importVerified = false,
                blocker = "Exception: ${e.message}",
                stages = listOf(
                    com.example.hub.integrated.IntegratedSpikeStage(
                        stage = "exception",
                        passed = false,
                        evidence = listOf("Exception: ${e.message}"),
                    ),
                ),
            )
        }
    }

/**
 * Write the spike result to [android.content.Context.filesDir] as a JSON report.
 */
private fun writeReport(context: android.content.Context, result: IntegratedSpikeResult): String? {
    return runCatching {
        val file = java.io.File(context.filesDir, "integrated_spike_report.json")
        file.parentFile?.mkdirs()
        file.writeText(result.toJson())
        file.absolutePath
    }.getOrNull()
}

@Composable
private fun ProfileMetaCard(
    sourceProfileName: String,
    capturedSaveLength: Int,
    freshProfileName: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Profile Metadata",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Source: $sourceProfileName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Captured save length: $capturedSaveLength chars",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Fresh profile: $freshProfileName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun StageResultCard(result: IntegratedSpikeResult, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (result.passed) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Integrated Spike Result",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (result.passed) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    },
                )
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    color = if (result.passed) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = if (result.passed) "PASSED" else "FAILED",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (result.passed) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onError
                        },
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            result.stages.forEach { stage ->
                IntegratedSpikeStageRow(stage = stage)
            }
        }
    }
}

@Composable
private fun IntegratedSpikeStageRow(
    stage: com.example.hub.integrated.IntegratedSpikeStage,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val badgeColor = if (stage.passed) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.error
        }
        Surface(
            color = badgeColor,
            shape = MaterialTheme.shapes.extraSmall,
            modifier = Modifier.padding(end = 4.dp),
        ) {
            Text(
                text = if (stage.passed) "✓" else "✗",
                style = MaterialTheme.typography.labelSmall,
                color = if (stage.passed) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onError
                },
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
        Text(
            text = stage.stage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
    stage.evidence.forEach { line ->
        Spacer(modifier = Modifier.height(1.dp))
        Text(
            text = "  $line",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(modifier = Modifier.height(2.dp))
}

@Composable
private fun DestroySurviveCard(
    destroyedWebView: Boolean,
    profileDataSurvived: Boolean,
    modifier: Modifier = Modifier,
) {
    val isPass = destroyedWebView && profileDataSurvived
    Card(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isPass) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Critical Invariant: Destroy→Survive",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isPass) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    },
                )
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    color = if (isPass) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = if (isPass) "PASSED" else "FAILED",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isPass) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onError
                        },
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val badgeColor = if (destroyedWebView) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
                Surface(
                    color = badgeColor,
                    shape = MaterialTheme.shapes.extraSmall,
                    modifier = Modifier.padding(end = 4.dp),
                ) {
                    Text(
                        text = if (destroyedWebView) "✓" else "✗",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (destroyedWebView) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onError
                        },
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
                Text(
                    text = "webView.destroy() succeeded: $destroyedWebView",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val badgeColor = if (profileDataSurvived) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
                Surface(
                    color = badgeColor,
                    shape = MaterialTheme.shapes.extraSmall,
                    modifier = Modifier.padding(end = 4.dp),
                ) {
                    Text(
                        text = if (profileDataSurvived) "✓" else "✗",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (profileDataSurvived) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onError
                        },
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
                Text(
                    text = "profileDataSurvived: $profileDataSurvived",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "This is the critical data-preservation invariant proof: WebView.destroy() releases the WebView but does NOT wipe the profile's localStorage.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
