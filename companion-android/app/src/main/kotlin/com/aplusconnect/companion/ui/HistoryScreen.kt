package com.aplusconnect.companion.ui

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aplusconnect.companion.data.Direction
import com.aplusconnect.companion.data.FileHistoryRules
import com.aplusconnect.companion.data.SharedFileEntry
import com.aplusconnect.companion.ui.components.AplusCard
import com.aplusconnect.companion.ui.components.EmptyBlock
import com.aplusconnect.companion.ui.components.GhostButton
import com.aplusconnect.companion.ui.components.HairlineDivider
import com.aplusconnect.companion.ui.components.IconTile
import com.aplusconnect.companion.ui.components.OutlineButton
import com.aplusconnect.companion.ui.components.Segment
import com.aplusconnect.companion.ui.theme.AplusTheme
import com.aplusconnect.companion.ui.theme.AplusType
import com.aplusconnect.companion.ui.theme.Size
import com.aplusconnect.companion.ui.theme.Space

internal data class HistoryUi(
    val entries: List<SharedFileEntry>,
    val direction: Direction,
)

internal class HistoryActions(
    val onBack: () -> Unit,
    val onDirection: (Direction) -> Unit,
    val onOpen: (SharedFileEntry) -> Unit,
    val onOpenFolder: () -> Unit,
    val onClear: () -> Unit,
)

/**
 * History: every file shared with the computer, sent or received. Opened from
 * the History button on Files; finished transfers live here, not on Files.
 */
@Composable
internal fun HistoryScreen(ui: HistoryUi, actions: HistoryActions, contentPadding: PaddingValues) {
    val c = AplusTheme.colors
    val sent = FileHistoryRules.count(ui.entries, Direction.Sent)
    val received = FileHistoryRules.count(ui.entries, Direction.Received)
    val rows = FileHistoryRules.of(ui.entries, ui.direction)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = Space.screen),
        verticalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = Space.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = actions.onBack, modifier = Modifier.heightIn(min = Size.target)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back to Files", tint = c.textPrimary)
            }
            Text(
                "History",
                style = AplusType.titleLg,
                color = c.textPrimary,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            if (ui.entries.isNotEmpty()) GhostButton("Clear", actions.onClear)
        }
        Text(
            "Recent files shared between this phone and the computer. Tap one to open it.",
            style = AplusType.bodySm,
            color = c.textSecondary,
        )

        Segment(
            options = listOf("Sent · $sent", "Received · $received"),
            selected = if (ui.direction == Direction.Sent) 0 else 1,
            onSelect = { actions.onDirection(if (it == 0) Direction.Sent else Direction.Received) },
        )

        OutlineButton(
            "Open folder",
            actions.onOpenFolder,
            icon = Icons.Outlined.FolderOpen,
            fullWidth = true,
        )

        AplusCard(padding = PaddingValues(horizontal = Space.s5, vertical = Space.s2)) {
            if (rows.isEmpty()) {
                if (ui.direction == Direction.Received) {
                    EmptyBlock(
                        Icons.Outlined.Download,
                        "Nothing received yet",
                        "Files the computer sends to this phone show up here.",
                    )
                } else {
                    EmptyBlock(
                        Icons.Outlined.Upload,
                        "Nothing sent yet",
                        "Files this phone sends to the computer show up here.",
                    )
                }
            }
            rows.forEachIndexed { i, entry ->
                if (i > 0) HairlineDivider(inset = Size.tile + Space.s4)
                HistoryRow(entry, onClick = { actions.onOpen(entry) })
            }
        }
        Box(Modifier.height(Space.s6))
    }
}

@Composable
private fun HistoryRow(entry: SharedFileEntry, onClick: () -> Unit) {
    val c = AplusTheme.colors
    val sent = entry.direction == Direction.Sent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Size.rowDouble)
            .clickable(role = Role.Button, onClickLabel = "Open ${entry.name}", onClick = onClick)
            .padding(vertical = Space.s2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s4),
    ) {
        IconTile(if (sent) Icons.Outlined.Upload else Icons.Outlined.Download, if (sent) Tone.Accent else Tone.Success)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.name, style = AplusType.body, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                historyMeta(entry),
                style = AplusType.bodySm.copy(fontFeatureSettings = AplusType.tabular),
                color = c.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun historyMeta(entry: SharedFileEntry): String = buildString {
    if (entry.sizeBytes > 0) append(humanBytes(entry.sizeBytes)).append(" · ")
    append(
        DateUtils.getRelativeTimeSpanString(entry.timestamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
    )
    append(if (entry.direction == Direction.Sent) " · To computer" else " · From computer")
}
