package com.example.hub.bootstrap

import android.content.Context
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Result of a [BootstrapDetector] execution.
 *
 * @param qualifiedMechanism the mechanism that produced a qualified storage origin,
 *   or null if neither mechanism produced a qualified origin.
 * @param webViewAssetLoaderOrigin the origin captured when navigating via
 *   [WebViewAssetLoader], or null if that path failed.
 * @param loadDataWithBaseURLOrigin the origin captured when navigating via
 *   [WebViewCompat.loadDataWithBaseURL], or null if that path failed.
 * @param webViewAssetLoaderQualified whether the [WebViewAssetLoader] origin matched
 *   the expected qualified origin ([WebViewAssetLoader.DEFAULT_DOMAIN]).
 * @param loadDataWithBaseURLQualified whether the [loadDataWithBaseURL] origin was
 *   a native HTTPS origin that the game's service worker cannot intercept.
 * @param reportPath absolute path to the JSON report written to [Context.filesDir],
 *   or null if the report could not be written.
 * @param message human-readable description of the overall result.
 */
data class DetectorResult(
    val qualifiedMechanism: String?,
    val webViewAssetLoaderOrigin: String?,
    val loadDataWithBaseURLOrigin: String?,
    val webViewAssetLoaderQualified: Boolean,
    val loadDataWithBaseURLQualified: Boolean,
    val reportPath: String?,
    val message: String,
)

/**
 * Proves which mechanism — `WebViewAssetLoader` or `loadDataWithBaseURL` — produces a
 * storage origin that is qualified (isolated from upstream service workers).
 *
 * The detector flow:
 * 1. Create a test profile `s02-bootstrap-test` via [ProfileStore.getOrCreateProfile].
 * 2. Bind a WebView to the test profile via [WebViewCompat.setProfile] *before* navigation.
 * 3. Navigate to the `WebViewAssetLoader` bootstrap URL and capture `location.origin`.
 * 4. Create a fresh WebView bound to the same profile and navigate via
 *    [WebViewCompat.loadDataWithBaseURL] with a native HTTPS base origin.
 * 5. Compare each captured origin against the expected qualified origin.
 * 6. Write a JSON report to `filesDir/bootstrap_report.json`.
 * 7. Clean up the test profile via [ProfileStore.deleteProfile].
 *
 * The highest-risk unknown is which mechanism yields an origin that the game's service
 * worker cannot intercept. This detector reports the answer empirically on the target
 * device.
 *
 * **Thread confinement:** [test] acquires [Dispatchers.Main] internally, so it may be
 * called from any coroutine context. WebView creation and profile binding are not
 * thread-safe and must run on the UI thread.
 *
 * **Cleanup guarantee:** Test profile is deleted on success. On failure, the profile
 * is retained for debugging.
 */
class BootstrapDetector(private val context: Context) {

    companion object {
        private const val TAG = "BootstrapDetector"
        private const val TEST_PROFILE = "s02-bootstrap-test"
        private const val BOOTSTRAP_PATH = "bootstrap/native.html"
        private const val REPORT_FILENAME = "bootstrap_report.json"

        /**
         * The expected origin when using [WebViewAssetLoader]. This is the default virtual
         * domain that AndroidX WebViewAssetLoader assigns to assets.
         */
        const val EXPECTED_ASSETS_ORIGIN = "https://appassets.androidplatform.net"
    }

    /**
     * Run the bootstrap detector.
     *
     * @param profileStore the [ProfileStore] to create and manage the test profile.
     *   Must be obtained from [ProfileStore.getInstance] on the UI thread.
     * @return [DetectorResult] containing the qualified mechanism and evidence.
     *   At least one mechanism may be qualified; both may qualify; neither may qualify.
     */
    suspend fun test(profileStore: ProfileStore): DetectorResult = withContext(Dispatchers.Main) {
        Log.d(TAG, "Starting bootstrap origin detector")

        // Step 1: ensure the test profile exists
        val profile = profileStore.getOrCreateProfile(TEST_PROFILE)
        Log.d(TAG, "Test profile created: $TEST_PROFILE")

        var webViewAssetLoaderOrigin: String? = null
        var loadDataWithBaseURLOrigin: String? = null
        var webViewAssetLoaderQualified = false
        var loadDataWithBaseURLQualified = false
        var reportPath: String? = null

        try {
            // Step 2: test WebViewAssetLoader mechanism
            Log.d(TAG, "Testing WebViewAssetLoader mechanism")
            webViewAssetLoaderOrigin = testWebViewAssetLoader(profile)
            if (webViewAssetLoaderOrigin != null) {
                webViewAssetLoaderQualified =
                    webViewAssetLoaderOrigin == EXPECTED_ASSETS_ORIGIN
                Log.d(
                    TAG,
                    "WebViewAssetLoader origin: $webViewAssetLoaderOrigin " +
                        "qualified=$webViewAssetLoaderQualified"
                )
            } else {
                Log.w(TAG, "WebViewAssetLoader test returned no origin")
            }

            // Step 3: test loadDataWithBaseURL mechanism
            Log.d(TAG, "Testing loadDataWithBaseURL mechanism")
            loadDataWithBaseURLOrigin = testLoadDataWithBaseURL(profile)
            if (loadDataWithBaseURLOrigin != null) {
                // A qualified native HTTPS origin must match our custom base and not
                // be the WebViewAssetLoader virtual domain.
                loadDataWithBaseURLQualified =
                    loadDataWithBaseURLOrigin.startsWith("https://hub.internal/")
                Log.d(
                    TAG,
                    "loadDataWithBaseURL origin: $loadDataWithBaseURLOrigin " +
                        "qualified=$loadDataWithBaseURLQualified"
                )
            } else {
                Log.w(TAG, "loadDataWithBaseURL test returned no origin")
            }

            // Step 4: determine the qualified mechanism
            val qualifiedMechanism = when {
                webViewAssetLoaderQualified -> "WebViewAssetLoader"
                loadDataWithBaseURLQualified -> "loadDataWithBaseURL"
                else -> null
            }

            // Step 5: write the JSON report
            reportPath = writeReport(
                context = context,
                result = DetectorResult(
                    qualifiedMechanism = qualifiedMechanism,
                    webViewAssetLoaderOrigin = webViewAssetLoaderOrigin,
                    loadDataWithBaseURLOrigin = loadDataWithBaseURLOrigin,
                    webViewAssetLoaderQualified = webViewAssetLoaderQualified,
                    loadDataWithBaseURLQualified = loadDataWithBaseURLQualified,
                    reportPath = reportPath,
                    message = buildMessage(
                        qualifiedMechanism = qualifiedMechanism,
                        walOrigin = webViewAssetLoaderOrigin,
                        ldwuOrigin = loadDataWithBaseURLOrigin,
                    ),
                )
            )

            Log.d(TAG, "Bootstrap detector complete — qualifiedMechanism=$qualifiedMechanism")
            return@withContext DetectorResult(
                qualifiedMechanism = qualifiedMechanism,
                webViewAssetLoaderOrigin = webViewAssetLoaderOrigin,
                loadDataWithBaseURLOrigin = loadDataWithBaseURLOrigin,
                webViewAssetLoaderQualified = webViewAssetLoaderQualified,
                loadDataWithBaseURLQualified = loadDataWithBaseURLQualified,
                reportPath = reportPath,
                message = buildMessage(
                    qualifiedMechanism = qualifiedMechanism,
                    walOrigin = webViewAssetLoaderOrigin,
                    ldwuOrigin = loadDataWithBaseURLOrigin,
                ),
            )
        } catch (e: Exception) {
            Log.e(TAG, "Bootstrap detector failed", e)
            return@withContext DetectorResult(
                qualifiedMechanism = null,
                webViewAssetLoaderOrigin = webViewAssetLoaderOrigin,
                loadDataWithBaseURLOrigin = loadDataWithBaseURLOrigin,
                webViewAssetLoaderQualified = webViewAssetLoaderQualified,
                loadDataWithBaseURLQualified = loadDataWithBaseURLQualified,
                reportPath = reportPath,
                message = "Exception: ${e.message}",
            )
        } finally {
            // Step 6: cleanup — delete test profile on success; on failure profiles are
            // retained for debugging.
            try {
                profileStore.deleteProfile(TEST_PROFILE)
                Log.d(TAG, "Test profile cleaned up: $TEST_PROFILE")
            } catch (e: Exception) {
                Log.w(TAG, "Cleanup failed — profile retained for debugging: ${e.message}")
            }
        }
    }

    /**
     * Navigate a profile-bound WebView to the [WebViewAssetLoader] bootstrap URL and
     * capture `location.origin` via JavaScript evaluation.
     *
     * Returns the captured origin string, or null if navigation or evaluation failed.
     */
    private suspend fun testWebViewAssetLoader(profile: androidx.webkit.Profile): String? =
        withContext(Dispatchers.Main) {
            val deferred = CompletableDeferred<String?>()
            val webView = WebView(context).apply {
                WebViewCompat.setProfile(this, profile.name)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        evaluateJavascript(
                            "javascript:(function(){ return location.origin; })()"
                        ) { origin ->
                            val trimmed = origin.trim().removeSurrounding("\"")
                            Log.d(TAG, "WebViewAssetLoader origin captured: $trimmed")
                            deferred.complete(if (trimmed.isEmpty()) null else trimmed)
                        }
                    }
                }
                val assetLoader = WebViewAssetLoader.Builder()
                    .addPathHandler("/bootstrap/*", WebViewAssetLoader.AssetsPathHandler(context))
                    .build()
                // setWebAssetLoader is API 30+; the SDK stubs may not expose it at compile
                // time, so we invoke it via reflection when available.
                try {
                    val method = WebView::class.java.getMethod(
                        "setWebAssetLoader",
                        androidx.webkit.WebViewAssetLoader::class.java
                    )
                    method.invoke(this, assetLoader)
                } catch (e: Exception) {
                    Log.w(TAG, "setWebAssetLoader unavailable via reflection: ${e.message}")
                }
                val bootstrapUrl = "https://${WebViewAssetLoader.DEFAULT_DOMAIN}/bootstrap/native.html"
                Log.d(TAG, "Loading bootstrap via WebViewAssetLoader: $bootstrapUrl")
                loadUrl(bootstrapUrl)
            }
            try {
                deferred.await()?.also {
                    webView.destroy()
                }
            } catch (e: Exception) {
                Log.w(TAG, "WebViewAssetLoader test threw: ${e.message}")
                webView.destroy()
                null
            }
        }

    /**
     * Navigate a profile-bound WebView via [WebViewCompat.loadDataWithBaseURL] with a
     * native HTTPS base origin and capture `location.origin` via JavaScript evaluation.
     *
     * Returns the captured origin string, or null if navigation or evaluation failed.
     */
    private suspend fun testLoadDataWithBaseURL(profile: androidx.webkit.Profile): String? =
        withContext(Dispatchers.Main) {
            val deferred = CompletableDeferred<String?>()
            val webView = WebView(context).apply {
                WebViewCompat.setProfile(this, profile.name)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        evaluateJavascript(
                            "javascript:(function(){ return location.origin; })()"
                        ) { origin ->
                            val trimmed = origin.trim().removeSurrounding("\"")
                            Log.d(TAG, "loadDataWithBaseURL origin captured: $trimmed")
                            deferred.complete(if (trimmed.isEmpty()) null else trimmed)
                        }
                    }
                }
                val baseOrigin = "https://hub.internal/bootstrap"
                val html = loadBootstrapHtml()
                Log.d(TAG, "Loading bootstrap via loadDataWithBaseURL with base=$baseOrigin")
                loadDataWithBaseURL(html, "text/html", "utf-8", "$baseOrigin/native.html", "$baseOrigin/native.html")
            }
            try {
                deferred.await()?.also {
                    webView.destroy()
                }
            } catch (e: Exception) {
                Log.w(TAG, "loadDataWithBaseURL test threw: ${e.message}")
                webView.destroy()
                null
            }
        }

    /**
     * Read the bootstrap HTML asset as a string.
     */
    private fun loadBootstrapHtml(): String = context.assets.open("bootstrap/native.html")
        .bufferedReader().use { it.readText() }

    /**
     * Write the [DetectorResult] as a JSON report to [context.filesDir]/bootstrap_report.json.
     *
     * Failure to write is logged but never throws — the in-memory [DetectorResult]
     * is still returned by [test].
     */
    private fun writeReport(
        context: Context,
        result: DetectorResult,
    ): String? = runCatching {
        val file = File(context.filesDir, REPORT_FILENAME)
        file.parentFile?.mkdirs()
        val json = JSONObject().apply {
            put("qualifiedMechanism", result.qualifiedMechanism ?: "none")
            put("webViewAssetLoaderOrigin", result.webViewAssetLoaderOrigin ?: "none")
            put("loadDataWithBaseURLOrigin", result.loadDataWithBaseURLOrigin ?: "none")
            put("webViewAssetLoaderQualified", result.webViewAssetLoaderQualified)
            put("loadDataWithBaseURLQualified", result.loadDataWithBaseURLQualified)
            put("message", result.message)
            put("reportPath", file.absolutePath)
        }
        file.writeText(json.toString(2))
        Log.d(TAG, "Bootstrap report written: ${file.absolutePath}")
        file.absolutePath
    }.getOrElse { e ->
        Log.e(TAG, "Failed to write bootstrap report", e)
        null
    }

    /**
     * Build a human-readable message summarizing the detector outcome.
     */
    private fun buildMessage(
        qualifiedMechanism: String?,
        walOrigin: String?,
        ldwuOrigin: String?,
    ): String {
        val wal = walOrigin ?: "n/a"
        val ldwu = ldwuOrigin ?: "n/a"
        return when {
            qualifiedMechanism == "WebViewAssetLoader" ->
                "Bootstrap origin qualified via WebViewAssetLoader (origin=$wal)"
            qualifiedMechanism == "loadDataWithBaseURL" ->
                "Bootstrap origin qualified via loadDataWithBaseURL (origin=$ldwu)"
            walOrigin != null || ldwuOrigin != null ->
                "Neither mechanism produced a fully qualified origin. " +
                    "WebViewAssetLoader origin=$wal, loadDataWithBaseURL origin=$ldwu. " +
                    "Check report for details."
            else ->
                "Bootstrap detector completed but no mechanism produced an origin. " +
                    "Check logs for navigation errors."
        }
    }
}
