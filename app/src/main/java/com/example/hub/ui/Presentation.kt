package com.example.hub.ui

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Last presentation sample read back from the page.
 * [textZoom] is the WebView setting. The other fields come from JavaScript.
 */
data class PresentationAck(
    val ok: Boolean,
    val mode: PageScaleMode,
    val requestedPageZoom: Int,
    val rootZoom: String,
    val bodyZoom: String,
    val rootComputed: String,
    val bodyComputed: String,
    val viewportScale: Double?,
    val innerWidth: Int,
    val innerHeight: Int,
    val devicePixelRatio: Double,
    val scrollX: Int,
    val scrollY: Int,
    val visualWidth: Double?,
    val visualHeight: Double?,
    val gameFitEnabled: Boolean?,
    val fitRatio: Double?,
    val maxRight: Int,
    val textZoom: Int,
)

const val FE_PRESENTATION_STYLE_ID = "hub-fe-presentation"

/**
 * Applies one presentation strategy and returns before/after metrics.
 * It does not navigate, and it does not call the game's save routine.
 *
 * [flush] reassigns zoom so a fold can repaint a stale magnified frame.
 * FE000000 keeps the hub percent on body zoom. Root zoom on that page changes the layout viewport.
 */
fun presentationScript(mode: PageScaleMode, pageZoomPercent: Int, flush: Boolean): String {
    val literal = zoomLiteral(pageZoomPercent)
    val css = JSONObject.quote(if (mode == PageScaleMode.READABLE) readableCss() else "")
    val modeName = mode.stored()
    val flushFlag = if (flush) "true" else "false"
    return "(function(){ try {" +
        " function snap(){" +
        " var vv = window.visualViewport;" +
        " var root = document.documentElement;" +
        " var body = document.body;" +
        " var maxRight = 0;" +
        " if (body) {" +
        " var nodes = body.querySelectorAll('button, .tab-button, .shark-upgrade, .autobuyerdiv');" +
        " for (var i = 0; i < nodes.length; i++) {" +
        " var rect = nodes[i].getBoundingClientRect();" +
        " if (rect.right > maxRight) maxRight = rect.right; } }" +
        " var fit = null;" +
        " try { if (typeof Options !== 'undefined' && Options.fitToWidth) fit = !!Options.fitToWidth(); } catch (e) {}" +
        " var would = null;" +
        " if (fit) would = Math.min((window.innerWidth || 0) / 1376, 1);" +
        " return {iw: window.innerWidth || 0, ih: window.innerHeight || 0, dpr: window.devicePixelRatio || 0," +
        " sx: Math.round(window.scrollX || 0), sy: Math.round(window.scrollY || 0)," +
        " vw: vv ? vv.width : null, vh: vv ? vv.height : null, vs: vv ? vv.scale : null," +
        " root: root && root.style ? (root.style.zoom || '') : ''," +
        " body: body && body.style ? (body.style.zoom || '') : ''," +
        " rootCss: root ? String(getComputedStyle(root).zoom || '') : ''," +
        " bodyCss: body ? String(getComputedStyle(body).zoom || '') : ''," +
        " fit: fit, would: would, maxRight: Math.round(maxRight)}; }" +
        " var before = snap();" +
        " var mode = '$modeName'; var literal = '$literal'; var flush = $flushFlag; var css = $css;" +
        " var root = document.documentElement; var body = document.body;" +
        " if (!root) return JSON.stringify({ok:false, reason:'no-root', before:before, after:before, mode:mode, want:literal});" +
        " var node = document.getElementById('$FE_PRESENTATION_STYLE_ID');" +
        " if (typeof maybeFitToWidth === 'function' && !window.__hubOriginalFit) window.__hubOriginalFit = maybeFitToWidth;" +
        " if (mode === 'readable') {" +
        " if (!node) { node = document.createElement('style'); node.id = '$FE_PRESENTATION_STYLE_ID';" +
        " (document.head || root).appendChild(node); }" +
        " node.textContent = css;" +
        " root.style.zoom = '';" +
        " root.style.setProperty('--hub-screen', ((screen && screen.width) || window.innerWidth) + 'px');" +
        " window.maybeFitToWidth = function() {" +
        " document.documentElement.style.zoom = '';" +
        " if (document.body) { document.body.style.zoom = literal; document.body.style['-moz-transform'] = ''; }" +
        " var cap = (screen && screen.width) ? screen.width : window.innerWidth;" +
        " document.documentElement.style.setProperty('--hub-screen', cap + 'px');" +
        " };" +
        " window.maybeFitToWidth();" +
        " if (body) body.style.zoom = literal; void root.offsetWidth;" +
        " } else if (mode === 'game-fit') {" +
        " if (node && node.parentNode) node.parentNode.removeChild(node);" +
        " if (window.__hubOriginalFit) window.maybeFitToWidth = window.__hubOriginalFit;" +
        " root.style.zoom = '';" +
        " if (typeof maybeFitToWidth === 'function') maybeFitToWidth();" +
        " } else {" +
        " if (node && node.parentNode) node.parentNode.removeChild(node);" +
        " root.style.zoom = literal; void root.offsetWidth;" +
        " if (flush) { void root.getBoundingClientRect(); }" +
        " }" +
        " var after = snap();" +
        " var want = parseFloat(literal);" +
        " var ok = false;" +
        " if (mode === 'game-fit') {" +
        " var ratio = parseFloat(after.body);" +
        " var expect = Math.min((after.iw || 0) / 1376, 1);" +
        " ok = after.root === '' && isFinite(ratio) && Math.abs(ratio - expect) < 0.03;" +
        " } else if (mode === 'readable') {" +
        " var appliedReadable = parseFloat(after.body);" +
        " ok = after.root === '' && isFinite(appliedReadable) && Math.abs(appliedReadable - want) < 0.02 && (!after.vs || Math.abs(after.vs - 1) < 0.05);" +
        " } else {" +
        " var appliedHub = parseFloat(after.root);" +
        " ok = isFinite(appliedHub) && Math.abs(appliedHub - want) < 0.02 && (!after.vs || Math.abs(after.vs - 1) < 0.05); }" +
        " return JSON.stringify({ok:ok, mode:mode, want:literal, before:before, after:after});" +
        " } catch (e) { return JSON.stringify({ok:false, reason:String(e).slice(0, 160)}); } })()"
}

fun readableCss(): String = """
    .autobuyerdiv, .tallautobuyerdiv, .autobuyerunlockbutton,
    .options-button, .options-div, .mini-options-button, .mid-options-button,
    .semi-options-button, .minier-options-button, .wide-mini-options-button,
    .challengediv, .infinitychallengediv, .eternitychallengediv,
    .complexitychallengediv, .chromadiv, .oraclediv, .box,
    .eternitymilestonediv, .finalitymilestonediv, .goaldiv, .complexityachievementdiv {
      width: auto !important;
      max-width: calc(100% - 16px) !important;
      height: auto !important;
      box-sizing: border-box;
    }
    .box { left: 8px !important; margin-left: 0 !important; }
    html, body, #main {
      max-width: var(--hub-screen, 100%) !important;
    }
    #main table {
      display: block !important;
      width: 100% !important;
      max-width: var(--hub-screen, 100%) !important;
      margin-left: auto !important;
      margin-right: auto !important;
    }
    #main tr {
      display: flex !important;
      flex-wrap: wrap !important;
      justify-content: center !important;
      align-items: center !important;
      gap: 4px 10px !important;
      max-width: var(--hub-screen, 100%) !important;
    }
    #main td {
      display: block !important;
      width: auto !important;
      max-width: var(--hub-screen, 100%) !important;
    }
    button, .buttonlike {
      max-width: var(--hub-screen, 100%) !important;
      height: auto !important;
      white-space: normal !important;
      box-sizing: border-box;
    }
""".trimIndent()

fun zoomAcknowledged(requestedPercent: Int, actual: String?): Boolean {
    val numeric = actual?.toFloatOrNull() ?: return false
    if (numeric > 2f) return false
    return abs(numeric - clampZoom(requestedPercent) / 100f) < 0.02f
}

fun appliedZoomPercent(ack: PresentationAck): Int? {
    val raw = when (ack.mode) {
        PageScaleMode.READABLE -> ack.bodyZoom.ifBlank { ack.bodyComputed }
        PageScaleMode.GAME_FIT -> ack.bodyZoom
        PageScaleMode.HUB -> ack.rootZoom.ifBlank { ack.rootComputed }
    }
    if (!zoomAcknowledged(100, raw) && raw.toFloatOrNull()?.let { it > 2f } == true) return null
    val numeric = raw.toFloatOrNull() ?: return null
    if (numeric > 2f) return null
    return (numeric * 100).roundToInt()
}

fun parsePresentationAck(raw: String?, requestedPageZoom: Int, textZoom: Int): PresentationAck? {
    val jsonText = unwrapPresentationResult(raw) ?: return null
    val json = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
    val after = json.optJSONObject("after")
    val mode = PageScaleMode.fromStored(json.optString("mode"))
    return PresentationAck(
        ok = json.optBoolean("ok"),
        mode = mode,
        requestedPageZoom = requestedPageZoom,
        rootZoom = after?.optString("root").orEmpty(),
        bodyZoom = after?.optString("body").orEmpty(),
        rootComputed = after?.optString("rootCss").orEmpty(),
        bodyComputed = after?.optString("bodyCss").orEmpty(),
        viewportScale = after?.optDoubleOrNull("vs"),
        innerWidth = after?.optInt("iw") ?: 0,
        innerHeight = after?.optInt("ih") ?: 0,
        devicePixelRatio = after?.optDouble("dpr") ?: 0.0,
        scrollX = after?.optInt("sx") ?: 0,
        scrollY = after?.optInt("sy") ?: 0,
        visualWidth = after?.optDoubleOrNull("vw"),
        visualHeight = after?.optDoubleOrNull("vh"),
        gameFitEnabled = if (after != null && after.has("fit") && !after.isNull("fit")) after.optBoolean("fit") else null,
        fitRatio = after?.optDoubleOrNull("would"),
        maxRight = after?.optInt("maxRight") ?: 0,
        textZoom = textZoom,
    )
}

private fun JSONObject.optDoubleOrNull(name: String): Double? {
    if (!has(name) || isNull(name)) return null
    return optDouble(name)
}

private fun unwrapPresentationResult(raw: String?): String? {
    if (raw.isNullOrBlank() || raw == "null") return null
    return try {
        val value = org.json.JSONArray("[$raw]").opt(0)
        when (value) {
            null, JSONObject.NULL -> null
            is String -> value.takeIf { it.isNotEmpty() }
            else -> value.toString()
        }
    } catch (e: Exception) {
        raw.removeSurrounding("\"").takeIf { it.isNotEmpty() && it != "null" }
    }
}
