package com.example.hub

import android.content.Context
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.hub.ui.PageScaleMode
import com.example.hub.ui.assessPresentation
import com.example.hub.ui.parsePresentationAck
import com.example.hub.ui.presentationSettled
import com.example.hub.ui.zoomAcknowledged
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Fold and unfold a Shark session through the Hub activity. The WebView and
 * its document stay in place, and the layout viewport has to match the view.
 */
@RunWith(AndroidJUnit4::class)
class FoldPlaySurfaceTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @After
    fun restorePosture() {
        shell("cmd device_state state reset")
    }

    @Test
    fun liveSharkKeepsItsDocumentAcrossFoldAndUnfold() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences(DISPLAY_PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getString(SHARK, null)
        prefs.edit().putString(SHARK, ZOOM_PREFS).commit()
        try {
            fold()
            openShark()
            val cover = waitForViewport(zoomPercent = 90, width = 380..460, viewWidth = 900..1200)
            val identity = cover.identity
            val marker = cover.marker
            val doc = cover.doc
            val nav = cover.nav
            assertTrue(switchTab().startsWith("tabs"))
            shell("logcat -c")
            unfold()
            val inner = waitForViewport(zoomPercent = 110, width = 780..920, viewWidth = 1800..2300)
            assertEquals(identity, inner.identity)
            assertEquals(marker, inner.marker)
            assertEquals(doc, inner.doc)
            assertEquals(nav, inner.nav)
            assertNoReload("unfold")
            assertTrue(switchTab().startsWith("tabs"))
            shell("logcat -c")
            fold()
            val refolded = waitForViewport(zoomPercent = 90, width = 380..460, viewWidth = 900..1200)
            assertEquals(identity, refolded.identity)
            assertEquals(marker, refolded.marker)
            assertEquals(doc, refolded.doc)
            assertEquals(nav, refolded.nav)
            assertNoReload("refold")
            unfold()
            val again = waitForViewport(zoomPercent = 110, width = 780..920, viewWidth = 1800..2300)
            assertEquals(identity, again.identity)
            assertEquals(marker, again.marker)
            returnToHub()

            unfold()
            openShark()
            val startedOpen = waitForViewport(zoomPercent = 110, width = 780..920, viewWidth = 1800..2300)
            shell("logcat -c")
            fold()
            val closedAfterOpen = waitForViewport(zoomPercent = 90, width = 380..460, viewWidth = 900..1200)
            assertEquals(startedOpen.identity, closedAfterOpen.identity)
            assertEquals(startedOpen.marker, closedAfterOpen.marker)
            assertEquals(startedOpen.doc, closedAfterOpen.doc)
            assertEquals(startedOpen.nav, closedAfterOpen.nav)
            assertNoReload("fold-after-inner-start")
            returnToHub()
        } finally {
            if (previous == null) prefs.edit().remove(SHARK).commit() else prefs.edit().putString(SHARK, previous).commit()
        }
    }

    private fun openShark() {
        shell("logcat -c")
        clickWhenEnabled("play-shark-incremental")
        val started = System.currentTimeMillis()
        val ready = started + 90_000
        var detail = ""
        var earlyLog = ""
        while (System.currentTimeMillis() < ready) {
            composeRule.waitForIdle()
            val opening = composeRule.onAllNodesWithText("Opening shark-incremental", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
            val menu = composeRule.onAllNodesWithContentDescription("Menu").fetchSemanticsNodes().isNotEmpty()
            val recovery = composeRule.onAllNodesWithText("Session Recovery Required").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Save diagnostics").fetchSemanticsNodes().isNotEmpty()
            detail = "menu=$menu opening=$opening recovery=$recovery"
            if (earlyLog.isEmpty() && System.currentTimeMillis() - started > 2_000) {
                earlyLog = shell("logcat -d -s HubNavigation:D SessionController:D FoldPlaySurfaceTest:I AndroidRuntime:E")
            }
            if (recovery) break
            if (menu && !opening) break
            Thread.sleep(200)
        }
        if (composeRule.onAllNodesWithText("Session Recovery Required").fetchSemanticsNodes().isNotEmpty() ||
            composeRule.onAllNodesWithText("Save diagnostics").fetchSemanticsNodes().isNotEmpty()
        ) {
            val note = shell("logcat -d -s RecoveryOverlay:D SessionController:E")
            throw AssertionError("recovery overlay\n${note.take(1500)}")
        }
        if (composeRule.onAllNodesWithContentDescription("Menu").fetchSemanticsNodes().isEmpty()) {
            throw AssertionError("menu missing ($detail)\nearly:\n${earlyLog.take(1500)}")
        }
        eval(requireWeb(), MARKER)
    }

    private fun returnToHub() {
        if (composeRule.onAllNodesWithText("Return to hub").fetchSemanticsNodes().isEmpty()) {
            composeRule.onNodeWithContentDescription("Menu").performClick()
        }
        composeRule.onNodeWithText("Return to hub").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTag("play-shark-incremental").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForViewport(zoomPercent: Int, width: IntRange, viewWidth: IntRange): Reading {
        val deadline = System.currentTimeMillis() + 15_000
        var last = ""
        var stable = 0
        var latest = ""
        while (System.currentTimeMillis() < deadline) {
            val reading = readViewport(zoomPercent)
            latest = reading.detail
            val ok = reading.settled &&
                reading.innerWidth in width &&
                reading.viewWidth in viewWidth &&
                reading.zoomOk &&
                reading.marker == "held"
            if (ok && reading.signature == last) {
                stable += 1
                if (stable >= 2) return reading
            } else if (ok) {
                last = reading.signature
                stable = 1
            } else {
                stable = 0
            }
            Thread.sleep(150)
        }
        throw AssertionError("viewport did not settle for zoom $zoomPercent: $latest")
    }

    private fun readViewport(zoomPercent: Int): Reading {
        val web = requireWeb()
        val box = measure(web)
        val raw = eval(web, PROBE)
        val json = JSONObject(raw)
        val wrapped = JSONObject()
            .put("ok", false)
            .put("mode", "hub")
            .put("fault", "none")
            .put("after", json)
        val ack = parsePresentationAck(wrapped.toString(), zoomPercent, 100)
        assertNotNull(raw, ack)
        val fault = assessPresentation(
            ack!!,
            box.width,
            box.height,
            box.density,
            box.scale,
            box.parentWidth,
        )
        val zoomOk = zoomAcknowledged(zoomPercent, json.optString("root").ifBlank { json.optString("rootCss") })
        val marker = json.optString("marker")
        val doc = json.optString("doc")
        val nav = json.optInt("nav")
        val detail = "fault=$fault view=${box.width}x${box.height} parent=${box.parentWidth} " +
            "inner=${ack.innerWidth}x${ack.innerHeight} vs=${ack.viewportScale} scale=${box.scale} " +
            "zoom=${json.optString("root")} marker=$marker doc=$doc nav=$nav mode=${PageScaleMode.HUB.stored()}"
        return Reading(
            settled = presentationSettled(fault),
            innerWidth = ack.innerWidth,
            viewWidth = box.width,
            zoomOk = zoomOk,
            marker = marker,
            doc = doc,
            nav = nav,
            identity = box.identity,
            signature = "${ack.innerWidth}x${ack.innerHeight}:${box.width}x${box.height}:$fault",
            detail = detail,
        )
    }

    private fun switchTab(): String = eval(requireWeb(), TAB)

    private fun assertNoReload(step: String) {
        val log = shell("logcat -d -s WebViewHost:I WebViewHost:D SessionController:D")
        assertFalse("$step reloaded the document\n$log", log.contains("loadUrl"))
    }

    private fun requireWeb(): WebView {
        val deadline = System.currentTimeMillis() + 25_000
        var seen = ""
        while (System.currentTimeMillis() < deadline) {
            findWebView(composeRule.activity)?.let { return it }
            seen = webUrls(composeRule.activity)
            Thread.sleep(200)
        }
        throw AssertionError("Shark WebView is missing urls=$seen")
    }

    private fun findWebView(activity: MainActivity): WebView? {
        val ref = AtomicReference<WebView?>()
        val latch = CountDownLatch(1)
        activity.runOnUiThread {
            ref.set(walk(activity.window.decorView))
            latch.countDown()
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        return ref.get()
    }

    private fun webUrls(activity: MainActivity): String {
        val ref = AtomicReference("")
        val latch = CountDownLatch(1)
        activity.runOnUiThread {
            val urls = ArrayList<String>()
            collectUrls(activity.window.decorView, urls)
            ref.set(urls.joinToString())
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
        return ref.get()
    }

    private fun collectUrls(view: View, urls: MutableList<String>) {
        if (view is WebView) urls.add(view.url ?: "null")
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) collectUrls(view.getChildAt(index), urls)
        }
    }

    private fun walk(view: View): WebView? {
        if (view is WebView && view.url?.contains("shark-incremental") == true) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                walk(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun measure(web: WebView): Box {
        val ref = AtomicReference<Box>()
        val latch = CountDownLatch(1)
        web.post {
            val parent = (web.parent as? View)?.width ?: 0
            ref.set(
                Box(
                    width = web.width,
                    height = web.height,
                    density = web.resources.displayMetrics.density,
                    scale = web.scale,
                    parentWidth = parent,
                    identity = System.identityHashCode(web),
                ),
            )
            latch.countDown()
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        return ref.get()
    }

    private fun eval(web: WebView, script: String): String {
        val value = AtomicReference("eval-timeout")
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
            org.json.JSONArray("[$raw]").opt(0)?.toString() ?: ""
        } catch (e: Exception) {
            raw
        }
    }

    private fun fold() {
        shell("cmd device_state state 0")
        waitSize("1080")
        composeRule.waitForIdle()
    }

    private fun unfold() {
        shell("cmd device_state state 2")
        waitSize("2208")
        composeRule.waitForIdle()
    }

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

    private fun clickWhenEnabled(tag: String) {
        composeRule.waitUntil(timeoutMillis = 8_000) {
            if (composeRule.onAllNodesWithText("Later").fetchSemanticsNodes().isNotEmpty()) {
                composeRule.onNodeWithText("Later").performClick()
            }
            val nodes = composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes()
            nodes.isNotEmpty() && nodes.all { SemanticsProperties.Disabled !in it.config }
        }
        // A touch injected after folding misses this button. Invoke its click action.
        val node = composeRule.onNodeWithTag(tag).fetchSemanticsNode()
        composeRule.runOnUiThread {
            node.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke()
        }
    }

    private fun shell(command: String): String {
        val parcel: ParcelFileDescriptor =
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(parcel).use { stream ->
            InputStreamReader(stream).readText()
        }
    }

    private data class Box(
        val width: Int,
        val height: Int,
        val density: Float,
        val scale: Float,
        val parentWidth: Int,
        val identity: Int,
    )

    private data class Reading(
        val settled: Boolean,
        val innerWidth: Int,
        val viewWidth: Int,
        val zoomOk: Boolean,
        val marker: String,
        val doc: String,
        val nav: Int,
        val identity: Int,
        val signature: String,
        val detail: String,
    )

    companion object {
        private const val SHARK = "shark-incremental"
        private const val DISPLAY_PREFS = "hub_display_preferences"
        private const val ZOOM_PREFS =
            """{"folded":{"text":100,"page":90},"unfolded":{"text":100,"page":110},"mode":"hub"}"""
        private const val MARKER =
            "(function(){ window.__hubFoldMarker='held';" +
                " if (!window.__hubDocId) window.__hubDocId=String(Math.random());" +
                " return window.__hubFoldMarker; })()"
        private const val PROBE =
            "(function(){ var vv=window.visualViewport; var root=document.documentElement; var body=document.body;" +
                " return JSON.stringify({iw:window.innerWidth||0, ih:window.innerHeight||0," +
                " sw:root?root.scrollWidth:0, cw:root?root.clientWidth:0," +
                " vs:vv?vv.scale:null, vw:vv?vv.width:null, vh:vv?vv.height:null," +
                " root:root&&root.style?(root.style.zoom||''):'', body:body&&body.style?(body.style.zoom||''):''," +
                " rootCss:root?String(getComputedStyle(root).zoom||''):''," +
                " bodyCss:body?String(getComputedStyle(body).zoom||''):''," +
                " dpr:window.devicePixelRatio||0, marker:window.__hubFoldMarker||''," +
                " doc:window.__hubDocId||''," +
                " nav:(performance.getEntriesByType('navigation')||[]).length}); })()"
        private const val TAB =
            "(function(){ var tabs=document.querySelectorAll('.tab-button');" +
                " if (tabs.length<2) return 'missing '+tabs.length;" +
                " var next=0; for (var i=0;i<tabs.length;i++){" +
                " if ((' '+tabs[i].className+' ').indexOf(' selected ')>=0){ next=(i+1)%tabs.length; break; } }" +
                " tabs[next].click(); return 'tabs '+tabs.length; })()"
    }
}
