package com.example.hub.session

import android.content.pm.ActivityInfo
import android.view.ViewGroup
import android.webkit.WebView

/**
 * Folding a Pixel Fold changes screen size, smallest width, screen layout,
 * and often density. Those changes must resize the existing WebView.
 *
 * [CONFIG_CHANGE_MASK] is the set handled by the activity so Android does not
 * destroy it. If a recreation still happens, the WebView is detached and
 * handed back instead of loading the page again.
 */
object DisplayContinuity {
    const val CONFIG_CHANGE_MASK: Int =
        ActivityInfo.CONFIG_ORIENTATION or
            ActivityInfo.CONFIG_SCREEN_SIZE or
            ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE or
            ActivityInfo.CONFIG_SCREEN_LAYOUT or
            ActivityInfo.CONFIG_DENSITY or
            ActivityInfo.CONFIG_UI_MODE or
            ActivityInfo.CONFIG_FONT_SCALE or
            ActivityInfo.CONFIG_KEYBOARD or
            ActivityInfo.CONFIG_KEYBOARD_HIDDEN or
            ActivityInfo.CONFIG_NAVIGATION

    fun covers(configChanges: Int): Boolean = configChanges and CONFIG_CHANGE_MASK == CONFIG_CHANGE_MASK

    /** A display-size change never requests a new document. */
    fun requestsNavigation(): Boolean = false
}

enum class Continuity {
    /** The same WebView was resized. In-memory JavaScript is intact. */
    LIVE_VIEW_RESIZED,

    /** The activity was recreated, but the same WebView instance was reattached. */
    RETAINED_ACROSS_RECREATION,

    /**
     * The process or view was destroyed after a capture completed.
     * The game can resume from that capture. Ticks after the capture are gone.
     */
    RESTORED_FROM_CAPTURE,

    /**
     * Android destroyed the process before a capture finished.
     * Progress since the previous save cannot be recovered.
     */
    UNSAVED_PROGRESS_LOST,
}

fun continuityFor(
    activityRecreated: Boolean,
    webViewRetained: Boolean,
    captureCompleted: Boolean,
): Continuity = when {
    !activityRecreated -> Continuity.LIVE_VIEW_RESIZED
    webViewRetained -> Continuity.RETAINED_ACROSS_RECREATION
    captureCompleted -> Continuity.RESTORED_FROM_CAPTURE
    else -> Continuity.UNSAVED_PROGRESS_LOST
}

fun detachForRetain(view: WebView): WebView {
    (view.parent as? ViewGroup)?.removeView(view)
    return view
}
