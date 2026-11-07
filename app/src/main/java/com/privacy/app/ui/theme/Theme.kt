package com.privacy.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Mono = FontFamily.Monospace

/** Pure black, pure white, and the greys between. No colour accent: contrast and motion do the work. */
private val LightColors = lightColorScheme(
    primary = Color(0xFF171717), onPrimary = Color.White,
    primaryContainer = Color(0xFFECECEC), onPrimaryContainer = Color(0xFF171717),
    secondary = Color(0xFF4A4A4A), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6E6E6), onSecondaryContainer = Color(0xFF171717),
    tertiary = Color(0xFF4A4A4A), onTertiary = Color.White,
    background = Color(0xFFF7F7F7), onBackground = Color(0xFF171717),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF171717),
    surfaceVariant = Color(0xFFEBEBEB), onSurfaceVariant = Color(0xFF6B6B6B),
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF2F2F2), surfaceContainer = Color(0xFFEEEEEE),
    surfaceContainerHigh = Color(0xFFFFFFFF), surfaceContainerHighest = Color(0xFFE9E9E9),
    outline = Color(0xFFBDBDBD), outlineVariant = Color(0xFFE0E0E0),
    error = Color(0xFFC62828)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFECECEC), onPrimary = Color(0xFF171717),
    primaryContainer = Color(0xFF2B2B2B), onPrimaryContainer = Color(0xFFECECEC),
    secondary = Color(0xFFC4C4C4), onSecondary = Color(0xFF171717),
    secondaryContainer = Color(0xFF353535), onSecondaryContainer = Color(0xFFECECEC),
    tertiary = Color(0xFFC4C4C4), onTertiary = Color(0xFF171717),
    background = Color(0xFF1B1B1B), onBackground = Color(0xFFECECEC),
    surface = Color(0xFF232323), onSurface = Color(0xFFECECEC),
    surfaceVariant = Color(0xFF2B2B2B), onSurfaceVariant = Color(0xFF9A9A9A),
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = Color(0xFF161616), surfaceContainerLow = Color(0xFF1F1F1F), surfaceContainer = Color(0xFF242424),
    surfaceContainerHigh = Color(0xFF2A2A2A), surfaceContainerHighest = Color(0xFF303030),
    outline = Color(0xFF3A3A3A), outlineVariant = Color(0xFF2A2A2A),
    error = Color(0xFFFF6B5E)
)

// Shades instead of colours (used by the passcode strength meter etc.)
val SuccessGreen = Color(0xFFBDBDBD)
val WarnAmber = Color(0xFF7A7A7A)

private val base = Typography()
private fun typographyFor(f: FontFamily?): Typography {
    val t = if (f == null) base else base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = f), displayMedium = base.displayMedium.copy(fontFamily = f), displaySmall = base.displaySmall.copy(fontFamily = f),
        headlineLarge = base.headlineLarge.copy(fontFamily = f), headlineMedium = base.headlineMedium.copy(fontFamily = f), headlineSmall = base.headlineSmall.copy(fontFamily = f),
        titleLarge = base.titleLarge.copy(fontFamily = f), titleMedium = base.titleMedium.copy(fontFamily = f), titleSmall = base.titleSmall.copy(fontFamily = f),
        bodyLarge = base.bodyLarge.copy(fontFamily = f), bodyMedium = base.bodyMedium.copy(fontFamily = f), bodySmall = base.bodySmall.copy(fontFamily = f)
    )
    return t.copy(
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp, fontSize = 26.sp),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelMedium = t.labelMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
        labelSmall = t.labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp)
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp), extraLarge = RoundedCornerShape(28.dp)
)

/** "dark", "light" or "system" → true when the app should be dark. */
@Composable
fun isDarkFor(mode: String): Boolean = when (mode) { "light" -> false; "system" -> isSystemInDarkTheme(); else -> true }

@Composable
fun PrivacyTheme(darkTheme: Boolean = isSystemInDarkTheme(), fontKey: String = AppFonts.DEFAULT, content: @Composable () -> Unit) {
    val scheme = if (darkTheme) DarkColors else LightColors
    MaterialTheme(colorScheme = scheme, typography = androidx.compose.runtime.remember(fontKey) { typographyFor(AppFonts.of(fontKey).family) }, shapes = AppShapes) {
        // Text and icons that are not inside a Surface used to fall back to black – invisible on the dark theme
        // (that was the "missing chat header icons"). Everything now defaults to the theme's own text colour.
        CompositionLocalProvider(LocalContentColor provides scheme.onBackground, content = content)
    }
}
