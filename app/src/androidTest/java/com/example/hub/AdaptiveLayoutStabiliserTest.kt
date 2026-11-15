package com.example.hub

import android.os.ParcelFileDescriptor
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.hub.games.DocumentLayout
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
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Two counters with no reserved selectors. The line under them is measured
 * before the stabiliser learns, and again after. The live game is not opened.
 */
@RunWith(AndroidJUnit4::class)
class AdaptiveLayoutStabiliserTest {

    @After
    fun restorePosture() {
        shell("cmd device_state state reset")
    }

    @Test
    fun reservationsStopRepeatedJumpsAndSurviveReload() {
        val narrow = learn(411, 800, "{}", fullChecks = true)
        println("adaptive layout 411 before=${narrow.before} after=${narrow.after}")
        shell("cmd device_state state 2")
        waitSize("2208")
        val wide = learn(840, 700, narrow.saved, fullChecks = false)
        println("adaptive layout 840 before=${wide.before} after=${wide.after}")
        val saved = JSONObject(wide.saved)
        assertTrue("each width keeps its own reservation: $saved", saved.length() >= 2)
        assertTrue(saved.has(narrow.config))
        assertTrue(saved.has(wide.config))
        reload(411, 800, wide.saved, narrow.config, wide.config)
        reload(840, 700, wide.saved, wide.config, narrow.config)
    }

    private fun learn(cssWidth: Int, cssHeight: Int, saved: String, fullChecks: Boolean): Learned {
        return ActivityScenario.launch(WebViewProbeActivity::class.java).use { scenario ->
            val web = addPage(scenario, cssWidth, cssHeight)
            waitWidth(web, cssWidth)
            val before = json(eval(web, MEASURE))
            assertTrue(
                "short and long text did not move the sentinel at $cssWidth: $before",
                before.getDouble("delta") > 1.0,
            )
            val marker = eval(web, script(cssWidth, saved))
            assertEquals(DocumentLayout.MARKER, marker)
            Thread.sleep(250)
            learnReservations(web)
            val after = json(eval(web, MEASURE))
            assertTrue(
                "learned reservations still moved the sentinel at $cssWidth: before=$before after=$after",
                after.getDouble("delta") <= 1.0,
            )
            if (fullChecks) checkStableCases(web)
            val state = json(eval(web, STATE))
            Learned(
                before = before.getDouble("delta"),
                after = after.getDouble("delta"),
                config = state.getString("config"),
                saved = state.getJSONObject("saved").toString(),
            )
        }
    }

    private fun checkStableCases(web: WebView) {
        val shorter = json(eval(web, SHORTER))
        assertEquals(shorter.getString("before"), shorter.getString("after"))
        assertTrue(shorter.getString("before").isNotEmpty())

        val once = json(eval(web, GROW_ONCE))
        Thread.sleep(300)
        val onceLater = json(eval(web, ONCE_STATE))
        assertEquals("", once.getString("min"))
        assertEquals("", once.getString("mark"))
        assertEquals("", onceLater.getString("min"))
        assertEquals("", onceLater.getString("mark"))

        val hidden = json(eval(web, HIDE))
        assertEquals(hidden.getString("before"), hidden.getString("hidden"))
        assertEquals(hidden.getString("before"), hidden.getString("shown"))
        Thread.sleep(300)
        val hiddenLater = json(eval(web, HEIGHTS))
        assertEquals(hidden.getString("before"), hiddenLater.getString("a"))
        val still = json(eval(web, MEASURE))
        assertTrue("hiding cleared the reservation: $still", still.getDouble("delta") <= 1.0)

        val tab = json(eval(web, OPEN_TAB))
        assertTrue(
            "opening a tab did not move the sentinel: $tab",
            tab.getDouble("after") >= tab.getDouble("before") + 40.0,
        )
        assertEquals(tab.getString("min"), tab.getString("minAfter"))
        Thread.sleep(300)
        val tabLater = json(eval(web, HEIGHTS))
        assertEquals(tab.getString("min"), tabLater.getString("a"))
    }

    private fun reload(
        cssWidth: Int,
        cssHeight: Int,
        saved: String,
        ownConfig: String,
        otherConfig: String,
    ) {
        ActivityScenario.launch(WebViewProbeActivity::class.java).use { scenario ->
            val web = addPage(scenario, cssWidth, cssHeight)
            waitWidth(web, cssWidth)
            val marker = eval(web, script(cssWidth, saved))
            assertEquals(DocumentLayout.MARKER, marker)
            val state = json(eval(web, STATE))
            assertEquals(ownConfig, state.getString("config"))
            val stored = state.getJSONObject("saved")
            assertTrue(stored.has(otherConfig))
            val own = stored.getJSONObject(ownConfig)
            assertTrue(own.length() >= 2)
            val applied = json(eval(web, HEIGHTS))
            assertEquals(px(own, "osc-a"), applied.getString("a"))
            assertEquals(px(own, "osc-b"), applied.getString("b"))
            val first = json(eval(web, MEASURE))
            assertTrue(
                "saved reservation was not stable on the first alternation at $cssWidth: $first",
                first.getDouble("delta") <= 1.0,
            )
            scenario.onActivity { web.destroy() }
        }
    }

    private fun px(bucket: JSONObject, id: String): String {
        val names = bucket.keys()
        while (names.hasNext()) {
            val key = names.next()
            if (key.substringBefore('\n') == "#$id") return "${bucket.getInt(key)}px"
        }
        throw AssertionError("no reservation for #$id in $bucket")
    }

    private fun learnReservations(web: WebView) {
        val deadline = System.currentTimeMillis() + 12_000
        var last = ""
        while (System.currentTimeMillis() < deadline) {
            val state = json(eval(web, HEIGHTS))
            last = state.toString()
            if (state.getString("a").isNotEmpty() && state.getString("b").isNotEmpty()) return
            eval(web, "document.getElementById('osc-a').textContent=window.__hubLong;document.getElementById('osc-b').textContent=window.__hubLong;'ok'")
            Thread.sleep(80)
            eval(web, "document.getElementById('osc-a').textContent=window.__hubShort;document.getElementById('osc-b').textContent=window.__hubShort;'ok'")
            Thread.sleep(80)
        }
        throw AssertionError("reservations were not learned: $last ${eval(web, DEBUG)}")
    }

    private fun script(cssWidth: Int, saved: String): String =
        DocumentLayout.injectionScript(
            limitCssPx = cssWidth,
            savedReservations = saved,
            textZoomPercent = 100,
            pageZoomLiteral = "1.00",
        )

    private fun addPage(
        scenario: ActivityScenario<WebViewProbeActivity>,
        cssWidth: Int,
        cssHeight: Int,
    ): WebView {
        val ready = CountDownLatch(1)
        val ref = AtomicReference<WebView>()
        scenario.onActivity { activity ->
            val density = activity.resources.displayMetrics.density
            val web = WebView(activity)
            web.settings.javaScriptEnabled = true
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    ready.countDown()
                }
            }
            activity.findViewById<ViewGroup>(android.R.id.content).addView(
                web,
                ViewGroup.LayoutParams(
                    (cssWidth * density).roundToInt().coerceAtLeast(1),
                    (cssHeight * density).roundToInt().coerceAtLeast(1),
                ),
            )
            web.loadDataWithBaseURL("https://hub.test/stable", FIXTURE, "text/html", "utf-8", null)
            ref.set(web)
        }
        assertTrue(ready.await(15, TimeUnit.SECONDS))
        return ref.get()
    }

    private fun waitWidth(web: WebView, cssWidth: Int) {
        val deadline = System.currentTimeMillis() + 8_000
        val slack = max(16.0, cssWidth * 0.05)
        var last = 0.0
        while (System.currentTimeMillis() < deadline) {
            last = eval(web, "(window.innerWidth||0)").toDoubleOrNull() ?: 0.0
            if (abs(last - cssWidth) <= slack) return
            Thread.sleep(100)
        }
        throw AssertionError("viewport did not become $cssWidth: $last")
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
        assertTrue(latch.await(20, TimeUnit.SECONDS))
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

    private fun json(raw: String): JSONObject = JSONObject(raw)

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

    private fun shell(command: String): String {
        val parcel: ParcelFileDescriptor =
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(parcel).use { stream ->
            InputStreamReader(stream).readText()
        }
    }

    private data class Learned(
        val before: Double,
        val after: Double,
        val config: String,
        val saved: String,
    )

    companion object {
        private val FIXTURE = """
            <!DOCTYPE html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <style>
                body { margin: 0; font-size: 16px; }
                #osc-a, #osc-b, #once {
                  width: 160px;
                  font: 16px/20px monospace;
                  overflow-wrap: anywhere;
                  word-break: break-all;
                }
                #panel-tab { display: none; height: 80px; }
                #sentinel { height: 20px; }
              </style>
            </head>
            <body>
              <div id="osc-a">1.5</div>
              <div id="osc-b">1.5</div>
              <div id="once">1</div>
              <div id="panel-tab">Core</div>
              <div id="sentinel">mark</div>
              <script>
                window.__hubShort = '1.5';
                window.__hubLong = '1234567890123456789012345678901234567890';
              </script>
            </body>
            </html>
        """.trimIndent()

        private const val MEASURE =
            "(function(){" +
                " var a=document.getElementById('osc-a');" +
                " var b=document.getElementById('osc-b');" +
                " var s=document.getElementById('sentinel');" +
                " function set(text){ a.textContent=text; b.textContent=text; }" +
                " set(window.__hubShort);" +
                " var start=s.getBoundingClientRect().top;" +
                " var shortH=a.getBoundingClientRect().height;" +
                " set(window.__hubLong);" +
                " var longH=a.getBoundingClientRect().height;" +
                " var delta=0;" +
                " for (var i=0;i<30;i++) {" +
                " set(i%2?window.__hubShort:window.__hubLong);" +
                " delta=Math.max(delta, Math.abs(s.getBoundingClientRect().top-start)); }" +
                " set(window.__hubShort);" +
                " return JSON.stringify({delta:delta, shortH:shortH, longH:longH, iw:window.innerWidth||0});" +
                "})()"

        private const val HEIGHTS =
            "(function(){" +
                " var a=document.getElementById('osc-a');" +
                " var b=document.getElementById('osc-b');" +
                " return JSON.stringify({a:a.style.minHeight,b:b.style.minHeight});" +
                "})()"

        private const val STATE =
            "(function(){" +
                " var w=Math.round((window.innerWidth||0)/8)*8;" +
                " return JSON.stringify({config:w+'x'+(window.__hubTextZoom||100)+'x'+(window.__hubPageZoom||'1.00')," +
                " saved:window.__hubStableSaved||{}});" +
                "})()"

        private const val SHORTER =
            "(function(){" +
                " var a=document.getElementById('osc-a');" +
                " var before=a.style.minHeight;" +
                " a.textContent='1';" +
                " return JSON.stringify({before:before, after:a.style.minHeight});" +
                "})()"

        private const val GROW_ONCE =
            "(function(){" +
                " var el=document.getElementById('once');" +
                " el.textContent=window.__hubLong;" +
                " return JSON.stringify({min:el.style.minHeight, mark:el.getAttribute('data-hub-stable')||''});" +
                "})()"

        private const val ONCE_STATE =
            "(function(){" +
                " var el=document.getElementById('once');" +
                " return JSON.stringify({min:el.style.minHeight, mark:el.getAttribute('data-hub-stable')||''});" +
                "})()"

        private const val HIDE =
            "(function(){" +
                " var a=document.getElementById('osc-a');" +
                " var before=a.style.minHeight;" +
                " a.style.display='none';" +
                " var hidden=a.style.minHeight;" +
                " a.style.display='';" +
                " return JSON.stringify({before:before, hidden:hidden, shown:a.style.minHeight});" +
                "})()"

        private const val OPEN_TAB =
            "(function(){" +
                " var a=document.getElementById('osc-a');" +
                " var s=document.getElementById('sentinel');" +
                " var before=s.getBoundingClientRect().top;" +
                " var min=a.style.minHeight;" +
                " document.getElementById('panel-tab').style.display='block';" +
                " return JSON.stringify({before:before, after:s.getBoundingClientRect().top," +
                " min:min, minAfter:a.style.minHeight});" +
                "})()"

        private const val DEBUG =
            "(function(){" +
                " var types=[];" +
                " try { types=PerformanceObserver.supportedEntryTypes||[]; } catch (e) {}" +
                " return JSON.stringify({types:types.join(','), attributed:window.__hubStableAttributed||0," +
                " fallback:window.__hubStableFallback||0, installed:window.__hubStableInstalled||0," +
                " saved:window.__hubStableSaved||{}});" +
                "})()"
    }
}
