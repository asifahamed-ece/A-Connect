package com.aplusconnect.companion.ui.theme

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.aplusconnect.companion.ui.theme.Primitives as P

/**
 * The semantic roles of docs/DESIGN.md §2.2, resolved for one theme.
 *
 * Successor to the old six-colour `ThemeColors`. Read it with
 * `AplusTheme.colors` instead of passing colours down as parameters.
 */
@Immutable
internal data class AplusColors(
    val isDark: Boolean,
    val canvas: Color,
    val surface: Color,
    val surfaceSunken: Color,
    val surfaceElevated: Color,
    /** Static chrome: nothing scrolls beneath it. `textMuted` is allowed on it. */
    val glassChrome: Color,
    /** Chrome that content scrolls under, and overlays. Faintest text: `textSecondary`. */
    val glassOverlay: Color,
    val glassEdge: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    /** Decorative hairlines only. */
    val divider: Color,
    /** Inputs, switch tracks: anything that must be seen (>= 3:1). */
    val outline: Color,
    val accent: Color,
    val onAccent: Color,
    val accentStrong: Color,
    val accentSubtle: Color,
    val accentSubtleStrong: Color,
    val success: Color,
    val successSubtle: Color,
    val warning: Color,
    val warningSubtle: Color,
    val danger: Color,
    val dangerSubtle: Color,
    val focusRing: Color,
    val scrim: Color,
    /** The two soft glows of the ambient canvas (§1.3). */
    val glowA: Color,
    val glowB: Color,
    /** The glass toggle and slider knob: a lit bead, highlight to shade. */
    val knob: Color = P.Neutral0,
    val knobShade: Color = P.Neutral100,
)

internal val LightAplusColors = AplusColors(
    isDark = false,
    canvas = P.Neutral050,
    surface = P.Neutral0,
    surfaceSunken = P.Neutral025,
    surfaceElevated = P.Neutral0,
    glassChrome = P.Neutral0.copy(alpha = 0.72f),
    glassOverlay = P.Neutral0.copy(alpha = 0.86f),
    glassEdge = P.Neutral0.copy(alpha = 0.60f),
    textPrimary = P.Ink,
    textSecondary = P.Neutral600,
    textMuted = P.Neutral500,
    divider = P.Neutral200,
    outline = P.Neutral400,
    accent = P.Blue600,
    onAccent = P.Neutral0,
    accentStrong = P.Blue700,
    accentSubtle = P.Blue050,
    accentSubtleStrong = P.Blue100,
    success = P.Green700,
    successSubtle = P.Green050,
    warning = P.Amber700,
    warningSubtle = P.Amber050,
    danger = P.Red700,
    dangerSubtle = P.Red050,
    focusRing = P.Blue600,
    scrim = P.Ink.copy(alpha = 0.28f),
    glowA = P.Blue600.copy(alpha = 0.18f),
    glowB = P.Blue300.copy(alpha = 0.16f),
)

internal val DarkAplusColors = AplusColors(
    isDark = true,
    canvas = P.Neutral950,
    surface = P.Neutral900,
    surfaceSunken = P.Neutral875,
    surfaceElevated = P.Neutral875,
    glassChrome = P.Neutral900.copy(alpha = 0.64f),
    glassOverlay = P.Neutral900.copy(alpha = 0.86f),
    glassEdge = P.Neutral0.copy(alpha = 0.08f),
    textPrimary = P.Snow,
    textSecondary = P.Neutral800,
    textMuted = P.Neutral750,
    divider = P.Neutral850,
    outline = P.Neutral700,
    accent = P.Blue400,
    onAccent = P.Neutral950,
    accentStrong = P.Blue300,
    accentSubtle = P.Blue900,
    accentSubtleStrong = P.Blue950Strong,
    success = P.Green300,
    successSubtle = P.Green950,
    warning = P.Amber300,
    warningSubtle = P.Amber950,
    danger = P.Red300,
    dangerSubtle = P.Red950,
    focusRing = P.Blue400,
    scrim = P.Black.copy(alpha = 0.52f),
    glowA = P.Blue400.copy(alpha = 0.14f),
    glowB = P.Blue600.copy(alpha = 0.12f),
    knobShade = P.Neutral800,
)

/** Every glass role becomes its solid fallback (§5.3, "Reduce transparency"). */
internal fun AplusColors.solidified(): AplusColors = copy(
    glassChrome = surfaceElevated,
    glassOverlay = surfaceElevated,
    glassEdge = Color.Transparent,
)

/** All ~30 Material roles, so stock components never fall back to baseline purple (audit S4). */
private fun AplusColors.toMaterial(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentSubtle,
        onPrimaryContainer = accentStrong,
        inversePrimary = accentStrong,
        secondary = accentStrong,
        onSecondary = onAccent,
        secondaryContainer = accentSubtle,
        onSecondaryContainer = accentStrong,
        tertiary = success,
        onTertiary = surface,
        tertiaryContainer = successSubtle,
        onTertiaryContainer = success,
        background = canvas,
        onBackground = textPrimary,
        surface = surface,
        onSurface = textPrimary,
        surfaceVariant = surfaceSunken,
        onSurfaceVariant = textSecondary,
        surfaceTint = Color.Transparent,
        inverseSurface = textPrimary,
        inverseOnSurface = surface,
        error = danger,
        onError = surface,
        errorContainer = dangerSubtle,
        onErrorContainer = danger,
        outline = outline,
        outlineVariant = divider,
        scrim = scrim,
        surfaceBright = surface,
        surfaceDim = canvas,
        surfaceContainerLowest = surface,
        surfaceContainerLow = surfaceSunken,
        surfaceContainer = surface,
        surfaceContainerHigh = surfaceElevated,
        surfaceContainerHighest = surfaceElevated,
    )
}

/** Mono for codes, IPs, paths (§3). */
internal object AplusType {
    private val Default = FontFamily.Default
    val tabular = "tnum"

    val display = TextStyle(fontFamily = Default, fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold)
    val titleLg = TextStyle(fontFamily = Default, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
    val title = TextStyle(fontFamily = Default, fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontFamily = Default, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal)
    val bodySm = TextStyle(fontFamily = Default, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal)
    val label = TextStyle(fontFamily = Default, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    val caption = TextStyle(fontFamily = Default, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal)
    val monoLg = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.08.em,
    )
    /** Changing numbers in proportional text: %, battery, sizes, counts. */
    val numeric = bodySm.copy(fontFeatureSettings = tabular)
}

private val AplusTypography = Typography(
    headlineMedium = AplusType.display,
    titleLarge = AplusType.titleLg,
    titleMedium = AplusType.title,
    bodyLarge = AplusType.body,
    bodyMedium = AplusType.bodySm,
    labelLarge = AplusType.label,
    labelMedium = AplusType.caption,
    labelSmall = AplusType.caption,
)

private val AplusShapes = Shapes(
    extraSmall = RoundedCornerShape(Radius.xs),
    small = RoundedCornerShape(Radius.sm),
    medium = RoundedCornerShape(Radius.md),
    large = RoundedCornerShape(Radius.lg),
    extraLarge = RoundedCornerShape(Radius.xl),
)

/** Motion tokens (§6.1). Read through [AplusMotion] so reduced motion is honoured in one place. */
internal object Motion {
    const val press = 90
    const val fast = 140
    const val base = 220
    const val slow = 320
    const val breathe = 1600
    val standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val enter = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val exit = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
}

/** Resolved motion for the current reduce-motion state. */
@Immutable
internal class AplusMotion(val reduced: Boolean) {
    /** Default spring: damping 0.82, stiffness 380. */
    fun <T> spring(): FiniteAnimationSpec<T> =
        if (reduced) snap() else spring(dampingRatio = 0.82f, stiffness = 380f)

    /** Press release: the one ColorOS-like overshoot. */
    fun <T> release(): FiniteAnimationSpec<T> =
        if (reduced) snap() else spring(dampingRatio = 0.68f, stiffness = 500f)

    /** Page and sheet movement. */
    fun <T> page(): FiniteAnimationSpec<T> =
        if (reduced) snap() else spring(dampingRatio = 0.9f, stiffness = 220f)

    fun <T> press(): FiniteAnimationSpec<T> =
        if (reduced) snap() else tween(Motion.press, easing = Motion.standard)

    /** Colour and opacity keep a short fade under reduced motion, so state changes stay perceivable. */
    fun <T> fade(): FiniteAnimationSpec<T> =
        tween(if (reduced) 120 else Motion.fast, easing = Motion.standard)

    fun <T> enter(): FiniteAnimationSpec<T> =
        tween(if (reduced) 120 else Motion.base, easing = Motion.enter)
}

/** How the app follows light/dark (Settings → Appearance). */
internal enum class ThemeMode(val key: String, val label: String) {
    System("system", "Follow system"),
    Light("light", "Light"),
    Dark("dark", "Dark");

    companion object {
        fun fromKey(key: String): ThemeMode = entries.firstOrNull { it.key == key } ?: System
    }
}

internal val LocalAplusColors = staticCompositionLocalOf { LightAplusColors }
internal val LocalAplusMotion = staticCompositionLocalOf { AplusMotion(reduced = false) }
internal val LocalReducedTransparency = staticCompositionLocalOf { false }
internal val LocalBarAlpha = staticCompositionLocalOf { barAlphaFor(BAR_CLARITY_DEFAULT, reduceTransparency = false) }

/** Settings > Bar transparency, 0 = solid, 100 = clearest. */
internal const val BAR_CLARITY_DEFAULT = 60

/**
 * Tint opacity of the floating bar and the glass toggles for a clarity level.
 * The clearest setting keeps 28 % tint plus blur, so labels stay readable on any content.
 * Reduce transparency always wins and makes them solid.
 */
internal fun barAlphaFor(clarity: Int, reduceTransparency: Boolean): Float =
    if (reduceTransparency) 1f else (0.96f - clarity.coerceIn(0, 100) * 0.0068f)

/** Accessors, so call sites read `AplusTheme.colors.accent`. */
internal object AplusTheme {
    val colors: AplusColors
        @Composable @ReadOnlyComposable get() = LocalAplusColors.current
    val motion: AplusMotion
        @Composable @ReadOnlyComposable get() = LocalAplusMotion.current
    val reducedTransparency: Boolean
        @Composable @ReadOnlyComposable get() = LocalReducedTransparency.current
    /** Opacity of the floating bar's tint, shared by the glass toggles so they match it. */
    val barAlpha: Float
        @Composable @ReadOnlyComposable get() = LocalBarAlpha.current
}

/**
 * True when the system animator scale is 0 (Developer options, accessibility "Remove animations",
 * or a phone's power saving mode that switches animations off).
 *
 * Watched live: before 4.2.1 it was read once, so turning power saving on and off could leave the
 * app half still (the bits moving, the glow and shimmer frozen) until it restarted.
 */
@Composable
internal fun systemReducesMotion(): Boolean {
    val context = LocalContext.current
    val resolver = context.contentResolver
    fun read(): Boolean = try {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    } catch (_: Exception) {
        false
    }
    var reduced by remember(context) { mutableStateOf(read()) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { reduced = read() }
        }
        try {
            resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        } catch (_: Exception) {
            // Previews and tests have no settings provider.
        }
        onDispose { try { resolver.unregisterContentObserver(observer) } catch (_: Exception) {} }
    }
    return reduced
}

@Composable
internal fun AplusTheme(
    mode: ThemeMode = ThemeMode.System,
    reduceTransparency: Boolean = false,
    reduceMotion: Boolean = false,
    barClarity: Int = BAR_CLARITY_DEFAULT,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val base = if (dark) DarkAplusColors else LightAplusColors
    val colors = if (reduceTransparency) base.solidified() else base
    val motion = AplusMotion(reduced = reduceMotion || systemReducesMotion())

    CompositionLocalProvider(
        LocalAplusColors provides colors,
        LocalAplusMotion provides motion,
        LocalReducedTransparency provides reduceTransparency,
        LocalBarAlpha provides barAlphaFor(barClarity, reduceTransparency),
    ) {
        MaterialTheme(
            colorScheme = colors.toMaterial(),
            typography = AplusTypography,
            shapes = AplusShapes,
            content = content,
        )
    }
}
