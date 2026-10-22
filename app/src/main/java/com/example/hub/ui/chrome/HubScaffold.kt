package com.example.hub.ui.chrome

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.hub.ui.theme.HubBrandTitle
import com.example.hub.ui.theme.HubColors
import java.util.Locale

@Composable
fun HubScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
        ),
        topBar = topBar,
        content = content,
    )
}

@Composable
fun HubTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    branded: Boolean = false,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .padding(vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                navigationIcon()
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (branded) title.uppercase(Locale.ROOT) else title,
                        style = if (branded) HubBrandTitle else MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = if (branded) 1 else Int.MAX_VALUE,
                        overflow = if (branded) TextOverflow.Ellipsis else TextOverflow.Clip,
                    )
                }
                actions()
            }
            if (branded) {
                HubBrandRule(modifier = Modifier.padding(top = 6.dp))
            }
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                        .padding(start = 20.dp, end = 20.dp, top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun HubBrandRule(modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(10.dp),
    ) {
        val y = size.height / 2f
        val center = size.width / 2f
        val gap = 5.dp.toPx()
        val stroke = 1.dp.toPx()
        drawLine(
            color = HubColors.Gold,
            start = Offset(0f, y),
            end = Offset(center - gap, y),
            strokeWidth = stroke,
        )
        drawLine(
            color = HubColors.Gold,
            start = Offset(center + gap, y),
            end = Offset(size.width, y),
            strokeWidth = stroke,
        )
        val diamond = 3.dp.toPx()
        val path = Path().apply {
            moveTo(center, y - diamond)
            lineTo(center + diamond, y)
            lineTo(center, y + diamond)
            lineTo(center - diamond, y)
            close()
        }
        drawPath(path, HubColors.GoldLight)
    }
}
