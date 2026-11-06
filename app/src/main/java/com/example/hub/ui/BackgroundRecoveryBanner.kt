package com.example.hub.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.hub.session.BackgroundNotice
import com.example.hub.ui.chrome.HubOutlinedButton
import com.example.hub.ui.chrome.HubWrappedText
import com.example.hub.ui.chrome.hubReadableWidth

@Composable
fun BackgroundRecoveryBanner(
    notice: BackgroundNotice,
    onCatchUp: () -> Unit,
    onContinue: () -> Unit,
    onInspect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .testTag("background-recovery"),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier
                .hubReadableWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HubWrappedText(notice.headline, style = MaterialTheme.typography.titleMedium)
            HubWrappedText(notice.detail, style = MaterialTheme.typography.bodyMedium)
            if (notice.offerCatchUp) {
                HubOutlinedButton(
                    onClick = onCatchUp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("background-catch-up"),
                ) {
                    androidx.compose.material3.Text("Run offline progression")
                }
            }
            if (notice.offerContinue) {
                HubOutlinedButton(
                    onClick = onContinue,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("background-continue"),
                ) {
                    androidx.compose.material3.Text("Continue current session")
                }
            }
            if (notice.offerInspect && !notice.checkpointId.isNullOrBlank()) {
                HubOutlinedButton(
                    onClick = onInspect,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("background-inspect"),
                ) {
                    androidx.compose.material3.Text("Inspect recovery checkpoint")
                }
            }
        }
    }
}
