package com.example.hub

import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.hub.games.SharkIncrementalAdapter
import com.example.hub.games.SharkLayout
import com.example.hub.session.DocumentGate
import com.example.hub.session.DocumentPhase
import com.example.hub.session.StorageBootstrap
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Isolation matrix for Shark blank-screen failures.
 * Uses disposable in-memory WebViews only — never touches the user's hub profile.
 */
@RunWith(AndroidJUnit4::class)
class SharkBlankScreenMatrixTest {

    @Test
    fun matrixReportsSuccessCountsForDirectBootstrapAndLayoutVariants() {
        val rounds = 5
        val a = runScenario(rounds) { DirectLoad(injectLayout = true) }
        val b = runScenario(rounds) { BootstrapThenGame(injectLayout = true) }
        val c = runScenario(rounds) { BootstrapThenGame(injectLayout = false) }
        val dCold = runScenario(rounds) { DirectLoad(injectLayout = true) }
        val dWarm = runScenario(rounds) { WarmReload(injectLayout = true) }

        val summary =
            "A_direct=${a.ok}/$rounds B_bootstrap=${b.ok}/$rounds " +
                "C_bootstrapNoCss=${c.ok}/$rounds D_cold=${dCold.ok}/$rounds D_warm=${dWarm.ok}/$rounds " +
                "failures A=${a.failures} B=${b.failures} C=${c.failures} Dcold=${dCold.failures} Dwarm=${dWarm.failures}"
        assertTrue(
            summary,
            a.ok == rounds && b.ok == rounds && c.ok == rounds && dCold.ok == rounds && dWarm.ok == rounds,
        )
    }

    private fun runScenario(rounds: Int, factory: () -> Scenario): Result {
        var ok = 0
        val failures = mutableListOf<String>()
        repeat(rounds) { i ->
            val outcome = factory().runOnce()
            if (outcome == null) {
                ok += 1
            } else {
                failures.add("round=$i $outcome")
            }
        }
        return Result(ok, failures)
    }

    private data class Result(val ok: Int, val failures: List<String>)

    private interface Scenario {
        fun runOnce(): String?
    }

    private class DirectLoad(private val injectLayout: Boolean) : Scenario {
        override fun runOnce(): String? = withWebView { scenario, web ->
            val finished = CountDownLatch(1)
            scenario.onActivity {
                web.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        finished.countDown()
                    }
                }
                web.loadUrl(LAUNCH)
            }
            if (!finished.await(60, TimeUnit.SECONDS)) return@withWebView "page-timeout"
            if (injectLayout) eval(web, SharkLayout.injectionScript())
            awaitReady(web)
        }
    }

    private class BootstrapThenGame(private val injectLayout: Boolean) : Scenario {
        override fun runOnce(): String? = withWebView { scenario, web ->
            val gate = DocumentGate().apply {
                phase = DocumentPhase.PEEK
                storageKey = "shark_inc_save"
                launchUrl = LAUNCH
            }
            val peekDone = CountDownLatch(1)
            scenario.onActivity {
                web.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? {
                        if (!StorageBootstrap.shouldServe(
                                gate.phase,
                                request.url.toString(),
                                gate.launchUrl,
                                request.isForMainFrame,
                            )
                        ) {
                            return null
                        }
                        val html = StorageBootstrap.document(gate.phase, gate.storageKey, null)
                        return WebResourceResponse(
                            "text/html",
                            "utf-8",
                            200,
                            "OK",
                            mapOf("Cache-Control" to "no-store"),
                            html.byteInputStream(StandardCharsets.UTF_8),
                        )
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (gate.phase == DocumentPhase.PEEK) peekDone.countDown()
                    }
                }
                web.loadUrl(LAUNCH)
            }
            if (!peekDone.await(30, TimeUnit.SECONDS)) return@withWebView "peek-timeout"
            gate.phase = DocumentPhase.GAME
            val gameDone = CountDownLatch(1)
            scenario.onActivity {
                web.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        gameDone.countDown()
                    }
                }
                web.loadUrl(LAUNCH)
            }
            if (!gameDone.await(60, TimeUnit.SECONDS)) return@withWebView "game-timeout"
            if (injectLayout) eval(web, SharkLayout.injectionScript())
            awaitReady(web)
        }
    }

    private class WarmReload(private val injectLayout: Boolean) : Scenario {
        override fun runOnce(): String? = withWebView { scenario, web ->
            val first = CountDownLatch(1)
            scenario.onActivity {
                web.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        first.countDown()
                    }
                }
                web.loadUrl(LAUNCH)
            }
            if (!first.await(60, TimeUnit.SECONDS)) return@withWebView "first-timeout"
            if (injectLayout) eval(web, SharkLayout.injectionScript())
            val miss = awaitReady(web)
            if (miss != null) return@withWebView miss
            val second = CountDownLatch(1)
            scenario.onActivity {
                web.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        second.countDown()
                    }
                }
                web.reload()
            }
            if (!second.await(60, TimeUnit.SECONDS)) return@withWebView "reload-timeout"
            if (injectLayout) eval(web, SharkLayout.injectionScript())
            awaitReady(web)
        }
    }

    companion object {
        private const val LAUNCH = "https://mrredshark77.github.io/shark-incremental/"

        private fun withWebView(
            block: (ActivityScenario<WebViewProbeActivity>, WebView) -> String?,
        ): String? {
            var result: String? = "scenario-not-run"
            ActivityScenario.launch(WebViewProbeActivity::class.java).use { scenario ->
                val webRef = AtomicReference<WebView>()
                scenario.onActivity { activity ->
                    val web = WebView(activity)
                    web.settings.javaScriptEnabled = true
                    web.settings.domStorageEnabled = true
                    web.webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = true
                    }
                    activity.findViewById<ViewGroup>(android.R.id.content).addView(
                        web,
                        ViewGroup.LayoutParams(400, 800),
                    )
                    webRef.set(web)
                }
                result = block(scenario, webRef.get())
                scenario.onActivity { webRef.get().destroy() }
            }
            return result
        }

        private fun awaitReady(web: WebView): String? {
            val deadline = System.currentTimeMillis() + 45_000
            while (System.currentTimeMillis() < deadline) {
                if (eval(web, SharkIncrementalAdapter.JS_UI_READY) == "true") return null
                Thread.sleep(400)
            }
            val probe = eval(
                web,
                "(function(){ var app=document.getElementById('app'); var offline=document.getElementById('offline');" +
                    " return JSON.stringify({bootstrap:window.__hubStorageBootstrap||''," +
                    " playerVersion:(typeof player!=='undefined'&&player)?player.VERSION:null," +
                    " app:app?{d:app.style.display,h:app.getBoundingClientRect().height}:null," +
                    " offline:offline?{d:offline.style.display,h:offline.getBoundingClientRect().height}:null," +
                    " fish:(document.getElementById('fish-amount')||{}).textContent||null}); })()",
            )
            return "not-ready probe=$probe"
        }

        private fun eval(webView: WebView, script: String): String {
            val value = AtomicReference("")
            val latch = CountDownLatch(1)
            webView.post {
                webView.evaluateJavascript(script) { result ->
                    value.set(result?.trim('"')?.replace("\\\"", "\"") ?: "")
                    latch.countDown()
                }
            }
            assertTrue(latch.await(20, TimeUnit.SECONDS))
            return value.get()
        }
    }
}
