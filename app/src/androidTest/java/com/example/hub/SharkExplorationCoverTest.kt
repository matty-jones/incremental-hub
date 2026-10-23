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
import com.example.hub.games.SharkIncrementalAdapter
import com.example.hub.games.SharkLayout
import com.example.hub.ui.assessPresentation
import com.example.hub.ui.nudgeStaleViewport
import com.example.hub.ui.parsePresentationAck
import com.example.hub.ui.presentationScript
import com.example.hub.ui.presentationSettled
import com.example.hub.ui.PageScaleMode
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Exploration's main button on a disposable Shark profile. The personal
 * shark profile is not opened or deleted.
 */
@RunWith(AndroidJUnit4::class)
class SharkExplorationCoverTest {

    @After
    fun restorePosture() {
        shell("cmd device_state state reset")
    }

    @Test
    fun explorationButtonStaysInsideTheCoverAndTheInnerScreen() {
        shell("cmd device_state state 0")
        waitSize("1080")
        val finishes = AtomicInteger(0)
        ActivityScenario.launch(WebViewProbeActivity::class.java).use { scenario ->
            val web = addWeb(scenario, finishes)
            assertTrue("Shark did not become usable", waitReady(web))
            val loads = finishes.get()
            eval(web, SharkLayout.injectionScript())
            eval(web, MARKER)
            eval(web, FIXTURE)
            val cover = waitForButton(web, stacked = true)
            assertInside(cover, "cover")
            assertTrue("cover button is covered by ${cover.hit}", cover.receivesClick)
            assertTrue(cover.width > cover.innerWidth * 0.7)
            val doc = eval(web, "window.__hubExploreDoc")
            unfold()
            waitForViewport(web)
            val inner = waitForButton(web, stacked = false)
            assertInside(inner, "inner")
            assertTrue("unfolded button width ${inner.width}", inner.width in 150f..280f)
            assertEquals(doc, eval(web, "window.__hubExploreDoc"))
            assertEquals(loads, finishes.get())
            scenario.onActivity {
                (web.parent as? ViewGroup)?.removeView(web)
                web.destroy()
            }
        }
        deleteDisposable()
    }

    private fun assertInside(box: ButtonBox, label: String) {
        assertTrue(
            "$label left ${box.left} width ${box.width} viewport ${box.innerWidth} hit=${box.hit}",
            box.left >= -1f && box.top >= -1f && box.right <= box.innerWidth + 1f && box.width > 40f && box.height > 30f,
        )
        assertTrue("$label bottom ${box.bottom} viewport ${box.innerHeight}", box.bottom <= box.innerHeight + 1f)
    }

    private fun waitForButton(web: WebView, stacked: Boolean): ButtonBox {
        val deadline = System.currentTimeMillis() + 8_000
        var last = ""
        var stable = 0
        var latest = ButtonBox(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, false, "")
        while (System.currentTimeMillis() < deadline) {
            eval(web, SharkLayout.injectionScript())
            latest = readButton(web)
            val shaped = if (stacked) latest.width > latest.innerWidth * 0.7 else latest.width in 150f..280f
            val ok = latest.left >= -1f && latest.right <= latest.innerWidth + 1f &&
                latest.top >= -1f && latest.bottom <= latest.innerHeight + 1f &&
                latest.width > 40f && latest.height > 30f && latest.receivesClick && shaped
            val signature = "${latest.left}:${latest.width}:${latest.innerWidth}"
            if (ok && signature == last) {
                stable += 1
                if (stable >= 2) return latest
            } else if (ok) {
                last = signature
                stable = 1
            } else {
                stable = 0
            }
            Thread.sleep(150)
        }
        throw AssertionError("exploration button did not settle stacked=$stacked: $latest")
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
            detail = "fault=$fault view=${box.width}x${box.height} inner=${ack.innerWidth}x${ack.innerHeight} vs=${ack.viewportScale}"
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

    private fun readButton(web: WebView): ButtonBox {
        val raw = eval(web, MEASURE)
        val json = JSONObject(raw)
        return ButtonBox(
            left = json.optDouble("l").toFloat(),
            top = json.optDouble("t").toFloat(),
            right = json.optDouble("r").toFloat(),
            bottom = json.optDouble("b").toFloat(),
            width = json.optDouble("w").toFloat(),
            height = json.optDouble("h").toFloat(),
            innerWidth = json.optDouble("iw").toFloat(),
            innerHeight = json.optDouble("ih").toFloat(),
            receivesClick = json.optBoolean("hit"),
            hit = json.optString("hitId"),
        )
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

    private data class ButtonBox(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val width: Float,
        val height: Float,
        val innerWidth: Float,
        val innerHeight: Float,
        val receivesClick: Boolean,
        val hit: String,
    )

    companion object {
        private const val PROFILE = "hub-qual-explore-cover"
        private const val SHARK = "https://mrredshark77.github.io/shark-incremental/"
        private const val MARKER = "(function(){ window.__hubExploreDoc='alive'; return window.__hubExploreDoc; })()"
        private const val FIXTURE =
            "(function(){ var old=document.getElementById('hub-explore-fixture'); if (old) old.remove();" +
                " var host=document.createElement('div'); host.id='hub-explore-fixture';" +
                " host.innerHTML='<div class=\"explore-div\"><div class=\"explore-desc\">" +
                "<button id=\"hub-explore-button\" type=\"button\">Your current base is 0/s.<br>Explore the ocean!</button>" +
                "<div><div style=\"min-height:118px;text-align:left;\"><h3>Pacific Ocean</h3>Corals: 0</div>" +
                "<div class=\"table-left\"><button class=\"explore-cost\" type=\"button\">Double corals</button>" +
                "<button class=\"explore-cost\" type=\"button\">Double depth</button></div></div>" +
                "<div><span>While exploring: ocean text and a milestone at 100m.</span></div>" +
                "</div></div>';" +
                " document.body.insertBefore(host, document.body.firstChild);" +
                " return host.id; })()"
        private const val MEASURE =
            "(function(){ var button=document.getElementById('hub-explore-button');" +
                " if (!button) return JSON.stringify({l:0,t:0,r:0,b:0,w:0,h:0,iw:window.innerWidth||0,ih:window.innerHeight||0,hit:false,hitId:'missing'});" +
                " button.scrollIntoView({block:'center',inline:'nearest'});" +
                " var r=button.getBoundingClientRect(); var iw=window.innerWidth||0; var ih=window.innerHeight||0;" +
                " var cx=r.left+r.width/2; var cy=r.top+Math.min(r.height/2, 24);" +
                " var hit=document.elementFromPoint(cx, cy);" +
                " var owned=hit===button || !!(hit && button.contains(hit));" +
                " window.__hubExploreTap=0;" +
                " if (owned) { button.addEventListener('click', function(){ window.__hubExploreTap=1; }, {once:true}); hit.click(); }" +
                " return JSON.stringify({l:r.left,t:r.top,r:r.right,b:r.bottom,w:r.width,h:r.height,iw:iw,ih:ih," +
                " hit:owned && window.__hubExploreTap===1, hitId: hit ? (hit.id||hit.tagName) : ''}); })()"
    }
}
