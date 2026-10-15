package com.example.hub.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FoldMetricsTest {

    @Test
    fun pxToDpUsesDisplayDensity() {
        assertEquals(160f, pxToDp(px = 480, density = 3f), 0.001f)
        assertEquals(1080f / 2.625f, pxToDp(px = 1080, density = 2.625f), 0.001f)
    }

    @Test
    fun pxToDpDoesNotTreat160AsTheDensity() {
        val px = 480
        val density = 3f
        val wrongMdpiGuess = px / 160f
        assertEquals(3f, wrongMdpiGuess, 0.001f)
        assertEquals(160f, pxToDp(px, density), 0.001f)
    }
}
