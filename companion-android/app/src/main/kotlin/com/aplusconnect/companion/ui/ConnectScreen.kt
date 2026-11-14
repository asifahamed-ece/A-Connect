package com.aplusconnect.companion.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aplusconnect.companion.ui.components.AplusCard
import com.aplusconnect.companion.ui.components.ActionRow
import com.aplusconnect.companion.ui.components.Banner
import com.aplusconnect.companion.ui.components.GhostButton
import com.aplusconnect.companion.ui.components.HairlineDivider
import com.aplusconnect.companion.ui.components.PrimaryButton
import com.aplusconnect.companion.ui.components.SectionHeader
import com.aplusconnect.companion.ui.components.StatTile
import com.aplusconnect.companion.ui.components.StatusPill
import com.aplusconnect.companion.ui.components.SwitchRow
import com.aplusconnect.companion.ui.components.Tag
import com.aplusconnect.companion.ui.components.TonalButton
import com.aplusconnect.companion.ui.components.toneColors
import com.aplusconnect.companion.ui.theme.AplusTheme
import com.aplusconnect.companion.ui.theme.AplusType
import com.aplusconnect.companion.ui.theme.Radius
import com.aplusconnect.companion.ui.theme.Size
import com.aplusconnect.companion.ui.theme.Space

/**
 * Connect (DESIGN.md §10.1): everything about the link lives here — status,
 * pairing, the kill switch, reconnect, forget, mirroring, and the two
 * permissions that keep the link useful in the background.
 */
@Composable
internal fun ConnectScreen(ui: ConnectUi, actions: ConnectActions, contentPadding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = Space.screen),
        verticalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        PageTitle("Connect")

        Hero(ui, actions)

        if (ui.phase == ConnectionPhase.Connected || ui.phase == ConnectionPhase.Mirroring) {
            if (!ui.keyboardActive) {
                Banner(
                    "Clipboard won't sync until A+ Connect Keyboard is chosen.",
                    Tone.Warning,
                    actionLabel = "Set keyboard",
                    onAction = actions.onSetKeyboard,
                )
            }
            Vitals(ui.vitals)
            MirrorCard(mirroring = ui.phase == ConnectionPhase.Mirroring, onStop = actions.onStopMirroring)
        }

        SectionHeader("Connection")
        AplusCard(padding = PaddingValues(horizontal = Space.s5, vertical = Space.s2)) {
            SwitchRow(
                title = "Phone connection",
                subtitle = if (ui.companionEnabled) "On — the computer can reach this phone."
                else "Off — nothing is sent. The computer is told why.",
                checked = ui.companionEnabled,
                onCheckedChange = actions.onToggleCompanion,
                icon = Icons.Outlined.PowerSettingsNew,
            )
            if (ui.paired || ui.hostPort.isNotEmpty()) {
                HairlineDivider(inset = Size.tile + Space.s4)
                ActionRow(
                    title = "Computer",
                    subtitle = ui.hostPort.ifEmpty { "Not set" },
                    subtitleStyle = AplusType.mono,
                    icon = Icons.Outlined.Laptop,
                    iconTone = Tone.Neutral,
                    actionLabel = "Pair again",
                    onAction = actions.onScanQr,
                )
            }
            HairlineDivider(inset = Size.tile + Space.s4)
            ActionRow(
                title = "Encrypted",
                subtitle = "The computer's certificate is pinned. No one else can listen in.",
                icon = Icons.Outlined.Lock,
                iconTone = Tone.Neutral,
            )
            if (ui.paired) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    GhostButton("Forget pairing", actions.onForget, danger = true)
                }
            }
        }

        Permissions(ui, actions)
        Box(Modifier.height(Space.s6))
    }
}

@Composable
internal fun PageTitle(text: String, trailing: @Composable (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = Space.s4, bottom = Space.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = AplusType.titleLg,
            color = AplusTheme.colors.textPrimary,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        trailing?.invoke()
    }
}

@Composable
private fun Hero(ui: ConnectUi, actions: ConnectActions) {
    val c = AplusTheme.colors
    val glow = when (ui.phase) {
        ConnectionPhase.Connected, ConnectionPhase.Mirroring -> Tone.Success
        else -> null
    }
    AplusCard(glow = glow) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill(
                ui.phase.label,
                ui.phase.tone,
                breathing = ui.phase.breathes,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Box(Modifier.weight(1f))
            if (ui.phase != ConnectionPhase.NotPaired) Tag("Encrypted", Icons.Outlined.Lock)
        }

        Box(Modifier.height(Space.s5))
        DataLinkGraphic(ui.phase)
        Box(Modifier.height(Space.s5))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(ui.deviceName, style = AplusType.titleLg, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    transportLine(ui),
                    style = AplusType.bodySm,
                    color = c.textSecondary,
                    maxLines = 2,
                )
            }
            IconButton(onClick = actions.onRename, modifier = Modifier.size(Size.target)) {
                Icon(Icons.Outlined.Edit, contentDescription = "Rename this phone", tint = c.textSecondary)
            }
        }
        if (ui.hostPort.isNotEmpty() && ui.phase != ConnectionPhase.NotPaired) {
            Text(ui.hostPort, style = AplusType.mono, color = c.textSecondary)
        }

        val primary = ui.phase.primaryAction
        if (primary != PrimaryAction.None || ui.phase == ConnectionPhase.Connecting) {
            Box(Modifier.height(Space.s5))
        }
        when (primary) {
            PrimaryAction.ScanQr -> {
                PrimaryButton("Scan QR code", actions.onScanQr, icon = Icons.Outlined.QrCodeScanner, fullWidth = true)
                GhostButton("Enter code instead", actions.onEnterCode, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
            PrimaryAction.TurnOn -> PrimaryButton("Turn on", { actions.onToggleCompanion(true) }, fullWidth = true)
            PrimaryAction.Reconnect -> PrimaryButton("Reconnect", actions.onReconnect, fullWidth = true)
            PrimaryAction.StopMirroring -> {}
            PrimaryAction.None -> if (ui.phase == ConnectionPhase.Connecting) {
                TonalButton("Cancel", actions.onCancelConnect, fullWidth = true)
            }
        }
    }
}

private fun transportLine(ui: ConnectUi): String = when (ui.phase) {
    ConnectionPhase.NotPaired -> "Scan the QR code shown in A+ Connect on your computer."
    ConnectionPhase.Off -> "Phone connection is off."
    else -> ui.statusDetail
}

@Composable
private fun Vitals(v: PhoneVitals) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
        StatTile(v.batteryPercent?.let { "$it%" } ?: "—", if (v.charging) "Charging" else "Battery", Modifier.weight(1f))
        StatTile(v.androidVersion, "Android", Modifier.weight(1f))
        StatTile(v.storageFreeBytes?.let { humanBytes(it) } ?: "—", "Storage free", Modifier.weight(1f))
    }
}

@Composable
private fun MirrorCard(mirroring: Boolean, onStop: () -> Unit) {
    val c = AplusTheme.colors
    AplusCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s4)) {
            com.aplusconnect.companion.ui.components.IconTile(Icons.Outlined.Cast, if (mirroring) Tone.Success else Tone.Accent)
            Column(Modifier.weight(1f)) {
                Text("Mirror", style = AplusType.title, color = c.textPrimary)
                Text(
                    if (mirroring) "Your screen is showing on the computer."
                    else "Start mirroring from the computer. You'll be asked here to allow screen capture.",
                    style = AplusType.bodySm,
                    color = c.textSecondary,
                )
            }
        }
        if (mirroring) {
            Box(Modifier.height(Space.s4))
            TonalButton("Stop mirroring", onStop, fullWidth = true)
        }
    }
}

@Composable
private fun Permissions(ui: ConnectUi, actions: ConnectActions) {
    SectionHeader("Permissions")
    AplusCard(padding = PaddingValues(horizontal = Space.s5, vertical = Space.s2)) {
        val allSet = ui.batteryKeepAlive && ui.notificationAccess
        if (allSet) {
            ActionRow(
                title = "All set",
                subtitle = "Runs in the background and shares notifications.",
                icon = Icons.Outlined.CheckCircle,
                iconTone = Tone.Success,
            )
        } else {
            ActionRow(
                title = "Stay connected in the background",
                subtitle = "Keeps the link alive while the screen is off.",
                icon = Icons.Outlined.BatteryChargingFull,
                iconTone = if (ui.batteryKeepAlive) Tone.Success else Tone.Accent,
                done = if (ui.batteryKeepAlive) "Allowed" else null,
                actionLabel = "Allow",
                onAction = actions.onAllowBattery,
            )
            HairlineDivider(inset = Size.tile + Space.s4)
            ActionRow(
                title = "Notification access",
                subtitle = "Shows this phone's notifications on the computer.",
                icon = Icons.Outlined.Notifications,
                iconTone = if (ui.notificationAccess) Tone.Success else Tone.Accent,
                done = if (ui.notificationAccess) "Allowed" else null,
                actionLabel = "Allow",
                onAction = actions.onAllowNotifications,
            )
        }
    }
}
