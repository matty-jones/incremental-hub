package com.example.hub.games

import android.webkit.ValueCallback
import android.webkit.WebView
import com.example.hub.session.StorageBootstrap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Official Synergism 4.4.0 at `https://synergism.cc/`. One progress key,
 * `Synergysave2`. The production bundle does not publish `saveSynergy`.
 * The settings control calls that same save with `true`, which writes the
 * key and does not grant the export quark bonus.
 */
class SynergismAdapter : GameAdapter {
    override val adapterId: String = "synergism-adapter"
    override val adapterVersion: String = "1.0.0"
    override val storageKey: String = SynergismSave.KEY
    override val usesSaveEnvelope: Boolean = true
    override val readinessScript: String = JS_READY
    override val storageEntries: List<StorageEntry> = listOf(
        StorageEntry(SynergismSave.KEY, required = true, role = StorageRole.PROGRESS),
    )
    override val deniedStorageKeys: Set<String> = PREFERENCE_KEYS

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
            decline = "Capture deferred: offline simulation is running. Last good snapshot was kept."
            return null
        }
        if (json.has("error")) return null
        val save = json.optString("save", "")
        if (!SynergismSave.acceptable(save)) return null
        return SaveBundle(listOf(StorageSlot(SynergismSave.KEY, save)))
    }

    override suspend fun validate(payload: String): Boolean = envelopeOk(payload)

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
        return PeekOutcome.Value(
            SaveEnvelope.encode(listOf(StorageSlot(SynergismSave.KEY, text))),
        )
    }

    override suspend fun confirmBootstrap(webView: WebView): Boolean {
        val raw = withTimeoutOrNull(2_000L) { evaluateJs(webView, JS_BOOTSTRAP) }
        return StorageBootstrap.markerConfirms(unwrapJsResult(raw))
    }

    override fun summarize(payload: String?): SaveSummary {
        if (payload.isNullOrBlank()) {
            return SaveSummary(SaveStanding.ABSENT, null, "No save", 0)
        }
        val save = primary(payload)
        if (save == null) {
            return SaveSummary(SaveStanding.INVALID, null, "Synergism save rejected", payload.length)
        }
        val reason = SynergismSave.rejection(save)
        if (reason != null) {
            return SaveSummary(SaveStanding.INVALID, null, "Synergism save rejected: $reason", payload.length)
        }
        val markers = SynergismSave.markers(save)
            ?: return SaveSummary(SaveStanding.INVALID, null, "Synergism save rejected", payload.length)
        val standing = if (markers.fresh) SaveStanding.FRESH else SaveStanding.PROGRESSED
        val detail = "version=${markers.version} singularity=${markers.singularityCount} " +
            "highestSingularity=${markers.highestSingularityCount} worlds=${markers.worlds.toPlainString()} " +
            "goldenQuarks=${markers.goldenQuarks.toPlainString()} " +
            "totalQuarksEver=${markers.totalQuarksEver.toPlainString()} " +
            "offlinetick=${markers.offlineTick.toPlainString()}"
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
        return SynergismSave.compare(live, stored)
    }

    override fun chooseStored(candidates: List<String>): String? {
        val pointer = candidates.firstOrNull() ?: return null
        val standing = summarize(pointer).standing
        if (standing != SaveStanding.FRESH && standing != SaveStanding.PROGRESSED) return null
        for (other in candidates.drop(1)) {
            val otherStanding = summarize(other).standing
            if (otherStanding != SaveStanding.FRESH && otherStanding != SaveStanding.PROGRESSED) continue
            if (compare(other, pointer) == SaveComparison.INCONCLUSIVE) return null
        }
        return pointer
    }

    override suspend fun acceptsRuntime(webView: WebView, importedPayload: String): Boolean {
        val snapshot = primaryMarkers(importedPayload) ?: return false
        val runtime = readRuntime(webView) ?: return false
        if (runtime.warping) return false
        val markers = runtime.markers ?: return false
        return SynergismSave.accepts(snapshot, markers, runtime.pageVersion)
    }

    override suspend fun runtimeVerdict(webView: WebView, importedPayload: String): RuntimeVerdict {
        val snapshot = primaryMarkers(importedPayload) ?: return RuntimeVerdict.REJECTED
        val deadline = System.currentTimeMillis() + WARP_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            val runtime = readRuntime(webView) ?: return RuntimeVerdict.REJECTED
            if (!runtime.warping) {
                val markers = runtime.markers ?: return RuntimeVerdict.REJECTED
                return if (SynergismSave.accepts(snapshot, markers, runtime.pageVersion)) {
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

    override suspend fun nativeImport(webView: WebView, exportText: String): ImportOutcome {
        val script = "(function(){ try {" +
            " var text = ${JSONObject.quote(exportText)};" +
            " var input = document.getElementById('importfile');" +
            " if (!input || typeof DataTransfer === 'undefined' || typeof File === 'undefined') return 'no-input';" +
            " var file = new File([text], 'synergism-save.txt', {type:'text/plain'});" +
            " var transfer = new DataTransfer();" +
            " transfer.items.add(file);" +
            " input.files = transfer.files;" +
            " input.dispatchEvent(new Event('change', {bubbles:true}));" +
            " return 'dispatched';" +
            "} catch (e) { return 'err'; } })()"
        val raw = withTimeoutOrNull(10_000L) { evaluateJs(webView, script) } ?: return ImportOutcome.Dispatched("timeout")
        return ImportOutcome.Dispatched(unwrapJsResult(raw) ?: "empty")
    }

    private fun envelopeOk(payload: String): Boolean {
        val decoded = SaveEnvelope.decode(payload, envelopePolicy())
        if (decoded !is EnvelopeDecode.Ok) return false
        val save = decoded.bundle.slot(SynergismSave.KEY)?.value ?: return false
        return SynergismSave.acceptable(save)
    }

    private fun primary(payload: String): String? {
        val decoded = SaveEnvelope.decode(payload, envelopePolicy())
        if (decoded !is EnvelopeDecode.Ok) return null
        return decoded.bundle.slot(SynergismSave.KEY)?.value
    }

    private fun primaryMarkers(payload: String?): SynergismSave.Markers? {
        val save = payload?.let { primary(it) } ?: return null
        return SynergismSave.markers(save)
    }

    private suspend fun readRuntime(webView: WebView): RuntimeView? {
        val raw = withTimeoutOrNull(5_000L) { evaluateJs(webView, JS_RUNTIME) } ?: return null
        val text = unwrapJsResult(raw) ?: return null
        val json = try {
            JSONObject(text)
        } catch (_: Exception) {
            return null
        }
        val pageVersion = json.optString("versionText", "")
        val warping = json.optBoolean("warping", false)
        val save = if (json.isNull("save")) null else json.optString("save", "").ifEmpty { null }
        val markers = save?.let { SynergismSave.markers(it) }
        return RuntimeView(pageVersion, warping, markers)
    }

    private suspend fun evaluateJs(webView: WebView, script: String): String? {
        val deferred = CompletableDeferred<String?>()
        val callback: ValueCallback<String?> = ValueCallback { deferred.complete(it) }
        webView.evaluateJavascript(script, callback)
        return deferred.await()
    }

    private data class RuntimeView(
        val pageVersion: String,
        val warping: Boolean,
        val markers: SynergismSave.Markers?,
    )

    companion object {
        private const val WARP_WAIT_MS = 60_000L
        val PREFERENCE_KEYS: Set<String> = setOf(
            "language",
            "theme",
            "statSymbols",
            "copyToClipboard",
            "monospaceFont",
            "synergism-tab-order",
            "mobileHotkeyPanelCorner",
            "mobileHotkeyPanelEnabled",
        )
        private val JS_STATE =
            "function shown(id){" +
                " var node=document.getElementById(id); if(!node) return false;" +
                " var style=getComputedStyle(node);" +
                " if(style.display==='none'||style.visibility==='hidden') return false;" +
                " var rect=node.getBoundingClientRect();" +
                " return rect.width>1 && rect.height>1;" +
                "}" +
                "function warping(){" +
                " var exit=document.getElementById('exitOffline');" +
                " var exitHidden=!!(exit && exit.style.visibility==='hidden');" +
                " if(shown('offlineContainer') && exitHidden) return true;" +
                " if(shown('offlineContainer')) return false;" +
                " var loading=document.body && document.body.classList.contains('loading');" +
                " return !!(loading && shown('transparentBG'));" +
                "}" +
                "function recovery(){" +
                " var node=document.getElementById('preloadRecovery'); if(!node) return false;" +
                " var style=getComputedStyle(node);" +
                " if(style.display==='none'||style.visibility==='hidden') return false;" +
                " return node.getClientRects().length>0;" +
                "}"
        private val JS_PROBE =
            "(function(){ return !!document.getElementById('savegame') || " +
                "(document.title||'').indexOf('Synergism')>=0; })()"
        internal val JS_READY =
            "(function(){" + JS_STATE +
                " if (window.__hubStorageBootstrap) return false;" +
                " var ver=document.getElementById('versionnumber');" +
                " var text=ver?(ver.textContent||''):'';" +
                " if (text.indexOf('${SynergismSave.REFERENCE}')<0) return false;" +
                " if (recovery()) return false;" +
                " var play=document.getElementById('preloadPlayOffline');" +
                " if (play && getComputedStyle(play).display!=='none' && play.getClientRects().length>0) return false;" +
                " if (!document.getElementById('savegame')) return false;" +
                " if (warping()) return false;" +
                " return true;" +
                "})()"
        private val JS_CAPTURE =
            "(function(){" + JS_STATE +
                " var proto=Storage.prototype; var original=proto.setItem;" +
                " try {" +
                "  if (recovery()) return JSON.stringify({error:'recovery'});" +
                "  if (warping()) return JSON.stringify({deferred:true});" +
                "  var button=document.getElementById('savegame');" +
                "  if (!button) return JSON.stringify({error:'no-save'});" +
                "  var wrote=false;" +
                "  proto.setItem=function(key, value){" +
                "   if (this===localStorage && key==='${SynergismSave.KEY}') wrote=true;" +
                "   return original.apply(this, arguments);" +
                "  };" +
                "  button.click();" +
                "  if (!wrote) return JSON.stringify({error:'no-write'});" +
                "  var save=localStorage.getItem('${SynergismSave.KEY}');" +
                "  if (save==null) return JSON.stringify({error:'empty'});" +
                "  return JSON.stringify({save:save});" +
                " } catch (e) { return JSON.stringify({error:'storage'}); }" +
                " finally { proto.setItem=original; }" +
                "})()"
        private val JS_PEEK =
            "(function(){" +
                " try {" +
                "  var save=localStorage.getItem('${SynergismSave.KEY}');" +
                "  if (save==null) return 'empty';" +
                "  return save;" +
                " } catch (e) { return 'failed'; }" +
                "})()"
        private val JS_BOOTSTRAP =
            "(function(){ return window.__hubStorageBootstrap || ''; })()"
        private val JS_RUNTIME =
            "(function(){" + JS_STATE +
                " var ver=document.getElementById('versionnumber');" +
                " var save=null; var failed=false;" +
                " try { save=localStorage.getItem('${SynergismSave.KEY}'); }" +
                " catch (e) { failed=true; }" +
                " return JSON.stringify({versionText: ver?(ver.textContent||''):'', warping: warping(), failed: failed, save: save});" +
                "})()"
    }
}
