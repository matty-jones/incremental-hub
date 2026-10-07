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
import com.example.hub.games.GameQualifier
import com.example.hub.games.QualificationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val TAG = "GameQualificationScreen"

/**
 * Host composable for the candidate-game qualification spike.
 *
 * On launch it runs [GameQualifier.qualify] for both Shark Incremental
 * and FE000000, then displays the results as per-game cards with
 * PASSED/FAILED badges, evidence lines, and a blocker banner if either
 * game has a showstopper issue.
 *
 * Callers supply [onRefresh] to trigger a re-run of the qualification.
 */
@Composable
fun GameQualificationScreen(onRefresh: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var sharkResult by remember { mutableStateOf<QualificationResult?>(null) }
    var fe000000Result by remember { mutableStateOf<QualificationResult?>(null) }
    var reportPath by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }

    LaunchedEffect(context) {
        coroutineScope.launch {
            val results = runQualification(context)
            sharkResult = results["shark-incremental"]
            fe000000Result = results["fe000000"]
            reportPath = writeReport(context, listOfNotNull(sharkResult, fe000000Result))
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
                text = "Candidate Game Qualification",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )

            Text(
                text = "Proves capture→export→fresh-profile import→verify round-trip on-device.",
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
                // Shark Incremental card
                item {
                    sharkResult?.let { result ->
                        GameResultCard(result = result)
                    } ?: LoadingCard("Shark Incremental …")
                }
                item {
                    fe000000Result?.let { result ->
                        GameResultCard(result = result)
                    } ?: sharkResult?.let { LoadingCard("FE000000 …") }
                }
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Divider()
                    Spacer(modifier = Modifier.height(8.dp))
                }
                // Blocker banner
                item {
                    val blocker = listOfNotNull(
                        sharkResult?.blocker,
                        fe000000Result?.blocker,
                    ).firstOrNull()
                    if (blocker != null) {
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
                                    text = blocker,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                    }
                }
                item {
                    if (sharkResult != null || fe000000Result != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Divider()
                        Spacer(modifier = Modifier.height(8.dp))
                    }
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
                            message = "Re-running qualification …",
                            duration = SnackbarDuration.Indefinite,
                        )
                        if (snackbarResult == SnackbarResult.Dismissed ||
                            snackbarResult == SnackbarResult.ActionPerformed
                        ) {
                            val results = runQualification(context)
                            sharkResult = results["shark-incremental"]
                            fe000000Result = results["fe000000"]
                            reportPath = writeReport(context, listOfNotNull(sharkResult, fe000000Result))
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
 * Run qualification for both games sequentially on [Dispatchers.Main].
 */
private suspend fun runQualification(context: android.content.Context): Map<String, QualificationResult> =
    withContext(Dispatchers.Main) {
        val profileStore = ProfileStore.getInstance()
        val qualifier = GameQualifier(context)

        val results = mutableMapOf<String, QualificationResult>()

        val sharkResult = runCatching {
            qualifier.qualify(
                profileStore = profileStore,
                adapter = com.example.hub.games.SharkIncrementalAdapter(),
                launchUrl = "https://mrredshark77.github.io/shark-incremental/",
                gameId = "shark-incremental",
                displayName = "Shark Incremental",
            )
        }.getOrElse { e ->
            com.example.hub.games.QualificationResult(
                gameId = "shark-incremental",
                displayName = "Shark Incremental",
                stages = listOf(
                    com.example.hub.games.StageResult(
                        stage = "exception",
                        passed = false,
                        evidence = listOf("Exception: ${e.message}"),
                    ),
                ),
                blocker = "Exception: ${e.message}",
            )
        }
        results["shark-incremental"] = sharkResult

        val fe000000Result = runCatching {
            qualifier.qualify(
                profileStore = profileStore,
                adapter = com.example.hub.games.Fe000000Adapter(),
                launchUrl = "https://dan-simon.github.io/misc/fe000000/",
                gameId = "fe000000",
                displayName = "FE000000",
            )
        }.getOrElse { e ->
            com.example.hub.games.QualificationResult(
                gameId = "fe000000",
                displayName = "FE000000",
                stages = listOf(
                    com.example.hub.games.StageResult(
                        stage = "exception",
                        passed = false,
                        evidence = listOf("Exception: ${e.message}"),
                    ),
                ),
                blocker = "Exception: ${e.message}",
            )
        }
        results["fe000000"] = fe000000Result

        results
    }

/**
 * Write the qualification report for all results to [android.content.Context.filesDir].
 */
private fun writeReport(context: android.content.Context, results: List<QualificationResult>): String? {
    return runCatching {
        val file = java.io.File(context.filesDir, "qualification_report.json")
        file.parentFile?.mkdirs()
        val json = JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("gameCount", results.size)
            put("passedCount", results.count { it.passed })
            put("results", JSONObject().apply {
                results.forEach { r ->
                    put(r.gameId, JSONObject(r.toJson()))
                }
            })
        }
        file.writeText(json.toString(2))
        file.absolutePath
    }.getOrNull()
}

@Composable
private fun GameResultCard(result: QualificationResult, modifier: Modifier = Modifier) {
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
                    text = result.displayName,
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
                StageEvidenceRow(stage = stage)
            }
            if (result.totalDurationMs > 0L) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Total duration: ${result.totalDurationMs}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StageEvidenceRow(stage: com.example.hub.games.StageResult, modifier: Modifier = Modifier) {
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
