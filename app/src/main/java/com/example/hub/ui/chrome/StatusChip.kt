package com.example.hub.ui.chrome

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.hub.ui.theme.HubChipCorner
import com.example.hub.ui.theme.hubOnSuccessContainer
import com.example.hub.ui.theme.hubOnWarningContainer
import com.example.hub.ui.theme.hubSuccessContainer
import com.example.hub.ui.theme.hubWarningContainer

enum class HubChipKind {
    Ready,
    New,
    Waiting,
    Warning,
    Neutral,
}

@Composable
fun StatusChip(
    text: String,
    modifier: Modifier = Modifier,
    kind: HubChipKind = HubChipKind.Neutral,
) {
    val container = when (kind) {
        HubChipKind.Ready -> hubSuccessContainer()
        HubChipKind.Warning -> hubWarningContainer()
        HubChipKind.New, HubChipKind.Waiting, HubChipKind.Neutral ->
            MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when (kind) {
        HubChipKind.Ready -> hubOnSuccessContainer()
        HubChipKind.Warning -> hubOnWarningContainer()
        HubChipKind.New, HubChipKind.Waiting, HubChipKind.Neutral ->
            MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = modifier,
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(HubChipCorner),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
