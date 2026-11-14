package com.aplusconnect.companion.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * ColorOS & LocalSend-inspired Aquamorphic Palette
 */
internal val ColorOSGreen = Color(0xFF00C37B)
internal val ColorOSGreenDark = Color(0xFF009B60)
internal val ColorOSBlue = Color(0xFF1D68FF)
internal val ColorOSWarning = Color(0xFFFF9E0B)
internal val ColorOSError = Color(0xFFFF4D4F)

// Dark Theme Surfaces
internal val DarkBg = Color(0xFF0E1114)
internal val DarkSurface = Color(0xFF161A1F)
internal val DarkSurfaceElevated = Color(0xFF20262E)
internal val DarkBorder = Color(0xFF2B333E)
internal val DarkTextPrimary = Color(0xFFF7F9FC)
internal val DarkTextSecondary = Color(0xFF8F98A8)
internal val DarkTextMuted = Color(0xFF5B6373)

// Light Theme Surfaces
internal val LightBg = Color(0xFFF4F6F9)
internal val LightSurface = Color(0xFFFFFFFF)
internal val LightSurfaceElevated = Color(0xFFEDF2F7)
internal val LightBorder = Color(0xFFE2E7EF)
internal val LightTextPrimary = Color(0xFF15181E)
internal val LightTextSecondary = Color(0xFF6A7382)
internal val LightTextMuted = Color(0xFF98A2B3)

/** The Material 3 scheme used while the system is in dark mode. */
internal val DarkColorScheme = darkColorScheme(
    primary = ColorOSGreen,
    background = DarkBg,
    surface = DarkSurface,
    onPrimary = Color.White,
    onBackground = DarkTextPrimary,
    onSurface = DarkTextPrimary
)

/** The Material 3 scheme used while the system is in light mode. */
internal val LightColorScheme = lightColorScheme(
    primary = ColorOSGreen,
    background = LightBg,
    surface = LightSurface,
    onPrimary = Color.White,
    onBackground = LightTextPrimary,
    onSurface = LightTextPrimary
)

/**
 * The palette the screens draw with, resolved for one system theme.
 *
 * Every tab takes these six colours as parameters instead of asking for the
 * system theme itself, so a tab looks the same wherever it is used.
 */
internal data class ThemeColors(
    val surface: Color,
    val elevated: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color
)

/** The palette for the current system theme. */
internal fun themeColors(isDark: Boolean): ThemeColors = ThemeColors(
    surface = if (isDark) DarkSurface else LightSurface,
    elevated = if (isDark) DarkSurfaceElevated else LightSurfaceElevated,
    border = if (isDark) DarkBorder else LightBorder,
    textPrimary = if (isDark) DarkTextPrimary else LightTextPrimary,
    textSecondary = if (isDark) DarkTextSecondary else LightTextSecondary,
    textMuted = if (isDark) DarkTextMuted else LightTextMuted
)
