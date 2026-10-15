package com.example.hub.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowMetricsCalculator
import com.example.hub.session.SessionState
import com.example.hub.ui.theme.HubWideWidth

/**
 * Fold-aware layout. The WebView stays full-bleed; status and navigation
 * bars are left to the chrome drawn on top. IME padding still applies while
 * a session is open, and a vertical hinge still reserves start padding.
 *
 * @param state the current session state — used to skip IME when closed
 * @param content the content to lay out
 * @param modifier modifier for the outer box
 */
@Composable
fun FoldAwareLayout(
    state: SessionState,
    content: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val foldingFeature by produceState<FoldingFeature?>(initialValue = null, context) {
        val activity = context.findActivity() ?: return@produceState
        runCatching {
            WindowInfoTracker.getOrCreate(activity)
                .windowLayoutInfo(activity)
                .collect { info ->
                    value = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
                }
        }
    }

    val hingePadding: Dp = foldingFeature?.let { feature ->
        if (feature.orientation == FoldingFeature.Orientation.VERTICAL) {
            with(density) { feature.bounds.width().toDp() }
        } else {
            0.dp
        }
    } ?: 0.dp

    val baseModifier = if (state is SessionState.Closed) {
        Modifier.fillMaxSize()
    } else {
        Modifier
            .fillMaxSize()
            .imePadding()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(start = hingePadding),
    ) {
        Box(
            modifier = baseModifier.fillMaxSize(),
        ) {
            content()
        }
    }
}

/**
 * Returns true when the current window is wide enough to host a side-panel
 * menu alongside the WebView without crowding.
 *
 * Uses [HubWideWidth], the same threshold as the in-game sheet.
 */
@Composable
fun isWideWindow(): Boolean {
    val context = LocalContext.current
    val metrics = WindowMetricsCalculator.getOrCreate()
        .computeMaximumWindowMetrics(context)
    val widthDp = with(LocalDensity.current) { metrics.bounds.width().toDp() }
    return widthDp >= HubWideWidth
}

internal fun pxToDp(px: Int, density: Float): Float {
    if (density <= 0f) return px.toFloat()
    return px / density
}

internal fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
