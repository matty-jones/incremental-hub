package com.example.hub.ui

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.hub.diagnostics.TestResult
import com.example.hub.diagnostics.WebViewCapabilities
import com.example.hub.ui.theme.hubOnSuccessContainer
import com.example.hub.ui.theme.hubSuccess
import com.example.hub.ui.theme.hubSuccessContainer
import kotlinx.coroutines.launch

/**
 * Displays the results of [WebViewCapabilities] detection plus an optional
 * [ProfileIsolationTest][com.example.hub.diagnostics.ProfileIsolationTest] outcome.
 *
 * When [capabilities.allSupported] is false a remediation hint is shown.
 */
@Composable
fun CapabilityScreen(
    capabilities: WebViewCapabilities,
    isolationResult: TestResult?,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        modifier = modifier,
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
                text = "WebView Capability Report",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                item {
                    FeatureCard(
                        label = "MULTI_PROFILE",
                        supported = capabilities.multiProfile,
                    )
                }
                item {
                    FeatureCard(
                        label = "DOCUMENT_START_SCRIPT",
                        supported = capabilities.documentStartScript,
                    )
                }
                item {
                    FeatureCard(
                        label = "WEB_MESSAGE_LISTENER",
                        supported = capabilities.webMessageListener,
                    )
                }
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Divider()
                    Spacer(modifier = Modifier.height(8.dp))
                }
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "WebView Package",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = capabilities.webViewPackage ?: "unknown",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "WebView Version",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = capabilities.webViewVersion ?: "unknown",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Divider()
                    Spacer(modifier = Modifier.height(8.dp))
                }
                item {
                    isolationResult?.let { result ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "Profile Isolation Test",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                val color = if (result.passed) {
                                    hubSuccess()
                                } else {
                                    MaterialTheme.colorScheme.error
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = if (result.passed) "PASSED" else "FAILED",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = color,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = result.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                item {
                    if (!capabilities.allSupported) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                            ),
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "REMEDIATION REQUIRED",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                val missing = buildList {
                                    if (!capabilities.multiProfile) add("MULTI_PROFILE")
                                    if (!capabilities.documentStartScript) add("DOCUMENT_START_SCRIPT")
                                    if (!capabilities.webMessageListener) add("WEB_MESSAGE_LISTENER")
                                }
                                Text(
                                    text = "Missing features: ${missing.joinToString()}. " +
                                        "Update Android System WebView to the latest version " +
                                        "or use a fallback path that does not depend on these features.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            androidx.compose.material3.Button(
                onClick = {
                    coroutineScope.launch {
                        val result = snackbarHostState.showSnackbar(
                            message = "Refreshing detection …",
                            duration = SnackbarDuration.Short,
                        )
                        if (result == SnackbarResult.Dismissed) {
                            onRefresh()
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
            ) {
                Text("Refresh")
            }
        }
    }
}

@Composable
private fun FeatureCard(label: String, supported: Boolean, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            val badgeColor = if (supported) {
                hubSuccessContainer()
            } else {
                MaterialTheme.colorScheme.errorContainer
            }
            val badgeTextColor = if (supported) {
                hubOnSuccessContainer()
            } else {
                MaterialTheme.colorScheme.onErrorContainer
            }
            androidx.compose.material3.Surface(
                color = badgeColor,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = if (supported) "Supported" else "Missing",
                    color = badgeTextColor,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}
