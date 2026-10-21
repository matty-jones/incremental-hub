package com.example.hub.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Named Hub colours. Gold and the cool game accents are fixed.
 * Outline is lifted from the starting #354653 so card edges clear 3:1 on Surface.
 */
object HubColors {
    val SpaceBlack = Color(0xFF01060B)
    val Surface = Color(0xFF111B24)
    val SurfaceRaised = Color(0xFF1A2833)
    val Outline = Color(0xFF5A7384)
    val OutlineSubtle = Color(0xFF354653)

    val Gold = Color(0xFFE6B450)
    val GoldLight = Color(0xFFF5D38A)
    val OnGold = Color(0xFF2A1C00)
    val GoldContainer = Color(0xFF3D2E12)

    val Cyan = Color(0xFF45DCEB)
    val Teal = Color(0xFF35C7B7)
    val Azure = Color(0xFF58B9F0)
    val Ice = Color(0xFF81D9E8)

    val TextPrimary = Color(0xFFF0F3F5)
    val TextSecondary = Color(0xFFAEC0C9)

    val Success = Color(0xFF8FCB9B)
    val OnSuccess = Color(0xFF062810)
    val SuccessContainer = Color(0xFF143528)
    val OnSuccessContainer = Color(0xFFC8F5D4)

    val Warning = Color(0xFFFF8C42)
    val WarningContainer = Color(0xFF4A2810)
    val OnWarningContainer = Color(0xFFFFD8C2)

    val Error = Color(0xFFFF8F80)
    val OnError = Color(0xFF3B0907)
    val ErrorContainer = Color(0xFF6E2B28)
    val OnErrorContainer = Color(0xFFFFDAD6)

    val LightCanvas = Color(0xFFF4F7F8)
    val LightSurface = Color(0xFFFFFFFF)
    val LightRaised = Color(0xFFE7EEF2)
    val LightOutline = Color(0xFF5C7380)
    val LightGold = Color(0xFF7A5608)
    val LightGoldContainer = Color(0xFFFFE3A8)
    val LightOnGoldContainer = Color(0xFF2A1C00)
    val LightText = Color(0xFF102028)
    val LightTextSecondary = Color(0xFF3E5560)
    val LightSuccess = Color(0xFF0E6B3A)
    val LightOnSuccess = Color(0xFFFFFFFF)
    val LightSuccessContainer = Color(0xFFD7F3E3)
    val LightOnSuccessContainer = Color(0xFF0C3D22)
    val LightWarning = Color(0xFF9A3412)
    val LightWarningContainer = Color(0xFFFFE4D0)
    val LightOnWarningContainer = Color(0xFF4A1E08)

    val GameAccents = listOf(Cyan, Teal, Azure, Ice)
}

@Composable
private fun hubIsDark(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

@Composable
fun hubSuccess(): Color = if (hubIsDark()) HubColors.Success else HubColors.LightSuccess

@Composable
fun hubOnSuccess(): Color = if (hubIsDark()) HubColors.OnSuccess else HubColors.LightOnSuccess

@Composable
fun hubSuccessContainer(): Color =
    if (hubIsDark()) HubColors.SuccessContainer else HubColors.LightSuccessContainer

@Composable
fun hubOnSuccessContainer(): Color =
    if (hubIsDark()) HubColors.OnSuccessContainer else HubColors.LightOnSuccessContainer

@Composable
fun hubWarning(): Color = if (hubIsDark()) HubColors.Warning else HubColors.LightWarning

@Composable
fun hubWarningContainer(): Color =
    if (hubIsDark()) HubColors.WarningContainer else HubColors.LightWarningContainer

@Composable
fun hubOnWarningContainer(): Color =
    if (hubIsDark()) HubColors.OnWarningContainer else HubColors.LightOnWarningContainer
