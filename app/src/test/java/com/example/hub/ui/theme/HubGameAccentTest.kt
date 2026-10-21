package com.example.hub.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HubGameAccentTest {

    @Test
    fun purpleSourceColourDoesNotPaintFe000000() {
        assertEquals(HubColors.Cyan.toArgb(), HubGameAccent.forGame("fe000000").toArgb())
        assertNotEquals(Color(0xFF4A148C).toArgb(), HubGameAccent.forGame("fe000000").toArgb())
    }

    @Test
    fun sharkDefinitionBlueDoesNotPaintShark() {
        assertEquals(HubColors.Azure.toArgb(), HubGameAccent.forGame("shark-incremental").toArgb())
        assertNotEquals(Color(0xFF1565C0).toArgb(), HubGameAccent.forGame("shark-incremental").toArgb())
    }

    @Test
    fun explicitAccentsIgnoreCallOrder() {
        val sharkFirst = HubGameAccent.forGame("shark-incremental").toArgb()
        val feSecond = HubGameAccent.forGame("fe000000").toArgb()
        val feFirst = HubGameAccent.forGame("fe000000").toArgb()
        val sharkSecond = HubGameAccent.forGame("shark-incremental").toArgb()
        assertEquals(HubColors.Azure.toArgb(), sharkFirst)
        assertEquals(HubColors.Cyan.toArgb(), feSecond)
        assertEquals(feFirst, feSecond)
        assertEquals(sharkFirst, sharkSecond)
    }

    @Test
    fun unknownIdsStayInTheCoolFamilyAndAreStable() {
        val ids = listOf("future-game", "zeta", "alpha")
        val forward = ids.map { HubGameAccent.forGame(it).toArgb() }
        val reverse = ids.asReversed().map { HubGameAccent.forGame(it).toArgb() }.asReversed()
        assertEquals(forward, reverse)
        val allowed = HubColors.GameAccents.map { it.toArgb() }.toSet()
        forward.forEach { accent ->
            assertTrue(accent in allowed)
            assertNotEquals(HubColors.Gold.toArgb(), accent)
        }
        assertEquals(forward[0], HubGameAccent.forGame("future-game").toArgb())
    }
}
