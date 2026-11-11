package com.example.hub

import android.os.ParcelFileDescriptor
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.hub.games.DocumentLayout
import com.example.hub.games.SharkLayout
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
 * A fish sentence whose rate changes length. The next line stays put.
 * The live Shark page and the personal profile are not opened.
 */
@RunWith(AndroidJUnit4::class)
class StableMetricLayoutTest {

    @After
    fun restorePosture() {
        shell("cmd device_state state reset")
    }

    @Test
    fun alternatingRatesKeepTheNextLineStill() {
        hold(411, 800)
        shell("cmd device_state state 2")
        waitSize("2208")
        hold(840, 700)
    }

    private fun hold(cssWidth: Int, cssHeight: Int) {
        ActivityScenario.launch(WebViewProbeActivity::class.java).use { scenario ->
            val web = addPage(scenario, cssWidth, cssHeight)
            val before = waitForShift(web, cssWidth)
            assertTrue(
                "rate length did not move the next line at $cssWidth: $before",
                abs(before.getDouble("longTop") - before.getDouble("shortTop")) > 1.0,
            )
            val marker = eval(
                web,
                DocumentLayout.injectionScript(
                    cssWidth,
                    SharkLayout.STABLE_METRICS,
                    SharkLayout.STABLE_LINE,
                ),
            )
            assertEquals(DocumentLayout.MARKER, marker)
            val after = json(eval(web, STABILITY))
            assertTrue("reserved rates still moved the next line at $cssWidth: $after", after.getDouble("delta") <= 1.0)
            assertEquals(after.getString("expect"), after.getString("text"))
            assertEquals("visible", after.getString("overflowX"))
            assertEquals("visible", after.getString("overflowY"))
            assertEquals("none", after.getString("maxHeight"))
            assertTrue("long rate was clipped at $cssWidth: $after", after.getBoolean("inside"))
            assertTrue("metric style missing at $cssWidth: $after", after.getBoolean("styled"))
            val minHeight = after.getString("minHeight")
            assertTrue("no reserved height at $cssWidth: $minHeight", minHeight != "0px" && minHeight != "auto")
            assertTrue(
                "opening a tab did not move the next line at $cssWidth: $after",
                after.getDouble("afterTab") >= after.getDouble("beforeTab") + 40.0,
            )
            scenario.onActivity { web.destroy() }
        }
    }

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
            web.loadDataWithBaseURL("https://hub.test/metric", FIXTURE, "text/html", "utf-8", null)
            ref.set(web)
        }
        assertTrue(ready.await(15, TimeUnit.SECONDS))
        return ref.get()
    }

    private fun waitForShift(web: WebView, cssWidth: Int): JSONObject {
        val deadline = System.currentTimeMillis() + 8_000
        var last = JSONObject()
        val slack = max(16.0, cssWidth * 0.05)
        while (System.currentTimeMillis() < deadline) {
            last = json(eval(web, FIND_SHIFT))
            val iw = last.optDouble("iw")
            if (last.optBoolean("found") && abs(iw - cssWidth) <= slack) return last
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

    companion object {
        private val FIXTURE = """
            <!DOCTYPE html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <style>
                body { margin: 0 10px; font-size: 16px; line-height: 1.25; font-family: sans-serif; }
                h2 { display: inline; font-weight: normal; font-size: 28px; line-height: 1.25; }
                #tab { display: none; height: 80px; }
                #below { height: 40px; }
              </style>
            </head>
            <body>
              <div id="fish-div">Your Shark has eaten <h2><text-style id="fish-amount">38.14SpDc</text-style></h2> <span id="fish-gain"></span> fish.</div>
              <div id="tab">Core</div>
              <div id="below">game</div>
            </body>
            </html>
        """.trimIndent()

        private const val FIND_SHIFT =
            "(function(){" +
                " var gain=document.getElementById('fish-gain');" +
                " var fish=document.getElementById('fish-div');" +
                " var below=document.getElementById('below');" +
                " var shortText='(+1.50 OoMs/s)';" +
                " function top(){ return below.getBoundingClientRect().top; }" +
                " function height(){ return fish.getBoundingClientRect().height; }" +
                " gain.textContent=shortText;" +
                " var shortTop=top();" +
                " var shortHeight=height();" +
                " var line=Math.max(1, gain.getBoundingClientRect().height);" +
                " var digits='';" +
                " var alphabet='1234567890';" +
                " var longText='';" +
                " for (var n=0; n<240; n++) {" +
                " digits+=alphabet.charAt(n%alphabet.length);" +
                " var candidate='(+'+digits+'/s)';" +
                " gain.textContent=candidate;" +
                " var gh=gain.getBoundingClientRect().height;" +
                " if (height()>shortHeight+1) {" +
                " if (gh<=line*1.6) longText=candidate;" +
                " break; } }" +
                " window.__hubMetricShort=shortText;" +
                " window.__hubMetricLong=longText;" +
                " gain.textContent=longText||shortText;" +
                " return JSON.stringify({found:longText.length>0, longText:longText," +
                " shortTop:shortTop, longTop:top(), shortHeight:shortHeight, longHeight:height()," +
                " iw:window.innerWidth||0, ih:window.innerHeight||0});" +
                "})()"

        private const val STABILITY =
            "(function(){" +
                " var gain=document.getElementById('fish-gain');" +
                " var fish=document.getElementById('fish-div');" +
                " var below=document.getElementById('below');" +
                " var tab=document.getElementById('tab');" +
                " var shortText=window.__hubMetricShort||'';" +
                " var longText=window.__hubMetricLong||'';" +
                " function top(){ return below.getBoundingClientRect().top; }" +
                " var min=1e9, max=-1e9;" +
                " for (var i=0; i<30; i++) {" +
                " gain.textContent=(i%2===0)?longText:shortText;" +
                " var t=top();" +
                " if (t<min) min=t;" +
                " if (t>max) max=t; }" +
                " gain.textContent=longText;" +
                " var gr=gain.getBoundingClientRect();" +
                " var fr=fish.getBoundingClientRect();" +
                " var cs=getComputedStyle(fish);" +
                " var beforeTab=top();" +
                " tab.style.display='block';" +
                " var afterTab=top();" +
                " var node=document.getElementById('hub-stable-metrics');" +
                " return JSON.stringify({delta:max-min, text:gain.textContent, expect:longText," +
                " overflowX:cs.overflowX, overflowY:cs.overflowY, maxHeight:cs.maxHeight," +
                " minHeight:cs.minHeight," +
                " inside:gr.width>0 && gr.top>=fr.top-1 && gr.bottom<=fr.bottom+1 &&" +
                " gr.left>=fr.left-1 && gr.right<=fr.right+1," +
                " beforeTab:beforeTab, afterTab:afterTab," +
                " styled:!!(node && node.textContent && node.textContent.indexOf('min-height')>=0)," +
                " iw:window.innerWidth||0});" +
                "})()"
    }
}
