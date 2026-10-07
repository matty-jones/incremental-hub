package com.example.hub.games

import java.util.Base64
import android.webkit.ValueCallback
import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Adapter for FE000000.
 *
 * Save format: gzip-compressed, base64-encoded JSON wrapped in
 * `FE000000SaveStart` / `FE000000SaveEnd` markers.
 *
 * localStorage key: `fe000000-save`
 * Origin: `https://dan-simon.github.io`
 * No automatic autosave — explicit save via Import/Load button.
 */
class Fe000000Adapter : GameAdapter {

    companion object {
        private const val SAVE_KEY = "fe000000-save"
        private const val MARKER_START = "FE000000SaveStart"
        private const val MARKER_END = "FE000000SaveEnd"

        private const val JS_SAVE_DATA_EXISTS =
            "return typeof saveData !== 'undefined' && saveData !== null"
        private const val JS_READ_SAVE =
            "return localStorage.getItem('$SAVE_KEY') || null"
        private const val JS_SET_SAVE =
            "localStorage.setItem('$SAVE_KEY', arguments[0]); 'ok'"

        private const val CAPTURE_TIMEOUT_MS = 10_000L
        private const val READY_TIMEOUT_MS = 10_000L
    }

    override val adapterId = "fe000000-adapter"
    override val adapterVersion = "1.0.0"

    // ── probe ──────────────────────────────────────────────────────────────
    override suspend fun probe(webView: WebView): Boolean {
        // Prefer JS global check; fall back to localStorage key presence.
        val hasGlobal: String? = withTimeoutOrNull(5_000L) {
            evaluateJs(webView, JS_SAVE_DATA_EXISTS)
        }
        if (hasGlobal == "true") return true
        val hasKey: String? = withTimeoutOrNull(5_000L) {
            evaluateJs(webView, JS_READ_SAVE)
        }
        return hasKey != null && hasKey != "null" && hasKey.trim().isNotEmpty()
    }

    // ── awaitReady ─────────────────────────────────────────────────────────
    override suspend fun awaitReady(webView: WebView, timeoutMs: Long): Boolean {
        val timeout: Long = timeoutMs
        var tries = 0
        while (tries < 20) {
            tries++
            val val0: String? = withTimeoutOrNull(500L) {
                evaluateJs(webView, JS_SAVE_DATA_EXISTS)
            } ?: "false"
            if (val0 == "true") return true
            kotlinx.coroutines.delay(500L)
        }
        return false
    }

    // ── capture ────────────────────────────────────────────────────────────
    override suspend fun capture(webView: WebView, result: QualificationResult): String? {
        val raw: String? = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
            evaluateJs(webView, JS_READ_SAVE)
        }
        if (raw.isNullOrEmpty() || raw == "null") return null

        val unquoted = raw.removeSurrounding("\"")
        val stripped = stripMarkers(unquoted)
        if (stripped == null) return null

        // Base64-decode.
        val b64Bytes: ByteArray = try {
            Base64.getDecoder().decode(stripped)
        } catch (e: Exception) {
            return null
        }
        // Gzip-decompress.
        val decompressed: ByteArray = try {
            val baos = ByteArrayOutputStream()
            GZIPInputStream(ByteArrayInputStream(b64Bytes)).use { gis ->
                gis.copyTo(baos)
            }
            baos.toByteArray()
        } catch (e: Exception) {
            return null
        }
        val jsonStr = String(decompressed, Charsets.UTF_8)
        // Validate JSON.
        try {
            JSONObject(jsonStr)
        } catch (e: Exception) {
            return null
        }
        // Return the raw (marker-wrapped) string as the capture payload.
        return unquoted
    }

    // ── validate ───────────────────────────────────────────────────────────
    override suspend fun validate(payload: String): Boolean {
        val stripped = stripMarkers(payload)
        if (stripped == null) return false
        val b64Bytes: ByteArray = try {
            Base64.getDecoder().decode(stripped)
        } catch (e: Exception) {
            return false
        }
        val decompressed: ByteArray = try {
            val baos = ByteArrayOutputStream()
            GZIPInputStream(ByteArrayInputStream(b64Bytes)).use { gis ->
                gis.copyTo(baos)
            }
            baos.toByteArray()
        } catch (e: Exception) {
            return false
        }
        val jsonStr = String(decompressed, Charsets.UTF_8)
        return try {
            val json = JSONObject(jsonStr)
            json.has("saveData") || json.has("player") || json.length() > 0
        } catch (e: Exception) {
            false
        }
    }

    // ── prepareRestore ─────────────────────────────────────────────────────
    override suspend fun prepareRestore(payload: String): String {
        return try {
            val stripped = stripMarkers(payload)
                ?: return payload
            val b64Bytes: ByteArray = Base64.getDecoder().decode(stripped)
            val decompressed: ByteArray = ByteArrayOutputStream().use { baos ->
                GZIPInputStream(ByteArrayInputStream(b64Bytes)).use { gis ->
                    gis.copyTo(baos)
                }
                baos.toByteArray()
            }
            // Re-serialize through JSONObject to normalise whitespace.
            val canonical: String = JSONObject(String(decompressed, Charsets.UTF_8)).toString()
            // gzip-compress.
            val compressed: ByteArray = ByteArrayOutputStream().use { baos ->
                GZIPOutputStream(baos).use { gos ->
                    gos.write(canonical.toByteArray(Charsets.UTF_8))
                }
                baos.toByteArray()
            }
            // base64-encode and wrap markers.
            val b64: String = Base64.getEncoder().encodeToString(compressed)
            "$MARKER_START$b64$MARKER_END"
        } catch (e: Exception) {
            payload
        }
    }

    // ── verifyLoaded ───────────────────────────────────────────────────────
    override suspend fun verifyLoaded(webView: WebView, importedPayload: String): Boolean {
        val writeOk: Boolean = try {
            val wv: String? = withTimeoutOrNull(5_000L) {
                evaluateJsArg(webView, JS_SET_SAVE, importedPayload)
            }
            "ok" == wv
        } catch (e: Exception) {
            false
        }
        if (!writeOk) return false
        val ready: Boolean = awaitReady(webView, 8_000L)
        if (!ready) return false
        val restored: String? = try {
            withTimeoutOrNull(5_000L) { evaluateJs(webView, JS_READ_SAVE) }
                ?.removeSurrounding("\"")
        } catch (e: Exception) {
            null
        }
        return restored == importedPayload
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /** Strip FE000000SaveStart / FE000000SaveEnd markers precisely via substring. */
    private fun stripMarkers(input: String): String? {
        val startIdx = input.indexOf(MARKER_START)
        if (startIdx < 0) return null
        val afterStart = input.substring(startIdx + MARKER_START.length)
        val endIdx = afterStart.indexOf(MARKER_END)
        if (endIdx < 0) return null
        return afterStart.substring(0, endIdx)
    }

    // ── JS bridge helper ───────────────────────────────────────────────────
    private suspend fun evaluateJs(webView: WebView, js: String): String? {
        val deferred = CompletableDeferred<String?>()
        val cb: ValueCallback<String?> = ValueCallback { deferred.complete(it) }
        webView.evaluateJavascript(js, cb)
        return deferred.await()
    }

    private suspend fun evaluateJsArg(webView: WebView, js: String, arg: String): String? {
        // Embed arg into the JS string (same pattern as ProfileIsolationTest).
        val jsWithArg = js.replace("arguments[0]", arg.replace("\\", "\\\\").replace("'", "\\'"))
        val deferred = CompletableDeferred<String?>()
        val cb: ValueCallback<String?> = ValueCallback { deferred.complete(it) }
        webView.evaluateJavascript(jsWithArg, cb)
        return deferred.await()
    }
}
