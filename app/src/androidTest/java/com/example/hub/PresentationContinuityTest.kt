package com.example.hub

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.PixelCopy
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.hub.games.SharkLayout
import com.example.hub.ui.PageScaleMode
import com.example.hub.ui.parsePresentationAck
import com.example.hub.ui.presentationScript
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import com.example.hub.ui.PresentationAck

/**
 * Fold and FE000000 scaling on one WebView. Scale correction must not navigate.
 */
@RunWith(AndroidJUnit4::class)
class PresentationContinuityTest {

    @Test
    fun foldAndFeScalingStayWithTheSession() {
        val report = StringBuilder()
        ActivityScenario.launch(WebViewProbeActivity::class.java).use { scenario ->
            val web = addWeb(scenario)
            val finishes = AtomicInteger(0)
            shell("cmd device_state state 2")
            waitSize("2208")
            load(web, SHARK, finishes)
            val sharkLoads = finishes.get()
            assertTrue(waitUntil(web, 45_000) { it == "app" || it == "offline" } != "pending")
            eval(web, SharkLayout.injectionScript())
            val initial = apply(web, PageScaleMode.HUB, 100)
            report.append("shark-initial $initial\n")
            assertAck(initial, 100)

            val scrollBefore = eval(web, SCROLL)
            report.append("scroll-before $scrollBefore\n")
            repeat(2) { pass ->
                fold()
                val folded = apply(web, PageScaleMode.HUB, 100)
                report.append("shark-fold-$pass $folded\n")
                assertAck(folded, 100)
                assertTrue(folded.innerWidth in 380..460)
                assertEquals(sharkLoads, finishes.get())
                Thread.sleep(350)
                val white = rightWhite(capture(scenario, web, "shark-fold-$pass"))
                report.append("shark-fold-$pass white=$white\n")
                assertTrue("folded frame still magnified white=$white", white < 0.08f)
                unfold()
                val unfolded = apply(web, PageScaleMode.HUB, 100)
                report.append("shark-unfold-$pass $unfolded\n")
                assertAck(unfolded, 100)
                assertTrue(unfolded.innerWidth in 780..900)
                assertEquals(sharkLoads, finishes.get())
            }
            val scrollAfter = eval(web, SCROLL_READ)
            report.append("scroll-after $scrollAfter\n")

            unfold()
            assertAck(apply(web, PageScaleMode.HUB, 110), 110)
            fold()
            assertAck(apply(web, PageScaleMode.HUB, 90), 90)
            unfold()
            val restored = apply(web, PageScaleMode.HUB, 110)
            assertAck(restored, 110)
            assertEquals(sharkLoads, finishes.get())

            scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
            Thread.sleep(400)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            Thread.sleep(400)
            assertEquals(sharkLoads, finishes.get())
            assertAck(apply(web, PageScaleMode.HUB, 100), 100)

            load(web, FE, finishes)
            val feLoads = finishes.get()
            assertTrue(feLoads > sharkLoads)
            assertEquals("fe", waitUntil(web, 60_000) { it == "fe" })
            Thread.sleep(500)
            report.append("fe-default ").append(eval(web, PROBE)).append('\n')
            fold()
            Thread.sleep(400)
            report.append("fe-default-folded ").append(eval(web, PROBE)).append('\n')
            report.append("java-default ").append(javaState(web)).append('\n')
            unfold()
            val readable = apply(web, PageScaleMode.READABLE, 100)
            report.append("fe-readable $readable\n")
            report.append("wide-unfolded ").append(eval(web, WIDE)).append('\n')
            assertAck(readable, 100)
            assertEquals(true, readable.gameFitEnabled)
            val mainZoom = apply(web, PageScaleMode.READABLE, 100)
            eval(web, TAB_AUTO)
            Thread.sleep(300)
            val autoZoom = apply(web, PageScaleMode.READABLE, 100)
            report.append("fe-main $mainZoom\n")
            report.append("fe-auto $autoZoom\n")
            assertAck(mainZoom, 100)
            assertAck(autoZoom, 100)
            assertEquals(mainZoom.bodyZoom.toFloat(), autoZoom.bodyZoom.toFloat(), 0.02f)
            eval(web, TAB_MAIN)

            fold()
            Thread.sleep(300)
            report.append("fe-folded-raw ").append(eval(web, PROBE)).append('\n')
            apply(web, PageScaleMode.READABLE, 100)
            Thread.sleep(250)
            val foldedFe = apply(web, PageScaleMode.READABLE, 100)
            report.append("fe-folded $foldedFe\n")
            report.append("fe-folded-probe ").append(eval(web, PROBE)).append('\n')
            report.append("wide-folded ").append(eval(web, WIDE)).append('\n')
            val rowText = eval(web, ROW_TEXT)
            report.append("fe-folded-text ").append(rowText).append('\n')
            report.append("java-folded ").append(javaState(web)).append('\n')
            report.append("shot-fe-folded white=${rightWhite(capture(scenario, web, "fe-readable-folded"))}\n")
            File(
                InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
                "presentation-continuity.txt",
            ).writeText(report.toString())
            assertTrue(rowText, rowText.contains("Generator 1") && rowText.contains("Cost"))
            assertAck(foldedFe, 100)
            assertTrue("width ${foldedFe.innerWidth}", foldedFe.innerWidth in 380..460)
            eval(web, TAB_AUTO)
            Thread.sleep(300)
            val foldedAuto = apply(web, PageScaleMode.READABLE, 100)
            report.append("fe-folded-auto $foldedAuto\n")
            assertAck(foldedAuto, 100)
            assertEquals(foldedFe.bodyZoom.toFloat(), foldedAuto.bodyZoom.toFloat(), 0.02f)
            capture(scenario, web, "fe-readable-folded-autobuyers")
            eval(web, TAB_MAIN)
            unfold()
            assertAck(apply(web, PageScaleMode.READABLE, 75), 75)
            assertAck(apply(web, PageScaleMode.READABLE, 100), 100)
            assertAck(apply(web, PageScaleMode.READABLE, 125), 125)
            assertAck(apply(web, PageScaleMode.READABLE, 100), 100)
            fold()
            Thread.sleep(300)
            apply(web, PageScaleMode.READABLE, 100)
            Thread.sleep(250)
            val foldedAfterZoom = apply(web, PageScaleMode.READABLE, 100)
            report.append("fe-folded-after-zoom $foldedAfterZoom\n")
            assertAck(foldedAfterZoom, 100)
            assertTrue("width after zoom ${foldedAfterZoom.innerWidth}", foldedAfterZoom.innerWidth in 380..460)
            unfold()

            val gameFit = apply(web, PageScaleMode.GAME_FIT, 100)
            report.append("fe-game-fit $gameFit\n")
            assertTrue(gameFit.ok)
            val expect = kotlin.math.min(gameFit.innerWidth / 1376.0, 1.0)
            val actual = gameFit.bodyZoom.toDouble()
            assertTrue("fit $actual expected $expect", kotlin.math.abs(actual - expect) < 0.03)
            val fitMain = gameFit.bodyZoom
            eval(web, TAB_AUTO)
            Thread.sleep(300)
            val fitAuto = apply(web, PageScaleMode.GAME_FIT, 100)
            report.append("fe-game-fit-auto $fitAuto\n")
            assertTrue(kotlin.math.abs(fitAuto.bodyZoom.toDouble() - fitMain.toDouble()) < 0.03)

            scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
            Thread.sleep(400)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            Thread.sleep(400)
            assertEquals(feLoads, finishes.get())
            scenario.onActivity { web.destroy() }
        }
        val text = report.toString()
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
        File(dir, "presentation-continuity.txt").writeText(text)
    }

    private fun apply(web: WebView, mode: PageScaleMode, percent: Int): PresentationAck {
        val raw = eval(web, presentationScript(mode, percent, flush = true))
        val ack = parsePresentationAck(raw, percent, 100)
        assertNotNull(raw, ack)
        return ack!!
    }

    private fun assertAck(ack: PresentationAck, percent: Int) {
        assertTrue(ack.toString(), ack.ok)
        val scale = ack.viewportScale
        assertNotNull(scale)
        assertTrue("viewport ${ack.viewportScale}", kotlin.math.abs(scale!! - 1.0) < 0.05)
        if (ack.mode == PageScaleMode.HUB || ack.mode == PageScaleMode.READABLE) {
            val shown = if (ack.mode == PageScaleMode.READABLE) ack.bodyZoom else ack.rootZoom
            val value = shown.toFloat()
            assertTrue("$shown for $percent", kotlin.math.abs(value - percent / 100f) < 0.02f)
        }
    }

    private fun fold() {
        shell("cmd device_state state 0")
        waitSize("1080")
        Thread.sleep(600)
    }

    private fun unfold() {
        shell("cmd device_state state 2")
        waitSize("2208")
        Thread.sleep(600)
    }

    private fun addWeb(scenario: ActivityScenario<WebViewProbeActivity>): WebView {
        val ref = AtomicReference<WebView>()
        scenario.onActivity { activity ->
            val web = WebView(activity)
            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            web.settings.textZoom = 100
            activity.findViewById<ViewGroup>(android.R.id.content).addView(
                web,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
            ref.set(web)
        }
        return ref.get()
    }

    private fun load(web: WebView, url: String, finishes: AtomicInteger) {
        val latch = CountDownLatch(1)
        web.post {
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, finished: String?) {
                    finishes.incrementAndGet()
                    latch.countDown()
                }
            }
            web.loadUrl(url)
        }
        assertTrue("page finish $url", latch.await(90, TimeUnit.SECONDS))
    }

    private fun waitUntil(web: WebView, timeoutMs: Long, accept: (String) -> Boolean): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        var phase = "pending"
        while (System.currentTimeMillis() < deadline) {
            phase = eval(web, PHASE)
            if (accept(phase)) return phase
            Thread.sleep(300)
        }
        return phase
    }

    private fun waitSize(expected: String): String {
        val deadline = System.currentTimeMillis() + 8_000
        var size = ""
        while (System.currentTimeMillis() < deadline) {
            size = shell("wm size").trim()
            if (size.contains(expected)) return size
            Thread.sleep(200)
        }
        return size
    }

    private fun javaState(web: WebView): String {
        val value = AtomicReference("")
        val latch = CountDownLatch(1)
        web.post {
            value.set(
                "w=${web.width} h=${web.height} scale=${web.scale} wide=${web.settings.useWideViewPort}" +
                    " overview=${web.settings.loadWithOverviewMode} density=${web.resources.displayMetrics.density}",
            )
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
        return value.get()
    }

    private fun eval(web: WebView, script: String): String {
        val value = AtomicReference("eval-timeout")
        val latch = CountDownLatch(1)
        web.post {
            web.evaluateJavascript(script) { raw ->
                value.set(unwrap(raw))
                latch.countDown()
            }
        }
        if (!latch.await(15, TimeUnit.SECONDS)) return "eval-timeout"
        return value.get()
    }

    private fun unwrap(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return try {
            JSONArray("[$raw]").opt(0)?.toString() ?: ""
        } catch (e: Exception) {
            raw
        }
    }

    private fun shell(command: String): String {
        val parcel: ParcelFileDescriptor =
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(parcel).use { stream ->
            InputStreamReader(stream).readText()
        }
    }

    private fun capture(scenario: ActivityScenario<WebViewProbeActivity>, web: WebView, name: String): Bitmap {
        val bitmapRef = AtomicReference<Bitmap?>()
        val latch = CountDownLatch(1)
        scenario.onActivity { activity ->
            val width = web.width
            val height = web.height
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val loc = IntArray(2)
            web.getLocationInWindow(loc)
            val rect = Rect(loc[0], loc[1], loc[0] + width, loc[1] + height)
            PixelCopy.request(activity.window, rect, bitmap, {
                bitmapRef.set(bitmap)
                latch.countDown()
            }, Handler(Looper.getMainLooper()))
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        val bitmap = bitmapRef.get()!!
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
        FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    private fun rightWhite(bitmap: Bitmap): Float {
        val start = (bitmap.width * 0.92f).toInt()
        var white = 0
        var count = 0
        val stepY = (bitmap.height / 40).coerceAtLeast(1)
        val stepX = ((bitmap.width - start) / 8).coerceAtLeast(1)
        var y = 0
        while (y < bitmap.height) {
            var x = start
            while (x < bitmap.width) {
                val color = bitmap.getPixel(x, y)
                count += 1
                if (Color.red(color) > 230 && Color.green(color) > 230 && Color.blue(color) > 230) white += 1
                x += stepX
            }
            y += stepY
        }
        return white.toFloat() / count.coerceAtLeast(1)
    }

    companion object {
        private const val SHARK = "https://mrredshark77.github.io/shark-incremental/"
        private const val FE = "https://dan-simon.github.io/misc/fe000000/"
        private const val PHASE =
            "(function(){ try {" +
                " if (typeof Options !== 'undefined' && Options.fitToWidth && typeof player !== 'undefined' && player) return 'fe';" +
                " if (typeof player === 'undefined' || !player) return 'loading';" +
                " if (typeof offline !== 'undefined' && offline.active) return 'offline';" +
                " var app = document.getElementById('app');" +
                " if (app && app.style.display !== 'none') return 'app';" +
                " return 'pending'; } catch (e) { return 'error'; } })()"
        private const val SCROLL =
            "(function(){ var h = document.documentElement.scrollHeight; window.scrollTo(0, 80);" +
                " return JSON.stringify({h:h, sy:window.scrollY}); })()"
        private const val SCROLL_READ =
            "(function(){ return JSON.stringify({sy:window.scrollY, h:document.documentElement.scrollHeight}); })()"
        private const val TAB_AUTO =
            "(function(){ Tabs.setTab('autobuyers'); return player.currentTab; })()"
        private const val TAB_MAIN =
            "(function(){ Tabs.setTab('main'); return player.currentTab; })()"
        private const val ROW_TEXT =
            "(function(){ var row = document.querySelector('#b135') || document.querySelector('#main table tr');" +
                " return row ? row.innerText.replace(/\\s+/g, ' ').slice(0, 120) : 'none'; })()"
        private const val WIDE =
            "(function(){ var out = []; var nodes = document.querySelectorAll('body *');" +
                " for (var i = 0; i < nodes.length; i++) { var n = nodes[i];" +
                " var kids = n.children; var leaf = true;" +
                " for (var k = 0; k < kids.length; k++) { if (kids[k].scrollWidth >= n.scrollWidth - 2) { leaf = false; break; } }" +
                " if (leaf && n.scrollWidth > 420) { var cs = getComputedStyle(n);" +
                " out.push(n.tagName + '#' + (n.id || '') + ' sw=' + n.scrollWidth + ' mw=' + cs.maxWidth + ' ws=' + cs.whiteSpace); } }" +
                " return out.slice(0, 8).join(' | '); })()"
        private const val PROBE =
            "(function(){ var vv = window.visualViewport; var root = document.documentElement;" +
                " var meta = document.querySelector('meta[name=viewport]');" +
                " var widest = 0; var name = '';" +
                " if (document.body) { var nodes = document.body.querySelectorAll('div, table, tr');" +
                " for (var i = 0; i < nodes.length; i++) { var w = nodes[i].scrollWidth;" +
                " if (w > widest) { widest = w; name = nodes[i].tagName + '.' + (nodes[i].className || nodes[i].id || ''); } } }" +
                " return JSON.stringify({iw: window.innerWidth, ih: window.innerHeight, vs: vv ? vv.scale : null," +
                " root: root.style.zoom || '', body: document.body ? (document.body.style.zoom || '') : ''," +
                " sw: root.scrollWidth, bw: document.body ? document.body.scrollWidth : 0, cw: root.clientWidth," +
                " meta: meta ? meta.content : null, screen: screen.width, widest: widest, who: name}); })()"
    }
}
