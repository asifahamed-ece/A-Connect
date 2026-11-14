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
 * The ambient canvas: background plus two very soft accent glows that give
 * the glass something to refract. It never moves.
 */
@Composable
internal fun AmbientCanvas(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val c = AplusTheme.colors
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(c.canvas)
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(c.glowA, c.glowA.copy(alpha = 0f)),
                        center = Offset(size.width * 0.1f, size.height * 0.02f),
                        radius = size.width * 0.9f,
                    )
                )
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(c.glowB, c.glowB.copy(alpha = 0f)),
                        center = Offset(size.width * 1.0f, size.height * 0.55f),
                        radius = size.width * 0.8f,
                    )
                )
            },
        content = content,
    )
}
