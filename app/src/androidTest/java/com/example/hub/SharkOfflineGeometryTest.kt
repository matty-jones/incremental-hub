package com.example.hub

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.PixelCopy
import android.view.ViewGroup
import android.widget.FrameLayout
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.hub.games.SharkIncrementalAdapter
import com.example.hub.games.SharkLayout
import com.example.hub.games.SharkProbe
import org.json.JSONArray
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

/**
 * Measures Shark's real offline overlay on the foldable display.
 * Disposable WebView storage only; it does not open the user's Hub profile.
 */
@RunWith(AndroidJUnit4::class)
class SharkOfflineGeometryTest {

    @Test
    fun zeroInitialHeightThenResizeAndDoneButton() {
        val report = StringBuilder()
        ActivityScenario.launch(WebViewProbeActivity::class.java).use { scenario ->
            val webRef = AtomicReference<WebView>()
            scenario.onActivity { activity ->
                val web = WebView(activity)
                web.settings.javaScriptEnabled = true
                web.settings.domStorageEnabled = true
                activity.findViewById<ViewGroup>(android.R.id.content).addView(
                    web,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                webRef.set(web)
            }
            val web = webRef.get()
            shell("cmd device_state state 2")
            load(web, LAUNCH)
            assertTrue(waitUntil(web, 40_000) { it == "app" || it == "offline" } != "pending")
            eval(web, SAVE_THEN_REWRITE.replace("__AGE__", "90").replace("__RADIO__", "1"))
            onWeb(web) {
                it.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 1)
                it.requestLayout()
            }
            load(web, LAUNCH)
            val tiny = waitUntil(web, 20_000) { it == "offline" || it == "app" }
            report.append("tiny-phase=$tiny web=").append(viewSize(web)).append(' ').append(probe(web)).append('\n')
            onWeb(web) {
                it.layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
                it.requestLayout()
            }
            Thread.sleep(800)
            report.append("resized web=").append(viewSize(web)).append(' ').append(probe(web)).append('\n')
            eval(web, SharkLayout.injectionScript())
            eval(web, "(function(){ window.dispatchEvent(new Event('resize')); return true; })()")
            Thread.sleep(400)
            report.append("resized-layout ").append(probe(web)).append('\n')
            val done = waitUntil(web, 12_000) {
                eval(web, "(function(){ var d=document.getElementById('offline-done'); return d && d.style.display !== 'none' ? 'done' : 'wait'; })()") == "done"
            }
            report.append("done-without-fixed phase=$done ").append(probe(web)).append('\n')
            report.append("shot-done ").append(capture(scenario, web, "done-without-fixed")).append('\n')
            eval(web, com.example.hub.ui.pageZoomScript(100))
            Thread.sleep(300)
            report.append("zoom-100 ").append(probe(web)).append('\n')
            scenario.onActivity { web.destroy() }
        }
        val text = report.toString()
        Log.i(TAG, text)
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
        File(dir, "shark-zero-resize.txt").writeText(text)
        assertTrue(text, text.contains("tiny-phase="))
    }

    @Test
    fun agedOfflineSaveReportsOverlayGeometry() {
        val report = StringBuilder()
        var layoutPaints = false
        var enteredOffline = false
        ActivityScenario.launch(WebViewProbeActivity::class.java).use { scenario ->
            val webRef = AtomicReference<WebView>()
            scenario.onActivity { activity ->
                val web = WebView(activity)
                web.settings.javaScriptEnabled = true
                web.settings.domStorageEnabled = true
                web.webViewClient = WebViewClient()
                activity.findViewById<ViewGroup>(android.R.id.content).addView(
                    web,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                webRef.set(web)
            }
            val web = webRef.get()
            shell("cmd device_state state reset")
            Thread.sleep(500)
            report.append("start ").append(shell("wm size").trim()).append('\n')

            eval(web, "localStorage.removeItem('shark_inc_save'); 'cleared'")
            load(web, LAUNCH)
            val fresh = waitUntil(web, 45_000) { phase ->
                phase == "app" || phase == "offline"
            }
            report.append("fresh-phase=$fresh\n")
            report.append("fresh ").append(probe(web)).append('\n')
            val saved = eval(web, SAVE_THEN_REWRITE.replace("__AGE__", "5").replace("__RADIO__", "1"))
            report.append("recent-write=$saved\n")

            load(web, LAUNCH)
            val recent = waitUntil(web, 30_000) { it == "app" || it == "offline" }
            report.append("recent-phase=$recent ").append(probe(web)).append('\n')

            eval(web, SAVE_THEN_REWRITE.replace("__AGE__", "180").replace("__RADIO__", "1"))
            load(web, LAUNCH)
            val aged = waitUntil(web, 30_000) { it == "app" || it == "offline" }
            enteredOffline = aged == "offline"
            val agedProbe = probe(web)
            val agedLater = probe(web)
            report.append("aged-no-css phase=$aged\n")
            report.append("aged-no-css-a $agedProbe\n")
            report.append("aged-no-css-b $agedLater\n")
            report.append("shot-no-css ").append(capture(scenario, web, "aged-no-css")).append('\n')

            eval(web, SharkLayout.injectionScript())
            Thread.sleep(400)
            val layoutProbe = probe(web)
            val ready = eval(web, SharkIncrementalAdapter.JS_UI_READY)
            layoutPaints = layoutProbe.contains("\"hit\":true") && ready == "true"
            report.append("aged-layout ready=$ready $layoutProbe\n")
            report.append("shot-layout ").append(capture(scenario, web, "aged-layout")).append('\n')

            eval(web, FIXED_OVERLAY)
            Thread.sleep(400)
            report.append("aged-fixed ready=")
                .append(eval(web, SharkIncrementalAdapter.JS_UI_READY))
                .append(' ')
                .append(probe(web))
                .append('\n')
            report.append("shot-fixed ").append(capture(scenario, web, "aged-fixed")).append('\n')

            shell("cmd device_state state 0")
            val foldedSize = waitSize("1080x2092")
            Thread.sleep(800)
            scenario.onActivity { web.requestLayout() }
            Thread.sleep(400)
            report.append("folded-size=$foldedSize\n")
            report.append("folded ready=")
                .append(eval(web, SharkIncrementalAdapter.JS_UI_READY))
                .append(' ')
                .append(probe(web))
                .append('\n')
            report.append("shot-folded ").append(capture(scenario, web, "aged-folded")).append('\n')

            shell("cmd device_state state 2")
            val openedSize = waitSize("2208x1840")
            Thread.sleep(800)
            report.append("unfolded-size=$openedSize\n")
            report.append("unfolded ").append(probe(web)).append('\n')

            onWeb(web) {
                it.settings.useWideViewPort = true
                it.settings.loadWithOverviewMode = true
            }
            eval(web, SAVE_THEN_REWRITE.replace("__AGE__", "180").replace("__RADIO__", "1"))
            load(web, LAUNCH)
            val wide = waitUntil(web, 30_000) { it == "app" || it == "offline" }
            eval(web, SharkLayout.injectionScript())
            Thread.sleep(400)
            report.append("wide-layout phase=$wide ready=")
                .append(eval(web, SharkIncrementalAdapter.JS_UI_READY))
                .append(' ')
                .append(probe(web))
                .append('\n')
            report.append("shot-wide ").append(capture(scenario, web, "aged-wide-layout")).append('\n')

            eval(web, SAVE_THEN_REWRITE.replace("__AGE__", "180").replace("__RADIO__", "0"))
            load(web, LAUNCH)
            val disabled = waitUntil(web, 30_000) { it == "app" || it == "offline" }
            eval(web, SharkLayout.injectionScript())
            Thread.sleep(400)
            report.append("offline-disabled phase=$disabled ready=")
                .append(eval(web, SharkIncrementalAdapter.JS_UI_READY))
                .append(' ')
                .append(probe(web))
                .append('\n')

            shell("cmd device_state state reset")
            scenario.onActivity { web.destroy() }
        }

        val text = report.toString()
        Log.i(TAG, text)
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
        File(dir, "shark-offline-geometry.txt").writeText(text)
        assertTrue(
            "offline=$enteredOffline layoutPaints=$layoutPaints\n$text",
            enteredOffline && layoutPaints,
        )
    }

    private fun onWeb(web: WebView, block: (WebView) -> Unit) {
        val latch = CountDownLatch(1)
        web.post {
            block(web)
            latch.countDown()
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
    }

    private fun load(web: WebView, url: String) {
        val latch = CountDownLatch(1)
        web.post {
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, finished: String?) {
                    latch.countDown()
                }
            }
            web.loadUrl(url)
        }
        assertTrue("page finish $url", latch.await(60, TimeUnit.SECONDS))
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

    private fun probe(web: WebView): String = eval(web, SharkProbe.JS)

    private fun viewSize(web: WebView): String {
        val size = AtomicReference("?")
        val latch = CountDownLatch(1)
        web.post {
            size.set("${web.width}x${web.height}")
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
        return size.get()
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
            val value = JSONArray("[$raw]").opt(0)
            value?.toString() ?: ""
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

    private fun capture(
        scenario: ActivityScenario<WebViewProbeActivity>,
        web: WebView,
        name: String,
    ): String {
        val bitmapRef = AtomicReference<Bitmap?>()
        val code = AtomicInteger(-1)
        val latch = CountDownLatch(1)
        scenario.onActivity { activity ->
            val width = web.width
            val height = web.height
            if (width < 2 || height < 2) {
                code.set(-2)
                latch.countDown()
                return@onActivity
            }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val loc = IntArray(2)
            web.getLocationInWindow(loc)
            val rect = Rect(loc[0], loc[1], loc[0] + width, loc[1] + height)
            PixelCopy.request(activity.window, rect, bitmap, { result ->
                code.set(result)
                bitmapRef.set(bitmap)
                latch.countDown()
            }, Handler(Looper.getMainLooper()))
        }
        if (!latch.await(5, TimeUnit.SECONDS)) return "pixelcopy-timeout"
        val bitmap = bitmapRef.get() ?: return "pixelcopy-empty code=${code.get()}"
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
        val file = File(dir, "$name.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return "code=${code.get()} ${bitmap.width}x${bitmap.height} ${sample(bitmap)} file=${file.absolutePath}"
    }

    private fun sample(bitmap: Bitmap): String {
        var teal = 0
        var dark = 0
        var other = 0
        val stepX = (bitmap.width / 40).coerceAtLeast(1)
        val stepY = (bitmap.height / 40).coerceAtLeast(1)
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val color = bitmap.getPixel(x, y)
                val red = Color.red(color)
                val green = Color.green(color)
                val blue = Color.blue(color)
                if (green > 140 && blue > 120 && red < 90) {
                    teal += 1
                } else if (red < 50 && green < 50 && blue < 50) {
                    dark += 1
                } else {
                    other += 1
                }
                x += stepX
            }
            y += stepY
        }
        return "teal=$teal dark=$dark other=$other"
    }

    companion object {
        private const val TAG = "SHARK_PROBE"
        private const val LAUNCH = "https://mrredshark77.github.io/shark-incremental/"
        private const val PHASE =
            "(function(){ try {" +
                " if (typeof player === 'undefined' || !player) return 'loading';" +
                " if (typeof offline !== 'undefined' && offline.active) return 'offline';" +
                " var app = document.getElementById('app');" +
                " if (app && app.style.display !== 'none') return 'app';" +
                " return 'pending';" +
                " } catch (e) { return 'error'; } })()"
        private const val SAVE_THEN_REWRITE =
            "(function(){ try {" +
                " if (typeof save === 'function' && !(typeof offline !== 'undefined' && offline.nosave)) save();" +
                " var raw = localStorage.getItem('shark_inc_save');" +
                " if (!raw) return 'missing';" +
                " var p = JSON.parse(atob(raw));" +
                " p.latest_time = Date.now() - (__AGE__ * 1000);" +
                " if (!p.radios) p.radios = {};" +
                " p.radios.offline = __RADIO__;" +
                " if (typeof p.totalTimePlayed !== 'number' || p.totalTimePlayed < 30) p.totalTimePlayed = 600;" +
                " localStorage.setItem('shark_inc_save', btoa(JSON.stringify(p)));" +
                " return JSON.stringify({v:p.VERSION, radio:p.radios.offline, played:p.totalTimePlayed});" +
                " } catch (e) { return 'rewrite-failed:' + String(e).slice(0, 120); } })()"
        private const val FIXED_OVERLAY =
            "(function(){ var css = " +
                "'#offline{position:fixed !important;top:0 !important;left:0 !important;right:0 !important;bottom:0 !important;" +
                "width:auto !important;height:auto !important;min-height:0 !important;z-index:30 !important;}" +
                "#offline-graph{width:100% !important;max-width:100% !important;height:160px !important;}" +
                "#offline-speed,#offline-done{position:sticky;bottom:8px;}';" +
                " var node = document.getElementById('hub-offline-fixed');" +
                " if (!node) { node = document.createElement('style'); node.id = 'hub-offline-fixed';" +
                " (document.head || document.documentElement).appendChild(node); }" +
                " node.textContent = css; return 'fixed'; })()"
    }
}
