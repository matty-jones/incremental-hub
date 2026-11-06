package com.example.hub.ui.chrome

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import com.example.hub.ui.theme.HubReadingMaxWidth

fun Modifier.hubReadableWidth(): Modifier = this.widthIn(max = HubReadingMaxWidth).fillMaxWidth()

@Composable
fun HubReadableWidth(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.hubReadableWidth(), content = { content() })
    }
}

/** Lets long hashes, ids, and filenames wrap instead of running off the screen. */
fun wrapLongTokens(value: String): String {
    return value.lineSequence().joinToString("\n") { line ->
        line.split(' ').joinToString(" ") { word ->
            if (word.length <= 24) word else word.chunked(16).joinToString("\u200b")
        }
    }
}

@Composable
fun HubWrappedText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
) {
    Text(
        text = wrapLongTokens(text),
        modifier = modifier.fillMaxWidth(),
        style = style,
        color = color,
        softWrap = true,
        overflow = TextOverflow.Clip,
    )
}
