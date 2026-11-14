package com.aplusconnect.companion.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.aplusconnect.companion.ui.theme.AplusTheme
import com.aplusconnect.companion.ui.theme.AplusType
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild
import kotlinx.coroutines.launch

internal class NavDestination(val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val BarShape = RoundedCornerShape(28.dp)
private val PillShape = RoundedCornerShape(22.dp)
private val BarHeight = 68.dp

/** Height the bar takes from the bottom of the screen, for content padding. */
internal val FloatingNavReserve = BarHeight + 32.dp

/**
 * Floating glass navigation bar.
 *
 * Its tint opacity is Settings > Bar transparency ([AplusTheme.barAlpha]). A finger
 * on the bar lights it from that point: a soft glow, a brighter rim near the finger,
 * an accent halo and a ripple. Selection slides with a springy pill that squashes as
 * it lands, and the chosen icon bumps. Reduce motion keeps the light, drops the springs.
 */
@Composable
internal fun FloatingNavBar(
    items: List<NavDestination>,
    selected: Int,
    onSelect: (Int) -> Unit,
    glass: HazeState,
    modifier: Modifier = Modifier,
) {
    val c = AplusTheme.colors
    val reduced = AplusTheme.motion.reduced
    val alpha = AplusTheme.barAlpha
    val scope = rememberCoroutineScope()

    var touch by remember { mutableStateOf(Offset.Unspecified) }
    var pressed by remember { mutableStateOf(false) }
    val light by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = tween(if (pressed) 90 else 520),
        label = "navLight",
    )
    val lift by animateFloatAsState(
        targetValue = if (pressed && !reduced) 1.012f else 1f,
        animationSpec = if (pressed) tween(120) else spring(dampingRatio = 0.5f, stiffness = 500f),
        label = "navLift",
    )
    val ripple = remember { Animatable(1f) }
    var rippleAt by remember { mutableStateOf(Offset.Unspecified) }

    val glassMod = if (AplusTheme.reducedTransparency) {
        Modifier.drawWithContent { drawRect(c.surfaceElevated); drawContent() }
    } else {
        Modifier.hazeChild(
            state = glass,
            style = HazeStyle(
                backgroundColor = c.canvas,
                tint = HazeTint(c.surfaceElevated.copy(alpha = alpha)),
                blurRadius = 22.dp,
                noiseFactor = 0f,
            ),
        )
    }

    Box(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
            .height(BarHeight)
            .graphicsLayer { scaleX = lift; scaleY = lift }
            .shadow(
                elevation = (12 + 18 * light).dp,
                shape = BarShape,
                ambientColor = if (light > 0f) c.accent else Color.Black,
                spotColor = if (light > 0f) c.accent else Color.Black,
            )
            .clip(BarShape)
            .then(glassMod)
            .border(1.dp, if (pressed) c.accent.copy(alpha = 0.3f) else c.glassEdge, BarShape)
            .pointerInput(Unit) {
                // Observe the finger without consuming it, so the items still get their clicks.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    touch = down.position
                    rippleAt = down.position
                    pressed = true
                    if (!reduced) scope.launch { ripple.snapTo(0f); ripple.animateTo(1f, tween(650)) }
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.firstOrNull()?.let { touch = it.position }
                    } while (event.changes.any { it.pressed })
                    pressed = false
                }
            }
            .drawWithContent {
                drawContent()
                if (touch == Offset.Unspecified) return@drawWithContent
                if (light > 0f) {
                    drawRect(
                        Brush.radialGradient(
                            listOf(c.accent.copy(alpha = 0.26f * light), Color.Transparent),
                            center = touch,
                            radius = 110.dp.toPx(),
                        )
                    )
                    val rim = 1.5.dp.toPx()
                    drawRoundRect(
                        brush = Brush.radialGradient(
                            listOf(c.accentStrong.copy(alpha = light), Color.Transparent),
                            center = touch,
                            radius = 150.dp.toPx(),
                        ),
                        topLeft = Offset(rim / 2, rim / 2),
                        size = size.copy(width = size.width - rim, height = size.height - rim),
                        cornerRadius = CornerRadius(28.dp.toPx()),
                        style = Stroke(rim),
                    )
                }
                if (ripple.value < 1f && rippleAt != Offset.Unspecified) {
                    val r = ripple.value
                    drawCircle(
                        Brush.radialGradient(
                            listOf(c.accent.copy(alpha = 0.3f * (1f - r)), Color.Transparent),
                            center = rippleAt,
                            radius = (12 + 120 * r).dp.toPx(),
                        ),
                        radius = (12 + 120 * r).dp.toPx(),
                        center = rippleAt,
                    )
                }
            }
            .padding(6.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val slot = maxWidth / items.size
            val x by animateDpAsState(
                targetValue = slot * selected,
                animationSpec = if (reduced) tween(120) else spring(dampingRatio = 0.58f, stiffness = 420f),
                label = "navSlide",
            )
            // squash: wide and flat on landing, springs back
            val squash = remember { Animatable(1f) }
            LaunchedEffect(selected) {
                if (reduced) return@LaunchedEffect
                squash.snapTo(1.28f)
                squash.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 520f))
            }
            Box(
                Modifier
                    .offset(x = x)
                    .width(slot)
                    .fillMaxHeight()
                    .graphicsLayer {
                        scaleX = squash.value
                        scaleY = 1f - (squash.value - 1f) * 0.7f
                    }
                    .clip(PillShape)
                    .drawWithContent {
                        drawRect(c.accent.copy(alpha = 0.16f))
                        drawRoundRect(
                            c.accent.copy(alpha = 0.26f),
                            cornerRadius = CornerRadius(22.dp.toPx()),
                            style = Stroke(1.dp.toPx()),
                        )
                    },
            )
            Row(Modifier.fillMaxSize().selectableGroup()) {
                items.forEachIndexed { i, item ->
                    NavItem(
                        item = item,
                        selected = i == selected,
                        onClick = { onSelect(i) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun NavItem(item: NavDestination, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val c = AplusTheme.colors
    val reduced = AplusTheme.motion.reduced
    val tint by animateColorAsState(if (selected) c.accentStrong else c.textSecondary, tween(200), label = "navTint")
    val bump = remember { Animatable(1f) }
    var taps by remember { mutableIntStateOf(0) }
    LaunchedEffect(selected, taps) {
        if (!selected || reduced) return@LaunchedEffect
        bump.animateTo(0.78f, tween(110))
        bump.animateTo(1f, spring(dampingRatio = 0.32f, stiffness = 650f))
    }
    Column(
        modifier
            .fillMaxHeight()
            .clip(PillShape)
            .selectable(
                selected = selected,
                onClick = { taps++; onClick() },
                role = Role.Tab,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        Icon(
            if (selected) item.selectedIcon else item.icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.scale(bump.value).graphicsLayer { translationY = (1f - bump.value) * -14f },
        )
        Text(item.label, style = AplusType.caption, color = tint)
    }
}
