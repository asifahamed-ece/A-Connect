package com.aplusconnect.companion.ui.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aplusconnect.companion.ui.Tone
import com.aplusconnect.companion.ui.theme.AplusTheme
import com.aplusconnect.companion.ui.theme.AplusType
import com.aplusconnect.companion.ui.theme.Radius
import com.aplusconnect.companion.ui.theme.Size
import com.aplusconnect.companion.ui.theme.Space

/* ---------- tone ---------- */

internal data class ToneColors(val fg: Color, val bg: Color)

@Composable
internal fun toneColors(tone: Tone): ToneColors {
    val c = AplusTheme.colors
    return when (tone) {
        Tone.Neutral -> ToneColors(c.textSecondary, c.surfaceSunken)
        Tone.Accent -> ToneColors(c.accentStrong, c.accentSubtle)
        Tone.Success -> ToneColors(c.success, c.successSubtle)
        Tone.Warning -> ToneColors(c.warning, c.warningSubtle)
        Tone.Danger -> ToneColors(c.danger, c.dangerSubtle)
    }
}

/* ---------- motion ---------- */

/**
 * Press feedback by transform only (§6.2): scale down within 90 ms, release on
 * the spring. Layout bounds and hit areas never move.
 */
@Composable
internal fun Modifier.pressScale(source: InteractionSource, pressed: Float = 0.97f): Modifier {
    val motion = AplusTheme.motion
    val isPressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && !motion.reduced) pressed else 1f,
        animationSpec = if (isPressed) motion.press() else motion.release(),
        label = "press",
    )
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

/* ---------- surfaces ---------- */

/**
 * Solid content card (§8.5): surface, r-lg, 1 dp hairline, 20 dp padding.
 * [glow] draws a hairline glow on the top edge (connected state), never a border-width change.
 */
@Composable
internal fun AplusCard(
    modifier: Modifier = Modifier,
    glow: Tone? = null,
    padding: PaddingValues = PaddingValues(Space.s5),
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = AplusTheme.colors
    val shape = RoundedCornerShape(Radius.lg)
    val glowColor = glow?.let { toneColors(it).fg }
    val source = remember { MutableInteractionSource() }
    var m = modifier
        .fillMaxWidth()
    if (onClick != null) m = m.pressScale(source, 0.985f)
    m = m
        .clip(shape)
        .background(cardFill())
        .border(1.dp, c.divider, shape)
    if (glowColor != null) {
        m = m.drawWithContent {
            drawContent()
            drawRect(
                Brush.verticalGradient(
                    listOf(glowColor.copy(alpha = 0.16f), Color.Transparent),
                    endY = 56.dp.toPx(),
                )
            )
            drawLine(
                Brush.horizontalGradient(listOf(Color.Transparent, glowColor, Color.Transparent)),
                start = Offset(0f, 0.5.dp.toPx()),
                end = Offset(size.width, 0.5.dp.toPx()),
                strokeWidth = 1.5.dp.toPx(),
            )
        }
    }
    if (onClick != null) {
        m = m.clickable(interactionSource = source, indication = androidx.compose.material3.ripple(), onClickLabel = onClickLabel, onClick = onClick)
    }
    Column(modifier = m.padding(padding), content = content)
}

/** A sentence-case section header (§3: no all-caps). */
@Composable
internal fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = Space.s1, top = Space.s2)
            .heightIn(min = if (trailing != null) Size.target else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = AplusType.caption,
            color = AplusTheme.colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke(this)
    }
}

@Composable
internal fun HairlineDivider(inset: Dp = 0.dp) {
    HorizontalDivider(
        modifier = Modifier.padding(start = inset),
        thickness = 1.dp,
        color = AplusTheme.colors.divider,
    )
}

/* ---------- badges ---------- */

/**
 * Status pill (§8.6): 8 dp dot + word in the semantic subtle colours.
 * The dot breathes only while [breathing] (live work) and never under reduced motion.
 */
@Composable
internal fun StatusPill(label: String, tone: Tone, modifier: Modifier = Modifier, breathing: Boolean = false) {
    val t = toneColors(tone)
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(t.bg)
            .padding(horizontal = Space.s3, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusDot(t.fg, breathing)
        Text(label, style = AplusType.caption, color = t.fg)
    }
}

@Composable
internal fun StatusDot(color: Color, breathing: Boolean, size: Dp = Size.statusDot) {
    val animate = breathing && !AplusTheme.motion.reduced
    val alpha = if (animate) {
        val transition = rememberInfiniteTransition(label = "breathe")
        val a by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
            label = "breatheAlpha",
        )
        a
    } else 1f
    Box(
        Modifier
            .size(size)
            .graphicsLayer { this.alpha = alpha }
            .clip(CircleShape)
            .background(color)
    )
}

/** Neutral tag, e.g. "Encrypted", "Read-only". */
@Composable
internal fun Tag(text: String, icon: ImageVector? = null) {
    val c = AplusTheme.colors
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.xs))
            .background(c.surfaceSunken)
            .padding(horizontal = Space.s2, vertical = Space.s1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s1),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(14.dp))
        Text(text, style = AplusType.caption, color = c.textSecondary)
    }
}

/** 40 dp icon tile, subtle tone background. */
@Composable
internal fun IconTile(icon: ImageVector, tone: Tone = Tone.Accent, size: Dp = Size.tile) {
    val t = toneColors(tone)
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(Radius.sm))
            .background(t.bg),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = t.fg, modifier = Modifier.size(Size.iconMd))
    }
}

/* ---------- buttons (§8.1) ---------- */

@Composable
private fun ButtonContent(text: String, icon: ImageVector?, loading: Boolean, color: Color) {
    if (loading) {
        CircularProgressIndicator(color = color, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Space.s2))
    } else if (icon != null) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Space.s2))
    }
    Text(text, style = AplusType.label, maxLines = 1)
}

/** The one main action per surface. [fullWidth] gives the 52 dp full-width variant. */
@Composable
internal fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    loading: Boolean = false,
    enabled: Boolean = true,
    fullWidth: Boolean = false,
) {
    val c = AplusTheme.colors
    val source = remember { MutableInteractionSource() }
    Button(
        onClick = { if (!loading) onClick() },
        enabled = enabled,
        interactionSource = source,
        shape = RoundedCornerShape(Radius.sm),
        colors = ButtonDefaults.buttonColors(
            containerColor = c.accent,
            contentColor = c.onAccent,
            disabledContainerColor = c.accent.copy(alpha = 0.45f),
            disabledContentColor = c.onAccent.copy(alpha = 0.8f),
        ),
        contentPadding = PaddingValues(horizontal = Space.s5),
        modifier = modifier
            .pressScale(source)
            .then(if (fullWidth) Modifier.fillMaxWidth().height(Size.primaryButton) else Modifier.height(Size.control))
            .defaultMinSize(minHeight = Size.control),
    ) { ButtonContent(text, icon, loading, c.onAccent) }
}

@Composable
internal fun TonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    loading: Boolean = false,
    enabled: Boolean = true,
    fullWidth: Boolean = false,
) {
    val c = AplusTheme.colors
    val source = remember { MutableInteractionSource() }
    FilledTonalButton(
        onClick = { if (!loading) onClick() },
        enabled = enabled,
        interactionSource = source,
        shape = RoundedCornerShape(Radius.sm),
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = c.accentSubtle, contentColor = c.accentStrong),
        contentPadding = PaddingValues(horizontal = Space.s4),
        modifier = modifier
            .pressScale(source)
            .then(if (fullWidth) Modifier.fillMaxWidth().height(Size.target) else Modifier.height(Size.control)),
    ) { ButtonContent(text, icon, loading, c.accentStrong) }
}

@Composable
internal fun OutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    danger: Boolean = false,
    enabled: Boolean = true,
    fullWidth: Boolean = false,
) {
    val c = AplusTheme.colors
    val source = remember { MutableInteractionSource() }
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = source,
        shape = RoundedCornerShape(Radius.sm),
        border = androidx.compose.foundation.BorderStroke(1.dp, c.outline),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = c.surface,
            contentColor = if (danger) c.danger else c.textPrimary,
        ),
        contentPadding = PaddingValues(horizontal = Space.s4),
        modifier = modifier
            .pressScale(source)
            .then(if (fullWidth) Modifier.fillMaxWidth().height(Size.target) else Modifier.height(Size.control)),
    ) { ButtonContent(text, icon, false, c.textPrimary) }
}

/** Low emphasis. Use [danger] for destructive actions outside a confirmation dialog. */
@Composable
internal fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, danger: Boolean = false, icon: ImageVector? = null) {
    val c = AplusTheme.colors
    TextButton(
        onClick = onClick,
        shape = RoundedCornerShape(Radius.sm),
        colors = ButtonDefaults.textButtonColors(contentColor = if (danger) c.danger else c.accent),
        modifier = modifier.heightIn(min = Size.target),
    ) { ButtonContent(text, icon, false, c.accent) }
}

/** Filled danger: only ever the confirm button of a destructive dialog (§8.1). */
@Composable
internal fun DangerConfirmButton(text: String, onClick: () -> Unit) {
    val c = AplusTheme.colors
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(Radius.sm),
        colors = ButtonDefaults.buttonColors(containerColor = c.danger, contentColor = c.surface),
        modifier = Modifier.height(Size.control),
    ) { Text(text, style = AplusType.label) }
}

/* ---------- rows (§8.4) ---------- */

/**
 * A setting that is a switch. The whole row toggles, with merged semantics,
 * so TalkBack reads "Phone connection, On — …, Switch, on" (audit A10).
 */
@Composable
internal fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val c = AplusTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Size.rowDouble)
            .clip(RoundedCornerShape(Radius.sm))
            .toggleable(
                value = checked,
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(vertical = Space.s2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s4),
    ) {
        if (icon != null) IconTile(icon, if (checked) Tone.Accent else Tone.Neutral)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = AplusType.body.copy(fontWeight = AplusType.label.fontWeight), color = c.textPrimary)
            if (subtitle != null) Text(subtitle, style = AplusType.bodySm, color = c.textSecondary)
        }
        // onCheckedChange = null: the row owns the click and the semantics.
        GlassSwitch(checked = checked, pressed = pressed, enabled = enabled)
    }
}

/**
 * A row that leads somewhere or asks for something: permission, folder,
 * device name. Granted permissions show a success word instead of a button.
 */
@Composable
internal fun ActionRow(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTone: Tone = Tone.Accent,
    subtitleStyle: TextStyle = AplusType.bodySm,
    subtitleColor: Color? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    done: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val c = AplusTheme.colors
    var m = modifier.fillMaxWidth().heightIn(min = Size.rowDouble).clip(RoundedCornerShape(Radius.sm))
    if (onClick != null) m = m.clickable(role = Role.Button, onClick = onClick)
    Row(
        modifier = m.padding(vertical = Space.s2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s4),
    ) {
        if (icon != null) IconTile(icon, iconTone)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = AplusType.body.copy(fontWeight = AplusType.label.fontWeight), color = c.textPrimary)
            if (subtitle != null) {
                Text(subtitle, style = subtitleStyle, color = subtitleColor ?: c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        when {
            done != null -> StatusPill(done, Tone.Success)
            actionLabel != null && onAction != null -> TonalButton(actionLabel, onAction)
            onClick != null -> Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = c.textSecondary,
            )
        }
    }
}

/* ---------- feedback blocks (§8.10) ---------- */

/** Inline banner at the top of the affected card. */
@Composable
internal fun Banner(
    text: String,
    tone: Tone,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val t = toneColors(tone)
    val icon = when (tone) {
        Tone.Warning -> Icons.Outlined.WarningAmber
        Tone.Danger -> Icons.Outlined.ErrorOutline
        Tone.Success -> Icons.Outlined.CheckCircle
        else -> Icons.Outlined.Info
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(t.bg)
            .padding(start = Space.s4, end = Space.s2, top = Space.s3, bottom = Space.s3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        Icon(icon, contentDescription = null, tint = t.fg, modifier = Modifier.size(Size.iconMd))
        Text(text, style = AplusType.bodySm, color = t.fg, modifier = Modifier.weight(1f))
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction, colors = ButtonDefaults.textButtonColors(contentColor = t.fg), modifier = Modifier.heightIn(min = Size.target)) {
                Text(actionLabel, style = AplusType.label)
            }
        }
    }
}

/** Empty state: icon in a 64 dp accent-subtle circle, a title, one line, one recovery action. */
@Composable
internal fun EmptyBlock(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val c = AplusTheme.colors
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = Space.s8, horizontal = Space.s4),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(c.accentSubtle),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = c.accentStrong, modifier = Modifier.size(32.dp)) }
        Spacer(Modifier.height(Space.s1))
        Text(title, style = AplusType.title, color = c.textPrimary, textAlign = TextAlign.Center)
        Text(body, style = AplusType.bodySm, color = c.textSecondary, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(Space.s2))
            TonalButton(actionLabel, onAction)
        }
    }
}

/* ---------- data ---------- */

/** A compact stat: value in tabular figures over a label. */
@Composable
internal fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    val c = AplusTheme.colors
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.md))
            .background(cardFill())
            .padding(horizontal = Space.s3, vertical = Space.s3)
            .semantics(mergeDescendants = true) { contentDescription = "$label: $value" },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(value, style = AplusType.title.copy(fontFeatureSettings = AplusType.tabular), color = c.textPrimary, maxLines = 1)
        Text(label, style = AplusType.caption, color = c.textSecondary, maxLines = 1)
    }
}

internal enum class TransferState { Queued, Active, Done, Failed }

/** Transfer row (§8.11): icon tile, name, meta, 4 dp pill progress bar. */
@Composable
internal fun TransferRow(
    name: String,
    meta: String,
    state: TransferState,
    progress: Float?,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = AplusTheme.colors
    val tone = when (state) {
        TransferState.Done -> Tone.Success
        TransferState.Failed -> Tone.Danger
        TransferState.Active -> Tone.Accent
        TransferState.Queued -> Tone.Neutral
    }
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = Size.rowDouble).padding(vertical = Space.s2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s4),
    ) {
        IconTile(icon, tone)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(name, style = AplusType.body, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (state == TransferState.Active) {
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                        color = c.accent,
                        trackColor = c.divider,
                        strokeCap = StrokeCap.Round,
                        gapSize = 0.dp,
                        drawStopIndicator = {},
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                        color = c.accent,
                        trackColor = c.divider,
                    )
                }
            }
            Text(
                meta,
                style = AplusType.bodySm.copy(fontFeatureSettings = AplusType.tabular),
                color = when (state) {
                    TransferState.Failed -> c.danger
                    TransferState.Done -> c.success
                    else -> c.textSecondary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing?.invoke()
    }
}
