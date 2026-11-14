package com.aplusconnect.companion.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aplusconnect.companion.ui.theme.AplusTheme

/**
 * Glass toggle, tinted with the same opacity as the floating bar so the two read
 * as one material. The knob is a lit bead that springs across and squashes as it
 * lands; a finger on the row ([pressed]) lights the track like the bar.
 */
@Composable
internal fun GlassSwitch(checked: Boolean, pressed: Boolean, enabled: Boolean = true) {
    val c = AplusTheme.colors
    val alpha = AplusTheme.barAlpha
    val reduced = AplusTheme.motion.reduced
    val track by animateColorAsState(
        if (checked) c.accent.copy(alpha = 0.4f + 0.6f * alpha) else c.surfaceSunken.copy(alpha = alpha),
        tween(220),
        label = "switchTrack",
    )
    val edge by animateColorAsState(
        if (checked) c.accentStrong.copy(alpha = 0.55f) else c.outline.copy(alpha = 0.8f),
        tween(220),
        label = "switchEdge",
    )
    val glow by animateFloatAsState(
        (if (checked) 0.5f else 0f) + (if (pressed) 0.5f else 0f),
        tween(if (pressed) 90 else 300),
        label = "switchGlow",
    )
    val knobX by animateDpAsState(
        if (checked) 26.dp else 4.dp,
        if (reduced) tween(120) else spring(dampingRatio = 0.55f, stiffness = 520f),
        label = "switchKnob",
    )
    val squash = remember { Animatable(1f) }
    LaunchedEffect(checked) {
        if (reduced) return@LaunchedEffect
        squash.snapTo(1.35f)
        squash.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 600f))
    }
    val press by animateFloatAsState(if (pressed && !reduced) 1.18f else 1f, tween(120), label = "knobPress")

    Box(
        Modifier
            .alpha(if (enabled) 1f else 0.45f)
            .size(width = 54.dp, height = 32.dp)
            .drawBehind {
                if (glow > 0f) {
                    drawRoundRect(
                        Brush.radialGradient(
                            listOf(c.accent.copy(alpha = 0.45f * glow), Color.Transparent),
                            center = center,
                            radius = size.width * 0.9f,
                        ),
                        topLeft = Offset(-12.dp.toPx(), -12.dp.toPx()),
                        size = size.copy(size.width + 24.dp.toPx(), size.height + 24.dp.toPx()),
                        cornerRadius = CornerRadius(size.height),
                    )
                }
            }
            .background(track, CircleShape)
            .border(1.dp, edge, CircleShape),
        contentAlignment = Alignment.CenterStart,
    ) {
        GlassKnob(
            Modifier
                .offset(x = knobX)
                .size(24.dp)
                .graphicsLayer {
                    scaleX = squash.value * press
                    scaleY = 1f - (squash.value - 1f) * 0.35f
                },
        )
    }
}

@Composable
private fun GlassKnob(modifier: Modifier) {
    val c = AplusTheme.colors
    Box(
        modifier
            .shadow(3.dp, CircleShape)
            .background(
                Brush.radialGradient(listOf(c.knob, c.knob, c.knobShade), center = Offset(20f, 16f)),
                CircleShape,
            ),
    )
}

/** Settings > Bar transparency: 0 solid, 100 clearest, in steps of 5. Same glass as the toggles. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GlassSlider(
    value: Int,
    onValueChange: (Int) -> Unit,
    onValueChangeFinished: () -> Unit,
    enabled: Boolean,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val c = AplusTheme.colors
    val alpha = AplusTheme.barAlpha
    val interaction = remember { MutableInteractionSource() }
    val dragging by interaction.collectIsDraggedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val active = dragging || pressed
    val grow by animateFloatAsState(if (active) 1.15f else 1f, spring(dampingRatio = 0.5f, stiffness = 500f), label = "sliderKnob")
    Slider(
        value = value.toFloat(),
        onValueChange = { onValueChange((it / 5f).toInt() * 5) },
        onValueChangeFinished = onValueChangeFinished,
        valueRange = 0f..100f,
        steps = 19,
        enabled = enabled,
        interactionSource = interaction,
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f)
            .semantics { this.contentDescription = contentDescription },
        thumb = {
            Box(
                Modifier
                    .size(28.dp)
                    .graphicsLayer { scaleX = grow; scaleY = grow }
                    .drawBehind {
                        if (active) {
                            drawCircle(c.accent.copy(alpha = 0.18f), radius = size.width / 2 + 8.dp.toPx())
                            drawCircle(
                                Brush.radialGradient(listOf(c.accent.copy(alpha = 0.4f), Color.Transparent), radius = size.width * 1.2f),
                                radius = size.width * 1.2f,
                            )
                        }
                    },
                contentAlignment = Alignment.Center,
            ) { GlassKnob(Modifier.size(28.dp)) }
        },
        track = { state ->
            val fraction = (state.value - state.valueRange.start) / (state.valueRange.endInclusive - state.valueRange.start)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .background(c.surfaceSunken.copy(alpha = alpha), CircleShape)
                    .border(1.dp, c.outline.copy(alpha = 0.55f), CircleShape),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(c.accent.copy(alpha = 0.85f), CircleShape),
                )
            }
        },
    )
}
