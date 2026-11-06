package com.example.hub

import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.hub.games.OfflineSettlement
import com.example.hub.games.OfflineStart
import com.example.hub.games.SharkIncrementalAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Drives Shark's own simulateOffline, copied from upstream js/features/offline.js,
 * through the Hub adapter hook. The counters separate seconds the offline loop
 * applied from seconds a live tick applied. Wall time is not the game interval.
 */
@RunWith(AndroidJUnit4::class)
class SharkNativeOfflineTest {

    @Test
    fun eightHourGapIsAppliedOnceBySimulateOffline() {
        val html = InstrumentationRegistry.getInstrumentation().context.assets
            .open("shark-offline-runtime.html")
            .use { InputStreamReader(it).readText() }
        val adapter = SharkIncrementalAdapter()
        ActivityScenario.launch(WebViewProbeActivity::class.java).use { scenario ->
            val web = load(scenario, html)
            try {
                eval(web, "ageSeconds(8 * 60 * 60);")
                onWeb(web) { it.pauseTimers() }
                val started = onWeb(web) { adapter.startNativeOffline(it) }
                assertEquals(OfflineStart.STARTED, started)
                val wallStart = System.currentTimeMillis()
                onWeb(web) { it.resumeTimers() }
                val settlement = onWeb(web) { adapter.awaitOfflineSettlement(it, 20_000) }
                val wall = System.currentTimeMillis() - wallStart
                assertEquals(OfflineSettlement.COMPLETE, settlement)
                val done = JSONObject(eval(web, "counters()"))
                val offline = done.getDouble("offline")
                val live = done.getDouble("live")
                assertTrue("offline seconds $offline", offline in 28_000.0..30_000.0)
                assertTrue("live seconds consumed the gap: $live", live < 5.0)
                assertTrue("wall clock stood in for the game interval: ${wall}ms", wall < 15_000)

                assertEquals(OfflineStart.BUSY, onWeb(web) { adapter.startNativeOffline(it) })
                eval(web, "offlineDone();")
                val again = onWeb(web) { adapter.startNativeOffline(it) }
                assertEquals(OfflineStart.SHORT, again)
                val twice = JSONObject(eval(web, "counters()"))
                assertEquals(offline, twice.getDouble("offline"), 1.0)
            } finally {
                scenario.onActivity {
                    (web.parent as? ViewGroup)?.removeView(web)
                    web.destroy()
                }
            }
        }
    }

    @Test
    fun aLiveTickConsumesTheGapAndOfflineDoesNotRun() {
        val html = InstrumentationRegistry.getInstrumentation().context.assets
            .open("shark-offline-runtime.html")
            .use { InputStreamReader(it).readText() }
        val adapter = SharkIncrementalAdapter()
        ActivityScenario.launch(WebViewProbeActivity::class.java).use { scenario ->
            val web = load(scenario, html)
            try {
                eval(web, "ageSeconds(8 * 60 * 60); loop();")
                val afterLoop = JSONObject(eval(web, "counters()"))
                assertTrue(afterLoop.getDouble("live") > 28_000.0)
                assertEquals(0.0, afterLoop.getDouble("offline"), 0.0)
                val started = onWeb(web) { adapter.startNativeOffline(it) }
                assertEquals(OfflineStart.SHORT, started)
            } finally {
                scenario.onActivity {
                    (web.parent as? ViewGroup)?.removeView(web)
                    web.destroy()
                }
            }
        }
    }

    @Test
    fun disabledOfflineAndAnInterruptedRunStayDistinct() {
        val html = InstrumentationRegistry.getInstrumentation().context.assets
            .open("shark-offline-runtime.html")
            .use { InputStreamReader(it).readText() }
        val adapter = SharkIncrementalAdapter()
        ActivityScenario.launch(WebViewProbeActivity::class.java).use { scenario ->
            val web = load(scenario, html)
            try {
                eval(web, "ageSeconds(8 * 60 * 60); player.radios.offline = 0;")
                assertEquals(OfflineStart.DISABLED, onWeb(web) { adapter.startNativeOffline(it) })
                assertEquals(0.0, JSONObject(eval(web, "counters()")).getDouble("offline"), 0.0)

                eval(web, "player.radios.offline = 1; ageSeconds(8 * 60 * 60);")
                assertEquals(OfflineStart.STARTED, onWeb(web) { adapter.startNativeOffline(it) })
                val interrupted = onWeb(web) { adapter.awaitOfflineSettlement(it, 250) }
                assertEquals(OfflineSettlement.INTERRUPTED, interrupted)
                val mid = JSONObject(eval(web, "counters()"))
                assertTrue(mid.getBoolean("nosave") || mid.getDouble("offline") < 28_000.0)
                val finished = onWeb(web) { adapter.awaitOfflineSettlement(it, 20_000) }
                assertEquals(OfflineSettlement.COMPLETE, finished)
                val done = JSONObject(eval(web, "counters()"))
                assertTrue(done.getDouble("offline") in 28_000.0..30_000.0)
                assertEquals(OfflineStart.BUSY, onWeb(web) { adapter.startNativeOffline(it) })
                eval(web, "offlineDone();")
                assertEquals(OfflineStart.SHORT, onWeb(web) { adapter.startNativeOffline(it) })
                assertEquals(done.getDouble("offline"), JSONObject(eval(web, "counters()")).getDouble("offline"), 1.0)
            } finally {
                scenario.onActivity {
                    (web.parent as? ViewGroup)?.removeView(web)
                    web.destroy()
                }
            }
        }
    }

    private fun load(scenario: ActivityScenario<WebViewProbeActivity>, html: String): WebView {
        val ref = AtomicReference<WebView>()
        val latch = CountDownLatch(1)
        scenario.onActivity { activity ->
            val web = WebView(activity)
            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            activity.findViewById<ViewGroup>(android.R.id.content).addView(
                web,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    latch.countDown()
                }
            }
            web.loadDataWithBaseURL("https://mrredshark77.github.io/", html, "text/html", "utf-8", null)
            ref.set(web)
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        return ref.get()
    }

    private fun <T> onWeb(web: WebView, block: suspend (WebView) -> T): T {
        return runBlocking {
            withContext(Dispatchers.Main) { block(web) }
        }
    }

    private fun eval(web: WebView, script: String): String {
        val value = AtomicReference("")
        val latch = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            web.evaluateJavascript(script) { raw ->
                value.set(raw?.trim('"')?.replace("\\\"", "\"") ?: "")
                latch.countDown()
            }
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        return value.get()
    }
}
