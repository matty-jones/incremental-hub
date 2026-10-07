package com.example.hub.diagnostics

/**
 * Immutable snapshot of WebView feature support on the current device.
 *
 * @param multiProfile       whether [androidx.webkit.WebViewFeature.MULTI_PROFILE] is supported
 * @param documentStartScript whether [androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT] is supported
 * @param webMessageListener whether [androidx.webkit.WebViewFeature.WEB_MESSAGE_LISTENER] is supported
 * @param allSupported       true when all three required features are supported
 * @param webViewPackage     package name of the installed Android System WebView, or null if unavailable
 * @param webViewVersion     version name of the installed Android System WebView, or null if unavailable
 */
data class WebViewCapabilities(
    val multiProfile: Boolean,
    val documentStartScript: Boolean,
    val webMessageListener: Boolean,
    val allSupported: Boolean,
    val webViewPackage: String?,
    val webViewVersion: String?,
) {
    /** Convenience check: every required feature must be available. */
    val isSupported get() = allSupported && multiProfile && documentStartScript && webMessageListener
}
