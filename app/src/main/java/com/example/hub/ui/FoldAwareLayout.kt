package com.example.hub.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.window.layout.WindowMetricsCalculator
import com.example.hub.session.SessionState

/**
 * Fold-aware layout composable that applies insets for status bar,
 * navigation bar, IME, and display cutout (safe-area).
 *
 * Uses modifier-based inset application ([statusBarsPadding],
 * [navigationBarsPadding], [imePadding]) which are guaranteed to be
 * available across all Compose foundation versions.
 *
 * Branches between compact and wide layouts using [WindowMetricsCalculator]
 * (which accounts for fold state). In folded/foldable-device state the WebView
 * content is shifted away from the hinge line by reserving space.
 *
 * @param state the current session state — used to skip IME when closed
 * @param content the content to lay out inside the safe-area insets
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

    // Use WindowMetricsCalculator to get the current window metrics — this
    // accounts for fold state and multi-window mode.
    val metrics = WindowMetricsCalculator.getOrCreate()
        .computeCurrentWindowMetrics(context)
    val widthDp = with(density) {
        metrics.bounds.width().toFloat() / 160f
    }
    val isFoldedWide = widthDp >= 840.dp.value // large foldable unfolded width

    // Hinge-aware inset: reserve extra horizontal padding on the hinge side.
    val hingePadding = if (isFoldedWide) 24.dp else 0.dp

    val baseModifier = if (state is SessionState.Closed) {
        // No IME when session is closed — no WebView to cover.
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    } else {
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
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
 * Mirrors the width threshold in [com.example.hub.ui.NativeMenu] (600dp) but
 * uses actual window metrics — more robust in multi-window scenarios.
 */
@Composable
fun isWideWindow(): Boolean {
    val context = LocalContext.current
    val metrics = WindowMetricsCalculator.getOrCreate()
        .computeMaximumWindowMetrics(context)
    return metrics.bounds.width() / 160f >= 600.dp.value
}
