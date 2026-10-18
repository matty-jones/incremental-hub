package com.example.hub.session

/**
 * Process-scoped handle so the activity can retain a live WebView across an
 * unavoidable configuration recreation. A normal fold does not use this,
 * because the activity handles those changes itself.
 */
object SessionRuntime {
    @Volatile
    var controller: SessionController? = null
}
