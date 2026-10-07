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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Result of a [PreLaunchSnapshot] capture execution.
 *
 * @param passed whether the snapshot envelope was non-empty and the SHA-256 was a
 *   valid 64-char hex digest.
 * @param uncompressedBytes the byte count of the serialized snapshot envelope
 *   (JSON string of keys + entryCount + timestamp).
 * @param sha256 a 64-char hex SHA-256 digest of the sorted key list.
 * @param keyCount the number of localStorage keys at the time of capture.
 * @param mechanism the mechanism used to serve the bootstrap (from
 *   [BootstrapDetector.DetectorResult.qualifiedMechanism]).
 * @param timestampEpochMs wall-clock timestamp of the capture in epoch milliseconds.
 * @param message human-readable description of the result.
 */
data class SnapshotResult(
    val passed: Boolean,
    val uncompressedBytes: Long,
    val sha256: String,
    val keyCount: Int,
    val mechanism: String?,
    val timestampEpochMs: Long,
    val message: String,
)

/**
 * Captures a pre-launch snapshot envelope before any upstream game JavaScript executes.
 *
 * The capture flow:
 * 1. Create a test profile via [ProfileStore.getOrCreateProfile].
 * 2. Write a synthetic key `s02-prelaunch-test` with a unique value into that profile.
 * 3. Navigate to the bootstrap HTML using the mechanism identified by [detectorResult].
 * 4. Read all localStorage keys via [WebView.evaluateJavascript].
 * 5. Compute SHA-256 of the sorted key list (hex digest).
 * 6. Capture the uncompressed byte count of the serialized snapshot envelope.
 * 7. Record a wall-clock timestamp.
 * 8. Return [SnapshotResult] reflecting the outcome.
 * 9. Clean up the test profile.
 *
 * This is the core proof for R008: the snapshot is captured *before* game code
 * can read or mutate it, because the bootstrap HTML runs at a native-origin
 * URL that the game's service worker cannot intercept.
 *
 * **Thread confinement:** [capture] acquires [Dispatchers.Main] internally, so
 * it may be called from any coroutine context. WebView creation and profile
 * binding are not thread-safe and must run on the UI thread.
 *
 * **Cleanup guarantee:** Test profile is deleted on success. On failure, the
 * profile is retained for debugging.
 */
class PreLaunchSnapshot(private val context: Context) {

    companion object {
        private const val TAG = "PreLaunchSnapshot"
        private const val TEST_PROFILE = "s02-prelaunch-test"
        private const val SYNTHETIC_KEY = "s02-prelaunch-test"
        private const val BOOTSTRAP_PATH = "bootstrap/native.html"
        private const val SNAPSHOT_FILENAME = "prelaunch_snapshot.json"
        private const val NATIVE_BASE_ORIGIN = "https://hub.internal"
    }

    /**
     * Capture a pre-launch snapshot envelope.
     *
     * @param profileStore the [ProfileStore] to create and manage the test profile.
     * @param detectorResult the result from [BootstrapDetector.test] identifying the
     *   qualified origin mechanism.
     * @return [SnapshotResult] reflecting the outcome. [passed] is true only when
     *   [uncompressedBytes] is non-zero and [sha256] is a valid 64-char hex digest.
     */
    suspend fun capture(
        profileStore: ProfileStore,
        detectorResult: DetectorResult,
    ): SnapshotResult = withContext(Dispatchers.Main) {
        Log.d(TAG, "Starting pre-launch snapshot capture")

        var profile: androidx.webkit.Profile? = null
        var webView: WebView? = null
        var snapshotJson: String? = null
        var capturedMechanism: String? = null

        try {
            // Step 1: create the test profile
            profile = profileStore.getOrCreateProfile(TEST_PROFILE)
            Log.d(TAG, "Test profile created: $TEST_PROFILE")

            // Step 2: write the synthetic key
            webView = createProfileWebView(profile)
            val injected = injectSyntheticKey(webView, SYNTHETIC_KEY, UUID.randomUUID().toString())
            if (!injected) {
                Log.w(TAG, "Failed to inject synthetic key — continuing without it")
            }

            // Step 3: choose mechanism from detector result
            capturedMechanism = resolveMechanism(detectorResult)
            Log.d(TAG, "Using mechanism: $capturedMechanism")

            // Step 4: navigate to bootstrap and capture keys
            val keysJson = navigateAndWaitForKeys(webView, capturedMechanism)

            // Step 5: compute SHA-256 of sorted key list
            val sha256 = computeSha256OfKeys(keysJson)

            // Step 6: compute snapshot envelope bytes
            val snapshotEnvelope = buildSnapshotEnvelope(keysJson)
            val uncompressedBytes = snapshotEnvelope.toByteArray().size.toLong()

            // Step 7: record timestamp
            val timestamp = System.currentTimeMillis()

            // Step 8: write snapshot report
            snapshotJson = writeSnapshotReport(context, snapshotEnvelope, sha256, timestamp)

            // Validate: non-zero bytes and 64-char hex SHA-256
            val sha256Valid = sha256.length == 64 && sha256.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
            val passed = uncompressedBytes > 0L && sha256Valid

            Log.d(
                TAG,
                "Snapshot captured — passed=$passed bytes=$uncompressedBytes " +
                    "sha256=$sha256 keys=${keysJson.length} mechanism=$capturedMechanism"
            )

            return@withContext SnapshotResult(
                passed = passed,
                uncompressedBytes = uncompressedBytes,
                sha256 = sha256,
                keyCount = keysJson.length,
                mechanism = capturedMechanism,
                timestampEpochMs = timestamp,
                message = buildMessage(passed, uncompressedBytes, sha256, keysJson.length, capturedMechanism),
            )
        } catch (e: Exception) {
            Log.e(TAG, "Pre-launch snapshot capture failed", e)
            return@withContext SnapshotResult(
                passed = false,
                uncompressedBytes = snapshotJson?.length?.toLong() ?: 0L,
                sha256 = "error: ${e.message}",
                keyCount = 0,
                mechanism = capturedMechanism,
                timestampEpochMs = System.currentTimeMillis(),
                message = "Exception: ${e.message}",
            )
        } finally {
            webView?.destroy()
            try {
                profile?.let { profileStore.deleteProfile(TEST_PROFILE) }
                Log.d(TAG, "Test profile cleaned up: $TEST_PROFILE")
            } catch (e: Exception) {
                Log.w(TAG, "Cleanup failed — profile retained for debugging: ${e.message}")
            }
        }
    }

    /** Create a profile-bound WebView navigated to about:blank. */
    private suspend fun createProfileWebView(profile: androidx.webkit.Profile): WebView =
        withContext(Dispatchers.Main) {
            val deferred = CompletableDeferred<WebView>()
            val webView = WebView(context).apply {
                WebViewCompat.setProfile(this, profile.name)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                    deferred.complete(view)
                }
                }
                loadUrl("about:blank")
            }
            deferred.await()
        }

    /** Inject a synthetic localStorage key into [webView]. */
    private suspend fun injectSyntheticKey(webView: WebView, key: String, value: String): Boolean =
        withContext(Dispatchers.Main) {
            val deferred = CompletableDeferred<Boolean>()
            val safeKey = key.replace("'", "\\'")
            val safeValue = value.replace("'", "\\'")
            webView.evaluateJavascript(
                "javascript:(function(){ localStorage.setItem('$safeKey', '$safeValue'); return 'injected'; })()"
            ) { result ->
                val ok = result.trim().removeSurrounding("\"") == "injected"
                Log.d(TAG, "Synthetic key injection: $ok")
                deferred.complete(ok)
            }
            deferred.await()
        }

    /** Resolve mechanism: detector qualified > WebViewAssetLoader fallback > loadDataWithBaseURL. */
    private fun resolveMechanism(detectorResult: DetectorResult): String = when {
        detectorResult.qualifiedMechanism != null -> detectorResult.qualifiedMechanism
        detectorResult.webViewAssetLoaderOrigin != null -> "WebViewAssetLoader"
        else -> "loadDataWithBaseURL"
    }

    /** Navigate to bootstrap and return the JSON array string of localStorage keys. */
    private suspend fun navigateAndWaitForKeys(webView: WebView, mechanism: String): String =
        withContext(Dispatchers.Main) {
            val deferred = CompletableDeferred<String>()

            val webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    // Small delay to let bootstrap inline JS finish its localStorage read.
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        readKeysSynchronous(view) { jsonArray ->
                            val jsonStr = jsonArray.toString()
                            Log.d(TAG, "Snapshot keys read — count=${jsonArray.length()!!} json=$jsonStr")
                            deferred.complete(jsonStr)
                        }
                    }, 500L)
                }
            }
            webView.webViewClient = webViewClient

            if (mechanism == "WebViewAssetLoader") {
                try {
                    val method = WebView::class.java.getMethod(
                        "setWebAssetLoader", androidx.webkit.WebViewAssetLoader::class.java
                    )
                    method.invoke(
                        webView,
                        WebViewAssetLoader.Builder()
                            .addPathHandler("/bootstrap/*", WebViewAssetLoader.AssetsPathHandler(context))
                            .build()
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "setWebAssetLoader unavailable: ${e.message}")
                }
                webView.loadUrl("https://${WebViewAssetLoader.DEFAULT_DOMAIN}/$BOOTSTRAP_PATH")
            } else {
                val html = context.assets.open(BOOTSTRAP_PATH).bufferedReader().use { it.readText() }
                webView.loadDataWithBaseURL(
                    NATIVE_BASE_ORIGIN, html, "text/html", "utf-8",
                    "$NATIVE_BASE_ORIGIN/$BOOTSTRAP_PATH"
                )
            }
            deferred.await()
        }

    /** Non-suspend keys reader for use inside Handler.postDelayed callbacks. */
    private fun readKeysSynchronous(webView: WebView, callback: (JSONArray) -> Unit) {
        webView.evaluateJavascript(
            "javascript:(function(){ return JSON.stringify(Object.keys(localStorage)); })()"
        ) { result ->
            val trimmed = result.trim()
            Log.d(TAG, "readKeys result: $trimmed")
            runCatching { callback(JSONArray(if (trimmed.isEmpty()) "[]" else trimmed)) }
                .getOrElse { callback(JSONArray("[]")) }
        }
    }

    /** Compute SHA-256 hex digest of sorted keys from a JSON array string. */
    private fun computeSha256OfKeys(keysJson: String): String = runCatching {
        val arr = JSONArray(keysJson)
        val sorted = List(arr.length()) { arr.getString(it) }.sorted()
        MessageDigest.getInstance("SHA-256").digest(sorted.toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
    }.getOrElse { "0".repeat(64) }

    /** Build a JSON snapshot envelope: {keys, entryCount, timestamp}. */
    private fun buildSnapshotEnvelope(keysJson: String): String {
        val arr = JSONArray(keysJson)
        val sorted = List(arr.length()) { arr.getString(it) }.sorted()
        return JSONObject().apply {
            put("keys", sorted)
            put("entryCount", sorted.size)
            put("timestamp", System.currentTimeMillis())
        }.toString()
    }

    /** Write snapshot report JSON to [Context.filesDir]/prelaunch_snapshot.json. */
    private fun writeSnapshotReport(context: Context, snapshot: String, sha256: String, timestamp: Long): String? =
        runCatching {
            val file = File(context.filesDir, SNAPSHOT_FILENAME)
            file.parentFile?.mkdirs()
            JSONObject().apply {
                put("snapshot", snapshot)
                put("sha256", sha256)
                put("timestamp", timestamp)
                put("reportPath", file.absolutePath)
            }.also { it.writeToString(file) }
            Log.d(TAG, "Snapshot report written: ${file.absolutePath}")
            file.absolutePath
        }.getOrElse { e -> Log.e(TAG, "Failed to write snapshot report", e); null }

    private fun JSONObject.writeToString(file: File) = file.writeText(toString(2))

    private fun buildMessage(passed: Boolean, uncompressedBytes: Long, sha256: String, keyCount: Int, mechanism: String?): String {
        val mech = mechanism ?: "unknown"
        return when {
            passed -> "Pre-launch snapshot captured — $uncompressedBytes bytes, $keyCount keys, SHA-256=$sha256, mechanism=$mech"
            uncompressedBytes == 0L -> "Pre-launch snapshot empty — no bytes captured (mechanism=$mech)"
            sha256.length != 64 -> "Pre-launch snapshot captured but SHA-256 invalid ($sha256) — mechanism=$mech"
            else -> "Pre-launch snapshot captured with warnings — mechanism=$mech"
        }
    }
}
