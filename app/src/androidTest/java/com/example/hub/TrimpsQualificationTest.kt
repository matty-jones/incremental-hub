package com.example.hub

import android.os.ParcelFileDescriptor
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import com.example.hub.games.PayloadDigest
import com.example.hub.games.PeekOutcome
import com.example.hub.games.QualificationResult
import com.example.hub.games.SaveComparison
import com.example.hub.games.SaveEnvelope
import com.example.hub.games.TrimpsAdapter
import com.example.hub.games.TrimpsLayout
import com.example.hub.games.TrimpsSave
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
 * Disposable Trimps profile on the fold AVD. Records hashes and lengths only.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class TrimpsQualificationTest {
    private val adapter = TrimpsAdapter()
    private val ticket = QualificationResult("trimps", "Trimps", emptyList())

    @Test
    fun liveSession() {
        val lines = ArrayList<String>()
        shell("cmd device_state state 2")
        lines += "postureStart=" + shell("wm size").replace("\n", " ")
        recordCatalogue(lines)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            runCatching { ProfileStore.getInstance().deleteProfile(PROFILE) }
            ProfileStore.getInstance().getOrCreateProfile(PROFILE)
        }
        ActivityScenario.launch(WebViewProbeActivity::class.java).use { scenario ->
            val finishes = AtomicInteger(0)
            val web = addWeb(scenario, finishes)
            val ready = waitReady(web, 120_000)
            val probe = json(eval(web, PROBE))
            lines += "case1=" + if (ready && probe.optString("version") == TrimpsSave.REFERENCE) "PASS" else "FAIL"
            lines += "version=" + probe.optString("version")
            lines += "food=" + probe.optString("food")
            lines += "warping=" + probe.optBoolean("warping")
            lines += "title=" + probe.optString("title")
            if (!ready) {
                lines += "case2=BLOCKED no-ready-game"
                writeReport(lines)
                assertTrue(lines.joinToString("\n"), false)
            }
            if (probe.optBoolean("warping")) {
                shot("trimps-warp-unfolded")
                val deferred = capture(web)
                lines += "case9=" + if (deferred == null && decline().contains("deferred")) "PASS" else "FAIL"
                lines += "case9Decline=" + decline()
                dismissWarp(web)
            }
            val first = capture(web)
            val firstOk = first != null && valid(first)
            lines += "case2=" + if (firstOk) "PASS" else "FAIL"
            lines += "case2Hash=" + digest(first)
            lines += "case2Length=" + (first?.length ?: -1)
            lines += "case2Summary=" + summary(first)
            lines += "case4=" + if (firstOk && summary(first).contains("backup=absent")) "PASS" else "FAIL"
            if (first != null) {
                val backup = captureAfter(web, "localStorage.setItem('trimpBackup1', localStorage.getItem('trimpSave1')); 'ok'")
                lines += "case5=" + if (backup != null && valid(backup) && summary(backup).contains("backup=present")) "PASS" else "FAIL"
                lines += "case5Hash=" + digest(backup)
                eval(web, "localStorage.removeItem('trimpBackup1'); 'ok'")
            } else {
                lines += "case5=BLOCKED no-primary"
            }
            val real = eval(web, "localStorage.getItem('trimpSave1') || ''")
            eval(web, "localStorage.setItem('trimpSave1', 'AAAA'); 'ok'")
            val rejected = peek(web)
            val rejectedOk = rejected is PeekOutcome.Value && !valid(rejected.payload)
            eval(web, "localStorage.setItem('trimpSave1', ${JSONObject.quote(real)}); 'ok'")
            lines += "case6=" + if (real.isNotEmpty() && rejectedOk) "PASS" else "FAIL"
            lines += "case6Length=" + if (rejected is PeekOutcome.Value) rejected.payload.length else -1
            val other = captureAfter(web, "game.global.universe = 2; 'ok'")
            val comparison = if (first != null && other != null) adapter.compare(other, first) else null
            lines += "case7=" + if (comparison == SaveComparison.INCONCLUSIVE) "PASS" else "FAIL"
            lines += "case7Comparison=" + comparison
            if (real.isNotEmpty()) {
                eval(web, "load(${JSONObject.quote(real)}); 'ok'")
                Thread.sleep(1_500)
            }
            val exported = eval(web, "(function(){ try { return save(true) || ''; } catch (e) { return ''; } })()")
            val imported = if (exported.length > 20) {
                eval(web, "try { load(${JSONObject.quote(exported)}); 'loaded'; } catch (e) { 'err'; }")
            } else {
                "no-export"
            }
            Thread.sleep(1_000)
            val afterImport = capture(web)
            lines += "importCall=" + imported
            lines += "importCaptured=" + if (afterImport != null && valid(afterImport)) "yes" else "no"
            lines += "importHash=" + digest(afterImport)
            val aged = eval(
                web,
                "(function(){ try {" +
                    " game.global.timeWarpLimit = 1;" +
                    " game.options.menu.offlineProgress.enabled = 1;" +
                    " save();" +
                    " var raw = localStorage.getItem('trimpSave1');" +
                    " var json = JSON.parse(LZString.decompressFromBase64(raw));" +
                    " json.global.lastOnline = Date.now() - 7200000;" +
                    " json.global.timeWarpLimit = 1;" +
                    " localStorage.setItem('trimpSave1', LZString.compressToBase64(JSON.stringify(json)));" +
                    " return 'aged'; } catch (e) { return 'err'; } })()",
            )
            if (aged == "aged") {
                val loadsBefore = finishes.get()
                web.post { web.loadUrl(URL) }
                val warpUp = waitUntil(20_000, 100) {
                    json(eval(web, PROBE)).optBoolean("warping") ||
                        eval(web, "(function(){ var n=document.getElementById('offlineWrapper'); if(!n) return '0'; var r=n.getBoundingClientRect(); return (r.width>20 && r.height>20) ? '1' : '0'; })()") == "1"
                }
                shot("trimps-warp-aged")
                val during = capture(web)
                lines += "case8=" + if (warpUp) "PASS" else "FAIL"
                lines += "case9=" + if (during == null && decline().contains("deferred")) "PASS" else "FAIL"
                lines += "case9Decline=" + decline()
                lines += "case9Reloads=" + (finishes.get() - loadsBefore)
                dismissWarp(web)
                val after = capture(web)
                lines += "case10=" + if (after != null && valid(after)) "PASS" else "FAIL"
                lines += "case10Hash=" + digest(after)
            } else {
                lines += "case8=BLOCKED age-edit-failed"
                lines += "case9=BLOCKED"
                lines += "case10=BLOCKED"
            }
            eval(web, "if (typeof game !== 'undefined' && game.options) game.options.menu.autoSave.enabled = 0; 'ok'")
            val beforeFold = primaryOf(web)
            val loads = finishes.get()
            val identity = System.identityHashCode(web)
            val foldedScale = posture("0", "trimps-folded", web, lines, "folded")
            posture("2", "trimps-unfolded", web, lines, "unfolded")
            val foldedAgain = posture("0", "trimps-folded-again", web, lines, "foldedAgain")
            val afterFold = primaryOf(web)
            val sameDocument = identity == System.identityHashCode(web) && finishes.get() == loads
            val sameSave = beforeFold.isNotEmpty() && beforeFold == afterFold
            val zoomHeld = foldedScale > 0f && kotlin.math.abs(foldedScale - foldedAgain) < 0.03f && foldedAgain < 1.25f
            val readable = lines.any { it.startsWith("folded=") && it.contains("foodOk=true") && it.contains("fightOk=true") }
            lines += "case11=" + if (sameDocument && sameSave && zoomHeld && readable) "PASS" else "FAIL"
            lines += "case11Loads=$loads/${finishes.get()} zoom=$foldedScale->$foldedAgain saveSame=$sameSave"
            lines += "case11Hash=" + PayloadDigest.sha256(beforeFold)
            eval(web, "if (typeof save === 'function') save(); 'ok'")
            val resume = primaryOf(web)
            web.post { web.onPause() }
            Thread.sleep(2_000)
            File(files(), RESUME).writeText(PayloadDigest.sha256(resume) + "\n" + resume.length)
            lines += "resumeHash=" + PayloadDigest.sha256(resume)
            lines += "resumeLength=" + resume.length
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
            writeReport(lines, "trimps-resume.txt")
            assertTrue(lines.joinToString("\n"), false)
        }
        val hash = expected.readText().lineSequence().first()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ProfileStore.getInstance().getOrCreateProfile(PROFILE)
        }
        ActivityScenario.launch(WebViewProbeActivity::class.java).use { scenario ->
            val web = addWeb(scenario, AtomicInteger(0))
            val ready = waitReady(web, 120_000)
            val probe = json(eval(web, PROBE))
            val live = primaryOf(web)
            lines += "case3=" + if (ready && PayloadDigest.sha256(live) == hash) "PASS" else "FAIL"
            lines += "case3Ready=$ready version=" + probe.optString("version")
            lines += "case3Hash=" + PayloadDigest.sha256(live)
            lines += "case3Expected=" + hash
            lines += "case3Warping=" + probe.optBoolean("warping")
            scenario.onActivity {
                (web.parent as? android.view.ViewGroup)?.removeView(web)
                web.loadUrl("about:blank")
                web.destroy()
            }
        }
        deleteDisposable()
        writeReport(lines, "trimps-resume.txt")
        assertFalse(lines.joinToString("\n"), lines.joinToString("\n").contains("=FAIL"))
    }

    private fun recordCatalogue(lines: MutableList<String>) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            Thread.sleep(8_000)
            scenario.onActivity { activity ->
                assertTrue(activity.window.decorView.isAttachedToWindow)
            }
            shell("uiautomator dump /sdcard/hub-ui.xml")
            shot("trimps-launcher")
            Thread.sleep(400)
            val xml = shell("cat /sdcard/hub-ui.xml")
            val listed = xml.contains("Trimps") || xml.contains("play-trimps")
            lines += "catalogueDump=" + if (listed) "saw-text" else "miss"
        }
    }

    private fun posture(state: String, name: String, web: WebView, lines: MutableList<String>, label: String): Float {
        shell("cmd device_state state $state")
        Thread.sleep(1_200)
        eval(web, TrimpsLayout.injectionScript())
        val size = shell("wm size").replace("\n", " ")
        shot(name)
        val box = json(eval(web, BOUNDS))
        val foodOk = box.optInt("foodH") >= 16
        val fightOk = box.optInt("fightH") >= 24 && box.optInt("fightW") >= 24
        lines += "$label=$size food=" + box.optString("food") + " fight=" + box.optString("fight") +
            " foodOk=$foodOk fightOk=$fightOk scale=" + box.optString("scale")
        return box.optDouble("scale", -1.0).toFloat()
    }

    private fun dismissWarp(web: WebView) {
        eval(web, "(function(){ try { if (typeof offlineProgress !== 'undefined') offlineProgress.finish(); return 'done'; } catch (e) { return 'err'; } })()")
        waitUntil(20_000) { !json(eval(web, PROBE)).optBoolean("warping") }
    }

    private fun captureAfter(web: WebView, script: String): String? {
        eval(web, script)
        return capture(web)
    }

    private fun capture(web: WebView): String? = runBlocking {
        withContext(Dispatchers.Main) { adapter.capture(web, ticket) }
    }

    private fun peek(web: WebView): PeekOutcome = runBlocking {
        withContext(Dispatchers.Main) { adapter.peekStorage(web) }
    }

    private fun valid(payload: String): Boolean = runBlocking { adapter.validate(payload) }

    private fun decline(): String = adapter.captureDecline().orEmpty()

    private fun summary(payload: String?): String =
        if (payload == null) "none" else adapter.summarize(payload).detail

    private fun digest(payload: String?): String {
        if (payload == null) return "absent"
        val decoded = SaveEnvelope.decode(payload, adapter.envelopePolicy())
        val primary = (decoded as? com.example.hub.games.EnvelopeDecode.Ok)
            ?.bundle?.slot(TrimpsSave.PRIMARY)?.value
        return "envelope=" + PayloadDigest.sha256(payload) +
            " primary=" + PayloadDigest.sha256OrAbsent(primary) +
            " len=" + payload.length
    }

    private fun primaryOf(web: WebView): String =
        eval(web, "localStorage.getItem('trimpSave1') || ''")

    private fun zoom(web: WebView): String =
        eval(web, "(function(){ var z=document.documentElement.style.zoom||''; var s=window.visualViewport?visualViewport.scale:1; return z+'/'+s; })()")

    private fun waitReady(web: WebView, timeoutMs: Long): Boolean = runBlocking {
        withContext(Dispatchers.Main) { adapter.awaitReady(web, timeoutMs) }
    }

    private fun waitUntil(timeoutMs: Long, stepMs: Long = 400, accept: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (accept()) return true
            Thread.sleep(stepMs)
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
                    if (view != null && url != null && url.contains("trimps.github.io")) {
                        view.evaluateJavascript(TrimpsLayout.injectionScript(), null)
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
        assertTrue(latch.await(20, TimeUnit.SECONDS))
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

    private fun writeReport(lines: List<String>, name: String = "trimps-qualification.txt") {
        val text = lines.joinToString("\n")
        File(files(), name).writeText(text)
        android.util.Log.i("TRIMPS_QUAL", text)
    }

    private fun files(): File =
        InstrumentationRegistry.getInstrumentation().targetContext.filesDir

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

    companion object {
        private const val PROFILE = "hub-qual-trimps"
        private const val URL = "https://trimps.github.io/"
        private const val RESUME = "trimps-resume.txt"
        private val PROBE =
            "(function(){ var g=(typeof game!=='undefined'&&game)?game.global:null;" +
                " var food=document.getElementById('foodOwned');" +
                " return JSON.stringify({version:g?g.stringVersion:'', food:food?(food.textContent||'').trim():''," +
                " warping:typeof usingRealTimeOffline!=='undefined'&&!!usingRealTimeOffline, title:document.title}); })()"
        private val BOUNDS =
            "(function(){ function box(id){ var n=document.getElementById(id); if(!n) return 'missing';" +
                " var r=n.getBoundingClientRect(); var d=getComputedStyle(n).display;" +
                " return d+' '+Math.round(r.width)+'x'+Math.round(r.height)+'@'+Math.round(r.left)+','+Math.round(r.top); }" +
                " function size(id){ var n=document.getElementById(id); if(!n) return {w:0,h:0}; var r=n.getBoundingClientRect(); return {w:Math.round(r.width),h:Math.round(r.height)}; }" +
                " var food=size('food'); var fight=size('fightBtn');" +
                " return JSON.stringify({food:box('food'), fight:box('fightBtn'), foodH:food.h, foodW:food.w, fightH:fight.h, fightW:fight.w," +
                " scale: window.visualViewport ? visualViewport.scale : 1}); })()"
    }
}
