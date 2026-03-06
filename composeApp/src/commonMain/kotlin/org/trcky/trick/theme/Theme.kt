package org.trcky.trick.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.jetbrains.compose.resources.Font
import trick.composeapp.generated.resources.Res
import trick.composeapp.generated.resources.satoshi_bold
import trick.composeapp.generated.resources.satoshi_medium
import trick.composeapp.generated.resources.satoshi_regular

/** Butter yellow accent from Vaultchat-style reference. */
private val TrickYellow = Color(0xFFFFFF81)

/** Very dark gray background. */
private val TrickBackground = Color(0xFF1A1A1A)

/** Slightly lighter dark surface. */
private val TrickSurface = Color(0xFF2C2C2C)

/** Dark for text on yellow (readable). */
private val TrickOnPrimary = Color(0xFF1A1A1A)

/** Light gray for secondary text. */
private val TrickOnSurfaceVariant = Color(0xFFCCCCCC)

/** Medium dark gray for dividers and borders. */
private val TrickOutline = Color(0xFF555555)
private val TrickOutlineVariant = Color(0xFF404040)

/** Dark theme color scheme: dark grays + butter yellow accent. */
val TrickDarkColorScheme = darkColorScheme(
    primary = TrickYellow,
    onPrimary = TrickOnPrimary,
    primaryContainer = TrickYellow.copy(alpha = 0.3f),
    onPrimaryContainer = TrickYellow,
    secondary = TrickOnSurfaceVariant,
    onSecondary = TrickBackground,
    secondaryContainer = TrickOutlineVariant,
    onSecondaryContainer = TrickOnSurfaceVariant,
    tertiary = TrickOnSurfaceVariant,
    onTertiary = TrickBackground,
    tertiaryContainer = TrickOutlineVariant,
    onTertiaryContainer = TrickOnSurfaceVariant,
    background = TrickBackground,
    onBackground = Color.White,
    surface = TrickSurface,
    onSurface = Color.White,
    surfaceVariant = TrickOutlineVariant,
    onSurfaceVariant = TrickOnSurfaceVariant,
    surfaceTint = TrickYellow,
    outline = TrickOutline,
    outlineVariant = TrickOutlineVariant,
    error = Color(0xFFCF6679),
    onError = Color.Black,
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFF2B8B5),
    inverseSurface = TrickOnSurfaceVariant,
    inverseOnSurface = TrickBackground,
    inversePrimary = TrickOnPrimary,
    scrim = Color.Black
)

/** Light background and surface for light theme. */
private val TrickLightBackground = Color(0xFFF5F5F5)
private val TrickLightSurface = Color(0xFFFFFFFF)
private val TrickLightOnSurfaceVariant = Color(0xFF5C5C5C)
private val TrickLightOutline = Color(0xFFB0B0B0)
private val TrickLightOutlineVariant = Color(0xFFE0E0E0)

/** Light theme color scheme: light grays/white + same butter yellow accent. */
val TrickLightColorScheme = lightColorScheme(
    primary = TrickYellow,
    onPrimary = TrickOnPrimary,
    primaryContainer = TrickYellow.copy(alpha = 0.4f),
    onPrimaryContainer = TrickOnPrimary,
    secondary = TrickLightOnSurfaceVariant,
    onSecondary = Color.White,
    secondaryContainer = TrickLightOutlineVariant,
    onSecondaryContainer = TrickLightOnSurfaceVariant,
    tertiary = TrickLightOnSurfaceVariant,
    onTertiary = Color.White,
    tertiaryContainer = TrickLightOutlineVariant,
    onTertiaryContainer = TrickLightOnSurfaceVariant,
    background = TrickLightBackground,
    onBackground = Color(0xFF1A1A1A),
    surface = TrickLightSurface,
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = TrickLightOutlineVariant,
    onSurfaceVariant = TrickLightOnSurfaceVariant,
    surfaceTint = TrickYellow,
    outline = TrickLightOutline,
    outlineVariant = TrickLightOutlineVariant,
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFF2B8B5),
    onErrorContainer = Color(0xFF410002),
    inverseSurface = Color(0xFF2F3033),
    inverseOnSurface = TrickLightBackground,
    inversePrimary = TrickYellow.copy(alpha = 0.8f),
    scrim = Color.Black
)

/** Theme preference: dark/light and toggle callback. Used so any screen can switch theme without prop-drilling. */
data class AppThemeState(
    val isDark: Boolean,
    val onToggleTheme: () -> Unit
)

val LocalAppTheme = compositionLocalOf<AppThemeState> {
    AppThemeState(isDark = true, onToggleTheme = {})
}

@Composable
private fun SatoshiFontFamily() = FontFamily(
    Font(Res.font.satoshi_medium, FontWeight.Normal),
    Font(Res.font.satoshi_bold, FontWeight.Medium),
    Font(Res.font.satoshi_bold, FontWeight.Bold),
)

@Composable
private fun SatoshiTypography(): Typography {
    val satoshi = SatoshiFontFamily()
    val default = Typography()
    return Typography(
        displayLarge = default.displayLarge.copy(fontFamily = satoshi),
        displayMedium = default.displayMedium.copy(fontFamily = satoshi),
        displaySmall = default.displaySmall.copy(fontFamily = satoshi),
        headlineLarge = default.headlineLarge.copy(fontFamily = satoshi),
        headlineMedium = default.headlineMedium.copy(fontFamily = satoshi),
        headlineSmall = default.headlineSmall.copy(fontFamily = satoshi),
        titleLarge = default.titleLarge.copy(fontFamily = satoshi),
        titleMedium = default.titleMedium.copy(fontFamily = satoshi),
        titleSmall = default.titleSmall.copy(fontFamily = satoshi),
        bodyLarge = default.bodyLarge.copy(fontFamily = satoshi),
        bodyMedium = default.bodyMedium.copy(fontFamily = satoshi),
        bodySmall = default.bodySmall.copy(fontFamily = satoshi),
        labelLarge = default.labelLarge.copy(fontFamily = satoshi),
        labelMedium = default.labelMedium.copy(fontFamily = satoshi),
        labelSmall = default.labelSmall.copy(fontFamily = satoshi),
    )
}

/**
 * Trick app theme: Vaultchat-style dark or light (single source of truth for iOS and Android).
 * Use [isDark] to choose scheme; provide [AppThemeState] via CompositionLocalProvider at app root for theme toggle.
 */
@Composable
fun TrickTheme(
    isDark: Boolean,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (isDark) TrickDarkColorScheme else TrickLightColorScheme,
        typography = SatoshiTypography(),
        content = content
    )
}
