package com.example.hub

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Disposable reads for the two Milestone 0 page facts that are still unverified:
 * Ethereal Farm's live title, and Antimatter Dimensions' browser warning.
 */
@RunWith(AndroidJUnit4::class)
class UpstreamPageProbeTest {

    @Test
    fun recordFarmTitleAndAdBrowserGate() {
        val report = StringBuilder()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            runCatching { ProfileStore.getInstance().deleteProfile(PROFILE) }
            ProfileStore.getInstance().getOrCreateProfile(PROFILE)
        }
        ActivityScenario.launch(WebViewProbeActivity::class.java).use { scenario ->
            val web = addWeb(scenario)
            load(web, FARM)
            val farm = eval(
                web,
                "(function(){ var kind = typeof saveNow; var thrown = '';" +
                    " try { if (kind === 'function') saveNow(); } catch (e) { thrown = String(e); }" +
                    " return JSON.stringify({title: document.title, prefix: window.localstorageNameHTML || '', saveNow: kind, thrown: thrown}); })()",
            )
            Thread.sleep(2_000)
            val farmKeys = eval(web, "JSON.stringify(Object.keys(localStorage))")
            report.append("farm=").append(farm).append('\n')
            report.append("farmKeys=").append(farmKeys).append('\n')
            load(web, AD)
            val ad = eval(
                web,
                "(function(){ var node = document.getElementById('browser-warning');" +
                    " var display = node ? getComputedStyle(node).display : 'missing';" +
                    " return JSON.stringify({ua: navigator.userAgent, warning: display, title: document.title}); })()",
            )
            report.append("ad=").append(ad).append('\n')
            scenario.onActivity {
                (web.parent as? android.view.ViewGroup)?.removeView(web)
                web.loadUrl("about:blank")
                web.destroy()
            }
        }
        val text = report.toString()
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
        File(dir, "upstream-page-probe.txt").writeText(text)
        android.util.Log.i("UPSTREAM_PROBE", text)
        assertTrue(text.contains("farm="))
        assertTrue(text.contains("ad="))
        assertFalseBlank(text)
        deleteDisposable(PROFILE)
    }

    private fun assertFalseBlank(text: String) {
        assertTrue(text, text.lineSequence().filter { it.contains('=') }.all { line ->
            line.substringAfter('=').length > 2
        })
    }

    private fun addWeb(scenario: ActivityScenario<WebViewProbeActivity>): WebView {
        val ref = AtomicReference<WebView>()
        scenario.onActivity { activity ->
            val web = WebView(activity)
            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            WebViewCompat.setProfile(web, PROFILE)
            activity.findViewById<android.view.ViewGroup>(android.R.id.content).addView(
                web,
                android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            ref.set(web)
        }
        return ref.get()
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
        assertTrue("load $url", latch.await(45, TimeUnit.SECONDS))
    }

    private fun eval(web: WebView, script: String): String {
        val latch = CountDownLatch(1)
        val ref = AtomicReference("")
        web.post {
            web.evaluateJavascript(script) { value ->
                ref.set(value ?: "")
                latch.countDown()
            }
        }
        assertTrue(latch.await(15, TimeUnit.SECONDS))
        return ref.get()
    }

    private fun deleteDisposable(name: String) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            runCatching { ProfileStore.getInstance().deleteProfile(name) }
                .onFailure { error ->
                    android.util.Log.i(
                        "UPSTREAM_PROBE",
                        "profile $name stays until the next process; ${error.message}",
                    )
                }
        }
    }

    companion object {
        private const val PROFILE = "hub-qual-upstream-probe"
        private const val FARM = "https://lodev.org/etherealfarm/"
        private const val AD = "https://ivark.github.io/AntimatterDimensions/"
    }
}
