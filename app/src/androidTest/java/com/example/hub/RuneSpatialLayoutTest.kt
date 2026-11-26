package com.example.hub

import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.hub.games.DocumentLayout
import com.example.hub.ui.cssPixelSize
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Rune Constructor geometry on both Pixel Fold panels. The document is a
 * local copy of the upstream board, so the live Shark page is not loaded
 * and the personal profile is not opened.
 */
@RunWith(AndroidJUnit4::class)
class RuneSpatialLayoutTest {

    @After
    fun restorePosture() {
        shell("cmd device_state state reset")
    }

    @Test
    fun everyRuneControlIsReachableOnBothFolds() {
        shell("cmd device_state state 0")
        waitSize("1080")
        ActivityScenario.launch(WebViewProbeActivity::class.java).use { scenario ->
            val web = addWeb(scenario)
            try {
                waitForViewport(web, wide = false)
                assertEquals(DocumentLayout.MARKER, inject(web))
                val hidden = JSONObject(eval(web, HIDDEN).ifBlank { "{}" })
                assertEquals("none", hidden.optString("display"))
                assertEquals("", hidden.optString("fit"))
                assertEquals("", hidden.optString("pan"))
                eval(web, SHOW)
                val cover = waitSurvey(web, cover = true)
                assertBoard(cover, cover = true)
                val beforeWrites = cover.getInt("writes")
                val beforeZoom = cover.getString("zoom")
                val beforePan = cover.getString("pan")
                val beforeApparent = cover.getInt("apparent")
                val beforeStore = cover.getInt("store")
                eval(web, REPLACE_LABEL)
                Thread.sleep(500)
                val ticked = waitSurvey(web, cover = true)
                assertBoard(ticked, cover = true)
                assertEquals(beforeZoom, ticked.getString("zoom"))
                assertEquals(beforePan, ticked.getString("pan"))
                assertEquals(beforeWrites, ticked.getInt("writes"))
                assertEquals(beforeApparent, ticked.getInt("apparent"))
                assertEquals(beforeStore, ticked.getInt("store"))
                unfold()
                waitForViewport(web, wide = true)
                assertEquals(DocumentLayout.MARKER, inject(web))
                val open = waitSurvey(web, cover = false)
                assertBoard(open, cover = false)
                shell("cmd device_state state 0")
                waitSize("1080")
                waitForViewport(web, wide = false)
                assertEquals(DocumentLayout.MARKER, inject(web))
                val coverAgain = waitSurvey(web, cover = true)
                assertBoard(coverAgain, cover = true)
            } finally {
                scenario.onActivity {
                    (web.parent as? ViewGroup)?.removeView(web)
                    web.destroy()
                }
            }
        }
    }

    private fun assertBoard(json: JSONObject, cover: Boolean) {
        assertEquals(json.toString(), "", json.optString("error"))
        assertEquals(json.toString(), 36, json.optInt("count"))
        assertEquals(json.toString(), 5, json.optInt("cols"))
        assertTrue(json.toString(), json.optBoolean("slots"))
        assertEquals(json.toString(), "", json.optString("gridFit"))
        assertTrue("extent ${json.optInt("apparent")}: $json", json.optInt("apparent") >= 760)
        assertEquals(json.toString(), 0.0, json.optDouble("pageX"), 0.0)
        assertEquals("hidden", json.optString("bodyX"))
        val ids = mutableSetOf<String>()
        val controls = json.getJSONArray("controls")
        for (i in 0 until controls.length()) {
            val control = controls.getJSONObject(i)
            val id = control.optString("id")
            ids.add(id)
            assertTrue(
                "$id is not on screen: $control",
                control.optBoolean("hit") && control.optBoolean("inside") && control.optBoolean("sized"),
            )
        }
        REQUIRED_IDS.forEach { id -> assertTrue("missing $id in $ids", ids.contains(id)) }
        if (cover) {
            assertEquals(json.toString(), "1", json.optString("pan"))
            assertEquals(json.toString(), "", json.optString("zoom"))
            assertEquals("rune-constructor-tab", json.optString("hostId"))
            assertTrue("right edge was already on screen: $json", json.optBoolean("rightPast"))
            assertTrue("pan host did not scroll: $json", json.optDouble("hostLeft") > 0.0)
            assertTrue(
                "pan host has nothing to scroll: $json",
                json.optDouble("hostScroll") > json.optDouble("hostClient") + 8.0,
            )
        } else {
            assertEquals(json.toString(), "", json.optString("pan"))
            assertEquals(json.toString(), "", json.optString("zoom"))
            assertEquals(json.toString(), "", json.optString("hostId"))
            assertFalse("open fold still overflows: $json", json.optBoolean("rightPast"))
        }
    }

    private fun waitSurvey(web: WebView, cover: Boolean): JSONObject {
        val deadline = System.currentTimeMillis() + 4_000
        var last = JSONObject()
        while (System.currentTimeMillis() < deadline) {
            last = JSONObject(eval(web, "window.__hubRuneSurvey(${if (cover) "'cover'" else "'open'"})").ifBlank { "{}" })
            if (runCatching { assertBoard(last, cover) }.isSuccess) return last
            Thread.sleep(150)
        }
        throw AssertionError("rune layout did not settle cover=$cover: $last")
    }

    private fun inject(web: WebView): String {
        val box = measure(web)
        return eval(web, DocumentLayout.injectionScript(cssPixelSize(box.width, box.density)))
    }

    private fun addWeb(scenario: ActivityScenario<WebViewProbeActivity>): WebView {
        val ref = AtomicReference<WebView>()
        val latch = CountDownLatch(1)
        scenario.onActivity { activity ->
            val web = WebView(activity)
            web.settings.javaScriptEnabled = true
            web.settings.useWideViewPort = true
            web.settings.loadWithOverviewMode = false
            web.settings.textZoom = 100
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    latch.countDown()
                }
            }
            activity.findViewById<ViewGroup>(android.R.id.content).addView(
                web,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
            web.loadDataWithBaseURL(null, RUNE_HTML, "text/html", "utf-8", null)
            ref.set(web)
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        assertEquals("ready", eval(ref.get(), SURVEY))
        return ref.get()
    }

    private fun waitForViewport(web: WebView, wide: Boolean) {
        val deadline = System.currentTimeMillis() + 12_000
        var detail = ""
        while (System.currentTimeMillis() < deadline) {
            val box = measure(web)
            val css = eval(web, "(function(){return String(window.innerWidth||0);})()").toDoubleOrNull() ?: 0.0
            val pixelsOk = if (wide) box.width >= 1800 else box.width in 900..1400
            val cssOk = if (wide) css > 700.0 else css in 350.0..700.0
            detail = "css=$css view=${box.width}x${box.height} density=${box.density}"
            if (pixelsOk && cssOk) return
            Thread.sleep(200)
        }
        throw AssertionError("viewport did not settle wide=$wide: $detail")
    }

    private fun unfold() {
        shell("cmd device_state state 2")
        waitSize("2208")
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

    private fun measure(web: WebView): Box {
        val ref = AtomicReference<Box>()
        val latch = CountDownLatch(1)
        web.post {
            ref.set(
                Box(
                    width = web.width,
                    height = web.height,
                    density = web.resources.displayMetrics.density,
                ),
            )
            latch.countDown()
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        return ref.get()
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
        if (!latch.await(15, TimeUnit.SECONDS)) return ""
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

    private fun shell(command: String): String {
        val parcel: ParcelFileDescriptor =
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(parcel).use { stream ->
            InputStreamReader(stream).readText()
        }
    }

    private data class Box(val width: Int, val height: Int, val density: Float)

    companion object {
        private val REQUIRED_IDS = listOf(
            "rune-clear-all",
            "rune-erase-mode",
            "rune-item-0",
            "rune-item-4",
            "rune-slot-0",
            "rune-slot-24",
            "rune-upgrade-0",
            "rune-upgrade-3",
        )
        private const val HIDDEN =
            "(function(){var board=document.getElementById('runes-div');" +
                "var tab=document.getElementById('rune-constructor-tab');" +
                "return JSON.stringify({fit:board?(board.getAttribute('data-hub-fit')||''):''," +
                "pan:board?(board.getAttribute('data-hub-pan')||''):''," +
                "display:tab?getComputedStyle(tab).display:''});})()"
        private const val SHOW =
            "(function(){var tab=document.getElementById('rune-constructor-tab');" +
                "tab.style.display='block';return tab.style.display;})()"
        private const val REPLACE_LABEL =
            "(function(){var label=document.getElementById('rune-slot-0-amount');" +
                "label.innerHTML='<text-style>12</text-style>';return label.innerHTML;})()"

        private val RUNE_HTML: String = runeDocument()

        private fun runeDocument(): String {
            val items = buildString {
                repeat(5) { index ->
                    append("""<div class="rune-div" id="rune-item-$index" onclick="void 0"></div>""")
                }
            }
            val slots = buildString {
                repeat(25) { index ->
                    val label = if (index == 0) {
                        """<div class="rune-top" id="rune-slot-0-amount"><text-style>0</text-style></div>"""
                    } else {
                        ""
                    }
                    append("""<div class="rune-div" id="rune-slot-$index" onclick="void 0">$label</div>""")
                }
            }
            val upgrades = buildString {
                repeat(4) { index ->
                    append("""<button id="rune-upgrade-$index" type="button">A</button>""")
                }
            }
            return """
                <!DOCTYPE html><html><head>
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <style>
                body { margin: 0 10px; overflow-x: hidden; }
                #runes-div {
                  display: grid;
                  grid-template: "a a a" "b c d" "e e e";
                  gap: 8px;
                  justify-content: center;
                }
                #runes-div > div { padding: 4px; border: solid 4px #927359; background: #F6C7A0; }
                #rune-top { grid-area: a; width: 808px; }
                #rune-top button { box-sizing: border-box; height: 32px; padding: 0; }
                #rune-bottom {
                  grid-area: e;
                  width: 808px;
                  display: grid;
                  grid-template-columns: repeat(4, 1fr);
                  min-height: 80px;
                }
                #rune-grid {
                  display: grid;
                  gap: 4px;
                  grid-template-columns: repeat(5, 80px);
                  grid-auto-rows: 80px;
                  width: 416px;
                  justify-content: center;
                  grid-area: c;
                }
                #rune-items {
                  width: 94px;
                  height: 416px;
                  display: grid;
                  gap: 4px;
                  grid-template-columns: 80px;
                  grid-auto-rows: 80px;
                  grid-area: b;
                }
                #rune-effects { width: 250px; height: 416px; grid-area: d; }
                .rune-div { position: relative; background: #4d3c2f; }
                </style></head><body><div id="app">
                <div id="rune-constructor-tab" style="display:none"><div id="runes-div">
                <div id="rune-top">
                  <button id="rune-clear-all" type="button">Clear all Runes</button>
                  <button id="rune-erase-mode" type="button">Erase Mode</button>
                </div>
                <div id="rune-items">$items</div>
                <div id="rune-grid">$slots</div>
                <div id="rune-effects"><div class="rune-effect">Fehu</div></div>
                <div id="rune-bottom">$upgrades</div>
                </div></div></div></body></html>
            """.trimIndent()
        }

        private val SURVEY = """
            (function(){
              function reveal(control, host) {
                var guard = 0;
                while (guard++ < 8) {
                  var c = control.getBoundingClientRect();
                  var h = host.getBoundingClientRect();
                  var left = Math.max(h.left, 0);
                  var right = Math.min(h.right, window.innerWidth || 0);
                  var dx = 0;
                  if (c.left < left) dx = c.left - left;
                  else if (c.right > right) dx = c.right - right;
                  if (dx) host.scrollLeft += dx;
                  var dy = 0;
                  if (c.top < 0) dy = c.top;
                  else if (c.bottom > (window.innerHeight || 0)) dy = c.bottom - (window.innerHeight || 0);
                  if (dy) window.scrollBy(0, dy);
                  if (!dx && !dy) break;
                }
              }
              function revealVertical(control) {
                var guard = 0;
                while (guard++ < 8) {
                  var c = control.getBoundingClientRect();
                  var dy = 0;
                  if (c.top < 0) dy = c.top;
                  else if (c.bottom > (window.innerHeight || 0)) dy = c.bottom - (window.innerHeight || 0);
                  if (!dy) break;
                  window.scrollBy(0, dy);
                }
              }
              window.__hubRuneSurvey = function(mode) {
                try {
                  var board = document.getElementById('runes-div');
                  var grid = document.getElementById('rune-grid');
                  var host = document.querySelector('[data-hub-pan-host]');
                  var page = document.scrollingElement || document.documentElement;
                  if (host) host.scrollLeft = 0;
                  if (page) page.scrollLeft = 0;
                  var zoom = board && board.style ? (board.style.zoom || '') : '';
                  var zoomNum = parseFloat(zoom);
                  if (!(zoomNum > 0)) zoomNum = 1;
                  var list = board ? board.querySelectorAll('button, a, input, [onclick]') : [];
                  var rightPast = false;
                  for (var n = 0; n < list.length; n++) {
                    var edge = list[n].getBoundingClientRect();
                    if (edge.left < -1 || edge.right > (window.innerWidth || 0) + 1) rightPast = true;
                  }
                  var a = document.getElementById('rune-slot-0').getBoundingClientRect();
                  var b = document.getElementById('rune-slot-1').getBoundingClientRect();
                  var d = document.getElementById('rune-slot-4').getBoundingClientRect();
                  var e = document.getElementById('rune-slot-5').getBoundingClientRect();
                  var pitch = b.left - a.left;
                  var cols = String(getComputedStyle(grid).gridTemplateColumns || '').trim().split(/\s+/).filter(Boolean).length;
                  var slots = Math.abs(a.top - b.top) < 2 && Math.abs(a.top - d.top) < 2 &&
                    a.left < b.left && b.left < d.left && e.top > a.top + 40 &&
                    Math.abs((d.left - a.left) - 4 * pitch) < 3 && cols === 5;
                  var rows = [];
                  var bad = '';
                  for (var i = 0; i < list.length; i++) {
                    var control = list[i];
                    if (mode === 'cover' && host) reveal(control, host);
                    else revealVertical(control);
                    var c = control.getBoundingClientRect();
                    var rendered = Math.min(c.width, c.height);
                    var unscaled = rendered / zoomNum;
                    var limit = Math.min(48, unscaled);
                    var horiz = c.left >= -1 && c.right <= (window.innerWidth || 0) + 1;
                    var vert = c.top >= -1 && c.bottom <= (window.innerHeight || 0) + 1;
                    var port = true;
                    if (host) {
                      var box = host.getBoundingClientRect();
                      var portLeft = Math.max(box.left, 0);
                      var portRight = Math.min(box.right, window.innerWidth || 0);
                      var portTop = Math.max(box.top, 0);
                      var portBottom = Math.min(box.bottom, window.innerHeight || 0);
                      port = c.left >= portLeft - 1 && c.right <= portRight + 1 &&
                        c.top >= portTop - 1 && c.bottom <= portBottom + 1;
                    }
                    var hit = document.elementFromPoint(c.left + c.width / 2, c.top + c.height / 2);
                    var owned = !!(hit && (hit === control || control.contains(hit)));
                    var inside = horiz && vert && port && c.width > 2 && c.height > 2;
                    var sized = rendered + 0.5 >= limit;
                    if (!(owned && inside && sized) && !bad) bad = control.id || '';
                    rows.push({id: control.id || '', hit: owned, inside: inside, sized: sized,
                      w: Math.round(c.width), h: Math.round(c.height)});
                  }
                  var store = -1;
                  try { store = window.localStorage.length; } catch (err) {}
                  return JSON.stringify({
                    zoom: zoom,
                    pan: board ? (board.getAttribute('data-hub-pan') || '') : '',
                    writes: window.__hubZoomWrites || 0,
                    apparent: parseInt(board ? (board.getAttribute('data-hub-apparent') || '0') : '0', 10) || 0,
                    hostId: host && host.id ? host.id : '',
                    hostLeft: host ? host.scrollLeft : 0,
                    hostScroll: host ? host.scrollWidth : 0,
                    hostClient: host ? host.clientWidth : 0,
                    rightPast: rightPast,
                    pageX: page ? page.scrollLeft : 0,
                    bodyX: getComputedStyle(document.body).overflowX,
                    cols: cols,
                    slots: slots,
                    gridFit: grid ? (grid.getAttribute('data-hub-fit') || '') : '',
                    count: rows.length,
                    bad: bad,
                    store: store,
                    iw: window.innerWidth || 0,
                    controls: rows,
                    error: ''
                  });
                } catch (e) {
                  return JSON.stringify({error: String(e), count: 0, controls: []});
                }
              };
              return 'ready';
            })()
        """.trimIndent()
    }
}
