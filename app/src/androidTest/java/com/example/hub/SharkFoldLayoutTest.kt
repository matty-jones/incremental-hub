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
import com.example.hub.ui.PageScaleMode
import com.example.hub.ui.assessPresentation
import com.example.hub.ui.nudgeStaleViewport
import com.example.hub.ui.parsePresentationAck
import com.example.hub.ui.presentationScript
import com.example.hub.ui.presentationSettled
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Core, an upgrade row, exploration, and the evolution tree on a disposable
 * Shark profile. The personal shark profile is not opened or deleted.
 */
@RunWith(AndroidJUnit4::class)
class SharkFoldLayoutTest {

    @After
    fun restorePosture() {
        shell("cmd device_state state reset")
    }

    @Test
    fun controlsStayInsideTheCoverAndTheInnerScreen() {
        shell("cmd device_state state 0")
        waitSize("1080")
        val finishes = AtomicInteger(0)
        ActivityScenario.launch(WebViewProbeActivity::class.java).use { scenario ->
            val web = addWeb(scenario, finishes)
            assertTrue("Shark did not become usable", waitReady(web))
            val loads = finishes.get()
            eval(web, MARKER)
            eval(web, SharkLayout.injectionScript())
            eval(web, DocumentLayout.injectionScript())
            val cover = waitForLayout(web, wide = false)
            assertLayout(cover, wide = false)
            val doc = cover.getString("doc")
            unfold()
            waitForViewport(web)
            eval(web, SharkLayout.injectionScript())
            eval(web, DocumentLayout.injectionScript())
            val inner = waitForLayout(web, wide = true)
            assertLayout(inner, wide = true)
            assertEquals(doc, inner.getString("doc"))
            assertEquals(loads, finishes.get())
            scenario.onActivity {
                (web.parent as? ViewGroup)?.removeView(web)
                web.destroy()
            }
        }
        deleteDisposable()
    }

    private fun assertLayout(json: JSONObject, wide: Boolean) {
        val viewportW = json.optDouble("iw").toFloat()
        val viewportH = json.optDouble("ih").toFloat()
        assertTrue(json.optBoolean("store"))
        val cap = json.optDouble("cap").toFloat()
        val bound = if (cap > 40f) minOf(cap, viewportW) else viewportW
        val cores = json.getJSONArray("cores")
        assertTrue(json.toString(), cores.length() >= 4)
        for (i in 0 until cores.length()) {
            assertButton("core-$i", cores.getJSONObject(i), bound, viewportH)
        }
        val first = cores.getJSONObject(0)
        val second = cores.getJSONObject(1)
        val piled = kotlin.math.abs(first.optDouble("ol") - second.optDouble("ol")) < 2.0 &&
            kotlin.math.abs(first.optDouble("ot") - second.optDouble("ot")) < 2.0
        assertTrue("core buttons overlap ${first.optDouble("ol")},${first.optDouble("ot")}", !piled)
        assertButton("upgrade", json.getJSONObject("upgrade"), bound, viewportH)
        assertButton("explore", json.getJSONObject("explore"), bound, viewportH)
        if (wide) {
            val exploreW = json.getJSONObject("explore").optDouble("w").toFloat()
            assertTrue("unfolded explore width $exploreW", exploreW in 150f..280f)
        }
        val tree = json.getJSONObject("tree")
        val left = tree.optDouble("l").toFloat()
        val right = tree.optDouble("r").toFloat()
        val width = tree.optDouble("w").toFloat()
        assertTrue(
            "tree $tree viewport $viewportW",
            left >= -1f && right <= bound + 1f && width > 40f,
        )
        assertEquals(json.optString("cols"), 5, columnCount(json.optString("cols")))
    }

    private fun assertButton(name: String, box: JSONObject, viewportW: Float, viewportH: Float) {
        val left = box.optDouble("l").toFloat()
        val top = box.optDouble("t").toFloat()
        val right = box.optDouble("r").toFloat()
        val bottom = box.optDouble("b").toFloat()
        val width = box.optDouble("w").toFloat()
        val height = box.optDouble("h").toFloat()
        assertTrue(
            "$name left $left right $right width $width viewport $viewportW hit=${box.optString("id")}",
            left >= -1f && right <= viewportW + 1f && width > 40f && height > 16f && box.optBoolean("hit"),
        )
        assertTrue("$name top $top bottom $bottom viewport $viewportH", top >= -1f && bottom <= viewportH + 1f)
    }

    private fun columnCount(columns: String): Int =
        columns.trim().split(Regex("\\s+")).count { it.isNotEmpty() && it != "none" }

    private fun waitForLayout(web: WebView, wide: Boolean): JSONObject {
        val deadline = System.currentTimeMillis() + 12_000
        var last = ""
        var stable = 0
        var latest = JSONObject()
        while (System.currentTimeMillis() < deadline) {
            web.post { nudgeStaleViewport(web) }
            val view = measure(web)
            val limit = if (view.density > 0f) (view.width / view.density).toInt() else view.width
            eval(web, SharkLayout.injectionScript())
            eval(web, DocumentLayout.injectionScript(limit))
            eval(web, viewportUnlockScript())
            latest = JSONObject(eval(web, MEASURE).ifBlank { "{}" })
            val ok = runCatching { assertLayout(latest, wide) }.isSuccess &&
                latest.optDouble("iw") > 200
            val signature = "${latest.optDouble("iw")}:${latest.optString("cols")}:${edge(latest)}"
            if (ok && signature == last) {
                stable += 1
                if (stable >= 2) return latest
            } else if (ok) {
                last = signature
                stable = 1
            } else {
                stable = 0
            }
            Thread.sleep(180)
        }
        val view = measure(web)
        throw AssertionError(
            "layout did not settle wide=$wide view=${view.width}x${view.height} density=${view.density}: $latest",
        )
    }

    private fun edge(json: JSONObject): String {
        val cores = json.optJSONArray("cores") ?: JSONArray()
        val first = if (cores.length() > 0) cores.getJSONObject(0).optDouble("r") else 0.0
        return "$first:${json.optJSONObject("tree")?.optDouble("r")}"
    }

    private fun waitForViewport(web: WebView) {
        val deadline = System.currentTimeMillis() + 12_000
        var last = ""
        var stable = 0
        var detail = ""
        while (System.currentTimeMillis() < deadline) {
            val box = measure(web)
            if (box.width < 1800) {
                Thread.sleep(150)
                continue
            }
            val raw = eval(
                web,
                presentationScript(
                    PageScaleMode.HUB,
                    100,
                    flush = true,
                    com.example.hub.ui.cssPixelSize(box.width, box.density),
                    com.example.hub.ui.cssPixelSize(box.height, box.density),
                ),
            )
            val ack = parsePresentationAck(raw, 100, 100)
            if (ack == null) {
                detail = raw
                Thread.sleep(150)
                continue
            }
            val fault = assessPresentation(ack, box.width, box.height, box.density, box.scale, box.parentWidth)
            detail = "fault=$fault view=${box.width}x${box.height} inner=${ack.innerWidth}x${ack.innerHeight}"
            val ok = presentationSettled(fault) && ack.innerWidth in 780..920
            if (ok && detail == last) {
                stable += 1
                if (stable >= 2) return
            } else if (ok) {
                last = detail
                stable = 1
            } else {
                stable = 0
                if (fault == "stale-viewport" || fault == "stale-frame" || fault == "native-view") {
                    web.post { nudgeStaleViewport(web) }
                }
            }
            Thread.sleep(180)
        }
        throw AssertionError("unfolded viewport did not settle: $detail")
    }

    private fun addWeb(scenario: ActivityScenario<WebViewProbeActivity>, finishes: AtomicInteger): WebView {
        val ref = AtomicReference<WebView>()
        val latch = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            runCatching { ProfileStore.getInstance().deleteProfile(PROFILE) }
        }
        scenario.onActivity { activity ->
            val web = WebView(activity)
            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            web.settings.useWideViewPort = true
            web.settings.loadWithOverviewMode = false
            web.setInitialScale(0)
            val profile = ProfileStore.getInstance().getOrCreateProfile(PROFILE)
            WebViewCompat.setProfile(web, profile.name)
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    finishes.incrementAndGet()
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
                    scale = web.scale,
                    parentWidth = (web.parent as? View)?.width ?: 0,
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

    private data class Box(
        val width: Int,
        val height: Int,
        val density: Float,
        val scale: Float,
        val parentWidth: Int,
    )

    companion object {
        private const val PROFILE = "hub-qual-fold-layout"
        private const val SHARK = "https://mrredshark77.github.io/shark-incremental/"
        private const val MARKER = "(function(){ window.__hubLayoutDoc='alive'; return window.__hubLayoutDoc; })()"
        private const val MEASURE =
            "(function(){" +
                " function show(id){ var el=document.getElementById(id); if(el) el.style.setProperty('display','block','important'); }" +
                " var app=document.getElementById('app'); if(app) app.style.setProperty('display','block','important');" +
                " var offline=document.getElementById('offline'); if(offline) offline.style.setProperty('display','none','important');" +
                " show('core-reactor-tab'); show('evolution-tree-tab'); show('explore-tab');" +
                " var table=document.getElementById('core-reactor-table');" +
                " if(table && table.querySelectorAll('.core-reactor-button').length<4){" +
                " while(table.firstChild) table.removeChild(table.firstChild);" +
                " ['Fe','Ni','O','Ne'].forEach(function(name,i){" +
                " var b=document.createElement('button'); b.className='core-reactor-button'; b.id='hub-core-'+i;" +
                " b.type='button'; b.textContent=name; table.appendChild(b); }); }" +
                " var tree=document.getElementById('evolution-tree');" +
                " if(tree && tree.children.length<5){" +
                " while(tree.firstChild) tree.removeChild(tree.firstChild);" +
                " for(var n=0;n<5;n++){ var cell=document.createElement('div');" +
                " var btn=document.createElement('button'); btn.className='evolution-tree-btn'; btn.type='button';" +
                " btn.textContent='N'+n; cell.appendChild(btn); tree.appendChild(cell); } }" +
                " if(!document.getElementById('hub-upg-btn')){" +
                " var row=document.createElement('div'); row.className='shark-upgrade'; row.id='hub-upg-row';" +
                " row.innerHTML='<div class=\"shark-upgrade-div\"><div><h2>Reach</h2></div><div>x2</div>" +
                "<div><button id=\"hub-upg-btn\" class=\"shark-upgrade-button\" type=\"button\">Cost: 1000 fish</button></div></div>';" +
                " document.body.insertBefore(row, document.body.firstChild); }" +
                " if(!document.getElementById('hub-explore-button')){" +
                " var host=document.createElement('div'); host.id='hub-explore-fixture';" +
                " host.innerHTML='<div class=\"explore-div\"><div class=\"explore-desc\">" +
                "<button id=\"hub-explore-button\" type=\"button\">Explore the ocean!</button>" +
                "<div><div class=\"table-left\"><button class=\"explore-cost\" type=\"button\">Double corals</button>" +
                "<button class=\"explore-cost\" type=\"button\">Double depth</button></div></div>" +
                "<div><span>Milestone at 100m.</span></div></div></div>';" +
                " document.body.insertBefore(host, document.body.firstChild); }" +
                " var before=window.localStorage?window.localStorage.length:-1;" +
                " if(window.__hubLayoutFit) window.__hubLayoutFit();" +
                " var after=window.localStorage?window.localStorage.length:-1;" +
                " function box(el){" +
                " if(!el) return {ok:false,l:0,t:0,r:0,b:0,w:0,h:0,hit:false,id:'missing'};" +
                " el.scrollIntoView({block:'center',inline:'nearest'});" +
                " var r=el.getBoundingClientRect(); var iw=window.innerWidth||0; var ih=window.innerHeight||0;" +
                " var cx=Math.min(Math.max(r.left+r.width/2,1), Math.max(iw-2,1));" +
                " var cy=Math.min(Math.max(r.top+Math.min(r.height/2,20),1), Math.max(ih-2,1));" +
                " var hit=document.elementFromPoint(cx,cy);" +
                " var owned=!!(hit && (hit===el || el.contains(hit)));" +
                " return {ok:true,l:r.left,t:r.top,r:r.right,b:r.bottom,w:r.width,h:r.height," +
                " ol:el.offsetLeft,ot:el.offsetTop,hit:owned," +
                " id:hit?(hit.id||hit.className||hit.tagName):''}; }" +
                " function shown(el){ if(!el) return false; var s=getComputedStyle(el);" +
                " if(s.display==='none'||s.visibility==='hidden') return false;" +
                " var r=el.getBoundingClientRect(); return r.width>2&&r.height>2; }" +
                " var cores=[]; var buttons=table?table.querySelectorAll('.core-reactor-button'):[];" +
                " for(var i=0;i<buttons.length;i++) if(shown(buttons[i])) cores.push(box(buttons[i]));" +
                " var treeEl=document.getElementById('evolution-tree');" +
                " var cols=treeEl?String(getComputedStyle(treeEl).gridTemplateColumns||''):'';" +
                " var meta=document.querySelector('meta[name=\"viewport\"]');" +
                " return JSON.stringify({iw:window.innerWidth||0,ih:window.innerHeight||0," +
                " dpr:window.devicePixelRatio||0, meta:meta?meta.getAttribute('content')||'':''," +
                " doc:window.__hubLayoutDoc||'', store:before===after, cap:window.__hubLayoutLimit||0," +
                " grid:table?(table.getAttribute('data-hub-track')||'')+' '+table.style.gridTemplateColumns:''," +
                " cores:cores," +
                " upgrade:box(document.getElementById('hub-upg-btn'))," +
                " explore:box(document.getElementById('hub-explore-button'))," +
                " tree:box(treeEl), cols:cols});" +
                "})()"
    }
}
