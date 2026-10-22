package com.example.hub.games

import android.webkit.ValueCallback
import android.webkit.WebView
import com.example.hub.session.StorageBootstrap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Trimps 5.10.1. Saves are LZString Base64 JSON under an opt-in envelope.
 * `trimpBackup1` is restored when the envelope has it, and is never treated
 * as further progress than `trimpSave1`.
 */
class TrimpsAdapter : GameAdapter {
    override val adapterId: String = "trimps-adapter"
    override val adapterVersion: String = "1.0.0"
    override val storageKey: String = TrimpsSave.PRIMARY
    override val usesSaveEnvelope: Boolean = true
    override val readinessScript: String = JS_READY
    override val storageEntries: List<StorageEntry> = listOf(
        StorageEntry(TrimpsSave.PRIMARY, required = true, role = StorageRole.PROGRESS),
        StorageEntry(TrimpsSave.BACKUP, required = false, role = StorageRole.BACKUP),
    )
    override val deniedStorageKeys: Set<String> = setOf("playFabPass", "playFabName", "accountToken")

    @Volatile
    private var decline: String? = null

    override fun captureDecline(): String? = decline

    override suspend fun probe(webView: WebView): Boolean {
        val result = withTimeoutOrNull(5_000L) { evaluateJs(webView, JS_PROBE) }
        return result == "true"
    }

    override suspend fun awaitReady(webView: WebView, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val ready = withTimeoutOrNull(500L) { evaluateJs(webView, JS_READY) }
            if (ready == "true") return true
            delay(400L)
        }
        return false
    }

    override suspend fun capture(webView: WebView, result: QualificationResult): String? =
        captureBundle(webView, result)?.let { SaveEnvelope.encode(it.slots) }

    override suspend fun captureBundle(webView: WebView, result: QualificationResult): SaveBundle? {
        decline = null
        val raw = withTimeoutOrNull(15_000L) { evaluateJs(webView, JS_CAPTURE) } ?: return null
        val text = unwrapJsResult(raw) ?: return null
        val json = try {
            JSONObject(text)
        } catch (_: Exception) {
            return null
        }
        if (json.optBoolean("deferred", false)) {
            decline = "Capture deferred: offline warp is running. Last good snapshot was kept."
            return null
        }
        if (json.has("error")) return null
        if (json.isNull("primary")) return null
        val primary = json.optString("primary", "")
        if (!TrimpsSave.acceptable(primary)) return null
        val backup = if (json.isNull("backup")) null else json.optString("backup", "").ifEmpty { null }
        if (backup != null && !TrimpsSave.acceptable(backup)) return null
        return SaveBundle(
            listOf(
                StorageSlot(TrimpsSave.PRIMARY, primary),
                StorageSlot(TrimpsSave.BACKUP, backup),
            ),
        )
    }

    override suspend fun validate(payload: String): Boolean = envelopeOk(payload)

    private fun envelopeOk(payload: String): Boolean {
        val decoded = SaveEnvelope.decode(payload, envelopePolicy())
        if (decoded !is EnvelopeDecode.Ok) return false
        val primary = decoded.bundle.slot(TrimpsSave.PRIMARY)?.value ?: return false
        if (!TrimpsSave.acceptable(primary)) return false
        val backup = decoded.bundle.slot(TrimpsSave.BACKUP)?.value
        return backup == null || TrimpsSave.acceptable(backup)
    }

    override suspend fun prepareRestore(payload: String): String = payload

    override suspend fun verifyLoaded(webView: WebView, importedPayload: String): Boolean {
        val peeked = peekStorage(webView)
        return peeked is PeekOutcome.Value && peeked.payload == importedPayload
    }

    override suspend fun peekStorage(webView: WebView): PeekOutcome {
        val raw = try {
            withTimeoutOrNull(5_000L) { evaluateJs(webView, JS_PEEK) }
        } catch (_: Exception) {
            return PeekOutcome.Failed
        } ?: return PeekOutcome.Failed
        val text = unwrapJsResult(raw) ?: return PeekOutcome.Failed
        if (text == "failed") return PeekOutcome.Failed
        if (text == "empty") return PeekOutcome.Empty
        val json = try {
            JSONObject(text)
        } catch (_: Exception) {
            return PeekOutcome.Failed
        }
        if (json.isNull("primary")) return PeekOutcome.Empty
        val primary = json.optString("primary", "")
        val backup = if (json.isNull("backup")) null else json.optString("backup", "").ifEmpty { null }
        return PeekOutcome.Value(
            SaveEnvelope.encode(
                listOf(
                    StorageSlot(TrimpsSave.PRIMARY, primary),
                    StorageSlot(TrimpsSave.BACKUP, backup),
                ),
            ),
        )
    }

    override suspend fun confirmBootstrap(webView: WebView): Boolean {
        val raw = withTimeoutOrNull(2_000L) { evaluateJs(webView, JS_BOOTSTRAP) }
        val marker = unwrapJsResult(raw)
        return StorageBootstrap.markerConfirms(marker)
    }

    override fun summarize(payload: String?): SaveSummary {
        if (payload.isNullOrBlank()) {
            return SaveSummary(SaveStanding.ABSENT, null, "No save", 0)
        }
        if (!envelopeOk(payload)) {
            return SaveSummary(SaveStanding.INVALID, null, "Trimps save rejected", payload.length)
        }
        val primary = when (val decoded = SaveEnvelope.decode(payload, envelopePolicy())) {
            is EnvelopeDecode.Ok -> decoded.bundle.slot(TrimpsSave.PRIMARY)?.value
            is EnvelopeDecode.Rejected -> null
        } ?: return SaveSummary(SaveStanding.INVALID, null, "Trimps save rejected", payload.length)
        val markers = TrimpsSave.markers(primary)
            ?: return SaveSummary(SaveStanding.INVALID, null, "Trimps save rejected", payload.length)
        val backup = when (val decoded = SaveEnvelope.decode(payload, envelopePolicy())) {
            is EnvelopeDecode.Ok -> decoded.bundle.slot(TrimpsSave.BACKUP)?.value
            is EnvelopeDecode.Rejected -> null
        }
        val standing = if (markers.fresh) SaveStanding.FRESH else SaveStanding.PROGRESSED
        val detail = "version=${markers.stringVersion} universe=${markers.universe} " +
            "portals=${markers.totalPortals} zone=${markers.zone} " +
            "backup=${if (backup == null) "absent" else "present"}"
        return SaveSummary(standing, null, detail, payload.length)
    }

    override fun compare(livePayload: String?, storedPayload: String?): SaveComparison {
        val storedStanding = summarize(storedPayload).standing
        if (storedStanding == SaveStanding.ABSENT) return SaveComparison.NO_SNAPSHOT
        val liveStanding = summarize(livePayload).standing
        if (liveStanding == SaveStanding.ABSENT) return SaveComparison.LIVE_MISSING
        if (liveStanding == SaveStanding.INVALID || storedStanding == SaveStanding.INVALID) {
            return SaveComparison.INCONCLUSIVE
        }
        if (livePayload != null && livePayload == storedPayload) return SaveComparison.MATCH
        val live = primaryMarkers(livePayload) ?: return SaveComparison.INCONCLUSIVE
        val stored = primaryMarkers(storedPayload) ?: return SaveComparison.INCONCLUSIVE
        return TrimpsSave.compare(live, stored)
    }

    override suspend fun acceptsRuntime(webView: WebView, importedPayload: String): Boolean {
        val snapshot = primaryMarkers(importedPayload) ?: return false
        val runtime = readRuntime(webView) ?: return false
        if (runtime.warping) return false
        return TrimpsSave.accepts(snapshot, runtime.markers)
    }

    override suspend fun runtimeVerdict(webView: WebView, importedPayload: String): RuntimeVerdict {
        val deadline = System.currentTimeMillis() + WARP_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            val runtime = readRuntime(webView) ?: return RuntimeVerdict.REJECTED
            if (!runtime.warping) {
                val snapshot = primaryMarkers(importedPayload) ?: return RuntimeVerdict.REJECTED
                return if (TrimpsSave.accepts(snapshot, runtime.markers)) {
                    RuntimeVerdict.ACCEPTED
                } else {
                    RuntimeVerdict.REJECTED
                }
            }
            delay(500L)
        }
        val still = readRuntime(webView)
        return if (still?.warping == true) RuntimeVerdict.DEFERRED else RuntimeVerdict.REJECTED
    }

    private fun primaryMarkers(payload: String?): TrimpsSave.Markers? {
        if (payload.isNullOrBlank()) return null
        val decoded = SaveEnvelope.decode(payload, envelopePolicy())
        if (decoded !is EnvelopeDecode.Ok) return null
        val primary = decoded.bundle.slot(TrimpsSave.PRIMARY)?.value ?: return null
        return TrimpsSave.markers(primary)
    }

    private suspend fun readRuntime(webView: WebView): RuntimeView? {
        val raw = withTimeoutOrNull(5_000L) { evaluateJs(webView, JS_RUNTIME) } ?: return null
        val text = unwrapJsResult(raw) ?: return null
        val json = try {
            JSONObject(text)
        } catch (_: Exception) {
            return null
        }
        val markers = TrimpsSave.fromGlobal(json) ?: return null
        return RuntimeView(markers, json.optBoolean("warping", false))
    }

    private suspend fun evaluateJs(webView: WebView, script: String): String? {
        val deferred = CompletableDeferred<String?>()
        val callback: ValueCallback<String?> = ValueCallback { deferred.complete(it) }
        webView.evaluateJavascript(script, callback)
        return deferred.await()
    }

    private data class RuntimeView(val markers: TrimpsSave.Markers, val warping: Boolean)

    companion object {
        private const val WARP_WAIT_MS = 60_000L
        private val JS_PROBE =
            "(function(){ return typeof game !== 'undefined' && !!game && !!game.global; })()"
        internal val JS_READY =
            "(function(){" +
                "if (window.__hubStorageBootstrap) return false;" +
                "if (typeof game === 'undefined' || !game || !game.global) return false;" +
                "if (game.global.stringVersion !== '5.10.1') return false;" +
                "function visible(node){" +
                " if (!node) return false;" +
                " var display = window.getComputedStyle ? getComputedStyle(node).display : node.style.display;" +
                " if (display === 'none') return false;" +
                " var rect = node.getBoundingClientRect();" +
                " return rect.width > 1 && rect.height > 1;" +
                "}" +
                "var warp = document.getElementById('offlineWrapper');" +
                "if (visible(warp)) return true;" +
                "var food = document.getElementById('foodOwned');" +
                "return !!(food && (food.textContent || '').trim().length > 0 && visible(food));" +
                "})()"
        private val JS_CAPTURE =
            "(function(){" +
                "try {" +
                " if (typeof usingRealTimeOffline !== 'undefined' && usingRealTimeOffline) {" +
                "  return JSON.stringify({deferred:true});" +
                " }" +
                " if (typeof save !== 'function') return JSON.stringify({error:'no-save'});" +
                " save();" +
                " var primary = localStorage.getItem('trimpSave1');" +
                " var backup = localStorage.getItem('trimpBackup1');" +
                " return JSON.stringify({primary: primary, backup: backup});" +
                "} catch (e) { return JSON.stringify({error:'storage'}); }" +
                "})()"
        private val JS_PEEK =
            "(function(){" +
                "try {" +
                " var primary = localStorage.getItem('trimpSave1');" +
                " if (primary == null) return 'empty';" +
                " return JSON.stringify({primary: primary, backup: localStorage.getItem('trimpBackup1')});" +
                "} catch (e) { return 'failed'; }" +
                "})()"
        private val JS_BOOTSTRAP =
            "(function(){ return window.__hubStorageBootstrap || ''; })()"
        private val JS_RUNTIME =
            "(function(){" +
                "if (typeof game === 'undefined' || !game || !game.global) return null;" +
                "var g = game.global;" +
                "return JSON.stringify({" +
                " stringVersion: g.stringVersion," +
                " version: g.version," +
                " universe: g.universe," +
                " totalPortals: g.totalPortals," +
                " highestLevelCleared: g.highestLevelCleared," +
                " highestRadonLevelCleared: g.highestRadonLevelCleared," +
                " totalHeliumEarned: g.totalHeliumEarned," +
                " totalRadonEarned: g.totalRadonEarned," +
                " challengeActive: g.challengeActive || ''," +
                " runningChallengeSquared: !!g.runningChallengeSquared," +
                " isBeta: !!g.isBeta," +
                " warping: typeof usingRealTimeOffline !== 'undefined' && !!usingRealTimeOffline" +
                "});" +
                "})()"
    }
}
