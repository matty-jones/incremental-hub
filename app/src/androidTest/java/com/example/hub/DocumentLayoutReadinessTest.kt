package com.example.hub

import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import com.example.hub.games.DocumentLayout
import com.example.hub.games.SharkIncrementalAdapter
import com.example.hub.ui.installSharkDocumentStart
import com.example.hub.ui.prepareSharkViewport
import com.example.hub.ui.sharkDocumentStartScript
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Layout injection must survive a missing body, then a real Shark document
 * must become ready. The personal Shark profile is not opened or deleted.
 */
@RunWith(AndroidJUnit4::class)
class DocumentLayoutReadinessTest {

    @After
    fun deleteDisposableProfile() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            runCatching { ProfileStore.getInstance().deleteProfile(PROFILE) }
        }
    }

    @Test
    fun missingBodyDoesNotInstallAndALaterBodyFitsOnce() {
        val console = Collections.synchronizedList(mutableListOf<String>())
        val inject = DocumentLayout.injectionScript(411)
        ActivityScenario.launch(WebViewProbeActivity::class.java).use { scenario ->
            val web = addPage(scenario, console, 411, 800)
            val early = json(
                eval(
                    web,
                    "(function(){" +
                        " var body = document.body;" +
                        " if (body && body.parentNode) body.parentNode.removeChild(body);" +
                        " var result = $inject;" +
                        " return JSON.stringify({missing:!document.body, result:result," +
                        " fit:typeof window.__hubLayoutFit, installs:window.__hubLayoutInstalls||0});" +
                        "})()",
                ),
            )
            assertTrue("body was recreated before the script ran: $early", early.getBoolean("missing"))
            assertEquals("no-body", early.getString("result"))
            assertEquals("undefined", early.getString("fit"))
            assertEquals(0, early.getInt("installs"))
            assertEquals(null, observeFault(console))

            eval(
                web,
                "(function(){" +
                    " var body = document.createElement('body'); body.style.margin='0';" +
                    " var app = document.createElement('div'); app.id='app';" +
                    " var tree = document.createElement('div'); tree.id='evolution-tree';" +
                    " tree.style.cssText='width:1600px;height:80px;display:block;';" +
                    " var child = document.createElement('div');" +
                    " child.style.cssText='width:1600px;height:40px;'; child.textContent='node';" +
                    " tree.appendChild(child); app.appendChild(tree); body.appendChild(app);" +
                    " document.documentElement.appendChild(body);" +
                    " return 'appended'; })()",
            )
            val fitted = json(
                eval(
                    web,
                    "(function(){" +
                        " var before = window.__hubLayoutInstalls || 0;" +
                        " var first = $inject;" +
                        " var second = $inject;" +
                        " var el = document.getElementById('evolution-tree');" +
                        " if (document.body) document.body.offsetHeight;" +
                        " if (window.__hubLayoutFit) window.__hubLayoutFit();" +
                        " return JSON.stringify({before:before, first:first, second:second," +
                        " installs:window.__hubLayoutInstalls||0, fit:typeof window.__hubLayoutFit," +
                        " zoom:el && el.style ? (el.style.zoom || '') : ''});" +
                        "})()",
                ),
            )
            assertEquals(DocumentLayout.MARKER, fitted.getString("first"))
            assertEquals(DocumentLayout.MARKER, fitted.getString("second"))
            assertEquals(1, fitted.getInt("installs"))
            assertTrue("install count changed around the body insert: $fitted", fitted.getInt("before") <= 1)
            assertEquals("function", fitted.getString("fit"))
            val zoom = fitted.getString("zoom").toFloatOrNull()
            assertTrue("evolution tree was not scaled down: $fitted", zoom != null && zoom < 1f)
            assertEquals(null, observeFault(console))
            scenario.onActivity { web.destroy() }
        }
    }

    @Test
    fun sharkColdLaunchesReachReadyAtBothWidths() {
        coldLaunch(411, 800)
        coldLaunch(840, 700)
    }

    private fun coldLaunch(width: Int, height: Int) {
        val console = Collections.synchronizedList(mutableListOf<String>())
        val starts = AtomicInteger(0)
        ActivityScenario.launch(WebViewProbeActivity::class.java).use { scenario ->
            val finished = CountDownLatch(1)
            val webRef = AtomicReference<WebView>()
            scenario.onActivity { activity ->
                val web = WebView(activity)
                web.settings.javaScriptEnabled = true
                web.settings.domStorageEnabled = true
                prepareSharkViewport(web)
                installSharkDocumentStart(web, SHARK)
                val profile = ProfileStore.getInstance().getOrCreateProfile(PROFILE)
                WebViewCompat.setProfile(web, profile.name)
                web.webChromeClient = consoleClient(console)
                web.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                        if (!url.contains("shark-incremental")) return
                        starts.incrementAndGet()
                        view.evaluateJavascript(sharkDocumentStartScript(), null)
                        view.evaluateJavascript(DocumentLayout.injectionScript(width), null)
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        if (!url.contains("shark-incremental")) return
                        view.evaluateJavascript(DocumentLayout.injectionScript(width), null)
                        finished.countDown()
                    }
                }
                activity.findViewById<ViewGroup>(android.R.id.content).addView(
                    web,
                    ViewGroup.LayoutParams(width, height),
                )
                web.loadUrl(SHARK)
                webRef.set(web)
            }
            assertTrue(
                "Shark page did not finish at ${width}x$height",
                finished.await(60, TimeUnit.SECONDS),
            )
            val web = webRef.get()
            val deadline = System.currentTimeMillis() + 90_000
            var ready = "false"
            var installed = "no"
            while (System.currentTimeMillis() < deadline) {
                ready = eval(web, SharkIncrementalAdapter.JS_UI_READY)
                installed = eval(web, "(function(){ return window.__hubLayoutInstalled ? 'yes' : 'no'; })()")
                if (ready == "true" && installed == "yes") break
                Thread.sleep(400)
            }
            val fault = observeFault(console)
            assertTrue(
                "Shark was not ready at ${width}x$height ready=$ready installed=$installed " +
                    "starts=${starts.get()} fault=$fault\n${console.takeLast(12)}",
                ready == "true" && installed == "yes" && fault == null && starts.get() >= 1,
            )
            scenario.onActivity { web.destroy() }
        }
    }

    private fun addPage(
        scenario: ActivityScenario<WebViewProbeActivity>,
        console: MutableList<String>,
        width: Int,
        height: Int,
    ): WebView {
        val ready = CountDownLatch(1)
        val ref = AtomicReference<WebView>()
        scenario.onActivity { activity ->
            val web = WebView(activity)
            web.settings.javaScriptEnabled = true
            web.webChromeClient = consoleClient(console)
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    ready.countDown()
                }
            }
            activity.findViewById<ViewGroup>(android.R.id.content).addView(
                web,
                ViewGroup.LayoutParams(width, height),
            )
            web.loadDataWithBaseURL(
                "https://hub.test/",
                "<!DOCTYPE html><html><head></head><body><p>ready</p></body></html>",
                "text/html",
                "utf-8",
                null,
            )
            ref.set(web)
        }
        assertTrue(ready.await(15, TimeUnit.SECONDS))
        return ref.get()
    }

    private fun consoleClient(console: MutableList<String>): WebChromeClient =
        object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                val text = message?.message() ?: return true
                console.add("${message.messageLevel()}: $text")
                return true
            }
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

    private fun observeFault(console: List<String>): String? =
        console.firstOrNull {
            it.contains("not of type 'Node'") || it.contains("Failed to execute 'observe'")
        }

    companion object {
        private const val PROFILE = "hub-qual-layout-ready"
        private const val SHARK = "https://mrredshark77.github.io/shark-incremental/"
    }
}
