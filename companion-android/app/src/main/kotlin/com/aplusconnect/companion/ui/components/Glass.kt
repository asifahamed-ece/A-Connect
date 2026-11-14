package com.aplusconnect.companion.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.aplusconnect.companion.ui.theme.AplusTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild

/**
 * Glass, the one place Haze is touched (DESIGN.md §5.3).
 *
 * Content is marked as the blur source with [glassSource]; chrome on top of
 * it (bottom bar, sticky header, scanner bars) reads it with [glassChrome].
 * On API 26–30 Haze draws the tint alone, which is the solid-ish fallback.
 * With "Reduce transparency" on, no blur is drawn and the tint is opaque.
 */
@Composable
internal fun rememberGlassState(): HazeState = remember { HazeState() }

internal fun Modifier.glassSource(state: HazeState): Modifier = haze(state)

/** Chrome that content scrolls under: the faintest text allowed on it is textSecondary. */
@Composable
internal fun Modifier.glassChrome(state: HazeState, overlay: Boolean = true): Modifier {
    val c = AplusTheme.colors
    val tint = if (overlay) c.glassOverlay else c.glassChrome
    if (AplusTheme.reducedTransparency) return this.background(tint)
    return this.hazeChild(
        state = state,
        style = HazeStyle(
            backgroundColor = c.canvas,
            tint = HazeTint(tint),
            blurRadius = if (overlay) 32.dp else 24.dp,
            noiseFactor = 0f,
        ),
    )
}

/**
 * The ambient canvas: the desktop's `.ambient` in Compose - four colour fields
 * in the same corners, then three orbs with real edges so the frosted bar has
 * something to blur rather than a smooth wash. It never moves.
 *
 * "Reduce transparency" drops the orbs, exactly as
 * `[data-transparency="reduce"] .orb { display: none }` does on the desktop.
 */
@Composable
internal fun AmbientCanvas(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val c = AplusTheme.colors
    val showOrbs = !AplusTheme.reducedTransparency
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(c.canvas)
            .drawBehind {
                val w = size.width
                val h = size.height

                // A soft field that fades out at `stop`, like the desktop's
                // `radial-gradient(…, transparent 72%)`.
                fun field(center: Offset, radius: Float, color: Color, stop: Float) {
                    drawRect(
                        Brush.radialGradient(
                            colorStops = arrayOf(0f to color, stop to color.copy(alpha = 0f), 1f to color.copy(alpha = 0f)),
                            center = center,
                            radius = radius,
                        )
                    )
                }

                // The desktop's four corners: 92%/0%, 30%/6%, 2%/100%, 78%/96%.
                field(Offset(w * 0.92f, 0f), w * 0.62f, c.canvasA, 0.72f)
                field(Offset(w * 0.30f, h * 0.06f), w * 0.55f, c.canvasC, 0.70f)
                field(Offset(w * 0.02f, h), w * 0.58f, c.canvasB, 0.72f)
                field(Offset(w * 0.78f, h * 0.96f), w * 0.48f, c.canvasD, 0.70f)

                if (showOrbs) {
                    fun orb(center: Offset, radius: Float, inner: Color, outer: Color) {
                        drawCircle(
                            brush = Brush.radialGradient(
                                colorStops = arrayOf(0f to inner, 0.72f to outer, 1f to outer),
                                center = center,
                                radius = radius,
                            ),
                            radius = radius,
                            center = center,
                            alpha = c.orbOpacity,
                        )
                    }
                    orb(Offset(w * 0.95f, h * 0.08f), w * 0.55f, c.orbA1, c.orbA2)
                    orb(Offset(w * 0.25f, h * 0.94f), w * 0.50f, c.orbB1, c.orbB2)
                    orb(Offset(w * 0.55f, h * 0.40f), w * 0.35f, c.orbC1, c.orbC2)
                }
            },
        content = content,
    )
}
