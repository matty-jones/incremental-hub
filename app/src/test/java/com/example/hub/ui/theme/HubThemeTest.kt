package com.example.hub.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnitType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HubThemeTest {

    @Test
    fun defaultSchemeIsDarkSpaceBlackAndGold() {
        val scheme = hubColorScheme()
        assertTrue(scheme.background.luminance() < 0.2f)
        assertTrue(scheme.onBackground.luminance() > 0.7f)
        assertEquals(HubColors.SpaceBlack.toArgb(), scheme.background.toArgb())
        assertEquals(HubColors.Gold.toArgb(), scheme.primary.toArgb())
        assertEquals(HubColors.OnGold.toArgb(), scheme.onPrimary.toArgb())
        assertEquals(HubColors.TextPrimary.toArgb(), scheme.onSurface.toArgb())
        assertEquals(HubColors.TextSecondary.toArgb(), scheme.onSurfaceVariant.toArgb())
        assertEquals(HubColors.Surface.toArgb(), scheme.surface.toArgb())
        assertEquals(HubColors.SurfaceRaised.toArgb(), scheme.surfaceContainerHigh.toArgb())
        assertNotEquals(HubColors.Gold.toArgb(), scheme.surfaceTint.toArgb())
    }

    @Test
    fun textAndStatusContrastClearsBodyThreshold() {
        val scheme = hubColorScheme()
        assertTrue(contrast(scheme.onBackground, scheme.background) >= 4.5f)
        assertTrue(contrast(scheme.onSurface, scheme.surface) >= 4.5f)
        assertTrue(contrast(scheme.onSurfaceVariant, scheme.surface) >= 4.5f)
        assertTrue(contrast(scheme.onPrimary, scheme.primary) >= 4.5f)
        assertTrue(contrast(scheme.onError, scheme.error) >= 4.5f)
        assertTrue(contrast(scheme.error, scheme.surface) >= 4.5f)
        assertTrue(contrast(scheme.onErrorContainer, scheme.errorContainer) >= 4.5f)
        assertTrue(contrast(HubColors.Success, scheme.surface) >= 4.5f)
        assertTrue(contrast(HubColors.OnSuccessContainer, HubColors.SuccessContainer) >= 4.5f)
        assertTrue(contrast(HubColors.Warning, scheme.surface) >= 4.5f)
        assertTrue(contrast(HubColors.OnWarningContainer, HubColors.WarningContainer) >= 4.5f)
        assertTrue(contrast(scheme.outline, scheme.surface) >= 3f)
        assertNotEquals(HubColors.Gold.toArgb(), HubColors.Warning.toArgb())
    }

    @Test
    fun lightSchemeStaysReadable() {
        val scheme = hubColorScheme(darkTheme = false)
        assertTrue(scheme.background.luminance() > scheme.onBackground.luminance())
        assertTrue(contrast(scheme.onBackground, scheme.background) >= 4.5f)
        assertTrue(contrast(scheme.onSurface, scheme.surface) >= 4.5f)
        assertTrue(contrast(scheme.onSurfaceVariant, scheme.surface) >= 4.5f)
        assertTrue(contrast(scheme.onPrimary, scheme.primary) >= 4.5f)
        assertTrue(contrast(scheme.error, scheme.surface) >= 4.5f)
        assertEquals(HubColors.LightGold.toArgb(), scheme.primary.toArgb())
        assertTrue(contrast(HubColors.LightSuccess, scheme.surface) >= 4.5f)
        assertTrue(contrast(HubColors.LightWarning, scheme.surface) >= 4.5f)
        assertTrue(contrast(HubColors.LightOnSuccessContainer, HubColors.LightSuccessContainer) >= 4.5f)
    }

    @Test
    fun typographyUsesBundledManropeOnEveryRole() {
        val roles = listOf(
            HubTypography.displayLarge,
            HubTypography.displayMedium,
            HubTypography.displaySmall,
            HubTypography.headlineLarge,
            HubTypography.headlineMedium,
            HubTypography.headlineSmall,
            HubTypography.titleLarge,
            HubTypography.titleMedium,
            HubTypography.titleSmall,
            HubTypography.bodyLarge,
            HubTypography.bodyMedium,
            HubTypography.bodySmall,
            HubTypography.labelLarge,
            HubTypography.labelMedium,
            HubTypography.labelSmall,
        )
        roles.forEach { style ->
            assertEquals(HubFontFamily, style.fontFamily)
        }
        assertEquals(FontWeight.SemiBold, HubTypography.headlineSmall.fontWeight)
        assertEquals(FontWeight.SemiBold, HubTypography.titleLarge.fontWeight)
        assertEquals(FontWeight.Medium, HubTypography.labelLarge.fontWeight)
        assertEquals(FontWeight.Normal, HubTypography.bodyLarge.fontWeight)
    }

    @Test
    fun brandTrackingStaysOffBodyCopy() {
        assertEquals(HubFontFamily, HubBrandTitle.fontFamily)
        assertEquals(FontWeight.SemiBold, HubBrandTitle.fontWeight)
        assertEquals(TextUnitType.Em, HubBrandTitle.letterSpacing.type)
        assertEquals(0.12f, HubBrandTitle.letterSpacing.value, 0.001f)
        val body = HubTypography.bodyLarge.letterSpacing
        val bodyIsWideEm = body.type == TextUnitType.Em && body.value >= 0.10f
        assertFalse(bodyIsWideEm)
        assertFalse(HubTypography.labelSmall.letterSpacing.let { it.type == TextUnitType.Em && it.value >= 0.10f })
    }

    @Test
    fun bundledFacesUseDistinctWeights() {
        assertEquals(
            listOf(400, 500, 600, 700),
            HubFontFaces.map { it.first.weight },
        )
        assertEquals(4, HubFontFaces.map { it.second }.distinct().size)
    }

    private fun contrast(foreground: Color, background: Color): Float {
        val lighter = maxOf(foreground.luminance(), background.luminance())
        val darker = minOf(foreground.luminance(), background.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }
}
