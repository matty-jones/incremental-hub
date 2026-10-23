package com.example.hub.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresentationTest {

    @Test
    fun hubScriptSetsZoomWithoutTouchingTheSave() {
        val script = presentationScript(PageScaleMode.HUB, 110, flush = false)
        assertTrue(script.contains("1.10"))
        assertFalse(script.contains("loadUrl"))
        assertFalse(script.contains("localStorage"))
        assertFalse(script.contains("save("))
        assertFalse(script.contains("toggleFitToWidth"))
        assertTrue(zoomAcknowledged(110, "1.10"))
        assertTrue(zoomAcknowledged(100, "1"))
        assertFalse(zoomAcknowledged(100, "2.625"))
    }

    @Test
    fun readableLayoutOverridesFitWithoutWritingTheSave() {
        val script = presentationScript(PageScaleMode.READABLE, 125, flush = true)
        assertTrue(script.contains("1.25"))
        assertTrue(script.contains("!important"))
        assertTrue(script.contains(FE_PRESENTATION_STYLE_ID))
        assertTrue(script.contains("maybeFitToWidth"))
        assertTrue(script.contains("autobuyerdiv"))
        assertFalse(script.contains("toggleFitToWidth"))
        assertFalse(script.contains("Saving"))
        assertFalse(script.contains("localStorage"))
    }

    @Test
    fun gameFitClearsTheHubZoom() {
        val script = presentationScript(PageScaleMode.GAME_FIT, 100, flush = false)
        assertTrue(script.contains("game-fit"))
        assertTrue(script.contains("removeChild"))
        assertTrue(script.contains("maybeFitToWidth"))
        assertFalse(script.contains("!important"))
        assertFalse(script.contains("toggleFitToWidth"))
    }

    @Test
    fun parserReadsTheAppliedZoom() {
        val payload = """{"ok":true,"mode":"readable","want":"1.00","after":{"root":"","body":"1","rootCss":"1","bodyCss":"1","vs":1,"iw":842,"ih":645,"dpr":2.625,"sx":0,"sy":12,"vw":842,"vh":645,"fit":true,"would":0.611919,"maxRight":400}}"""
        val ack = parsePresentationAck(JSONObject.quote(payload), requestedPageZoom = 100, textZoom = 90)
        assertNotNull(ack)
        assertTrue(ack!!.ok)
        assertEquals(PageScaleMode.READABLE, ack.mode)
        assertEquals(100, appliedZoomPercent(ack))
        assertEquals(90, ack.textZoom)
        assertEquals(true, ack.gameFitEnabled)
        assertEquals(12, ack.scrollY)
        assertEquals(
            "Requested page 100%. Applied body zoom 100%.",
            pageZoomDetail(100, PageScaleMode.READABLE, ack),
        )
        assertTrue(readableOverrideDetail(ack).contains("61%"))
        assertTrue(readableOverrideDetail(ack).contains("without changing the save"))
        assertEquals("Viewport scale 1.00.", viewportDetail(1.0))
        assertTrue(viewportDetail(1.4).contains("Reconciling"))
    }

    @Test
    fun deviceScaleIsNotShownAsPageZoom() {
        val payload = """{"ok":false,"mode":"hub","after":{"root":"","body":"","rootCss":"2.625","bodyCss":"1","vs":1,"iw":842,"ih":645,"dpr":2.625,"sx":0,"sy":0,"fit":null,"maxRight":10}}"""
        val ack = parsePresentationAck(payload, requestedPageZoom = 100, textZoom = 100)
        assertNotNull(ack)
        assertEquals(null, appliedZoomPercent(ack!!))
        assertTrue(pageZoomDetail(100, PageScaleMode.HUB, ack).contains("waiting"))
    }

    @Test
    fun zoomMatchOnThePreviousViewportIsNotSettled() {
        val cover = sample(innerWidth = 411, innerHeight = 749)
        assertEquals(
            FAULT_NONE,
            assessPresentation(cover, viewWidthPx = 1080, viewHeightPx = 1966, density = 2.625f, pageScale = 1f, parentWidthPx = 1080),
        )
        assertEquals(
            FAULT_STALE_VIEWPORT,
            assessPresentation(cover, viewWidthPx = 2208, viewHeightPx = 1700, density = 2.625f, pageScale = 1f, parentWidthPx = 2208),
        )
        assertTrue(presentationSettled(FAULT_NONE))
        assertFalse(presentationSettled(FAULT_STALE_VIEWPORT))
    }

    @Test
    fun nativeSizeAndStuckFrameAreDistinctFromOverflow() {
        val cover = sample(innerWidth = 411, innerHeight = 749)
        assertEquals(
            FAULT_NATIVE,
            assessPresentation(cover, viewWidthPx = 0, viewHeightPx = 0, density = 2.625f, pageScale = 1f, parentWidthPx = 1080),
        )
        assertEquals(
            FAULT_NATIVE,
            assessPresentation(cover, viewWidthPx = 400, viewHeightPx = 800, density = 2.625f, pageScale = 1f, parentWidthPx = 1080),
        )
        val magnified = sample(innerWidth = 411, innerHeight = 749, scale = 1.8)
        assertEquals(
            FAULT_STALE_FRAME,
            assessPresentation(magnified, viewWidthPx = 1080, viewHeightPx = 1966, density = 2.625f, pageScale = 1f, parentWidthPx = 1080),
        )
        val densityAsScale = sample(innerWidth = 411, innerHeight = 749)
        assertEquals(
            FAULT_NONE,
            assessPresentation(densityAsScale, viewWidthPx = 1080, viewHeightPx = 1966, density = 2.625f, pageScale = 2.625f, parentWidthPx = 1080),
        )
        val wide = sample(innerWidth = 411, innerHeight = 749, scrollWidth = 900)
        assertEquals(
            FAULT_OVERFLOW,
            assessPresentation(wide, viewWidthPx = 1080, viewHeightPx = 1966, density = 2.625f, pageScale = 1f, parentWidthPx = 1080),
        )
        assertTrue(presentationSettled(FAULT_OVERFLOW))
        val fitted = sample(innerWidth = 980, innerHeight = 1700, scale = 0.42, visualWidth = 980, visualHeight = 1700)
        assertEquals(
            FAULT_NONE,
            assessPresentation(fitted, viewWidthPx = 1080, viewHeightPx = 1866, density = 2.625f, pageScale = 1f, parentWidthPx = 1080),
        )
    }

    @Test
    fun sharkUnlockClearsZoomAndRewritesTheViewport() {
        val unlock = viewportUnlockScript()
        assertTrue(unlock.contains("width=device-width, initial-scale=1"))
        assertTrue(unlock.contains("root.style.zoom = ''"))
        assertTrue(unlock.contains("requestAnimationFrame"))
        assertFalse(unlock.contains("loadUrl"))
        assertFalse(unlock.contains("localStorage"))
        assertFalse(unlock.contains("save("))
        val held = presentationScript(
            PageScaleMode.HUB,
            100,
            flush = true,
            expectWidthCss = 842,
            expectHeightCss = 645,
            applyZoom = false,
        )
        assertTrue(held.contains("var applyZoom = false"))
        assertTrue(viewportReadyForZoom(FAULT_ZOOM))
        assertTrue(viewportReadyForZoom(FAULT_NONE))
        assertFalse(viewportReadyForZoom(FAULT_STALE_VIEWPORT))
    }

    @Test
    fun scriptNamesViewportFaultsWithoutNavigation() {
        val script = presentationScript(PageScaleMode.HUB, 100, flush = true, expectWidthCss = 842, expectHeightCss = 645)
        assertTrue(script.contains("842"))
        assertTrue(script.contains("645"))
        assertTrue(script.contains("stale-viewport"))
        assertTrue(script.contains("stale-frame"))
        assertTrue(script.contains("css-overflow"))
        assertFalse(script.contains("loadUrl"))
        assertFalse(script.contains("reload"))
        assertFalse(script.contains("localStorage"))
    }

    private fun sample(
        innerWidth: Int,
        innerHeight: Int,
        scale: Double = 1.0,
        scrollWidth: Int = innerWidth,
        visualWidth: Int = innerWidth,
        visualHeight: Int = innerHeight,
    ): PresentationAck {
        val payload = """{"ok":true,"mode":"hub","fault":"none","after":{"root":"1","body":"","rootCss":"1","bodyCss":"1","vs":$scale,"iw":$innerWidth,"ih":$innerHeight,"dpr":2.625,"vw":$visualWidth,"vh":$visualHeight,"sw":$scrollWidth,"cw":$innerWidth,"sx":0,"sy":0}}"""
        return parsePresentationAck(payload, requestedPageZoom = 100, textZoom = 100)!!
    }
}
