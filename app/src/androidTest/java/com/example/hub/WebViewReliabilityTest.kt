package com.example.hub

import android.content.MutableContextWrapper
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.hub.games.SharkIncrementalAdapter
import com.example.hub.session.detachForRetain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Real WebView coverage for the continuity and storage repairs.
 * These tests do not treat a mocked WebView as a game.
 */
@RunWith(AndroidJUnit4::class)
class WebViewReliabilityTest {

    @Test
    fun configurationChangeKeepsJavascriptAndDoesNotReload() {
        val loads = intArrayOf(0)
        val webRef = AtomicReference<WebView>()
        ActivityScenario.launch(WebViewProbeActivity::class.java).use { scenario ->
            val started = CountDownLatch(1)
            scenario.onActivity { activity ->
                val web = WebView(activity)
                web.settings.javaScriptEnabled = true
                web.settings.domStorageEnabled = true
                web.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        loads[0] += 1
                        started.countDown()
                    }
                }
                activity.findViewById<ViewGroup>(android.R.id.content).addView(
                    web,
                    ViewGroup.LayoutParams(320, 480),
                )
                web.loadDataWithBaseURL(ORIGIN, FIXTURE, "text/html", "utf-8", null)
                webRef.set(web)
            }
            assertTrue(started.await(15, TimeUnit.SECONDS))
            val before = eval(webRef.get())
            assertEquals("41", before)
            val seenLoads = loads[0]
            scenario.onActivity { activity ->
                activity.onConfigurationChanged(activity.resources.configuration)
            }
            val after = eval(webRef.get())
            assertEquals("41", after)
            assertEquals(seenLoads, loads[0])
        }
    }

    @Test
    fun retainedWebViewSurvivesActivityRecreation() {
        val webRef = AtomicReference<WebView>()
        ActivityScenario.launch(WebViewProbeActivity::class.java).use { scenario ->
            val started = CountDownLatch(1)
            scenario.onActivity { activity ->
                val web = WebView(MutableContextWrapper(activity))
                web.settings.javaScriptEnabled = true
                web.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        started.countDown()
                    }
                }
                activity.findViewById<ViewGroup>(android.R.id.content).addView(
                    web,
                    ViewGroup.LayoutParams(320, 480),
                )
                web.loadDataWithBaseURL(ORIGIN, FIXTURE, "text/html", "utf-8", null)
                webRef.set(web)
            }
            assertTrue(started.await(15, TimeUnit.SECONDS))
            scenario.onActivity {
                detachForRetain(webRef.get())
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                (webRef.get().context as MutableContextWrapper).baseContext = activity
                activity.findViewById<ViewGroup>(android.R.id.content).addView(
                    webRef.get(),
                    ViewGroup.LayoutParams(320, 480),
                )
            }
            assertEquals("41", eval(webRef.get()))
        }
    }

    @Test
    fun storageSurvivesWebViewDestructionButJavascriptDoesNot() {
        ActivityScenario.launch(WebViewProbeActivity::class.java).use { scenario ->
            val first = AtomicReference<WebView>()
            val started = CountDownLatch(1)
            scenario.onActivity { activity ->
                val web = WebView(activity)
                web.settings.javaScriptEnabled = true
                web.settings.domStorageEnabled = true
                web.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        started.countDown()
                    }
                }
                attach(activity, web)
                web.loadDataWithBaseURL(ORIGIN, FIXTURE, "text/html", "utf-8", null)
                first.set(web)
            }
            assertTrue(started.await(15, TimeUnit.SECONDS))
            assertEquals("kept", evalStored(first.get()))
            scenario.onActivity { first.get().destroy() }
            val second = AtomicReference<WebView>()
            val restarted = CountDownLatch(1)
            scenario.onActivity { activity ->
                val web = WebView(activity)
                web.settings.javaScriptEnabled = true
                web.settings.domStorageEnabled = true
                web.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        restarted.countDown()
                    }
                }
                attach(activity, web)
                web.loadDataWithBaseURL(ORIGIN, READ_ONLY, "text/html", "utf-8", null)
                second.set(web)
            }
            assertTrue(restarted.await(15, TimeUnit.SECONDS))
            assertEquals("kept", evalStored(second.get()))
            assertEquals("0", eval(second.get()))
            scenario.onActivity { second.get().destroy() }
        }
    }

    @Test
    fun hiddenSharkSurfaceIsNotReadyAndAVisibleOneIs() {
        ActivityScenario.launch(WebViewProbeActivity::class.java).use { scenario ->
            val webRef = AtomicReference<WebView>()
            val started = CountDownLatch(1)
            scenario.onActivity { activity ->
                val web = WebView(activity)
                web.settings.javaScriptEnabled = true
                web.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        started.countDown()
                    }
                }
                attach(activity, web)
                web.loadDataWithBaseURL(ORIGIN, HIDDEN_SHARK, "text/html", "utf-8", null)
                webRef.set(web)
            }
            assertTrue(started.await(15, TimeUnit.SECONDS))
            assertEquals("false", evalJs(webRef.get(), SharkIncrementalAdapter.JS_UI_READY))
            evalJs(
                webRef.get(),
                "(function(){ document.getElementById('app').style.display = ''; return true; })()",
            )
            assertEquals("true", evalJs(webRef.get(), SharkIncrementalAdapter.JS_UI_READY))
            scenario.onActivity { webRef.get().destroy() }
        }
    }

    @Test
    fun missingFaviconDoesNotChangeStoredProgress() {
        ActivityScenario.launch(WebViewProbeActivity::class.java).use { scenario ->
            val webRef = AtomicReference<WebView>()
            val started = CountDownLatch(1)
            scenario.onActivity { activity ->
                val web = WebView(activity)
                web.settings.javaScriptEnabled = true
                web.settings.domStorageEnabled = true
                web.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        started.countDown()
                    }
                }
                attach(activity, web)
                web.loadDataWithBaseURL(ORIGIN, FIXTURE, "text/html", "utf-8", null)
                webRef.set(web)
            }
            assertTrue(started.await(15, TimeUnit.SECONDS))
            assertEquals("41", eval(webRef.get()))
            assertEquals("kept", evalStored(webRef.get()))
            scenario.onActivity { webRef.get().destroy() }
        }
    }

    private fun attach(activity: android.app.Activity, web: WebView) {
        activity.findViewById<ViewGroup>(android.R.id.content).addView(
            web,
            ViewGroup.LayoutParams(320, 480),
        )
    }

    private fun eval(webView: WebView): String = evalJs(webView, "(function(){ return String(window.__tick || 0); })()")

    private fun evalStored(webView: WebView): String =
        evalJs(webView, "(function(){ return localStorage.getItem('hub_probe') || ''; })()")

    private fun evalJs(webView: WebView, script: String): String {
        val value = AtomicReference<String>()
        val latch = CountDownLatch(1)
        webView.post {
            webView.evaluateJavascript(script) { result ->
                value.set(result?.trim('"') ?: "")
                latch.countDown()
            }
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        return value.get().orEmpty()
    }

    companion object {
        private const val ORIGIN = "https://hub.test/"
        private val FIXTURE = """
            <!DOCTYPE html>
            <html><head><meta name="viewport" content="width=device-width, initial-scale=1"><link rel="icon" href="favicon.ico"></head>
            <body style="background:lightseagreen">
            <div id="app" style="display:none">ready</div>
            <script>
              window.__tick = 41;
              localStorage.setItem('hub_probe', 'kept');
              document.getElementById('app').style.display = '';
            </script>
            </body></html>
        """.trimIndent()
        private val READ_ONLY = """
            <!DOCTYPE html><html><body><script>window.__tick = 0;</script></body></html>
        """.trimIndent()
        private val HIDDEN_SHARK = """
            <!DOCTYPE html><html><body>
            <div id="app" style="display:none"></div>
            <div id="offline" style="display:none"></div>
            <script>var player = { VERSION: 3 };</script>
            </body></html>
        """.trimIndent()
    }
}
