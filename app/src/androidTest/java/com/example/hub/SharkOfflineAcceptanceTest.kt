package com.example.hub

import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.hub.games.SharkIncrementalAdapter
import com.example.hub.games.SharkLayout
import com.example.hub.games.SharkProbe
import com.example.hub.ui.pageZoomScript
import org.json.JSONArray
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Repeated Shark launches on the headless Pixel Fold emulator.
 * A launch counts only when the main game or a usable offline control is on screen.
 */
@RunWith(AndroidJUnit4::class)
class SharkOfflineAcceptanceTest {

    @Test
    fun repeatedLaunchesAndFoldPostures() {
        val results = mutableListOf<String>()
        var coldOk = 0
        var warmOk = 0
        var agedOk = 0
        var playFoldOk = 0
        var offlineFoldOk = 0
        ActivityScenario.launch(WebViewProbeActivity::class.java).use { scenario ->
            shell("cmd device_state state 2")
            val web = addWeb(scenario)
            present(web)
            val seeded = waitPhase(web, 45_000)
            results += "seed=$seeded ${brief(web)}"
            eval(web, REWRITE.replace("__AGE__", "5").replace("__RADIO__", "1"))
            destroy(web)

            repeat(COLD) { index ->
                val cold = addWeb(scenario)
                present(cold)
                val phase = waitPhase(cold, 30_000)
                val ready = eval(cold, SharkIncrementalAdapter.JS_UI_READY) == "true"
                val save = eval(cold, SAVE_PRESENT)
                val ok = phase == "app" && ready && save == "yes"
                if (ok) coldOk += 1
                results += "cold#$index phase=$phase ready=$ready save=$save"
                destroy(cold)
            }

            val warmHost = addWeb(scenario)
            repeat(WARM) { index ->
                present(warmHost)
                val phase = waitPhase(warmHost, 30_000)
                val ready = eval(warmHost, SharkIncrementalAdapter.JS_UI_READY) == "true"
                val save = eval(warmHost, SAVE_PRESENT)
                val ok = phase == "app" && ready && save == "yes"
                if (ok) warmOk += 1
                results += "warm#$index phase=$phase ready=$ready save=$save"
            }

            repeat(5) { index ->
                shell("cmd device_state state 2")
                val aged = addWeb(scenario)
                present(aged)
                waitPhase(aged, 30_000)
                eval(aged, REWRITE.replace("__AGE__", "120").replace("__RADIO__", "1"))
                present(aged)
                val phase = waitPhase(aged, 30_000)
                eval(aged, SharkLayout.injectionScript())
                Thread.sleep(300)
                val before = probe(aged)
                val ready = eval(aged, SharkIncrementalAdapter.JS_UI_READY) == "true"
                val speed = before.contains("\"speed\":{") && speedHit(before)
                var foldNote = ""
                if (index == 0) {
                    shell("cmd device_state state 0")
                    val folded = waitSize("1080x2092")
                    Thread.sleep(600)
                    eval(aged, SharkLayout.injectionScript())
                    val foldedProbe = probe(aged)
                    val foldedReady = eval(aged, SharkIncrementalAdapter.JS_UI_READY) == "true"
                    shell("cmd device_state state 2")
                    val opened = waitSize("2208x1840")
                    Thread.sleep(600)
                    eval(aged, SharkLayout.injectionScript())
                    val openedProbe = probe(aged)
                    val openedReady = eval(aged, SharkIncrementalAdapter.JS_UI_READY) == "true"
                    val foldOk = folded.contains("1080x2092") && opened.contains("2208x1840") &&
                        foldedReady && openedReady && speedHit(foldedProbe) && speedHit(openedProbe)
                    if (foldOk) offlineFoldOk += 1
                    foldNote = " fold=$foldOk folded=$folded opened=$opened"
                    results += "offline-folded $foldedProbe"
                    results += "offline-opened $openedProbe"
                }
                val showedDone = waitDone(aged, 20_000)
                val doneProbe = probe(aged)
                val doneHit = doneHit(doneProbe)
                if (showedDone && doneHit) {
                    eval(aged, CLICK_DONE)
                    Thread.sleep(400)
                }
                val after = waitPhase(aged, 8_000)
                val appReady = after == "app" && eval(aged, SharkIncrementalAdapter.JS_UI_READY) == "true"
                val ok = phase == "offline" && ready && speed && showedDone && doneHit && appReady
                if (ok) agedOk += 1
                results += "aged#$index phase=$phase ready=$ready speed=$speed done=$showedDone hit=$doneHit after=$after$foldNote"
                destroy(aged)
            }

            val playing = addWeb(scenario)
            present(playing)
            val playPhase = waitPhase(playing, 30_000)
            results += "play-base=$playPhase"
            repeat(5) { index ->
                val target = if (index % 2 == 0) "1080x2092" to 0 else "2208x1840" to 2
                shell("cmd device_state state ${target.second}")
                val size = waitSize(target.first)
                Thread.sleep(400)
                eval(playing, SharkLayout.injectionScript())
                val phase = waitPhase(playing, 8_000)
                val ready = eval(playing, SharkIncrementalAdapter.JS_UI_READY) == "true"
                val ok = size.contains(target.first) && phase == "app" && ready
                if (ok) playFoldOk += 1
                results += "play-fold#$index size=$size phase=$phase ready=$ready"
            }

            onWeb(playing) {
                it.onPause()
                it.pauseTimers()
            }
            Thread.sleep(400)
            val pausedSave = eval(playing, SAVE_PRESENT)
            onWeb(playing) {
                it.onResume()
                it.resumeTimers()
            }
            val resumed = waitPhase(playing, 8_000)
            val resumedReady = eval(playing, SharkIncrementalAdapter.JS_UI_READY) == "true"
            results += "resume phase=$resumed ready=$resumedReady pausedSave=$pausedSave"

            val reopened = addWeb(scenario)
            present(reopened)
            val reopenedPhase = waitPhase(reopened, 30_000)
            val reopenedSave = eval(reopened, SAVE_PRESENT)
            results += "reopen phase=$reopenedPhase save=$reopenedSave ready=${eval(reopened, SharkIncrementalAdapter.JS_UI_READY)}"
            shell("cmd device_state state reset")
            destroy(reopened)
            destroy(playing)
        }

        val summary = buildString {
            appendLine("cold=$coldOk/$COLD warm=$warmOk/$WARM aged=$agedOk/5")
            appendLine("playFold=$playFoldOk/5 offlineFold=$offlineFoldOk/1")
            results.forEach { appendLine(it) }
        }
        Log.i(TAG, summary)
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
        File(dir, "shark-acceptance.txt").writeText(summary)
        assertTrue(
            summary,
            coldOk == COLD && warmOk == WARM && agedOk == 5 && playFoldOk == 5 && offlineFoldOk == 1,
        )
    }

    private fun addWeb(scenario: ActivityScenario<WebViewProbeActivity>): WebView {
        val ref = AtomicReference<WebView>()
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
            ref.set(web)
        }
        return ref.get()
    }

    private fun destroy(web: WebView) {
        val latch = CountDownLatch(1)
        web.post {
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
    }

    private fun onWeb(web: WebView, block: (WebView) -> Unit) {
        val latch = CountDownLatch(1)
        web.post {
            block(web)
            latch.countDown()
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
    }

    private fun present(web: WebView) {
        val latch = CountDownLatch(1)
        web.post {
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.evaluateJavascript(pageZoomScript(100), null)
                    view?.evaluateJavascript(SharkLayout.injectionScript(), null)
                    latch.countDown()
                }
            }
            web.loadUrl(LAUNCH)
        }
        assertTrue(latch.await(60, TimeUnit.SECONDS))
    }

    private fun waitPhase(web: WebView, timeoutMs: Long): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        var phase = "pending"
        while (System.currentTimeMillis() < deadline) {
            phase = eval(web, PHASE)
            if (phase == "app" || phase == "offline") return phase
            Thread.sleep(250)
        }
        return phase
    }

    private fun waitDone(web: WebView, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (eval(web, DONE_SHOWN) == "yes") return true
            Thread.sleep(300)
        }
        return false
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

    private fun brief(web: WebView): String = probe(web).take(240)

    private fun speedHit(probe: String): Boolean = hitField(probe, "speed")

    private fun doneHit(probe: String): Boolean = hitField(probe, "done")

    private fun hitField(probe: String, name: String): Boolean {
        val marker = "\"$name\":{"
        val start = probe.indexOf(marker)
        if (start < 0) return false
        val slice = probe.substring(start, minOf(probe.length, start + 180))
        return slice.contains("\"hit\":true")
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

    companion object {
        private const val TAG = "SHARK_ACCEPT"
        private const val COLD = 10
        private const val WARM = 10
        private const val LAUNCH = "https://mrredshark77.github.io/shark-incremental/"
        private const val PHASE =
            "(function(){ try {" +
                " if (typeof player === 'undefined' || !player) return 'loading';" +
                " if (typeof offline !== 'undefined' && offline.active) return 'offline';" +
                " var app = document.getElementById('app');" +
                " if (app && app.style.display !== 'none') return 'app';" +
                " return 'pending';" +
                " } catch (e) { return 'error'; } })()"
        private const val SAVE_PRESENT =
            "(function(){ try { var raw = localStorage.getItem('shark_inc_save');" +
                " if (!raw) return 'no'; var p = JSON.parse(atob(raw));" +
                " return p && p.VERSION >= 3 ? 'yes' : 'no'; } catch (e) { return 'no'; } })()"
        private const val REWRITE =
            "(function(){ try {" +
                " if (typeof save === 'function' && !(typeof offline !== 'undefined' && offline.nosave)) save();" +
                " var raw = localStorage.getItem('shark_inc_save'); if (!raw) return 'missing';" +
                " var p = JSON.parse(atob(raw));" +
                " p.latest_time = Date.now() - (__AGE__ * 1000);" +
                " if (!p.radios) p.radios = {}; p.radios.offline = __RADIO__;" +
                " if (typeof p.totalTimePlayed !== 'number' || p.totalTimePlayed < 30) p.totalTimePlayed = 600;" +
                " localStorage.setItem('shark_inc_save', btoa(JSON.stringify(p)));" +
                " return 'ok'; } catch (e) { return String(e).slice(0, 80); } })()"
        private const val DONE_SHOWN =
            "(function(){ var d = document.getElementById('offline-done');" +
                " return d && d.style.display !== 'none' ? 'yes' : 'no'; })()"
        private const val CLICK_DONE =
            "(function(){ var d = document.getElementById('offline-done');" +
                " if (!d) return 'missing'; d.click();" +
                " if (typeof save === 'function') save(); return 'clicked'; })()"
    }
}
