package com.example.hub

import android.os.ParcelFileDescriptor
import android.util.Base64
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import com.example.hub.games.DocumentLayout
import com.example.hub.games.EnvelopeDecode
import com.example.hub.games.ImportOutcome
import com.example.hub.games.LzString
import com.example.hub.games.PayloadDigest
import com.example.hub.games.PeekOutcome
import com.example.hub.games.QualificationResult
import com.example.hub.games.SaveComparison
import com.example.hub.games.SaveEnvelope
import com.example.hub.games.SynergismAdapter
import com.example.hub.games.SynergismSave
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Disposable Synergism profile on the fold AVD. Records hashes and marker
 * summaries. The save text is written to the app files dir for the fixture
 * and is not copied into the report.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SynergismQualificationTest {
    private val adapter = SynergismAdapter()
    private val ticket = QualificationResult("synergism", "Synergism", emptyList())

    @Test
    fun liveSession() {
        val lines = ArrayList<String>()
        shell("cmd device_state state 2")
        lines += "postureStart=" + shell("wm size").replace("\n", " ")
        lines += "reference=${SynergismSave.REFERENCE_LABEL}"
        lines += "upstream=${SynergismSave.UPSTREAM}"
        recordCatalogue(lines)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            runCatching { ProfileStore.getInstance().deleteProfile(PROFILE) }
            ProfileStore.getInstance().getOrCreateProfile(PROFILE)
        }
        ActivityScenario.launch(WebViewProbeActivity::class.java).use { scenario ->
            val finishes = AtomicInteger(0)
            val web = addWeb(scenario, finishes)
            val ready = waitReady(web, 180_000)
            val probe = json(eval(web, PROBE))
            val versionOk = probe.optString("versionText").contains(SynergismSave.REFERENCE)
            lines += "case1=" + if (ready && versionOk && probe.optString("title").contains("Synergism")) "PASS" else "FAIL"
            lines += "versionText=" + probe.optString("versionText").take(180)
            lines += "title=" + probe.optString("title")
            lines += "warping=" + probe.optBoolean("warping")
            lines += "dialog=" + probe.optBoolean("dialog")
            lines += "recovery=" + probe.optBoolean("recovery")
            if (probe.optBoolean("dialog")) {
                shot("synergism-offline-dialog")
                eval(web, "var n=document.getElementById('exitOffline'); if(n) n.click(); 'ok'")
                Thread.sleep(500)
            }
            dismissDialogs(web)
            shot("synergism-playable")
            if (!ready || !versionOk) {
                lines += "case2=BLOCKED no-ready-game"
                blockedRest(lines)
                writeReport(lines)
                assertTrue(lines.joinToString("\n"), false)
            }
            var stored = eval(web, "localStorage.getItem('Synergysave2') || ''")
            if (stored.isEmpty()) {
                val seeded = capture(web)
                stored = primaryOf(seeded) ?: ""
            }
            File(files(), RAW).writeText(stored)
            lines += "rawLength=" + stored.length
            lines += "rawHash=" + PayloadDigest.sha256OrAbsent(stored.ifEmpty { null })
            describeSave(stored, lines)
            val before = markers(stored)
            val first = capture(web)
            val firstSave = primaryOf(first)
            val firstOk = first != null && valid(first) && firstSave != null && SynergismSave.acceptable(firstSave)
            lines += "case2=" + if (firstOk) "PASS" else "FAIL"
            lines += "case2Hash=" + digest(first)
            lines += "case2Length=" + (first?.length ?: -1)
            lines += "case2Summary=" + summary(first)
            lines += "case2Rejection=" + (firstSave?.let { SynergismSave.rejection(it) } ?: "no-save")
            if (firstSave != null) File(files(), RAW).writeText(firstSave)
            val after = markers(firstSave)
            val noExportBonus = before != null && after != null &&
                before.worlds == after.worlds &&
                before.golden == after.golden &&
                before.ever == after.ever
            lines += "case14=" + if (firstOk && noExportBonus) "PASS" else "FAIL"
            lines += "case14Worlds=" + before?.worlds + "->" + after?.worlds
            lines += "case14Golden=" + before?.golden + "->" + after?.golden
            val storedNow = eval(web, "localStorage.getItem('Synergysave2') || ''")
            val roundTrip = first != null && firstSave != null &&
                adapterPrepare(first) == first &&
                primaryOf(first) == firstSave &&
                slotCount(first) == 1
            lines += "case4=" + if (firstOk && roundTrip) "PASS" else "FAIL"
            lines += "case4Storage=" + if (storedNow == firstSave) "same" else "moved"
            if (firstSave != null) {
                val real = firstSave
                eval(web, "localStorage.setItem('Synergysave2', 'AAAA'); 'ok'")
                val rejected = peek(web)
                val rejectedOk = rejected is PeekOutcome.Value && !valid(rejected.payload)
                eval(web, "localStorage.setItem('Synergysave2', ${JSONObject.quote(real)}); 'ok'")
                val restored = eval(web, "localStorage.getItem('Synergysave2') || ''") == real
                val truncated = !SynergismSave.acceptable(real.dropLast(8).dropWhile { it == '=' })
                val future = !SynergismSave.acceptable(rewrite(real) { it.put("version", "4.5.0 future") })
                val testingSave = !SynergismSave.acceptable(rewrite(real) { it.put("exporttest", false) })
                val legacy = !SynergismSave.acceptable(
                    rewrite(real) { it.put("corruptions", JSONObject().put("used", JSONObject().put("viscosity", 1))) },
                )
                lines += "case5=" + if (rejectedOk && restored && truncated && future && testingSave && legacy) "PASS" else "FAIL"
                lines += "case5Flags=peek:$rejectedOk restored:$restored truncated:$truncated future:$future testing:$testingSave legacy:$legacy"
                val sings = envelope(rewrite(real) {
                    it.put("singularityCount", 1)
                    it.put("highestSingularityCount", 1)
                })
                val quarks = envelope(rewrite(real) {
                    it.put("worlds", 5)
                    it.put("goldenQuarks", 1)
                    it.put("totalQuarksEver", 5)
                })
                val comparison = adapter.compare(sings, quarks)
                val chosen = adapter.chooseStored(listOf(sings, quarks))
                val sameMarkers = envelope(rewrite(real) { it.put("coins", "hub-marker-only") })
                val tied = adapter.compare(sameMarkers, envelope(real))
                lines += "case6=" + if (
                    comparison == SaveComparison.INCONCLUSIVE &&
                    chosen == null &&
                    tied == SaveComparison.INCONCLUSIVE
                ) "PASS" else "FAIL"
                lines += "case6Comparison=$comparison chosen=${chosen == null} tied=$tied"
            } else {
                lines += "case5=BLOCKED no-save"
                lines += "case6=BLOCKED no-save"
            }
            if (first != null && firstSave != null) {
                runImportCases(web, firstSave, lines)
            } else {
                lines += "case13=BLOCKED no-save"
            }
            runOfflineCases(web, finishes, lines)
            runFoldCase(web, finishes, lines)
            val pointer = capture(web)
            val pointerHash = digest(pointer)
            eval(web, "(function(){ var b=document.getElementById('savegame'); if(!b||!b.parentNode) return 'missing'; var n=b.cloneNode(true); b.parentNode.replaceChild(n,b); return 'replaced'; })()")
            val failed = capture(web)
            val kept = digest(pointer) == pointerHash && failed == null && adapter.captureDecline().isNullOrBlank()
            lines += "case10=" + if (pointer != null && kept) "PASS" else "FAIL"
            lines += "case10Capture=" + (if (failed == null) "null" else "wrote")
            lines += "case10Decline=" + adapter.captureDecline()
            val resume = eval(web, "localStorage.getItem('Synergysave2') || ''")
            val resumeMarks = markers(resume)
            File(files(), RESUME).writeText(
                "hash=" + PayloadDigest.sha256(resume) + "\n" +
                    "singularity=" + (resumeMarks?.singularity ?: -1) + "\n" +
                    "highest=" + (resumeMarks?.highest ?: -1) + "\n" +
                    "worlds=" + (resumeMarks?.worlds ?: "missing") + "\n" +
                    "golden=" + (resumeMarks?.golden ?: "missing") + "\n" +
                    "ever=" + (resumeMarks?.ever ?: "missing") + "\n",
            )
            lines += "resumeHash=" + PayloadDigest.sha256(resume)
            lines += "resumeMarkers=s${resumeMarks?.singularity}/w${resumeMarks?.worlds}"
            lines += "case15=HOST unit suite is recorded outside this process"
            scenario.onActivity {
                (web.parent as? android.view.ViewGroup)?.removeView(web)
                web.loadUrl("about:blank")
                web.destroy()
            }
        }
        writeReport(lines)
        val text = lines.joinToString("\n")
        assertFalse(text, text.contains("=FAIL") || text.contains("=BLOCKED"))
    }

    @Test
    fun resumeAfterProcessDeath() {
        val expected = File(files(), RESUME)
        val lines = ArrayList<String>()
        if (!expected.isFile) {
            lines += "case3=BLOCKED no-prior-snapshot"
            writeReport(lines, "synergism-resume.txt")
            assertTrue(lines.joinToString("\n"), false)
        }
        val remembered = expected.readLines().associate {
            val parts = it.split("=", limit = 2)
            parts[0] to parts.getOrElse(1) { "" }
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ProfileStore.getInstance().getOrCreateProfile(PROFILE)
        }
        ActivityScenario.launch(WebViewProbeActivity::class.java).use { scenario ->
            val web = addWeb(scenario, AtomicInteger(0))
            val ready = waitReady(web, 180_000)
            dismissDialogs(web)
            val live = eval(web, "localStorage.getItem('Synergysave2') || ''")
            val liveMarks = markers(live)
            val sameIdentity = liveMarks != null &&
                liveMarks.singularity.toString() == remembered["singularity"] &&
                liveMarks.highest.toString() == remembered["highest"] &&
                atLeast(liveMarks.worlds, remembered["worlds"]) &&
                atLeast(liveMarks.golden, remembered["golden"]) &&
                atLeast(liveMarks.ever, remembered["ever"])
            lines += "case3=" + if (ready && sameIdentity) "PASS" else "FAIL"
            lines += "case3Ready=$ready"
            lines += "case3Probe=" + eval(web, PROBE).take(240)
            lines += "case3Hash=" + PayloadDigest.sha256(live)
            lines += "case3ExpectedHash=" + remembered["hash"]
            lines += "case3Summary=" + summary(envelope(live))
            scenario.onActivity {
                (web.parent as? android.view.ViewGroup)?.removeView(web)
                web.loadUrl("about:blank")
                web.destroy()
            }
        }
        deleteDisposable()
        writeReport(lines, "synergism-resume.txt")
        assertFalse(lines.joinToString("\n"), lines.joinToString("\n").contains("=FAIL"))
    }

    private fun runImportCases(web: WebView, save: String, lines: MutableList<String>) {
        eval(web, "localStorage.setItem('language', 'hub-canary'); 'ok'")
        val dispatched = dispatchImport(web, save)
        val settled = waitReady(web, 90_000)
        if (json(eval(web, PROBE)).optBoolean("dialog")) {
            eval(web, "var n=document.getElementById('exitOffline'); if(n) n.click(); 'ok'")
        }
        val imported = capture(web)
        val importedSave = primaryOf(imported)
        val decoded = importedSave?.let { SynergismSave.body(it) }
        val markersHeld = decoded != null &&
            decoded.optLong("singularityCount") == SynergismSave.body(save)?.optLong("singularityCount") &&
            decoded.optDouble("worlds") == SynergismSave.body(save)?.optDouble("worlds")
        val jsonText = try {
            String(Base64.decode(save, Base64.DEFAULT), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
        val lz = if (jsonText.startsWith("{")) LzString.compressToBase64(jsonText) else ""
        eval(web, "localStorage.setItem('language', 'hub-canary'); 'ok'")
        val lzDispatch = if (lz.length > 20) dispatchImport(web, lz) else ImportOutcome.Dispatched("no-lz")
        waitReady(web, 90_000)
        dismissDialogs(web)
        val afterLz = eval(web, "localStorage.getItem('Synergysave2') || ''")
        val lzJson = runCatching { SynergismSave.body(afterLz) != null }.getOrDefault(false)
        val canary = eval(web, "localStorage.getItem('language') || ''")
        val beforeInvalid = afterLz
        val bad = rewrite(save) { it.put("exporttest", false) }
        val badDispatch = dispatchImport(web, bad)
        Thread.sleep(1_500)
        dismissDialogs(web)
        val afterBad = eval(web, "localStorage.getItem('Synergysave2') || ''")
        val canaryAfter = eval(web, "localStorage.getItem('language') || ''")
        val ok = dispatched.detail == "dispatched" &&
            settled &&
            imported != null &&
            markersHeld &&
            lzDispatch.detail == "dispatched" &&
            lzJson &&
            canary == "hub-canary" &&
            badDispatch.detail == "dispatched" &&
            afterBad == beforeInvalid &&
            canaryAfter == "hub-canary"
        lines += "case13=" + if (ok) "PASS" else "FAIL"
        lines += "case13Import=${dispatched.detail} settled=$settled markers=$markersHeld"
        lines += "case13Lz=${lzDispatch.detail} json=$lzJson canary=$canary"
        lines += "case13Bad=${badDispatch.detail} kept=${afterBad == beforeInvalid} canaryAfter=$canaryAfter"
        lines += "case13Hash=" + digest(imported)
    }

    private fun runOfflineCases(web: WebView, finishes: AtomicInteger, lines: MutableList<String>) {
        eval(web, "localStorage.removeItem('language'); 'ok'")
        val before = markers(eval(web, "localStorage.getItem('Synergysave2') || ''"))
        val aged = eval(
            web,
            "(function(){ try {" +
                " var raw=localStorage.getItem('Synergysave2');" +
                " var json=JSON.parse(atob(raw));" +
                " json.offlinetick=Date.now()-7200000;" +
                " localStorage.setItem('Synergysave2', btoa(JSON.stringify(json)));" +
                " return 'aged'; } catch (e) { return 'err:'+e; } })()",
        )
        if (aged != "aged") {
            lines += "case7=BLOCKED age-edit-failed:$aged"
            lines += "case8=BLOCKED"
            lines += "case9=BLOCKED"
            return
        }
        val loadsBefore = finishes.get()
        web.post { web.loadUrl(URL) }
        var sawWarp = false
        val deadline = System.currentTimeMillis() + 90_000
        var during: String? = null
        var duringDecline = ""
        while (System.currentTimeMillis() < deadline) {
            eval(web, NUDGE)
            val state = json(eval(web, PROBE))
            if (state.optBoolean("warping") && !sawWarp) {
                sawWarp = true
                during = capture(web)
                duringDecline = adapter.captureDecline().orEmpty()
            }
            val reloaded = finishes.get() > loadsBefore
            val settled = reloaded && !state.optBoolean("warping") &&
                (state.optBoolean("dialog") || state.optString("versionText").contains(SynergismSave.REFERENCE))
            if (settled) break
            Thread.sleep(150)
        }
        shot("synergism-offline-aged")
        val probe = json(eval(web, PROBE))
        val dialogOrReady = probe.optBoolean("dialog") ||
            (probe.optString("versionText").contains(SynergismSave.REFERENCE) && !probe.optBoolean("warping"))
        lines += "case7=" + if (dialogOrReady) "PASS" else "FAIL"
        lines += "case7Field=offlinetick"
        lines += "case7Dialog=" + probe.optBoolean("dialog") + " warping=" + probe.optBoolean("warping")
        lines += "case7Version=" + probe.optString("versionText").take(80)
        lines += "case7Reloads=" + (finishes.get() - loadsBefore)
        if (sawWarp) {
            lines += "case8=" + if (during == null && duringDecline.contains("deferred")) "PASS" else "FAIL"
            lines += "case8Decline=$duringDecline"
        } else {
            lines += "case8=BLOCKED warp-not-observed"
        }
        dismissDialogs(web)
        val afterCapture = capture(web)
        val afterMarkers = markers(primaryOf(afterCapture))
        val singularityHeld = before != null && afterMarkers != null &&
            afterMarkers.singularity >= before.singularity &&
            afterMarkers.highest >= before.highest &&
            valid(afterCapture ?: "")
        lines += "case9=" + if (singularityHeld) "PASS" else "FAIL"
        lines += "case9Hash=" + digest(afterCapture)
        lines += "case9Singularity=" + before?.singularity + "->" + afterMarkers?.singularity
    }

    private fun runFoldCase(web: WebView, finishes: AtomicInteger, lines: MutableList<String>) {
        dismissDialogs(web)
        eval(web, "var tab=document.getElementById('settingstab'); if(tab) tab.click(); 'ok'")
        Thread.sleep(300)
        val before = eval(web, "localStorage.getItem('Synergysave2') || ''")
        val beforeMarkers = markers(before)
        val loads = finishes.get()
        val identity = System.identityHashCode(web)
        val foldedScale = posture("0", "synergism-folded", web, lines, "folded")
        posture("2", "synergism-unfolded", web, lines, "unfolded")
        val foldedAgain = posture("0", "synergism-folded-again", web, lines, "foldedAgain")
        val after = eval(web, "localStorage.getItem('Synergysave2') || ''")
        val afterMarkers = markers(after)
        val sameDocument = identity == System.identityHashCode(web) && finishes.get() == loads
        val sameProgress = beforeMarkers != null && afterMarkers != null &&
            beforeMarkers.singularity == afterMarkers.singularity &&
            beforeMarkers.highest == afterMarkers.highest &&
            beforeMarkers.worlds == afterMarkers.worlds &&
            beforeMarkers.golden == afterMarkers.golden &&
            beforeMarkers.ever == afterMarkers.ever
        val zoomHeld = foldedScale > 0f && kotlin.math.abs(foldedScale - foldedAgain) < 0.05f && foldedAgain < 1.25f
        val readable = lines.any { it.startsWith("folded=") && it.contains("saveOk=true") && it.contains("buildOk=true") }
        lines += "case11=" + if (sameDocument && sameProgress && zoomHeld && readable) "PASS" else "FAIL"
        lines += "case11Loads=$loads/${finishes.get()} zoom=$foldedScale->$foldedAgain progress=$sameProgress bytesSame=${before == after}"
    }

    private fun posture(state: String, name: String, web: WebView, lines: MutableList<String>, label: String): Float {
        shell("cmd device_state state $state")
        Thread.sleep(1_500)
        eval(web, DocumentLayout.injectionScript(0))
        dismissDialogs(web)
        eval(web, "var tab=document.getElementById('buildingstab'); if(tab) tab.click(); 'ok'")
        Thread.sleep(400)
        val size = shell("wm size").replace("\n", " ")
        shot(name)
        val box = json(eval(web, BOUNDS))
        lines += "$label=$size save=" + box.optString("save") + " build=" + box.optString("build") +
            " saveOk=" + box.optBoolean("saveOk") + " buildOk=" + box.optBoolean("buildOk") +
            " scale=" + box.optString("scale")
        eval(web, "var tab=document.getElementById('buildingstab'); if(tab) tab.click(); 'ok'")
        return box.optDouble("scale", -1.0).toFloat()
    }

    private fun describeSave(encoded: String, lines: MutableList<String>) {
        val json = SynergismSave.body(encoded)
        if (json == null) {
            lines += "saveShape=undecoded rejection=" + SynergismSave.rejection(encoded)
            return
        }
        val used = json.optJSONObject("corruptions")?.opt("used")
        lines += "fieldCount=" + json.length()
        lines += "versionField=" + json.optString("version")
        lines += "exporttest=" + json.opt("exporttest")
        lines += "corruptionsUsed=" + (used?.javaClass?.simpleName ?: "missing")
        lines += "rejection=" + (SynergismSave.rejection(encoded) ?: "none")
    }

    private fun recordCatalogue(lines: MutableList<String>) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            Thread.sleep(8_000)
            scenario.onActivity { activity ->
                assertTrue(activity.window.decorView.isAttachedToWindow)
            }
            shell("uiautomator dump /sdcard/hub-ui.xml")
            shot("synergism-launcher")
            Thread.sleep(400)
            val xml = shell("cat /sdcard/hub-ui.xml")
            lines += "catalogueDump=" + if (xml.contains("Synergism") || xml.contains("play-synergism")) "saw-text" else "miss"
        }
    }

    private fun blockedRest(lines: MutableList<String>) {
        listOf("case4", "case5", "case6", "case7", "case8", "case9", "case10", "case11", "case13", "case14").forEach {
            lines += "$it=BLOCKED"
        }
    }

    private fun dismissDialogs(web: WebView) {
        eval(
            web,
            "(function(){ var exit=document.getElementById('exitOffline'); if(exit) exit.click();" +
                " var ok=document.getElementById('ok_alert'); if(ok) ok.click();" +
                " var closes=document.querySelectorAll('#announcementOverlay .announcement-close');" +
                " for (var i=0;i<closes.length;i++) closes[i].click();" +
                " return 'ok'; })()",
        )
        Thread.sleep(300)
    }

    private fun rewrite(encoded: String, edit: (JSONObject) -> Unit): String {
        val json = JSONObject(String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8))
        edit(json)
        return Base64.encodeToString(json.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private fun envelope(save: String): String =
        SaveEnvelope.encode(listOf(com.example.hub.games.StorageSlot(SynergismSave.KEY, save)))

    private fun markers(save: String?): SaveMarks? {
        if (save.isNullOrEmpty()) return null
        val parsed = SynergismSave.markers(save) ?: return null
        return SaveMarks(
            parsed.singularityCount,
            parsed.highestSingularityCount,
            parsed.worlds.toPlainString(),
            parsed.goldenQuarks.toPlainString(),
            parsed.totalQuarksEver.toPlainString(),
        )
    }

    private fun capture(web: WebView): String? = runBlocking {
        withContext(Dispatchers.Main) { adapter.capture(web, ticket) }
    }

    private fun peek(web: WebView): PeekOutcome = runBlocking {
        withContext(Dispatchers.Main) { adapter.peekStorage(web) }
    }

    private fun atLeast(live: String, remembered: String?): Boolean {
        if (remembered == null) return false
        return live.toBigDecimal() >= remembered.toBigDecimal()
    }

    private fun dispatchImport(web: WebView, text: String): ImportOutcome.Dispatched = runBlocking {
        withContext(Dispatchers.Main) {
            adapter.nativeImport(web, text) as ImportOutcome.Dispatched
        }
    }

    private fun valid(payload: String): Boolean = runBlocking { adapter.validate(payload) }

    private fun summary(payload: String?): String =
        if (payload == null) "none" else adapter.summarize(payload).detail

    private fun adapterPrepare(payload: String): String = runBlocking { adapter.prepareRestore(payload) }

    private fun slotCount(payload: String): Int {
        val decoded = SaveEnvelope.decode(payload, adapter.envelopePolicy())
        return (decoded as? EnvelopeDecode.Ok)?.bundle?.slots?.count { it.value != null } ?: -1
    }

    private fun primaryOf(payload: String?): String? {
        if (payload == null) return null
        val decoded = SaveEnvelope.decode(payload, adapter.envelopePolicy())
        return (decoded as? EnvelopeDecode.Ok)?.bundle?.slot(SynergismSave.KEY)?.value
    }

    private fun digest(payload: String?): String {
        if (payload == null) return "absent"
        return "envelope=" + PayloadDigest.sha256(payload) +
            " primary=" + PayloadDigest.sha256OrAbsent(primaryOf(payload)) +
            " len=" + payload.length
    }

    private fun waitReady(web: WebView, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            eval(web, NUDGE)
            if (eval(web, SynergismAdapter.JS_READY) == "true") return true
            Thread.sleep(400)
        }
        return false
    }

    private fun addWeb(scenario: ActivityScenario<WebViewProbeActivity>, finishes: AtomicInteger): WebView {
        val ref = AtomicReference<WebView>()
        val latch = CountDownLatch(1)
        scenario.onActivity { activity ->
            val web = WebView(activity)
            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            web.settings.useWideViewPort = true
            web.settings.loadWithOverviewMode = true
            web.settings.textZoom = 100
            WebViewCompat.setProfile(web, PROFILE)
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    finishes.incrementAndGet()
                    if (view != null && url != null && url.contains("synergism.cc")) {
                        view.evaluateJavascript(DocumentLayout.injectionScript(0), null)
                    }
                }
            }
            activity.findViewById<android.view.ViewGroup>(android.R.id.content).addView(
                web,
                android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            web.loadUrl(URL)
            ref.set(web)
            latch.countDown()
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        return ref.get()
    }

    private fun eval(web: WebView, script: String): String {
        val latch = CountDownLatch(1)
        val ref = AtomicReference("")
        web.post {
            web.evaluateJavascript(script) { value ->
                ref.set(unwrap(value))
                latch.countDown()
            }
        }
        assertTrue(latch.await(30, TimeUnit.SECONDS))
        return ref.get()
    }

    private fun unwrap(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return try {
            val value = JSONArray("[$raw]").opt(0)
            value?.toString() ?: ""
        } catch (_: Exception) {
            raw.trim().removeSurrounding("\"")
        }
    }

    private fun json(text: String): JSONObject =
        try {
            JSONObject(text)
        } catch (_: Exception) {
            JSONObject()
        }

    private fun shot(name: String) {
        shell("screencap -p /sdcard/$name.png")
    }

    private fun writeReport(lines: List<String>, name: String = "synergism-qualification.txt") {
        val text = lines.joinToString("\n")
        File(files(), name).writeText(text)
        android.util.Log.i("SYNERGISM_QUAL", text)
    }

    private fun files(): File = InstrumentationRegistry.getInstrumentation().targetContext.filesDir

    private fun deleteDisposable() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            runCatching { ProfileStore.getInstance().deleteProfile(PROFILE) }
        }
    }

    private fun shell(command: String): String {
        val parcel: ParcelFileDescriptor =
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(parcel).use { stream ->
            InputStreamReader(stream).readText()
        }
    }

    private data class SaveMarks(
        val singularity: Long,
        val highest: Long,
        val worlds: String,
        val golden: String,
        val ever: String,
    )

    companion object {
        private const val PROFILE = "hub-qual-synergism"
        private const val URL = "https://synergism.cc/"
        private const val RESUME = "synergism-resume.txt"
        private const val RAW = "synergism-raw-save.txt"
        private val NUDGE =
            "(function(){ var play=document.getElementById('preloadPlayOffline');" +
                " if (play && getComputedStyle(play).display!=='none') { play.click(); return 'play'; }" +
                " var wrap=document.getElementById('confirmWrapper');" +
                " if (wrap && getComputedStyle(wrap).display==='block') {" +
                "  var ok=document.getElementById('ok_confirm'); if (ok) ok.click(); return 'confirm'; }" +
                " return 'none'; })()"
        private val PROBE =
            "(function(){ var ver=document.getElementById('versionnumber');" +
                " var exit=document.getElementById('exitOffline');" +
                " var exitHidden=!!(exit && exit.style.visibility==='hidden');" +
                " function shown(id){ var n=document.getElementById(id); if(!n) return false;" +
                "  var s=getComputedStyle(n); if(s.display==='none'||s.visibility==='hidden') return false;" +
                "  var r=n.getBoundingClientRect(); return r.width>1&&r.height>1; }" +
                " var dialog=shown('offlineContainer') && !exitHidden;" +
                " var loading=document.body && document.body.classList.contains('loading');" +
                " var warping=(shown('offlineContainer') && exitHidden) || (loading && shown('transparentBG') && !shown('offlineContainer'));" +
                " var recovery=shown('preloadRecovery');" +
                " return JSON.stringify({versionText:ver?(ver.textContent||''):'', title:document.title||''," +
                "  warping:warping, dialog:dialog, recovery:recovery}); })()"
        private val BOUNDS =
            "(function(){ function place(id){ var n=document.getElementById(id); if(!n) return null;" +
                " n.scrollIntoView({block:'center', inline:'nearest'});" +
                " var r=n.getBoundingClientRect(); var w=window.innerWidth||0; var h=window.innerHeight||0;" +
                " var wide=r.width>=16 && r.height>=16;" +
                " var horizontal=r.left>=-2 && r.right<=w+2;" +
                " var vertical=r.top<h && r.bottom>0;" +
                " return {text:Math.round(r.width)+'x'+Math.round(r.height)+'@'+Math.round(r.left)+','+Math.round(r.top)," +
                "  ok:!!(wide&&horizontal&&vertical)}; }" +
                " var row=document.getElementById('tabrow');" +
                " var toggle=document.getElementById('mobileMenuToggle');" +
                " if (row && toggle && getComputedStyle(row).display==='none') toggle.click();" +
                " var settings=document.getElementById('settingstab'); if (settings) settings.click();" +
                " var save=place('savegame'); var build=place('buildingstab');" +
                " return JSON.stringify({save:save?save.text:'missing', build:build?build.text:'missing'," +
                "  saveOk:!!(save&&save.ok), buildOk:!!(build&&build.ok)," +
                "  scale: window.visualViewport ? visualViewport.scale : 1}); })()"
    }
}
