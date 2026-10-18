package com.example.hub

import android.app.Activity
import android.os.Bundle
import android.widget.FrameLayout

/**
 * Empty host for WebView instrumentation. It does not construct a session,
 * so the tests can run when the device WebView lacks multi-profile support.
 */
class WebViewProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(FrameLayout(this))
    }
}
