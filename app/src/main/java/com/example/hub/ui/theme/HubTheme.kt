package com.example.hub.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.example.hub.R

val HubWideWidth = 600.dp
val HubChipCorner = 20.dp
val HubSheetCorner = 28.dp

internal val HubFontFaces = listOf(
    FontWeight.Normal to R.font.manrope_regular,
    FontWeight.Medium to R.font.manrope_medium,
    FontWeight.SemiBold to R.font.manrope_semibold,
    FontWeight.Bold to R.font.manrope_bold,
)

val HubFontFamily = FontFamily(
    HubFontFaces.map { (weight, resId) -> Font(resId, weight) },
)

private val BaseTypography = Typography()

private fun TextStyle.hub(weight: FontWeight = fontWeight ?: FontWeight.Normal): TextStyle =
    copy(fontFamily = HubFontFamily, fontWeight = weight)

val HubTypography = Typography(
    displayLarge = BaseTypography.displayLarge.hub(),
    displayMedium = BaseTypography.displayMedium.hub(),
    displaySmall = BaseTypography.displaySmall.hub(),
    headlineLarge = BaseTypography.headlineLarge.hub(FontWeight.SemiBold),
    headlineMedium = BaseTypography.headlineMedium.hub(FontWeight.SemiBold),
    headlineSmall = BaseTypography.headlineSmall.hub(FontWeight.SemiBold),
    titleLarge = BaseTypography.titleLarge.hub(FontWeight.SemiBold),
    titleMedium = BaseTypography.titleMedium.hub(FontWeight.SemiBold),
    titleSmall = BaseTypography.titleSmall.hub(FontWeight.Medium),
    bodyLarge = BaseTypography.bodyLarge.hub(),
    bodyMedium = BaseTypography.bodyMedium.hub(),
    bodySmall = BaseTypography.bodySmall.hub(),
    labelLarge = BaseTypography.labelLarge.hub(FontWeight.Medium),
    labelMedium = BaseTypography.labelMedium.hub(),
    labelSmall = BaseTypography.labelSmall.hub(),
)

/**
 * Launcher wordmark only. SemiBold, 22sp, wide tracking.
 * Other copy uses [HubTypography] without this spacing.
 */
val HubBrandTitle = TextStyle(
    fontFamily = HubFontFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = 22.sp,
    lineHeight = 28.sp,
    letterSpacing = 0.12.em,
)

val HubShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(HubSheetCorner),
    extraLarge = RoundedCornerShape(HubSheetCorner),
)

private val HubDarkColorScheme = darkColorScheme(
    primary = HubColors.Gold,
    onPrimary = HubColors.OnGold,
    primaryContainer = HubColors.GoldContainer,
    onPrimaryContainer = HubColors.GoldLight,
    inversePrimary = HubColors.LightGold,
    secondary = HubColors.TextSecondary,
    onSecondary = HubColors.SpaceBlack,
    secondaryContainer = HubColors.SurfaceRaised,
    onSecondaryContainer = HubColors.TextPrimary,
    tertiary = HubColors.Cyan,
    onTertiary = HubColors.SpaceBlack,
    tertiaryContainer = Color(0xFF0E3038),
    onTertiaryContainer = HubColors.Ice,
    background = HubColors.SpaceBlack,
    onBackground = HubColors.TextPrimary,
    surface = HubColors.Surface,
    onSurface = HubColors.TextPrimary,
    surfaceVariant = Color(0xFF223442),
    onSurfaceVariant = HubColors.TextSecondary,
    surfaceTint = HubColors.SpaceBlack,
    inverseSurface = HubColors.TextPrimary,
    inverseOnSurface = HubColors.SpaceBlack,
    error = HubColors.Error,
    onError = HubColors.OnError,
    errorContainer = HubColors.ErrorContainer,
    onErrorContainer = HubColors.OnErrorContainer,
    outline = HubColors.Outline,
    outlineVariant = HubColors.OutlineSubtle,
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF223442),
    surfaceDim = HubColors.SpaceBlack,
    surfaceContainerLowest = HubColors.SpaceBlack,
    surfaceContainerLow = Color(0xFF0A1218),
    surfaceContainer = HubColors.Surface,
    surfaceContainerHigh = HubColors.SurfaceRaised,
    surfaceContainerHighest = Color(0xFF223442),
)

private val HubLightColorScheme = lightColorScheme(
    primary = HubColors.LightGold,
    onPrimary = Color.White,
    primaryContainer = HubColors.LightGoldContainer,
    onPrimaryContainer = HubColors.LightOnGoldContainer,
    inversePrimary = HubColors.Gold,
    secondary = HubColors.LightTextSecondary,
    onSecondary = Color.White,
    secondaryContainer = HubColors.LightRaised,
    onSecondaryContainer = HubColors.LightText,
    tertiary = Color(0xFF0E6E78),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD4F4F8),
    onTertiaryContainer = Color(0xFF063036),
    background = HubColors.LightCanvas,
    onBackground = HubColors.LightText,
    surface = HubColors.LightSurface,
    onSurface = HubColors.LightText,
    surfaceVariant = HubColors.LightRaised,
    onSurfaceVariant = HubColors.LightTextSecondary,
    surfaceTint = HubColors.LightCanvas,
    inverseSurface = HubColors.LightText,
    inverseOnSurface = HubColors.LightCanvas,
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = HubColors.LightOutline,
    outlineVariant = Color(0xFFD5E0E6),
    scrim = Color(0xFF000000),
    surfaceBright = HubColors.LightSurface,
    surfaceDim = HubColors.LightRaised,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = HubColors.LightCanvas,
    surfaceContainer = Color(0xFFEEF3F6),
    surfaceContainerHigh = HubColors.LightRaised,
    surfaceContainerHighest = Color(0xFFDDE6EB),
)

fun hubColorScheme(darkTheme: Boolean = true): ColorScheme =
    if (darkTheme) HubDarkColorScheme else HubLightColorScheme

/**
 * Dark is the default. Pass [darkTheme] false to opt into the light scheme.
 * The system setting is ignored so a light-mode device still opens dark.
 */
@Composable
fun HubTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context.findActivity() ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(activity.window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = hubColorScheme(darkTheme),
        typography = HubTypography,
        shapes = HubShapes,
        content = content,
    )
}

fun ComponentActivity.enableHubEdgeToEdge() {
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
    )
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
