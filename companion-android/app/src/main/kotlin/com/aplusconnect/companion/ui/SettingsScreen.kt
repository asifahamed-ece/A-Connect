package com.aplusconnect.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material.icons.outlined.BlurOff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.aplusconnect.companion.ui.components.AplusCard
import com.aplusconnect.companion.ui.components.ActionRow
import com.aplusconnect.companion.ui.components.GlassSlider
import com.aplusconnect.companion.ui.components.HairlineDivider
import com.aplusconnect.companion.ui.components.SectionHeader
import com.aplusconnect.companion.ui.components.SwitchRow
import com.aplusconnect.companion.ui.theme.AplusTheme
import com.aplusconnect.companion.ui.theme.AplusType
import com.aplusconnect.companion.ui.theme.Size
import com.aplusconnect.companion.ui.theme.Space
import com.aplusconnect.companion.ui.theme.ThemeMode

internal data class SettingsUi(
    val themeMode: ThemeMode,
    val reduceTransparency: Boolean,
    val reduceMotion: Boolean,
    val barClarity: Int,
    val deviceName: String,
    val version: String,
    val systemVersion: String,
)

internal class SettingsActions(
    val onThemeMode: (ThemeMode) -> Unit,
    val onReduceTransparency: (Boolean) -> Unit,
    val onReduceMotion: (Boolean) -> Unit,
    val onBarClarity: (Int) -> Unit,
    val onBarClarityDone: () -> Unit,
    val onRename: () -> Unit,
)

/** Settings (DESIGN.md §10.5): appearance, device name, about. Feature settings live on their pages. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(ui: SettingsUi, actions: SettingsActions, contentPadding: PaddingValues) {
    val c = AplusTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = Space.screen),
        verticalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        PageTitle("Settings")

        SectionHeader("Appearance")
        AplusCard(padding = PaddingValues(horizontal = Space.s5, vertical = Space.s4)) {
            Text("Theme", style = AplusType.label, color = c.textPrimary)
            Box(Modifier.height(Space.s3))
            val modes = ThemeMode.entries
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                modes.forEachIndexed { i, mode ->
                    SegmentedButton(
                        selected = ui.themeMode == mode,
                        onClick = { actions.onThemeMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = c.accentSubtle,
                            activeContentColor = c.accentStrong,
                            activeBorderColor = c.outline,
                            inactiveContainerColor = c.surface,
                            inactiveContentColor = c.textSecondary,
                            inactiveBorderColor = c.outline,
                        ),
                        label = { Text(if (mode == ThemeMode.System) "System" else mode.label, style = AplusType.label) },
                        modifier = Modifier.height(Size.target),
                    )
                }
            }
            Box(Modifier.height(Space.s5))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Bar transparency", style = AplusType.label, color = c.textPrimary, modifier = Modifier.weight(1f))
                Text(
                    if (ui.reduceTransparency) "Solid" else "${ui.barClarity}%",
                    style = AplusType.label,
                    color = c.textSecondary,
                )
            }
            GlassSlider(
                value = if (ui.reduceTransparency) 0 else ui.barClarity,
                onValueChange = actions.onBarClarity,
                onValueChangeFinished = actions.onBarClarityDone,
                enabled = !ui.reduceTransparency,
                contentDescription = "Bar transparency",
            )
            Row {
                Text("Solid", style = AplusType.caption, color = c.textMuted, modifier = Modifier.weight(1f))
                Text("Clear", style = AplusType.caption, color = c.textMuted)
            }
            Box(Modifier.height(Space.s2))
            Text(
                if (ui.reduceTransparency) "Turn off Reduce transparency to adjust."
                else "Applies to the bottom bar and the toggles. Text stays readable at every level.",
                style = AplusType.caption,
                color = c.textSecondary,
            )
            Box(Modifier.height(Space.s2))
            SwitchRow(
                title = "Reduce transparency",
                subtitle = "Solid bars instead of glass.",
                checked = ui.reduceTransparency,
                onCheckedChange = actions.onReduceTransparency,
                icon = Icons.Outlined.BlurOff,
            )
            HairlineDivider(inset = Size.tile + Space.s4)
            SwitchRow(
                title = "Reduce motion",
                subtitle = "No springs, slides or pulsing. Also follows the system setting.",
                checked = ui.reduceMotion,
                onCheckedChange = actions.onReduceMotion,
                icon = Icons.Outlined.Animation,
            )
        }

        SectionHeader("This phone")
        AplusCard(padding = PaddingValues(horizontal = Space.s5, vertical = Space.s2)) {
            ActionRow(
                title = "Device name",
                subtitle = ui.deviceName,
                icon = Icons.Outlined.Smartphone,
                iconTone = Tone.Neutral,
                onClick = actions.onRename,
            )
        }

        SectionHeader("About")
        AplusCard(padding = PaddingValues(horizontal = Space.s5, vertical = Space.s2)) {
            ActionRow(
                title = "A+ Connect ${ui.version}",
                subtitle = "System version ${ui.systemVersion}",
                icon = Icons.Outlined.Info,
                iconTone = Tone.Neutral,
            )
        }
        Box(Modifier.height(Space.s6))
    }
}
