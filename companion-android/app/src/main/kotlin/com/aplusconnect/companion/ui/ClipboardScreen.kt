package com.aplusconnect.companion.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.aplusconnect.companion.data.ClipHistoryEntry
import com.aplusconnect.companion.ui.components.AplusCard
import com.aplusconnect.companion.ui.components.ActionRow
import com.aplusconnect.companion.ui.components.Banner
import com.aplusconnect.companion.ui.components.EmptyBlock
import com.aplusconnect.companion.ui.components.GhostButton
import com.aplusconnect.companion.ui.components.HairlineDivider
import com.aplusconnect.companion.ui.components.PrimaryButton
import com.aplusconnect.companion.ui.components.SectionHeader
import com.aplusconnect.companion.ui.components.StatusPill
import com.aplusconnect.companion.ui.components.SwitchRow
import com.aplusconnect.companion.ui.components.TonalButton
import com.aplusconnect.companion.ui.theme.AplusTheme
import com.aplusconnect.companion.ui.theme.AplusType
import com.aplusconnect.companion.ui.theme.Size
import com.aplusconnect.companion.ui.theme.Space

/**
 * Clipboard (DESIGN.md §10.3): sync, keyboard setup (the only place it
 * appears), sending text, and the history inline instead of a dialog.
 */
@Composable
internal fun ClipboardScreen(ui: ClipboardUi, actions: ClipboardActions, contentPadding: PaddingValues) {
    val c = AplusTheme.colors
    val (statusLabel, statusTone) = clipboardStatus(ui)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = Space.screen),
        verticalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        PageTitle("Clipboard") {
            StatusPill(statusLabel, statusTone, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }

        if (!ui.keyboardActive) {
            AplusCard {
                Banner("Copies need an extra tap until A+ Connect Keyboard is chosen.", Tone.Warning)
                Box(Modifier.height(Space.s4))
                Text(
                    "Android lets only the default keyboard read the clipboard in the background, so the keyboard is what carries a copy to the computer. There's no accessibility option on purpose: it would block contactless payments.",
                    style = AplusType.bodySm,
                    color = c.textSecondary,
                )
                Box(Modifier.height(Space.s4))
                PrimaryButton("Choose keyboard", actions.onChooseKeyboard, icon = Icons.Outlined.Keyboard, fullWidth = true)
            }
        }

        AplusCard(padding = PaddingValues(horizontal = Space.s5, vertical = Space.s2)) {
            SwitchRow(
                title = "Clipboard sync",
                subtitle = when {
                    !ui.syncOn -> "Off — copies stay on this device."
                    !ui.connected -> "Starts when the computer is connected."
                    !ui.keyboardActive -> "On, but copies need an extra tap."
                    else -> "Copy on one device, paste on the other."
                },
                checked = ui.syncOn,
                onCheckedChange = actions.onToggleSync,
                icon = Icons.Outlined.ContentPaste,
            )
            if (ui.keyboardActive) {
                HairlineDivider(inset = Size.tile + Space.s4)
                ActionRow(
                    title = "A+ Connect Keyboard",
                    subtitle = "Active. Updating the app can reset it; set it again here.",
                    icon = Icons.Outlined.Keyboard,
                    iconTone = Tone.Success,
                    actionLabel = "Change",
                    onAction = actions.onChooseKeyboard,
                )
            }
        }

        SectionHeader("Last synced")
        AplusCard {
            if (ui.lastText.isNullOrEmpty()) {
                Text("Copy text on either device to test sync.", style = AplusType.bodySm, color = c.textSecondary)
            } else {
                DirectionLine(ui.lastDirection, ui.lastTime)
                Box(Modifier.height(Space.s2))
                Text(ui.lastText, style = AplusType.body, color = c.textPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.height(Space.s4))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                TonalButton("Send text…", actions.onSendText, icon = Icons.Outlined.TextFields, enabled = ui.connected, modifier = Modifier.weight(1f))
                TonalButton("Send clipboard", actions.onSendCurrent, icon = Icons.Outlined.ContentPaste, enabled = ui.connected, modifier = Modifier.weight(1f))
            }
            if (!ui.connected) {
                Box(Modifier.height(Space.s2))
                Text("Connect a computer first.", style = AplusType.caption, color = c.textSecondary)
            }
        }

        SectionHeader("History") {
            if (ui.history.isNotEmpty()) GhostButton("Clear history", actions.onClearHistory, danger = true)
        }
        AplusCard(padding = PaddingValues(horizontal = Space.s5, vertical = Space.s2)) {
            if (ui.history.isEmpty()) {
                EmptyBlock(
                    Icons.Outlined.ContentPaste,
                    "No clips yet",
                    "Copy something on either device to see it here.",
                    actionLabel = if (!ui.connected) "Connect a computer" else null,
                    onAction = if (!ui.connected) actions.onGoToConnect else null,
                )
            }
            ui.history.forEachIndexed { i, entry ->
                if (i > 0) HairlineDivider()
                HistoryRow(entry, onCopy = { actions.onCopy(entry) })
            }
        }
        Box(Modifier.height(Space.s6))
    }
}

@Composable
private fun DirectionLine(direction: String?, time: Long) {
    val c = AplusTheme.colors
    val label = directionLabel(direction) ?: return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s1)) {
        Icon(
            Icons.AutoMirrored.Outlined.ArrowForward,
            contentDescription = null,
            tint = c.textSecondary,
            // From computer points back toward the phone.
            modifier = Modifier.size(Size.iconSm).graphicsLayer { if (direction == "pc_to_phone") rotationZ = 180f },
        )
        Text(
            if (time > 0) "$label · ${relative(time)}" else label,
            style = AplusType.caption.copy(fontFeatureSettings = AplusType.tabular),
            color = c.textSecondary,
        )
    }
}

@Composable
private fun HistoryRow(entry: ClipHistoryEntry, onCopy: () -> Unit) {
    val c = AplusTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = Space.s3), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
            DirectionLine(entry.direction, entry.timestamp)
            Text(entry.text, style = AplusType.body, color = c.textPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onCopy, modifier = Modifier.size(Size.target)) {
            Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy to this phone", tint = c.textSecondary)
        }
    }
}

private fun relative(time: Long): String =
    DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
