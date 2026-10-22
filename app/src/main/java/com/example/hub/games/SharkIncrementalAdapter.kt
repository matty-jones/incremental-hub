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
        internal const val SAVE_KEY = "shark_inc_save"
        internal const val MIN_VERSION = 3
        internal const val JS_PLAYER_EXISTS =
            "(function(){ return typeof player !== 'undefined' && player !== null; })()"
        internal const val JS_UI_READY =
            "(function(){" +
                " if (window.__hubStorageBootstrap) return false;" +
                " if (typeof player === 'undefined' || player === null || player.VERSION == null) return false;" +
                " function surfaceVisible(node){" +
                " if (!node) return false;" +
                " var inline = node.style && node.style.display;" +
                " if (inline === 'none') return false;" +
                " var computed = window.getComputedStyle ? getComputedStyle(node).display : inline;" +
                " if (computed === 'none') return false;" +
                " var rect = node.getBoundingClientRect();" +
                " return rect.width > 1 && rect.height > 1;" +
                " }" +
                " var offlineNode = document.getElementById('offline');" +
                " if (surfaceVisible(offlineNode)) {" +
                " var ot = document.getElementById('offline-time');" +
                " var textOk = !!(ot && (ot.textContent || '').trim().length > 0);" +
                " var calc = (typeof offline !== 'undefined' && offline) ? offline : null;" +
                " var active = !!(calc && calc.active);" +
                " if (!(textOk || active)) return false;" +
                " return controlUsable('offline-speed') || controlUsable('offline-done');" +
                " }" +
                " function controlUsable(id){" +
                " var node = document.getElementById(id);" +
                " if (!surfaceVisible(node)) return false;" +
                " var r = node.getBoundingClientRect();" +
                " var vw = window.innerWidth || 0;" +
                " var vh = window.innerHeight || 0;" +
                " return r.bottom > 0 && r.right > 0 && r.top < vh && r.left < vw;" +
                " }" +
                " var app = document.getElementById('app');" +
                " if (!surfaceVisible(app)) return false;" +
                " var fish = document.getElementById('fish-amount');" +
                " var antimatter = document.getElementById('antimatter-amount');" +
                " var god = document.getElementById('god-fish-amount');" +
                " return !!((fish && (fish.textContent || '').trim().length > 0)" +
                " || (antimatter && (antimatter.textContent || '').trim().length > 0)" +
                " || (god && (god.textContent || '').trim().length > 0));" +
                " })()"
        internal const val JS_PEEK_SAVE =
            "(function(){ return localStorage.getItem('$SAVE_KEY'); })()"
        internal const val JS_READ_SAVE =
            "(function(){ if (typeof save === 'function') { save(); } return localStorage.getItem('$SAVE_KEY'); })()"
        internal const val JS_BOOTSTRAP_MARKER =
            "(function(){ return window.__hubStorageBootstrap || ''; })()"
        internal const val JS_RUNTIME_PROGRESS =
            "(function(){ if (typeof player === 'undefined' || player === null) return null;" +
                " function mag(v){ if (v == null) return null;" +
                " if (typeof v === 'number' || typeof v === 'string') return v;" +
                " if (typeof v === 'object' && v.s != null && v.e != null) return {s:v.s, e:v.e};" +
                " if (typeof v.toString === 'function') return String(v); return null; }" +
                " return JSON.stringify({" +
                " VERSION: player.VERSION," +
                " feature: player.feature," +
                " totalTimePlayed: player.totalTimePlayed," +
                " shark_level: mag(player.shark_level)," +
                " total_fish: mag(player.total_fish)," +
                " prestige: {times: player.prestige ? player.prestige.times : null}," +
                " core: {times: player.core ? player.core.times : null}," +
                " humanoid: {times: player.humanoid ? player.humanoid.times : null}," +
                " singularity: {first: player.singularity ? player.singularity.first : null}" +
                " }); })()"
        private const val JS_SET_SAVE =
            "(function(){ localStorage.setItem('$SAVE_KEY', __PAYLOAD__); return true; })()"

        /** Minimal Shark save used only by the qualification injector. */
        internal const val QUALIFICATION_FIXTURE =
            "eyJWRVJTSU9OIjozLCJnb2xkIjoxMDAwLCJjbGlja3MiOjUwfQ=="
    }

    override val storageKey: String = SAVE_KEY

    override val adapterId = "shark-incremental-adapter"
    override val adapterVersion = "1.0.0"
    override val readinessScript: String = SharkProbe.JS
    override val qualificationFixture: String? = QUALIFICATION_FIXTURE

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
                evaluateJs(webView, JS_UI_READY)
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

    override suspend fun peekStorage(webView: WebView): PeekOutcome {
        val raw = try {
            withTimeoutOrNull(5_000L) { evaluateJs(webView, JS_PEEK_SAVE) }
        } catch (e: Exception) {
            return PeekOutcome.Failed
        } ?: return PeekOutcome.Failed
        val value = unwrapJsResult(raw)
        return if (value == null) PeekOutcome.Empty else PeekOutcome.Value(value)
    }

    override suspend fun confirmBootstrap(webView: WebView): Boolean {
        val raw = try {
            withTimeoutOrNull(5_000L) { evaluateJs(webView, JS_BOOTSTRAP_MARKER) }
        } catch (e: Exception) {
            return false
        } ?: return false
        val marker = unwrapJsResult(raw)
        return marker == "peek" || marker == "write" || marker == "rollback"
    }

    override fun summarize(payload: String?): SaveSummary = SharkProgress.summarize(payload)

    override fun compare(livePayload: String?, storedPayload: String?): SaveComparison =
        SharkProgress.compare(livePayload, storedPayload)

    override fun chooseStored(candidates: List<String>): String? = SharkProgress.chooseStored(candidates)

    override suspend fun acceptsRuntime(webView: WebView, importedPayload: String): Boolean {
        val expected = SharkProgress.parsePayload(importedPayload) ?: return false
        val raw = try {
            withTimeoutOrNull(5_000L) { evaluateJs(webView, JS_RUNTIME_PROGRESS) }
        } catch (e: Exception) {
            return false
        } ?: return false
        val runtime = SharkProgress.parseRuntime(unwrapJsResult(raw)) ?: return false
        return SharkProgress.acceptsRuntime(expected, runtime)
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
