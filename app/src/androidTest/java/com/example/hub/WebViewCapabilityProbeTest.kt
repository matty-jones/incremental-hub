package com.example.hub

import android.os.ParcelFileDescriptor
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Disposable page for alert, confirm, prompt, file input, and a data download.
 * The chrome client matches [com.example.hub.ui.WebViewHost]: console only.
 */
@RunWith(AndroidJUnit4::class)
class WebViewCapabilityProbeTest {

    @Test
    fun recordBrowserCapabilities() {
        val report = StringBuilder()
        val version = shell("dumpsys webviewupdate")
        report.append(version.lineSequence().firstOrNull { it.contains("Current WebView package") } ?: version)
        report.append('\n')
        ActivityScenario.launch(WebViewProbeActivity::class.java).use { scenario ->
            listOf("alert", "confirm", "prompt", "file", "download").forEach { name ->
                val web = addWeb(scenario)
                load(web, PAGE)
                val outcome = if (name == "file" || name == "download") gesture(web, name) else exercise(web, name)
                report.append(name).append('=').append(outcome).append('\n')
                scenario.onActivity {
                    (web.parent as? android.view.ViewGroup)?.removeView(web)
                    web.destroy()
                }
                Thread.sleep(300)
            }
        }
        val text = report.toString()
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
        File(dir, "webview-capabilities.txt").writeText(text)
        android.util.Log.i("WEBVIEW_CAPABILITY", text)
        listOf("alert=", "confirm=", "prompt=", "file=", "download=").forEach { label ->
            val line = text.lineSequence().first { it.startsWith(label) }
            assertTrue(line, line.substringAfter('=') in setOf("APPEARED", "RETURNED", "FAILED", "HUNG"))
        }
    }

    private fun exercise(web: WebView, name: String): String {
        val started = eval(web, "window.__hubStep='idle'; window.__hubGo='$name'; 'armed'", 2_000)
        if (started == null) return "HUNG"
        val deadline = System.currentTimeMillis() + 4_000
        var step = "idle"
        var dialog = false
        while (System.currentTimeMillis() < deadline) {
            val polled = eval(web, "window.__hubStep || ''", 1_000)
            if (polled == null) {
                if (name == "prompt") shell("input text a")
                shell("input keyevent 61")
                shell("input keyevent 66")
                Thread.sleep(400)
                val after = eval(web, "window.__hubStep || ''", 2_000)
                if (after == "returned" || after == "failed") return "APPEARED"
                val tapped = tapButton("OK") || tapButton("Allow") || tapButton("Download")
                if (tapped) {
                    Thread.sleep(400)
                    val accepted = eval(web, "window.__hubStep || ''", 2_000)
                    if (accepted == "returned" || accepted == "failed") return "APPEARED"
                }
                return "HUNG"
            }
            step = polled
            if (!dialog && dialogVisible(name)) dialog = true
            if (step == "returned" || step == "failed") break
            if (dialog) break
            Thread.sleep(200)
        }
        if (!dialog && (step == "returned" || step == "failed")) {
            Thread.sleep(700)
            dialog = dialogVisible(name)
        }
        if (dialog) {
            shell("input keyevent 4")
            Thread.sleep(400)
            return "APPEARED"
        }
        return when (step) {
            "returned" -> "RETURNED"
            "failed" -> "FAILED"
            "HUNG" -> "HUNG"
            else -> if (step == "started") "HUNG" else "FAILED"
        }
    }

    private fun gesture(web: WebView, name: String): String {
        val script = if (name == "file") {
            "(function(){ var input = document.createElement('input'); input.type='file';" +
                " input.style.cssText='position:fixed;left:80px;top:160px;width:240px;height:80px';" +
                " document.body.appendChild(input); var r = input.getBoundingClientRect();" +
                " return JSON.stringify({x:r.left+r.width/2,y:r.top+r.height/2}); })()"
        } else {
            "(function(){ var link = document.createElement('a'); link.href='data:text/plain,hub-download';" +
                " link.download='hub-note.txt'; link.textContent='hub-download';" +
                " link.style.cssText='position:fixed;left:80px;top:160px;width:240px;height:80px';" +
                " document.body.appendChild(link); var r = link.getBoundingClientRect();" +
                " return JSON.stringify({x:r.left+r.width/2,y:r.top+r.height/2}); })()"
        }
        val point = eval(web, script, 2_000) ?: return "HUNG"
        val decoded = point.replace("\\\"", "\"")
        android.util.Log.i("WEBVIEW_CAPABILITY", "gesture $name point=$decoded")
        val x = Regex("\"x\":([0-9.]+)").find(decoded)?.groupValues?.get(1)?.toFloat()?.toInt()
        val y = Regex("\"y\":([0-9.]+)").find(decoded)?.groupValues?.get(1)?.toFloat()?.toInt()
        if (x == null || y == null) return "FAILED"
        val origin = IntArray(2)
        val placed = CountDownLatch(1)
        web.post {
            web.getLocationOnScreen(origin)
            placed.countDown()
        }
        if (!placed.await(2, TimeUnit.SECONDS)) return "HUNG"
        shell("input tap ${origin[0] + x} ${origin[1] + y}")
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            if (dialogVisible(name) || (name == "download" && shell("dumpsys notification").contains("hub-note"))) {
                shell("input keyevent 4")
                return "APPEARED"
            }
            Thread.sleep(250)
        }
        return "RETURNED"
    }

    private fun tapButton(label: String): Boolean {
        shell("uiautomator dump /sdcard/hub-ui.xml")
        val dump = shell("cat /sdcard/hub-ui.xml")
        val needle = "text=\"$label\""
        val at = dump.indexOf(needle)
        if (at < 0) return false
        val bounds = Regex("bounds=\"\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]\"")
            .find(dump.substring(at.coerceAtMost(dump.length), (at + 400).coerceAtMost(dump.length)))
            ?: return false
        val x = (bounds.groupValues[1].toInt() + bounds.groupValues[3].toInt()) / 2
        val y = (bounds.groupValues[2].toInt() + bounds.groupValues[4].toInt()) / 2
        shell("input tap $x $y")
        return true
    }

    private fun dialogVisible(name: String): Boolean {
        val focus = shell("dumpsys window")
        if (name == "file") {
            return focus.contains("DocumentsUi") ||
                focus.contains("FilePicker") ||
                focus.contains("ResolverActivity")
        }
        if (name == "download") return false
        return focus.contains("AlertDialog") || focus.contains("android:id/alertTitle") || focus.contains("hub-$name")
    }

    private fun addWeb(scenario: ActivityScenario<WebViewProbeActivity>): WebView {
        val ref = AtomicReference<WebView>()
        scenario.onActivity { activity ->
            val web = WebView(activity)
            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            web.webChromeClient = object : WebChromeClient() {}
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

    private fun load(web: WebView, html: String) {
        val latch = CountDownLatch(1)
        web.post {
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    latch.countDown()
                }
            }
            web.loadDataWithBaseURL("https://hub.invalid/", html, "text/html", "utf-8", null)
        }
        assertTrue(latch.await(20, TimeUnit.SECONDS))
    }

    private fun eval(web: WebView, script: String, timeoutMs: Long): String? {
        val latch = CountDownLatch(1)
        val ref = AtomicReference<String?>(null)
        web.post {
            web.evaluateJavascript(script) { value ->
                ref.set(value?.trim()?.removeSurrounding("\""))
                latch.countDown()
            }
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return null
        return ref.get()?.removeSurrounding("\"")
    }

    private fun shell(command: String): String {
        val parcel: ParcelFileDescriptor =
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(parcel).use { stream ->
            InputStreamReader(stream).readText()
        }
    }

    companion object {
        private val PAGE = """
            <!DOCTYPE html><html><body><script>
            window.__hubStep = 'idle';
            window.__hubGo = '';
            setInterval(function() {
              var op = window.__hubGo;
              if (!op || window.__hubRan === op) return;
              window.__hubRan = op;
              window.__hubStep = 'started';
              try {
                if (op === 'alert') { alert('hub-alert'); window.__hubStep = 'returned'; }
                else if (op === 'confirm') { confirm('hub-confirm'); window.__hubStep = 'returned'; }
                else if (op === 'prompt') { prompt('hub-prompt', ''); window.__hubStep = 'returned'; }
                else if (op === 'file') {
                  var input = document.createElement('input');
                  input.type = 'file';
                  document.body.appendChild(input);
                  input.click();
                  window.__hubStep = 'returned';
                } else if (op === 'download') {
                  var link = document.createElement('a');
                  link.href = 'data:text/plain,hub-download';
                  link.download = 'hub-note.txt';
                  document.body.appendChild(link);
                  link.click();
                  window.__hubStep = 'returned';
                } else { window.__hubStep = 'failed'; }
              } catch (e) { window.__hubStep = 'failed'; }
            }, 50);
            </script></body></html>
        """.trimIndent()
    }
}
