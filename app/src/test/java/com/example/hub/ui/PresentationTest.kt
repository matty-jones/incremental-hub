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
}
