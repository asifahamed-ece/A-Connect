package com.aplusconnect.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aplusconnect.companion.ui.components.AplusCard
import com.aplusconnect.companion.ui.components.ActionRow
import com.aplusconnect.companion.ui.components.Banner
import com.aplusconnect.companion.ui.components.GhostButton
import com.aplusconnect.companion.ui.components.HairlineDivider
import com.aplusconnect.companion.ui.components.IconTile
import com.aplusconnect.companion.ui.components.OutlineButton
import com.aplusconnect.companion.ui.components.SectionHeader
import com.aplusconnect.companion.ui.components.TransferRow
import com.aplusconnect.companion.ui.components.TransferState
import com.aplusconnect.companion.ui.theme.AplusTheme
import com.aplusconnect.companion.ui.theme.AplusType
import com.aplusconnect.companion.ui.theme.Size
import com.aplusconnect.companion.ui.theme.Space

/**
 * Files (DESIGN.md §10.2): send, what is sending now, where received files go,
 * and whether the computer may browse this phone. Finished transfers are in
 * History (the button at the top right), so nothing is listed twice.
 */
@Composable
internal fun FilesScreen(ui: FilesUi, actions: FilesActions, contentPadding: PaddingValues) {
    val c = AplusTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = Space.screen),
        verticalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        PageTitle("Files") {
            OutlineButton(
                if (ui.historyCount > 0) "History · ${ui.historyCount}" else "History",
                actions.onOpenHistory,
                icon = Icons.Outlined.History,
            )
        }
        Text(
            if (ui.connected) "To ${ui.computerLabel} · Downloads" else "Not connected",
            style = AplusType.mono,
            color = c.textSecondary,
        )

        if (!ui.connected) {
            Banner(
                "Connect to your computer to send files.",
                Tone.Neutral,
                actionLabel = "Connect",
                onAction = actions.onGoToConnect,
            )
        }

        SectionHeader("Send file")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
            SendTile("File", Icons.Outlined.Description, ui.connected, actions.onPickFile, Modifier.weight(1f))
            SendTile("Photos and videos", Icons.Outlined.PhotoLibrary, ui.connected, actions.onPickMedia, Modifier.weight(1f))
        }

        // Only what is moving now, or failed and can be retried. Finished
        // files are listed once, in History - no second transfers list here.
        val live = ui.transfers.filter { it.isLive() }
        if (live.isNotEmpty()) {
            SectionHeader("Sending now")
            AplusCard(padding = PaddingValues(horizontal = Space.s5, vertical = Space.s2)) {
                live.forEachIndexed { i, t ->
                    if (i > 0) HairlineDivider(inset = Size.tile + Space.s4)
                    TransferRow(
                        name = t.name,
                        meta = transferMeta(t),
                        state = t.state,
                        progress = t.progress,
                        icon = Icons.Outlined.Upload,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        trailing = if (t.state == TransferState.Failed) {
                            {
                                Row {
                                    GhostButton("Retry", { actions.onRetry(t) })
                                    GhostButton("Dismiss", { actions.onDismiss(t) })
                                }
                            }
                        } else null,
                    )
                }
            }
        }

        SectionHeader("On this phone")
        AplusCard(padding = PaddingValues(horizontal = Space.s5, vertical = Space.s2)) {
            ActionRow(
                title = "Received files go to",
                subtitle = ui.receivedFolder,
                icon = Icons.Outlined.FolderOpen,
                iconTone = Tone.Neutral,
                actionLabel = "Change",
                onAction = actions.onChangeFolder,
            )
            HairlineDivider(inset = Size.tile + Space.s4)
            ActionRow(
                title = "Let the computer browse this phone",
                subtitle = if (ui.browseGranted) "Read-only. It can look, search and copy, never change anything."
                else "Needs all files access. It isn't an accessibility service, so payments keep working.",
                icon = Icons.Outlined.Folder,
                iconTone = if (ui.browseGranted) Tone.Success else Tone.Accent,
                actionLabel = if (ui.browseGranted) "Manage" else "Allow",
                onAction = actions.onGrantBrowse,
            )
            if (ui.storageFreeBytes != null) {
                HairlineDivider(inset = Size.tile + Space.s4)
                ActionRow(
                    title = "Storage free",
                    subtitle = humanBytes(ui.storageFreeBytes),
                    subtitleStyle = AplusType.numeric,
                    icon = Icons.Outlined.Storage,
                    iconTone = Tone.Neutral,
                )
            }
        }
        Box(Modifier.height(Space.s6))
    }
}

private fun transferMeta(t: TransferItem): String = when (t.state) {
    TransferState.Queued -> "Waiting…"
    TransferState.Active -> buildString {
        if (t.sizeBytes > 0) append(humanBytes(t.sizeBytes)).append(" · ")
        append(t.progress?.let { "${(it * 100).toInt()}%" } ?: "Sending…")
        append(" · To computer")
    }
    TransferState.Done -> "Sent to Downloads"
    TransferState.Failed -> "Didn't send: ${t.message ?: "the connection dropped"}"
}

@Composable
private fun SendTile(label: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val c = AplusTheme.colors
    AplusCard(
        modifier = modifier
            .heightIn(min = 112.dp)
            .semantics { role = Role.Button },
        onClick = if (enabled) onClick else null,
        onClickLabel = "Send $label",
    ) {
        IconTile(icon, if (enabled) Tone.Accent else Tone.Neutral)
        Box(Modifier.height(Space.s3))
        Text(label, style = AplusType.label, color = if (enabled) c.textPrimary else c.textSecondary)
    }
}

