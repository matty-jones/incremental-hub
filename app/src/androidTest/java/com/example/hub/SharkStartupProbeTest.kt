package com.example.hub

import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.hub.games.SharkIncrementalAdapter
import com.example.hub.session.DocumentGate
import com.example.hub.session.DocumentPhase
import com.example.hub.session.StorageBootstrap
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Captures Shark UI visibility after the hub peek→game load sequence.
 */
@RunWith(AndroidJUnit4::class)
class SharkStartupProbeTest {

    @Test
    fun peekThenGameReportsVisibleSurface() {
        val launch = "https://mrredshark77.github.io/shark-incremental/"
        val gate = DocumentGate().apply {
            phase = DocumentPhase.PEEK
            storageKey = "shark_inc_save"
            launchUrl = launch
        }
        val finishes = AtomicInteger(0)
        val lastUrl = AtomicReference("")
        val webRef = AtomicReference<WebView>()
        ActivityScenario.launch(WebViewProbeActivity::class.java).use { scenario ->
            val peekDone = CountDownLatch(1)
            scenario.onActivity { activity ->
                val web = WebView(activity)
                web.settings.javaScriptEnabled = true
                web.settings.domStorageEnabled = true
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
                        val html = StorageBootstrap.document(
                            gate.phase,
                            gate.storageKey,
                            gate.payload,
                        )
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
                        finishes.incrementAndGet()
                        lastUrl.set(url.orEmpty())
                        if (gate.phase == DocumentPhase.PEEK) peekDone.countDown()
                    }
                }
                activity.findViewById<ViewGroup>(android.R.id.content).addView(
                    web,
                    ViewGroup.LayoutParams(400, 800),
                )
                webRef.set(web)
                web.loadUrl(launch)
            }
            assertTrue("peek finish", peekDone.await(30, TimeUnit.SECONDS))
            val peekProbe = eval(webRef.get(), PROBE)
            gate.phase = DocumentPhase.GAME
            val gameDone = CountDownLatch(1)
            scenario.onActivity {
                webRef.get().webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        finishes.incrementAndGet()
                        lastUrl.set(url.orEmpty())
                        gameDone.countDown()
                    }
                }
                webRef.get().loadUrl(launch)
            }
            assertTrue("game finish", gameDone.await(60, TimeUnit.SECONDS))
            val deadline = System.currentTimeMillis() + 45_000
            var ready = "false"
            var probe = ""
            while (System.currentTimeMillis() < deadline) {
                ready = eval(webRef.get(), SharkIncrementalAdapter.JS_UI_READY)
                probe = eval(webRef.get(), PROBE)
                if (ready == "true") break
                Thread.sleep(500)
            }
            val fish = eval(
                webRef.get(),
                "(function(){ var n=document.getElementById('fish-div');" +
                    " if(!n) return 'missing';" +
                    " var r=n.getBoundingClientRect();" +
                    " return JSON.stringify({display:getComputedStyle(n).display," +
                    " w:r.width,h:r.height,top:r.top,text:(n.innerText||'').slice(0,80)});" +
                    " })()",
            )
            assertTrue(
                "Shark UI not visible after peek→game. ready=$ready peekProbe=$peekProbe " +
                    "probe=$probe fish=$fish finishes=${finishes.get()} url=${lastUrl.get()}",
                ready == "true",
            )
            assertTrue(
                "fish-div should have non-zero height: $fish",
                fish.contains("\"h\":") && !fish.contains("\"h\":0"),
            )
            scenario.onActivity { webRef.get().destroy() }
        }
    }

    @Test
    fun sharkLayoutGivesOfflineOverlayAViewportHeight() {
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
                activity.findViewById<ViewGroup>(android.R.id.content).addView(
                    web,
                    ViewGroup.LayoutParams(400, 800),
                )
                // Match Shark: percentage-height absolute overlay, no html/body height.
                web.loadDataWithBaseURL(
                    "https://hub.test/",
                    """
                    <!DOCTYPE html><html><body style="background:lightseagreen;margin:0">
                    <div id="app" style="display:none"></div>
                    <div id="offline" style="display:none;position:absolute;top:0;left:0;width:100%;height:100%;background:#181818;color:#fff">offline</div>
                    <script>var player = { VERSION: 3 };</script>
                    </body></html>
                    """.trimIndent(),
                    "text/html",
                    "utf-8",
                    null,
                )
                webRef.set(web)
            }
            assertTrue(started.await(15, TimeUnit.SECONDS))
            eval(
                webRef.get(),
                "(function(){ var o=document.getElementById('offline');" +
                    " o.style.display=''; o.style.height='0px'; return true; })()",
            )
            assertTrue(
                "zero-height offline must not be ready",
                eval(webRef.get(), SharkIncrementalAdapter.JS_UI_READY) == "false",
            )
            eval(
                webRef.get(),
                "(function(){ document.getElementById('offline').style.height='100%'; return true; })()",
            )
            eval(webRef.get(), com.example.hub.games.SharkLayout.injectionScript())
            eval(webRef.get(), "(function(){ document.body.offsetHeight; return true; })()")
            val ready = eval(webRef.get(), SharkIncrementalAdapter.JS_UI_READY)
            val offline = eval(
                webRef.get(),
                "(function(){ var o=document.getElementById('offline');" +
                    " var r=o.getBoundingClientRect();" +
                    " return JSON.stringify({h:r.height,w:r.width,display:getComputedStyle(o).display," +
                    " minH:getComputedStyle(o).minHeight}); })()",
            )
            assertTrue(
                "SharkLayout 100vh should make offline ready: ready=$ready offline=$offline",
                ready == "true",
            )
            scenario.onActivity { webRef.get().destroy() }
        }
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
        assertTrue(latch.await(15, TimeUnit.SECONDS))
        return value.get()
    }

    companion object {
        private const val PROBE =
            "(function(){ try {" +
                " var app=document.getElementById('app');" +
                " var offline=document.getElementById('offline');" +
                " return JSON.stringify({" +
                " bootstrap: window.__hubStorageBootstrap||''," +
                " player: typeof player!=='undefined'&&player!==null," +
                " appInline: app?String(app.style.display):null," +
                " appComputed: app?getComputedStyle(app).display:null," +
                " appRect: app?{w:app.getBoundingClientRect().width,h:app.getBoundingClientRect().height}:null," +
                " offlineInline: offline?String(offline.style.display):null," +
                " offlineComputed: offline?getComputedStyle(offline).display:null," +
                " readyState: document.readyState," +
                " bodyBg: getComputedStyle(document.body).backgroundColor" +
                " }); } catch(e){ return JSON.stringify({error:String(e)}); } })()"
    }
}
