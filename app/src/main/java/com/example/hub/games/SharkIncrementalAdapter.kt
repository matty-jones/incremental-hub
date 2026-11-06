package com.example.hub.games

import java.util.Base64
import android.webkit.ValueCallback
import android.webkit.WebView
import com.example.hub.session.StorageBootstrap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Adapter for Shark Incremental.
 *
 * Save format: base64-encoded JSON with a `VERSION` field (≥3).
 * `validate` only checks that encoding. `save()` then stringifies each
 * break_eternity Decimal with `toString()` (`1e100`, `ee21`, `(e^6)10`),
 * so progression parsing has to accept those forms or a checked save is rejected.
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

        /**
         * Reads Shark's clock and storage. The payload is hashed by the adapter
         * and is not written into Hub diagnostics.
         */
        internal const val JS_INSPECT_OFFLINE =
            "(function(){ try {" +
                " if (typeof player === 'undefined' || player === null) return 'missing';" +
                " var calc = (typeof offline !== 'undefined' && offline) ? offline : null;" +
                " return JSON.stringify({" +
                " latestTime: (typeof player.latest_time === 'number') ? player.latest_time : null," +
                " now: Date.now()," +
                " enabled: !!(player.radios && player.radios.offline)," +
                " active: !!(calc && calc.active)," +
                " nosave: !!(calc && calc.nosave)," +
                " elapsed: (calc && typeof calc.elapsed === 'number') ? calc.elapsed : 0," +
                " storage: localStorage.getItem('$SAVE_KEY')" +
                " });" +
                " } catch (e) { return 'error'; } })()"

        /**
         * Shark's load path is `simulateOffline((Date.now()-player.latest_time)/1000)`.
         * This calls that function. It does not take a duration from Hub.
         */
        internal const val JS_START_OFFLINE =
            "(function(){ try {" +
                " if (typeof player === 'undefined' || player === null || typeof simulateOffline !== 'function') return 'unsupported';" +
                " var calc = (typeof offline !== 'undefined' && offline) ? offline : null;" +
                " if (calc && (calc.active || calc.nosave)) return 'busy';" +
                " if (!player.radios || !player.radios.offline) return 'disabled';" +
                " var gap = (Date.now() - player.latest_time) / 1000;" +
                " if (!(gap > 60)) return 'short';" +
                " simulateOffline(gap);" +
                " return 'started';" +
                " } catch (e) { return 'error'; } })()"

        internal const val JS_OFFLINE_PHASE =
            "(function(){ try {" +
                " if (typeof offline === 'undefined' || !offline) return 'missing';" +
                " if (offline.nosave) return 'running';" +
                " if (typeof offline.elapsed === 'number' && offline.elapsed > 0) return 'settled';" +
                " if (offline.active) return 'running';" +
                " return 'idle';" +
                " } catch (e) { return 'error'; } })()"

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
        return StorageBootstrap.markerConfirms(marker)
    }

    override fun summarize(payload: String?): SaveSummary = SharkProgress.summarize(payload)

    override fun compare(livePayload: String?, storedPayload: String?): SaveComparison =
        SharkProgress.compare(livePayload, storedPayload)

    override fun classifyProgress(candidate: String?, reference: String?): ProgressionClass =
        SharkProgress.classifyCapture(candidate, reference)

    override fun chooseStored(candidates: List<String>): String? = SharkProgress.chooseStored(candidates)

    override fun offlineSupport(): OfflineSupport = OfflineSupport(
        OfflineCapability.NATIVE_ON_RESUME,
        "Shark calculates offline progress in loadGame when the save is more than 60 seconds old " +
            "and the offline radio is on. The same simulateOffline function can run on a frozen page " +
            "whose latest_time has not moved. Hub does not pass its own duration into that function.",
    )

    override suspend fun inspectOffline(webView: WebView): OfflineInspection {
        val raw = try {
            withTimeoutOrNull(5_000L) { evaluateJs(webView, JS_INSPECT_OFFLINE) }
        } catch (_: Exception) {
            return OfflineInspection.unsupported()
        }
        val text = unwrapJsResult(raw) ?: return OfflineInspection.unsupported()
        if (text == "missing" || text == "error") return OfflineInspection.unsupported()
        val json = try {
            JSONObject(text)
        } catch (_: Exception) {
            return OfflineInspection.unsupported()
        }
        val storage = json.optString("storage").takeIf { it.isNotBlank() && it != "null" }
        return OfflineInspection(
            supported = true,
            latestTimeMillis = json.optLongOrNull("latestTime"),
            gameNowMillis = json.optLongOrNull("now"),
            offlineEnabled = json.optBoolean("enabled"),
            simulationActive = json.optBoolean("active"),
            blocksSave = json.optBoolean("nosave"),
            payloadSha256 = storage?.let { PayloadDigest.sha256(it) },
        )
    }

    override suspend fun startNativeOffline(webView: WebView): OfflineStart {
        val raw = try {
            withTimeoutOrNull(5_000L) { evaluateJs(webView, JS_START_OFFLINE) }
        } catch (_: Exception) {
            return OfflineStart.FAILED
        }
        return when (unwrapJsResult(raw)) {
            "started" -> OfflineStart.STARTED
            "busy" -> OfflineStart.BUSY
            "disabled" -> OfflineStart.DISABLED
            "short" -> OfflineStart.SHORT
            "unsupported", "missing" -> OfflineStart.UNSUPPORTED
            else -> OfflineStart.FAILED
        }
    }

    override suspend fun awaitOfflineSettlement(webView: WebView, timeoutMs: Long): OfflineSettlement {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val phase = try {
                unwrapJsResult(withTimeoutOrNull(500L) { evaluateJs(webView, JS_OFFLINE_PHASE) })
            } catch (_: Exception) {
                null
            }
            when (phase) {
                "settled" -> return OfflineSettlement.COMPLETE
                "running", null -> Unit
                "missing", "error" -> return OfflineSettlement.UNSUPPORTED
                else -> return OfflineSettlement.INTERRUPTED
            }
            kotlinx.coroutines.delay(200L)
        }
        return OfflineSettlement.INTERRUPTED
    }

    override suspend fun replaceStoredSave(webView: WebView, payload: String): Boolean {
        val raw = try {
            withTimeoutOrNull(5_000L) { evaluateJsArg(webView, JS_SET_SAVE, payload) }
        } catch (_: Exception) {
            return false
        }
        return unwrapJsResult(raw) == "true"
    }

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

private fun JSONObject.optLongOrNull(name: String): Long? {
    if (!has(name) || isNull(name)) return null
    val value = opt(name)
    return when (value) {
        is Number -> value.toLong()
        else -> null
    }
}
