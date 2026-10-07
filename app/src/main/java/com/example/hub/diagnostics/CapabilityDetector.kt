package com.example.hub.diagnostics

import android.content.Context
import android.content.pm.PackageManager
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Queries the three required WebView features on the target device and writes a
 * machine-parseable JSON report to [Context.filesDir]/capability_report.json.
 *
 * Thread safety: [detect] may be called from any thread. It is idempotent — repeated
 * calls re-query and overwrite the report file.
 */
class CapabilityDetector(private val context: Context) {

    /**
     * Run feature detection and persist the report.
     *
     * @return a non-null [WebViewCapabilities] populated from the current device state.
     *   Booleans may be false (feature unsupported); package/version reflect the installed
     *   WebView or null if [WebViewCompat.getCurrentWebViewPackage] returns null.
     */
    fun detect(): WebViewCapabilities {
        val packageInfo = runCatching {
            WebViewCompat.getCurrentWebViewPackage(context)
        }.getOrNull()

        val multiProfile = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
        val documentStartScript = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        val webMessageListener = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)

        val allSupported = multiProfile && documentStartScript && webMessageListener

        val capabilities = WebViewCapabilities(
            multiProfile = multiProfile,
            documentStartScript = documentStartScript,
            webMessageListener = webMessageListener,
            allSupported = allSupported,
            webViewPackage = packageInfo?.packageName,
            webViewVersion = packageInfo?.versionName,
        )

        writeReport(context, capabilities)
        return capabilities
    }

    /**
     * Write the capability report as JSON to [context.filesDir]/capability_report.json.
     *
     * Failure to write is logged but never throws — the in-memory [WebViewCapabilities]
     * is still returned by [detect].
     */
    private fun writeReport(context: Context, capabilities: WebViewCapabilities) {
        val file = File(context.filesDir, "capability_report.json")
        val json = JSONObject().apply {
            put("webViewPackage", capabilities.webViewPackage ?: "unknown")
            put("webViewVersion", capabilities.webViewVersion ?: "unknown")
            put("multiProfile", capabilities.multiProfile)
            put("documentStartScript", capabilities.documentStartScript)
            put("webMessageListener", capabilities.webMessageListener)
            put("allSupported", capabilities.allSupported)
            put("isSupported", capabilities.isSupported)
        }
        try {
            file.parentFile?.mkdirs()
            file.writeText(json.toString(2))
        } catch (e: IOException) {
            android.util.Log.e(TAG, "Failed to write capability report", e)
        }
    }

    companion object {
        private const val TAG = "CapabilityDetector"
    }
}
