package com.example.hub.games

import android.webkit.ValueCallback
import android.webkit.WebView
import com.example.hub.session.StorageBootstrap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Adapter for FE000000.
 *
 * Live save path (saving.js, observed 2026-10-14):
 * `Saving.saveGame` writes `localStorage['fe000000-save']` by JSON-encoding
 * `player`, UTF-8 encoding, `pako.deflate` (zlib), base64, then a custom
 * alphabet (`0` → `0a`, `+` → `0b`, `/` → `0c`, padding stripped) wrapped in
 * `FE000000SaveStart` / `FE000000SaveEnd`.
 *
 * There is no `saveData` global. Readiness is `player` plus `Saving`.
 * Capture calls `Saving.saveGame(true, false)` so the heap is flushed before
 * localStorage is read. Restore calls `Saving.loadGame` when that function exists.
 *
 * There is no reliable progression order. Two valid unequal saves are
 * [ProgressionClass.UNORDERED]. [compare] may still report [SaveComparison.LIVE_AHEAD]
 * for any difference, and that result is not a capture-commit proof.
 */
class Fe000000Adapter : GameAdapter {

    companion object {
        internal const val SAVE_KEY = "fe000000-save"
        private const val MARKER_START = "FE000000SaveStart"
        private const val MARKER_END = "FE000000SaveEnd"
        private val REQUIRED_KEYS = listOf(
            "boost",
            "currentTab",
            "generators",
            "lastUpdate",
            "prestigePower",
            "stars",
        )

        internal const val JS_PLAYER_READY =
            "(function(){ return typeof player !== 'undefined' && player !== null && typeof Saving !== 'undefined' && typeof Saving.saveGame === 'function'; })()"
        internal const val JS_PEEK_SAVE =
            "(function(){ return localStorage.getItem('$SAVE_KEY'); })()"
        internal const val JS_BOOTSTRAP_MARKER =
            "(function(){ return window.__hubStorageBootstrap || ''; })()"
        internal const val JS_RUNTIME_LAST_UPDATE =
            "(function(){ if (typeof player === 'undefined' || player === null || player.lastUpdate === undefined) return null; return String(player.lastUpdate); })()"
        internal const val JS_FLUSH_AND_READ =
            "(function(){ if (typeof Saving !== 'undefined' && typeof Saving.saveGame === 'function') { Saving.saveGame(true, false); } return localStorage.getItem('$SAVE_KEY'); })()"
        internal const val JS_READINESS_PROBE =
            "(function(){ try {" +
                " var playerReady = typeof player !== 'undefined' && player !== null;" +
                " var savingReady = typeof Saving !== 'undefined' && typeof Saving.saveGame === 'function';" +
                " return JSON.stringify({game:'fe000000', player:playerReady, saving:savingReady});" +
                " } catch (e) { return 'probe-failed'; } })()"

        private const val CAPTURE_TIMEOUT_MS = 10_000L

        internal fun setSaveScript(payload: String): String {
            val quoted = JSONObject.quote(payload)
            return "(function(){ var payload = $quoted; localStorage.setItem('$SAVE_KEY', payload);" +
                " if (typeof Saving !== 'undefined' && typeof Saving.loadGame === 'function') {" +
                " try { Saving.loadGame(payload, false, 0, false, function(){}); return 'ok'; }" +
                " catch (e) { return 'error'; } }" +
                " return localStorage.getItem('$SAVE_KEY') === payload ? 'ok' : 'mismatch'; })()"
        }

        internal fun encodeSave(json: String): String {
            val compressed = deflateZlib(json.toByteArray(Charsets.UTF_8))
            val b64 = Base64.getEncoder().encodeToString(compressed)
            val custom = b64.replace(Regex("=+$"), "")
                .replace("0", "0a")
                .replace("+", "0b")
                .replace("/", "0c")
            return "$MARKER_START$custom$MARKER_END"
        }

        internal fun decodeSave(payload: String): String? {
            val stripped = stripMarkers(payload) ?: return null
            val standard = stripped.replace("0b", "+").replace("0c", "/").replace("0a", "0")
            val pad = (4 - standard.length % 4) % 4
            val padded = standard + "=".repeat(pad)
            val bytes = try {
                Base64.getDecoder().decode(padded)
            } catch (e: Exception) {
                return null
            }
            val inflated = try {
                inflateZlib(bytes)
            } catch (e: Exception) {
                return null
            }
            return String(inflated, Charsets.UTF_8)
        }

        private fun stripMarkers(input: String): String? {
            val startIdx = input.indexOf(MARKER_START)
            if (startIdx < 0) return null
            val afterStart = input.substring(startIdx + MARKER_START.length)
            val endIdx = afterStart.indexOf(MARKER_END)
            if (endIdx < 0) return null
            return afterStart.substring(0, endIdx)
        }

        private fun deflateZlib(data: ByteArray): ByteArray {
            val deflater = Deflater()
            return try {
                deflater.setInput(data)
                deflater.finish()
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                while (!deflater.finished()) {
                    val count = deflater.deflate(buffer)
                    if (count == 0) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } finally {
                deflater.end()
            }
        }

        private fun inflateZlib(data: ByteArray): ByteArray {
            val inflater = Inflater()
            return try {
                inflater.setInput(data)
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                while (!inflater.finished()) {
                    val count = inflater.inflate(buffer)
                    if (count == 0) {
                        if (inflater.needsInput()) break
                        if (inflater.needsDictionary()) break
                    } else {
                        output.write(buffer, 0, count)
                    }
                }
                output.toByteArray()
            } finally {
                inflater.end()
            }
        }
    }

    override val adapterId = "fe000000-adapter"
    override val adapterVersion = "1.1.0"
    override val storageKey: String = SAVE_KEY
    override val readinessScript: String = JS_READINESS_PROBE

    override suspend fun probe(webView: WebView): Boolean {
        val hasGlobal: String? = withTimeoutOrNull(5_000L) {
            evaluateJs(webView, JS_PLAYER_READY)
        }
        if (hasGlobal == "true") return true
        val hasKey: String? = withTimeoutOrNull(5_000L) {
            evaluateJs(webView, JS_FLUSH_AND_READ)
        }
        val unwrapped = unwrapJsResult(hasKey)
        return unwrapped != null && validate(unwrapped)
    }

    override suspend fun awaitReady(webView: WebView, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val ready: String? = withTimeoutOrNull(500L) {
                evaluateJs(webView, JS_PLAYER_READY)
            }
            if (ready == "true") return true
            kotlinx.coroutines.delay(500L)
        }
        return false
    }

    override suspend fun capture(webView: WebView, result: QualificationResult): String? {
        val raw: String? = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
            evaluateJs(webView, JS_FLUSH_AND_READ)
        }
        val unquoted = unwrapJsResult(raw) ?: return null
        if (!validate(unquoted)) return null
        return unquoted
    }

    override suspend fun validate(payload: String): Boolean {
        val jsonStr = decodeSave(payload) ?: return false
        return try {
            val json = JSONObject(jsonStr)
            REQUIRED_KEYS.all { json.has(it) }
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun prepareRestore(payload: String): String {
        return if (validate(payload)) payload else payload
    }

    override suspend fun verifyLoaded(webView: WebView, importedPayload: String): Boolean {
        if (!validate(importedPayload)) return false
        val script = setSaveScript(importedPayload)
        val result = try {
            unwrapJsResult(withTimeoutOrNull(8_000L) { evaluateJs(webView, script) })
        } catch (e: Exception) {
            null
        }
        return result == "ok"
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
        return StorageBootstrap.markerConfirms(marker)
    }

    override fun classifyProgress(candidate: String?, reference: String?): ProgressionClass {
        if (!structurallyValid(candidate)) return ProgressionClass.INVALID
        if (reference.isNullOrBlank()) return ProgressionClass.ADVANCED
        if (!structurallyValid(reference)) return ProgressionClass.INCOMPARABLE
        if (candidate == reference) return ProgressionClass.EQUAL
        return ProgressionClass.UNORDERED
    }

    override fun summarize(payload: String?): SaveSummary {
        if (payload.isNullOrBlank()) {
            return SaveSummary(SaveStanding.ABSENT, null, "No save key", 0)
        }
        val decoded = decodeSave(payload)
        if (decoded == null) {
            return SaveSummary(SaveStanding.INVALID, null, "Save did not parse", payload.length)
        }
        val valid = try {
            val json = JSONObject(decoded)
            REQUIRED_KEYS.all { json.has(it) }
        } catch (e: Exception) {
            false
        }
        return if (valid) {
            SaveSummary(SaveStanding.PROGRESSED, null, "structure ok", payload.length)
        } else {
            SaveSummary(SaveStanding.INVALID, null, "Save did not parse", payload.length)
        }
    }

    override fun compare(livePayload: String?, storedPayload: String?): SaveComparison {
        val stored = summarize(storedPayload)
        if (stored.standing == SaveStanding.ABSENT) return SaveComparison.NO_SNAPSHOT
        val live = summarize(livePayload)
        if (live.standing == SaveStanding.ABSENT) return SaveComparison.LIVE_MISSING
        if (live.standing != SaveStanding.PROGRESSED || stored.standing != SaveStanding.PROGRESSED) {
            return SaveComparison.INCONCLUSIVE
        }
        return if (livePayload == storedPayload) SaveComparison.MATCH else SaveComparison.LIVE_AHEAD
    }

    override fun chooseStored(candidates: List<String>): String? =
        candidates.firstOrNull { summarize(it).standing == SaveStanding.PROGRESSED }

    private fun structurallyValid(payload: String?): Boolean {
        if (payload.isNullOrBlank()) return false
        val decoded = decodeSave(payload) ?: return false
        return try {
            val json = JSONObject(decoded)
            REQUIRED_KEYS.all { json.has(it) }
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun acceptsRuntime(webView: WebView, importedPayload: String): Boolean {
        val decoded = decodeSave(importedPayload) ?: return false
        val expected = try {
            JSONObject(decoded).opt("lastUpdate")?.toString()
        } catch (e: Exception) {
            null
        } ?: return false
        val raw = try {
            withTimeoutOrNull(5_000L) { evaluateJs(webView, JS_RUNTIME_LAST_UPDATE) }
        } catch (e: Exception) {
            return false
        } ?: return false
        return unwrapJsResult(raw) == expected
    }

    private suspend fun evaluateJs(webView: WebView, js: String): String? {
        val deferred = CompletableDeferred<String?>()
        val cb: ValueCallback<String?> = ValueCallback { deferred.complete(it) }
        webView.evaluateJavascript(js, cb)
        return deferred.await()
    }
}
