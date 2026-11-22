package com.example.hub

import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import com.example.hub.games.DocumentLayout
import com.example.hub.games.SharkIncrementalAdapter
import com.example.hub.games.SharkLayout
import com.example.hub.ui.installSharkDocumentStart
import com.example.hub.ui.prepareSharkViewport
import com.example.hub.ui.sharkDocumentStartScript
import com.example.hub.ui.viewportUnlockScript
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * Pb-207 on the live Atomic Nuclei board stays one size and one place while
 * nucleus counters update. Folding still refits the board once. The personal
 * Shark profile is not opened or deleted.
 */
@RunWith(AndroidJUnit4::class)
class NucleiSpatialLayoutTest {

    @After
    fun restorePosture() {
        shell("cmd device_state state reset")
    }

    @Test
    fun pbButtonStaysPutWhileCountersUpdate() {
        fold()
        ActivityScenario.launch(WebViewProbeActivity::class.java).use { scenario ->
            val web = addWeb(scenario)
            assertTrue("Shark did not become usable", waitReady(web))
            val folded = settle(web, wide = false)
            val unfolded = settle(web, wide = true)
            assertTrue(
                "unfolding did not refit the board: $folded -> $unfolded",
                unfolded.getString("zoom") != folded.getString("zoom") ||
                    unfolded.getInt("writes") > folded.getInt("writes"),
            )
            scenario.onActivity {
                (web.parent as? ViewGroup)?.removeView(web)
                web.destroy()
            }
        }
        deleteDisposable()
    }

    private fun settle(web: WebView, wide: Boolean): JSONObject {
        if (wide) unfold() else fold()
        waitSize(if (wide) "2208" else "1080")
        val view = measure(web)
        val limit = if (view.density > 0f) (view.width / view.density).toInt() else view.width
        eval(web, SharkLayout.injectionScript())
        eval(web, DocumentLayout.injectionScript(limit))
        waitForInnerWidth(web, wide)
        val revealed = JSONObject(eval(web, REVEAL).ifBlank { "{}" })
        assertTrue("actinium board did not reveal: $revealed", revealed.optBoolean("btn"))
        eval(web, START_PUMP)
        waitForButton(web)
        waitForInnerWidth(web, wide)
        val samples = JSONArray()
        repeat(10) {
            samples.put(JSONObject(eval(web, SAMPLE).ifBlank { "{}" }))
            Thread.sleep(200)
        }
        eval(web, STOP_PUMP)
        assertStable(samples, wide)
        return samples.getJSONObject(0)
    }

    private fun assertStable(samples: JSONArray, wide: Boolean) {
        assertEquals("expected 10 samples: $samples", 10, samples.length())
        val first = samples.getJSONObject(0)
        assertTrue("Pb button has no box: $samples", first.optDouble("w") > 24 && first.optDouble("h") > 24)
        assertTrue("Pb button is not the hit target: $first", first.optBoolean("hit"))
        assertTrue("decay canvas is not inside the board: $first", first.optBoolean("canvas"))
        val viewport = first.optDouble("iw")
        assertTrue(
            "viewport ${if (wide) "unfolded" else "folded"} $viewport samples=$samples",
            if (wide) viewport > 700 else viewport in 350.0..700.0,
        )
        val zoom = first.optString("zoom")
        val writes = first.optInt("writes")
        for (i in 1 until samples.length()) {
            val sample = samples.getJSONObject(i)
            assertEquals("zoom moved during counters: $samples", zoom, sample.optString("zoom"))
            assertEquals("zoom rewritten during counters: $samples", writes, sample.optInt("writes"))
            assertTrue(
                "Pb moved during counters: $samples",
                abs(sample.optDouble("l") - first.optDouble("l")) <= 1.0 &&
                    abs(sample.optDouble("t") - first.optDouble("t")) <= 1.0 &&
                    abs(sample.optDouble("w") - first.optDouble("w")) <= 1.0 &&
                    abs(sample.optDouble("h") - first.optDouble("h")) <= 1.0,
            )
            assertTrue("Pb left the hit target: $sample", sample.optBoolean("hit"))
            val right = sample.optDouble("l") + sample.optDouble("w")
            val bottom = sample.optDouble("t") + sample.optDouble("h")
            assertTrue(
                "Pb is outside the viewport: $sample",
                sample.optDouble("l") >= -1.0 &&
                    sample.optDouble("t") >= -1.0 &&
                    right <= sample.optDouble("iw") + 1.0 &&
                    bottom <= sample.optDouble("ih") + 1.0,
            )
        }
    }

    private fun addWeb(scenario: ActivityScenario<WebViewProbeActivity>): WebView {
        val ref = AtomicReference<WebView>()
        val latch = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            runCatching { ProfileStore.getInstance().deleteProfile(PROFILE) }
        }
        scenario.onActivity { activity ->
            val web = WebView(activity)
            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            prepareSharkViewport(web)
            installSharkDocumentStart(web, SHARK)
            val profile = ProfileStore.getInstance().getOrCreateProfile(PROFILE)
            WebViewCompat.setProfile(web, profile.name)
            web.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    if (url.contains("shark-incremental")) {
                        view.evaluateJavascript(sharkDocumentStartScript(), null)
                    }
                }
            }
            activity.findViewById<ViewGroup>(android.R.id.content).addView(
                web,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
            web.loadUrl(SHARK)
            ref.set(web)
            latch.countDown()
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        return ref.get()
    }

    private fun waitForButton(web: WebView) {
        val deadline = System.currentTimeMillis() + 8_000
        var last = ""
        while (System.currentTimeMillis() < deadline) {
            last = eval(web, SAMPLE)
            val width = JSONObject(last.ifBlank { "{}" }).optDouble("w")
            if (width > 24) return
            Thread.sleep(150)
        }
        throw AssertionError("Pb button did not lay out: $last")
    }

    private fun waitForInnerWidth(web: WebView, wide: Boolean) {
        val deadline = System.currentTimeMillis() + 12_000
        var last = ""
        var previous = ""
        var stable = 0
        var detail = ""
        var unlocked = false
        while (System.currentTimeMillis() < deadline) {
            val box = measure(web)
            last = eval(web, "(function(){return String(window.innerWidth||0);})()")
            val width = last.toDoubleOrNull() ?: 0.0
            val pixelsOk = if (wide) box.width >= 1800 else box.width in 900..1400
            val cssOk = if (wide) width > 700 else width in 350.0..700.0
            detail = "css=$last view=${box.width}x${box.height} density=${box.density}"
            if (pixelsOk && !cssOk && !unlocked) {
                unlockViewport(web)
                unlocked = true
                stable = 0
            } else if (pixelsOk && cssOk && last == previous) {
                stable += 1
                if (stable >= 2) return
            } else {
                stable = 0
            }
            previous = last
            Thread.sleep(200)
        }
        throw AssertionError("inner width did not settle wide=$wide: $detail")
    }

    private fun waitReady(web: WebView): Boolean {
        val deadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < deadline) {
            if (eval(web, SharkIncrementalAdapter.JS_UI_READY) == "true") return true
            Thread.sleep(400)
        }
        return false
    }

    private fun measure(web: WebView): Box {
        val ref = AtomicReference<Box>()
        val latch = CountDownLatch(1)
        web.post {
            ref.set(
                Box(
                    width = web.width,
                    height = web.height,
                    density = web.resources.displayMetrics.density,
                ),
            )
            latch.countDown()
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        return ref.get()
    }

    private fun eval(web: WebView, script: String): String {
        val value = AtomicReference("")
        val latch = CountDownLatch(1)
        web.post {
            web.evaluateJavascript(script) { raw ->
                value.set(unwrap(raw))
                latch.countDown()
            }
        }
        if (!latch.await(15, TimeUnit.SECONDS)) return ""
        return value.get()
    }

    private fun unwrap(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return try {
            org.json.JSONArray("[$raw]").opt(0)?.toString() ?: ""
        } catch (e: Exception) {
            raw
        }
    }

    private fun unlockViewport(web: WebView) {
        val latch = CountDownLatch(1)
        web.post {
            web.evaluateJavascript(viewportUnlockScript(), null)
            web.post {
                @Suppress("DEPRECATION")
                web.setInitialScale(0)
                web.requestLayout()
                latch.countDown()
            }
        }
        latch.await(5, TimeUnit.SECONDS)
    }

    private fun fold() {
        shell("cmd device_state state 0")
        waitSize("1080")
    }

    private fun unfold() {
        shell("cmd device_state state 2")
        waitSize("2208")
    }

    private fun waitSize(expected: String) {
        val deadline = System.currentTimeMillis() + 8_000
        var size = ""
        while (System.currentTimeMillis() < deadline) {
            size = shell("wm size")
            if (size.contains(expected)) return
            Thread.sleep(150)
        }
        throw AssertionError("display did not become $expected: $size")
    }

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

    private data class Box(val width: Int, val height: Int, val density: Float)

    companion object {
        private const val PROFILE = "hub-qual-nuclei-layout"
        private const val SHARK = "https://mrredshark77.github.io/shark-incremental/"
        private const val REVEAL =
            "(function(){" +
                " var offline=document.getElementById('offline');" +
                " if(offline) offline.style.display='none';" +
                " var err='';" +
                " try{ if(typeof switchTab==='function') switchTab(12,0);" +
                " if(typeof updateTabs==='function') updateTabs(); }" +
                " catch(e){ err=String(e); }" +
                " var board=document.getElementById('decay-chain');" +
                " var btn=document.getElementById('decay-chain-btn-0')||" +
                " (board&&board.querySelector('.decay-chain-btn'));" +
                " if(btn) btn.scrollIntoView({block:'center',inline:'nearest'});" +
                " if(window.__hubLayoutFit) window.__hubLayoutFit();" +
                " return JSON.stringify({btn:!!btn, err:err, tab:typeof tab_name==='string'?tab_name:''," +
                " id:btn?(btn.id||''):'', text:btn?(btn.textContent||'').slice(0,80):''});" +
                "})()"
        private const val START_PUMP =
            "(function(){" +
                " if(window.__hubNucleiTimer) clearInterval(window.__hubNucleiTimer);" +
                " window.__hubNucleiPump=0; window.__hubNucleiErr='';" +
                " try{ if(typeof switchTab==='function') switchTab(12,0); }catch(e){}" +
                " window.__hubNucleiTimer=setInterval(function(){" +
                " window.__hubNucleiPump++;" +
                " try{ if(typeof updateDecaySeriesHTML==='function') updateDecaySeriesHTML();" +
                " else throw new Error('no-update'); }" +
                " catch(e){ window.__hubNucleiErr=String(e);" +
                " var level=document.getElementById('decay-chain-0-level');" +
                " var amount=document.getElementById('decay-chain-0-amount');" +
                " if(level) level.innerHTML='Level: '+window.__hubNucleiPump;" +
                " if(amount) amount.innerHTML=String(window.__hubNucleiPump); }" +
                " },50);" +
                " return 'pump'; })()"
        private const val STOP_PUMP =
            "(function(){ if(window.__hubNucleiTimer) clearInterval(window.__hubNucleiTimer);" +
                " window.__hubNucleiTimer=0; return 'stopped'; })()"
        private const val SAMPLE =
            "(function(){" +
                " var board=document.getElementById('decay-chain');" +
                " var btn=document.getElementById('decay-chain-btn-0')||" +
                " (board&&board.querySelector('.decay-chain-btn'));" +
                " var canvas=document.getElementById('decay-chain-canvas');" +
                " var r=btn?btn.getBoundingClientRect():null;" +
                " var hit=false;" +
                " if(r&&btn){" +
                " var cx=Math.min(Math.max(r.left+r.width/2,1), Math.max((window.innerWidth||2)-2,1));" +
                " var cy=Math.min(Math.max(r.top+r.height/2,1), Math.max((window.innerHeight||2)-2,1));" +
                " var el=document.elementFromPoint(cx,cy);" +
                " hit=!!(el&&(el===btn||btn.contains(el))); }" +
                " var shown=btn?getComputedStyle(btn).display:'missing';" +
                " return JSON.stringify({zoom:board&&board.style?(board.style.zoom||''):''," +
                " writes:window.__hubZoomWrites||0," +
                " dirty:board?(board.getAttribute('data-hub-dirty')||''):''," +
                " room:board?(board.getAttribute('data-hub-room')||''):''," +
                " l:r?r.left:0,t:r?r.top:0,w:r?r.width:0,h:r?r.height:0," +
                " iw:window.innerWidth||0,ih:window.innerHeight||0,hit:hit," +
                " canvas:!!(canvas&&board&&board.contains(canvas))," +
                " disp:shown, tab:typeof tab_name==='string'?tab_name:''," +
                " pump:window.__hubNucleiPump||0,err:window.__hubNucleiErr||''});" +
                "})()"
    }
}
