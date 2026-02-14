package net.discdd.trick.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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
        content = content
    )
}
