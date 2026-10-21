package com.example.hub.ui.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.hub.ui.theme.HubSheetCorner
import com.example.hub.ui.theme.HubWideWidth

/**
 * Where a compact overlay panel attaches. Wide windows ignore this and use a side sheet.
 */
enum class SheetEdge {
    Bottom,
    Top,
}

/**
 * Scrim plus a rounded panel. Compact windows use [edge].
 * Width at or above [HubWideWidth] gets a side sheet.
 */
@Composable
fun OverlaySheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissOnScrim: Boolean = true,
    edge: SheetEdge = SheetEdge.Bottom,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val wide = maxWidth >= HubWideWidth
        val fromTop = !wide && edge == SheetEdge.Top
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
                .clickable(
                    enabled = dismissOnScrim,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                )
                .semantics { contentDescription = "Dismiss" },
        )
        val shape = when {
            wide -> RoundedCornerShape(topEnd = HubSheetCorner, bottomEnd = HubSheetCorner)
            fromTop -> RoundedCornerShape(bottomStart = HubSheetCorner, bottomEnd = HubSheetCorner)
            else -> RoundedCornerShape(topStart = HubSheetCorner, topEnd = HubSheetCorner)
        }
        val alignment = when {
            wide -> Alignment.CenterStart
            fromTop -> Alignment.TopCenter
            else -> Alignment.BottomCenter
        }
        Surface(
            modifier = Modifier
                .align(alignment)
                .then(
                    if (wide) {
                        Modifier.width(320.dp).fillMaxHeight()
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = maxHeight * 0.85f)
                    },
                )
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {})
                },
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 8.dp,
        ) {
            val insetModifier = when {
                wide -> Modifier.statusBarsPadding().navigationBarsPadding()
                fromTop -> Modifier.windowInsetsPadding(
                    WindowInsets.displayCutout
                        .only(WindowInsetsSides.Top)
                        .union(WindowInsets.statusBars.only(WindowInsetsSides.Top)),
                )
                else -> Modifier.navigationBarsPadding()
            }
            Column(
                modifier = Modifier
                    .then(insetModifier)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!wide && !fromTop) {
                    SheetHandle()
                }
                content()
                if (fromTop) {
                    SheetHandle()
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.SheetHandle() {
    Box(
        modifier = Modifier
            .align(Alignment.CenterHorizontally)
            .size(width = 36.dp, height = 4.dp)
            .background(
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                RoundedCornerShape(2.dp),
            ),
    )
}
