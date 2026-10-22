package com.example.hub.ui

import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.example.hub.ui.theme.HubWideWidth

/**
 * Trimps frame for the Pixel Fold. The closed cover is forced to landscape.
 * Bottom padding shortens the WebView; it does not slide the page upward.
 *
 * Felix bottom radii are 91px on the cover and 48px on the inner panel, but
 * that rise is only at the corner. The words on the bar start about 45px in
 * and about 20px above the bar's own bottom, where the curve is already
 * nearly flat. The insets below leave the pause icon's baseline in the curve
 * and keep Save, the menu words, and the timer digits clear.
 */
internal object TrimpsFrame {
    const val GAME_ID = "trimps"
    const val COVER_BOTTOM_INSET_PX = 28
    const val INNER_BOTTOM_INSET_PX = 8

    fun requestsLandscape(gameId: String, smallestScreenWidthDp: Int): Boolean =
        gameId == GAME_ID && isCoverPosture(smallestScreenWidthDp)

    fun bottomInsetPx(gameId: String, smallestScreenWidthDp: Int): Int {
        if (gameId != GAME_ID) return 0
        return if (isCoverPosture(smallestScreenWidthDp)) {
            COVER_BOTTOM_INSET_PX
        } else {
            INNER_BOTTOM_INSET_PX
        }
    }
}

/** Cover versus inner panel. Width changes when the cover rotates; this does not. */
internal fun isCoverPosture(smallestScreenWidthDp: Int): Boolean =
    smallestScreenWidthDp < HubWideWidth.value.toInt()

@Composable
internal fun TrimpsCoverOrientation(gameId: String) {
    val context = LocalContext.current
    val smallestWidth = LocalConfiguration.current.smallestScreenWidthDp
    DisposableEffect(gameId, smallestWidth) {
        val activity = context.findActivity()
        activity?.requestedOrientation = if (TrimpsFrame.requestsLandscape(gameId, smallestWidth)) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}
