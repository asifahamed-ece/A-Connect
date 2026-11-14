package com.aplusconnect.companion.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aplusconnect.companion.ui.theme.AplusTheme
import com.aplusconnect.companion.ui.theme.AplusType
import com.aplusconnect.companion.ui.theme.Space
import kotlin.random.Random

/** What the link between the two devices shows. */
internal enum class LinkMode {
    /** Bytes travel both ways. */
    Live,
    /** Short handshake nibbles go out from the phone. */
    Pending,
    /** A quiet dashed rail. */
    Idle,
}

internal fun linkModeFor(phase: ConnectionPhase): LinkMode = when (phase) {
    ConnectionPhase.Connected, ConnectionPhase.Mirroring -> LinkMode.Live
    ConnectionPhase.Connecting -> LinkMode.Pending
    else -> LinkMode.Idle
}

internal class Packet(val bits: String, val toComputer: Boolean, val start: Long, val duration: Long)

/**
 * When packets leave and land, driven only by frame time.
 *
 * Before 4.2.1 a coroutine `delay` loop added packets while the frame clock was paused
 * (screen off, app in the background — the foreground service keeps the app alive), so
 * thousands piled up and the first frame back took long enough to look frozen. Now nothing
 * happens without a frame, and after a long pause the link starts busy again.
 */
internal class LinkSchedule(private val mode: LinkMode, private val random: Random = Random.Default) {
    private val pending = mode == LinkMode.Pending
    val interval: Long = if (pending) 1200 else 720
    private val duration: Long = if (pending) 2600 else 2000
    private var next = Long.MIN_VALUE

    var packets: List<Packet> = emptyList()
        private set

    /** Moves to frame time [t]. True when [packets] changed. */
    fun advance(t: Long): Boolean {
        if (mode == LinkMode.Idle) return false
        if (next == Long.MIN_VALUE || t - next > duration) {
            packets = seedPackets(mode, t)
            next = t + interval
            return true
        }
        var changed = false
        val alive = packets.filter { t - it.start < it.duration }
        if (alive.size != packets.size) {
            packets = alive
            changed = true
        }
        if (t >= next) {
            val toComputer = pending || random.nextFloat() < 0.6f
            val bits = buildString { repeat(if (pending) 4 else 8) { append(if (random.nextBoolean()) '1' else '0') } }
            packets = packets + Packet(bits, toComputer, t, duration)
            next = maxOf(next + interval, t + 1)
            changed = true
        }
        return changed
    }
}

/** Phone ⇄ computer with binary packets in transit (§10.1). Reduce motion keeps a still rail. */
@Composable
internal fun DataLinkGraphic(phase: ConnectionPhase) {
    val mode = linkModeFor(phase)
    val live = mode == LinkMode.Live
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = "Phone to computer: ${phase.label}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LinkNode(live, "This phone") { PhoneGlyph(live) }
        DataStream(mode, Modifier.weight(1f).padding(horizontal = Space.s2).height(48.dp))
        LinkNode(live, "Computer") { ComputerGlyph(live) }
    }
}

@Composable
private fun DataStream(mode: LinkMode, modifier: Modifier) {
    val c = AplusTheme.colors
    val reduced = AplusTheme.motion.reduced
    val measurer = rememberTextMeasurer()
    val bitStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        letterSpacing = 1.2.sp,
        color = c.accentStrong,
    )
    // Start busy: a few packets already mid-flight, so the link never looks empty
    // (and previews or screenshot tests, which have no frame clock, show bits too).
    var packets by remember(mode) { mutableStateOf(if (mode == LinkMode.Idle) emptyList() else seedPackets(mode, 0L)) }
    var now by remember { mutableLongStateOf(0L) }
    val animate = mode != LinkMode.Idle && !reduced
    // Each bit pattern is laid out once, not on every frame.
    val layouts = remember(measurer, bitStyle) { HashMap<String, androidx.compose.ui.text.TextLayoutResult>() }

    // One frame-driven loop for packets and the sweep: it pauses with the screen and picks up cleanly.
    LaunchedEffect(mode, animate) {
        if (!animate) return@LaunchedEffect
        val schedule = LinkSchedule(mode)
        while (true) {
            withFrameMillis { t ->
                now = t
                if (schedule.advance(t)) packets = schedule.packets
            }
        }
    }

    Canvas(
        modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
    ) {
        val y = size.height / 2
        val rail = 2.dp.toPx()
        if (mode == LinkMode.Idle) {
            drawLine(
                c.outline, Offset(0f, y), Offset(size.width, y), rail, StrokeCap.Round,
                PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx())),
            )
        } else {
            val a = if (mode == LinkMode.Live) 1f else 0.6f
            drawLine(
                Brush.horizontalGradient(
                    listOf(c.accent.copy(alpha = 0.55f * a), c.accent.copy(alpha = 0.22f * a), c.accent.copy(alpha = 0.55f * a)),
                ),
                Offset(0f, y), Offset(size.width, y), rail, StrokeCap.Round,
            )
            if (animate) {
                // Sweep from frame time too (2.6 s per pass), so it can't stall separately from the bits.
                val sweep = -0.4f + 1.8f * ((now % 2600L) / 2600f)
                val cx = size.width * sweep
                drawLine(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, c.accentStrong.copy(alpha = 0.9f), Color.Transparent),
                        startX = cx - size.width * 0.18f,
                        endX = cx + size.width * 0.18f,
                    ),
                    Offset(cx - size.width * 0.18f, y), Offset(cx + size.width * 0.18f, y), rail, StrokeCap.Round,
                )
            }
            packets.forEach { p -> drawPacket(p, now, layouts.getOrPut(p.bits) { measurer.measure(p.bits, bitStyle) }, c.accent, c.accentStrong) }
        }
        // fade both ends so packets appear out of, and sink into, the devices
        drawRect(
            Brush.horizontalGradient(
                0f to Color.Transparent, 0.1f to Color.Black, 0.9f to Color.Black, 1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }
}

internal fun seedPackets(mode: LinkMode, at: Long): List<Packet> =
    if (mode == LinkMode.Pending) {
        listOf(Packet("1010", true, at - 1300, 2600))
    } else {
        listOf(
            Packet("11010010", true, at - 560, 2000),
            Packet("01101100", false, at - 900, 2000),
            Packet("10011101", true, at - 1500, 2000),
        )
    }

private fun DrawScope.drawPacket(
    p: Packet,
    now: Long,
    text: androidx.compose.ui.text.TextLayoutResult,
    accent: Color,
    accentStrong: Color,
) {
    val raw = ((now - p.start).toFloat() / p.duration).coerceIn(0f, 1f)
    val t = if (raw < 0.5f) 2 * raw * raw else 1 - (-2 * raw + 2).let { it * it } / 2 // ease in-out
    val w = text.size.width.toFloat()
    val from = -0.08f * size.width - w
    val to = 1.08f * size.width
    val left = if (p.toComputer) from + (to - from) * t else to - (to - from) * t
    val y = size.height / 2
    val comet = 28.dp.toPx()
    val headX = if (p.toComputer) left + w else left
    val tailX = if (p.toComputer) headX - comet else headX + comet
    // glow, then the bright comet on the rail
    drawLine(accent.copy(alpha = 0.35f), Offset(tailX, y), Offset(headX, y), 7.dp.toPx(), StrokeCap.Round)
    drawLine(
        Brush.horizontalGradient(listOf(Color.Transparent, accentStrong), startX = tailX, endX = headX),
        Offset(tailX, y), Offset(headX, y), 3.dp.toPx(), StrokeCap.Round,
    )
    // to the computer above the rail, back to the phone below it
    val ty = if (p.toComputer) y - 5.dp.toPx() - text.size.height else y + 5.dp.toPx()
    drawText(text, topLeft = Offset(left, ty))
}

@Composable
private fun LinkNode(live: Boolean, label: String, glyph: @Composable () -> Unit) {
    val c = AplusTheme.colors
    val pulse = if (live && !AplusTheme.motion.reduced) {
        val t = rememberInfiniteTransition(label = "node")
        val v by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "nodePulse")
        v
    } else if (live) 0.5f else 0f
    val shape = RoundedCornerShape(20.dp)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .size(64.dp)
                .drawBehind {
                    if (live) {
                        drawCircle(
                            Brush.radialGradient(listOf(c.accent.copy(alpha = 0.38f * pulse), Color.Transparent)),
                            radius = size.width * 0.85f,
                        )
                    }
                }
                .background(if (live) c.accentSubtle else c.surfaceSunken, shape)
                .border(1.dp, if (live) c.accent.copy(alpha = 0.3f + 0.25f * pulse) else c.divider, shape),
            contentAlignment = Alignment.Center,
        ) { glyph() }
        Text(label, style = AplusType.caption, color = c.textSecondary)
    }
}

/** Drawn on a 32-unit grid so the two devices share stroke weight and corner style. */
@Composable
private fun PhoneGlyph(live: Boolean) {
    val c = AplusTheme.colors
    val ink = if (live) c.accentStrong else c.textSecondary
    val lines = screenLines(live)
    Canvas(Modifier.size(32.dp)) {
        val u = size.width / 32f
        drawRoundRect(ink, Offset(9 * u, 2.5f * u), Size(14 * u, 27 * u), CornerRadius(4 * u), style = Stroke(1.6f * u))
        drawRoundRect(
            if (live) c.accent.copy(alpha = 0.3f) else ink.copy(alpha = 0.1f),
            Offset(11 * u, 5.5f * u), Size(10 * u, 21 * u), CornerRadius(2 * u),
        )
        drawCircle(ink, 0.9f * u, Offset(16 * u, 7.4f * u))
        if (live) {
            listOf(13f to 5f, 16f to 3.5f, 19f to 4.5f).forEachIndexed { i, (y, w) ->
                drawLine(c.accentStrong.copy(alpha = lines[i]), Offset(13.5f * u, y * u), Offset((13.5f + w) * u, y * u), 1.4f * u, StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun ComputerGlyph(live: Boolean) {
    val c = AplusTheme.colors
    val ink = if (live) c.accentStrong else c.textSecondary
    val lines = screenLines(live)
    val cut = c.surfaceSunken
    Canvas(Modifier.size(32.dp)) {
        val u = size.width / 32f
        drawRoundRect(ink, Offset(5 * u, 6 * u), Size(22 * u, 15.5f * u), CornerRadius(2.4f * u), style = Stroke(1.6f * u))
        drawRoundRect(
            if (live) c.accent.copy(alpha = 0.3f) else ink.copy(alpha = 0.1f),
            Offset(7.4f * u, 8.4f * u), Size(17.2f * u, 10.7f * u), CornerRadius(1.2f * u),
        )
        drawRoundRect(ink, Offset(2.5f * u, 23.6f * u), Size(27 * u, 2.9f * u), CornerRadius(1.2f * u))
        drawLine(cut, Offset(13.5f * u, 23.9f * u), Offset(18.5f * u, 23.9f * u), 0.9f * u, StrokeCap.Round)
        if (live) {
            listOf(11.6f to 7f, 14.4f to 10f, 17f to 5f).forEachIndexed { i, (y, w) ->
                drawLine(c.accentStrong.copy(alpha = lines[i]), Offset(10 * u, y * u), Offset((10 + w) * u, y * u), 1.4f * u, StrokeCap.Round)
            }
        }
    }
}

/** Three screen lines that shimmer one after another while linked. */
@Composable
private fun screenLines(live: Boolean): List<Float> {
    if (!live || AplusTheme.motion.reduced) return listOf(1f, 1f, 1f)
    val t = rememberInfiniteTransition(label = "screen")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800)), label = "screenT")
    return List(3) { i ->
        val phase = (p + i * 0.14f) % 1f
        0.35f + 0.65f * (1f - kotlin.math.abs(phase * 2 - 1))
    }
}
