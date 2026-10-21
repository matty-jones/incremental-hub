package com.example.hub.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.hub.BuildConfig
import com.example.hub.ui.chrome.HubCard
import com.example.hub.ui.chrome.HubPrimaryButton
import com.example.hub.ui.chrome.HubScaffold
import com.example.hub.ui.chrome.HubTopBar

/**
 * Version, the workstation update check, and the debug-tools switch.
 * The check uses the same lookup that runs when the hub opens.
 */
@Composable
fun SettingsScreen(
    debugTools: Boolean,
    checkingForUpdate: Boolean,
    updateStatus: String?,
    onDebugToolsChange: (Boolean) -> Unit,
    onCheckForUpdate: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HubScaffold(
        modifier = modifier,
        topBar = {
            HubTopBar(
                title = "Settings",
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = "Back" },
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HubCard {
                Text(
                    text = "Version ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "Build ${BuildConfig.VERSION_CODE}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    text = "Looks on the workstation for a newer install. This is the same check that runs when the hub opens.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
                HubPrimaryButton(
                    onClick = onCheckForUpdate,
                    enabled = !checkingForUpdate,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .testTag("check-update"),
                ) {
                    Text(if (checkingForUpdate) "Checking" else "Check for update")
                }
                if (!updateStatus.isNullOrBlank()) {
                    Text(
                        text = updateStatus,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            HubCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Debug tools",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "Shows Inspect Save, Safe Launch, and Recover from Snapshot on each game.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Switch(
                        checked = debugTools,
                        onCheckedChange = onDebugToolsChange,
                        modifier = Modifier
                            .testTag("debug-tools")
                            .semantics { contentDescription = "Debug tools" },
                    )
                }
            }
        }
    }
}
