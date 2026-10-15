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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

val HubWideWidth = 600.dp
val HubChipCorner = 20.dp
val HubSheetCorner = 28.dp

private val HubDarkColorScheme = darkColorScheme(
    primary = Color(0xFFE6B450),
    onPrimary = Color(0xFF2A1C00),
    primaryContainer = Color(0xFF4A3510),
    onPrimaryContainer = Color(0xFFFFE6B0),
    inversePrimary = Color(0xFF8C5E00),
    secondary = Color(0xFF9AA4B5),
    onSecondary = Color(0xFF1A1E26),
    secondaryContainer = Color(0xFF2A3140),
    onSecondaryContainer = Color(0xFFD5DBE6),
    tertiary = Color(0xFFD7C4A3),
    onTertiary = Color(0xFF2A2116),
    tertiaryContainer = Color(0xFF3E3426),
    onTertiaryContainer = Color(0xFFF3E6D0),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE8E6E3),
    surface = Color(0xFF171B22),
    onSurface = Color(0xFFE8E6E3),
    surfaceVariant = Color(0xFF222833),
    onSurfaceVariant = Color(0xFFB7B3AC),
    surfaceTint = Color(0xFFE6B450),
    inverseSurface = Color(0xFFE8E6E3),
    inverseOnSurface = Color(0xFF1C1B19),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF3A4150),
    outlineVariant = Color(0xFF2A3140),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF3A4150),
    surfaceDim = Color(0xFF0E1116),
    surfaceContainerLowest = Color(0xFF090B0E),
    surfaceContainerLow = Color(0xFF13171E),
    surfaceContainer = Color(0xFF171B22),
    surfaceContainerHigh = Color(0xFF222833),
    surfaceContainerHighest = Color(0xFF2C3442),
)

private val HubLightColorScheme = lightColorScheme(
    primary = Color(0xFF8C5E00),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDDA8),
    onPrimaryContainer = Color(0xFF2A1C00),
    inversePrimary = Color(0xFFE6B450),
    secondary = Color(0xFF545D6E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD8DEEA),
    onSecondaryContainer = Color(0xFF1A1E26),
    tertiary = Color(0xFF6B5432),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF3E6D0),
    onTertiaryContainer = Color(0xFF2A2116),
    background = Color(0xFFF7F4EF),
    onBackground = Color(0xFF1C1B19),
    surface = Color(0xFFFFFCF8),
    onSurface = Color(0xFF1C1B19),
    surfaceVariant = Color(0xFFE7E1D8),
    onSurfaceVariant = Color(0xFF4A453C),
    surfaceTint = Color(0xFF8C5E00),
    inverseSurface = Color(0xFF31302D),
    inverseOnSurface = Color(0xFFF7F4EF),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF7C766C),
    outlineVariant = Color(0xFFD0C9BE),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFFCF8),
    surfaceDim = Color(0xFFE7E1D8),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F4EF),
    surfaceContainer = Color(0xFFF1EDE6),
    surfaceContainerHigh = Color(0xFFEBE6DE),
    surfaceContainerHighest = Color(0xFFE5DFD6),
)

private val BaseTypography = Typography()

val HubTypography = Typography(
    headlineSmall = BaseTypography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = BaseTypography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = BaseTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = BaseTypography.titleSmall.copy(fontWeight = FontWeight.Medium),
    labelLarge = BaseTypography.labelLarge.copy(fontWeight = FontWeight.Medium),
)

val HubShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(HubSheetCorner),
    extraLarge = RoundedCornerShape(HubSheetCorner),
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
