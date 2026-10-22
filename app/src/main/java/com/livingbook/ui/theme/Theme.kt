package com.livingbook.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.livingbook.data.ReadingFont
import com.livingbook.data.ThemeMode

// Fixed warm palette instead of dynamic color: the book should look like paper and ink on every phone.
private val LightColors = lightColorScheme(
    primary = Color(0xFF2F4A43),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD3E5DD),
    onPrimaryContainer = Color(0xFF0E2B24),
    inversePrimary = Color(0xFFA9CCBF),
    secondary = Color(0xFF7A4F12),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF6DDB8),
    onSecondaryContainer = Color(0xFF2B2A27),
    tertiary = Color(0xFF7A4F12),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF6DDB8),
    onTertiaryContainer = Color(0xFF2B2A27),
    background = Color(0xFFFBF7F0),
    onBackground = Color(0xFF2B2A27),
    surface = Color(0xFFFBF7F0),
    onSurface = Color(0xFF2B2A27),
    surfaceVariant = Color(0xFFEFE8DC),
    onSurfaceVariant = Color(0xFF57534C),
    surfaceTint = Color(0xFF2F4A43),
    inverseSurface = Color(0xFF33312D),
    inverseOnSurface = Color(0xFFF4EFE6),
    outline = Color(0xFF878176),
    outlineVariant = Color(0xFFD9D1C3),
    surfaceBright = Color(0xFFFBF7F0),
    surfaceDim = Color(0xFFE3DCD0),
    surfaceContainerLowest = Color(0xFFFFFDF9),
    surfaceContainerLow = Color(0xFFF7F2EA),
    surfaceContainer = Color(0xFFF3EDE3),
    surfaceContainerHigh = Color(0xFFEEE7DC),
    surfaceContainerHighest = Color(0xFFE8E1D5),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9CCBF),
    onPrimary = Color(0xFF12332B),
    primaryContainer = Color(0xFF2F4A43),
    onPrimaryContainer = Color(0xFFD3E8DF),
    inversePrimary = Color(0xFF2F4A43),
    secondary = Color(0xFFE0A458),
    onSecondary = Color(0xFF3A2600),
    secondaryContainer = Color(0xFF5C4019),
    onSecondaryContainer = Color(0xFFFCE3C2),
    tertiary = Color(0xFFE0A458),
    onTertiary = Color(0xFF3A2600),
    tertiaryContainer = Color(0xFF5C4019),
    onTertiaryContainer = Color(0xFFFCE3C2),
    background = Color(0xFF151413),
    onBackground = Color(0xFFE8E2D6),
    surface = Color(0xFF151413),
    onSurface = Color(0xFFE8E2D6),
    surfaceVariant = Color(0xFF2E2B27),
    onSurfaceVariant = Color(0xFFCDC5B8),
    surfaceTint = Color(0xFFA9CCBF),
    inverseSurface = Color(0xFFE8E2D6),
    inverseOnSurface = Color(0xFF33312D),
    outline = Color(0xFF9A9388),
    outlineVariant = Color(0xFF4A463F),
    surfaceBright = Color(0xFF3B3835),
    surfaceDim = Color(0xFF151413),
    surfaceContainerLowest = Color(0xFF100F0E),
    surfaceContainerLow = Color(0xFF1D1B19),
    surfaceContainer = Color(0xFF221F1D),
    surfaceContainerHigh = Color(0xFF2C2926),
    surfaceContainerHighest = Color(0xFF373330),
)

fun ThemeMode.isDark(systemDark: Boolean): Boolean = when (this) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/** App theme. Also keeps the system bar icons readable when the chosen theme differs from the system one. */
@Composable
fun LivingBookTheme(theme: ThemeMode, content: @Composable () -> Unit) {
    val dark = theme.isDark(isSystemInDarkTheme())
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}

val ReadingFont.family: FontFamily get() = if (this == ReadingFont.SERIF) FontFamily.Serif else FontFamily.SansSerif

/** Body style for book text: 18sp times the user's scale, generous line height. */
fun readingStyle(font: ReadingFont, scale: Float): TextStyle {
    val size = 18.sp * scale
    return TextStyle(fontFamily = font.family, fontSize = size, lineHeight = size * 1.6f)
}
