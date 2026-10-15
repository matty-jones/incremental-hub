package com.example.hub.games

import java.util.Base64
import android.webkit.ValueCallback
import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Adapter for Shark Incremental.
 *
 * Save format: base64-encoded JSON with a `VERSION` field (≥3).
 * Large numbers use break_eternity.js Decimal serialization
 * (`{s:…,e:…,c:[…]}`) which is preserved losslessly as opaque JSON —
 * never parsed into Double.
 *
 * localStorage key: `shark_inc_save`
 * Origin: `https://mrredshark77.github.io`
 */
class SharkIncrementalAdapter : GameAdapter {

    companion object {
        private const val SAVE_KEY = "shark_inc_save"
        private const val MIN_VERSION = 3
        internal const val JS_PLAYER_EXISTS =
            "(function(){ return typeof player !== 'undefined' && player !== null; })()"
        internal const val JS_READ_SAVE =
            "(function(){ if (typeof save === 'function') { save(); } return localStorage.getItem('$SAVE_KEY'); })()"
        private const val JS_SET_SAVE =
            "(function(){ localStorage.setItem('$SAVE_KEY', __PAYLOAD__); return true; })()"
    }

    override val adapterId = "shark-incremental-adapter"
    override val adapterVersion = "1.0.0"

    // ── probe ──────────────────────────────────────────────────────────────
    override suspend fun probe(webView: WebView): Boolean {
        val result: String? = withTimeoutOrNull(5_000L) {
            evaluateJs(webView, JS_PLAYER_EXISTS)
        }
        return result == "true"
    }

    // ── awaitReady ─────────────────────────────────────────────────────────
    override suspend fun awaitReady(webView: WebView, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val ready: String? = withTimeoutOrNull(500L) {
                evaluateJs(webView, JS_PLAYER_EXISTS)
            }
            if (ready == "true") return true
            kotlinx.coroutines.delay(500L)
        }
        return false
    }

    // ── capture ────────────────────────────────────────────────────────────
    override suspend fun capture(webView: WebView, result: QualificationResult): String? {
        val raw: String? = withTimeoutOrNull(10_000L) {
            evaluateJs(webView, JS_READ_SAVE)
        }
        if (raw.isNullOrEmpty() || raw == "null") return null

        val unquoted = unwrapJsResult(raw) ?: return null

        // Decode base64 and verify valid JSON.
        val decoded: String = try {
            String(Base64.getDecoder().decode(unquoted), Charsets.UTF_8)
        } catch (e: Exception) {
            return null
        }
        try {
            JSONObject(decoded)
        } catch (e: Exception) {
            return null
        }
        return unquoted
    }

    // ── validate ───────────────────────────────────────────────────────────
    override suspend fun validate(payload: String): Boolean {
        val decoded: String = try {
            String(Base64.getDecoder().decode(payload), Charsets.UTF_8)
        } catch (e: Exception) {
            return false
        }
        val json: JSONObject = try {
            JSONObject(decoded)
        } catch (e: Exception) {
            return false
        }
        val versionStr: String = json.optString("VERSION", "0")
        val version: Int = versionStr.toIntOrNull() ?: 0
        return version >= MIN_VERSION
    }

    // ── prepareRestore ─────────────────────────────────────────────────────
    override suspend fun prepareRestore(payload: String): String {
        return try {
            val decoded: String = String(Base64.getDecoder().decode(payload), Charsets.UTF_8)
            // Re-serialize through JSONObject to canonicalise whitespace.
            // Decimal objects are preserved as opaque JSON objects — never parsed.
            val canonical: String = JSONObject(decoded).toString()
            Base64.getEncoder().encodeToString(canonical.toByteArray(Charsets.UTF_8))
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
            "true" == wv
        } catch (e: Exception) {
            false
        }
        if (!writeOk) return false

        // Wait for the game to pick up the save.
        val ready: Boolean = awaitReady(webView, 8_000L)
        if (!ready) return false

        // Read back and compare.
        val restored: String? = try {
            unwrapJsResult(withTimeoutOrNull(5_000L) { evaluateJs(webView, JS_READ_SAVE) })
        } catch (e: Exception) {
            null
        }
        return restored == importedPayload
    }

    // ── JS bridge helper ───────────────────────────────────────────────────
    private suspend fun evaluateJs(webView: WebView, js: String): String? {
        val deferred = CompletableDeferred<String?>()
        val cb: ValueCallback<String?> = ValueCallback { deferred.complete(it) }
        webView.evaluateJavascript(js, cb)
        return deferred.await()
    }

    internal fun setSaveScript(payload: String): String =
        JS_SET_SAVE.replace("__PAYLOAD__", JSONObject.quote(payload))

    private suspend fun evaluateJsArg(webView: WebView, js: String, arg: String): String? {
        val jsWithArg = if (js == JS_SET_SAVE) setSaveScript(arg) else js.replace("__PAYLOAD__", JSONObject.quote(arg))
        val deferred = CompletableDeferred<String?>()
        val cb: ValueCallback<String?> = ValueCallback { deferred.complete(it) }
        webView.evaluateJavascript(jsWithArg, cb)
        return deferred.await()
    }
}
