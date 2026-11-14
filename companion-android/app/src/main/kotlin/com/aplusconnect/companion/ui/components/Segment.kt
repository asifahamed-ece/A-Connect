package com.aplusconnect.companion.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.aplusconnect.companion.ui.theme.AplusTheme
import com.aplusconnect.companion.ui.theme.AplusType
import com.aplusconnect.companion.ui.theme.Size

/**
 * A two-option segmented control (§8.3), e.g. Sent / Received on History.
 * The selected option is a raised pill in the sunken track; selection is
 * shown by fill, never by a border-width change.
 */
@Composable
internal fun Segment(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = AplusTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(c.surfaceSunken)
            .padding(4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { index, label ->
            SegmentOption(label, index == selected, { onSelect(index) })
        }
    }
}

@Composable
private fun RowScope.SegmentOption(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = AplusTheme.colors
    val motion = AplusTheme.motion
    val fill by animateColorAsState(if (selected) c.surface else Color.Transparent, motion.fade(), label = "segmentFill")
    val text by animateColorAsState(if (selected) c.textPrimary else c.textSecondary, motion.fade(), label = "segmentText")
    Box(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = Size.control)
            .clip(CircleShape)
            .background(fill)
            .then(if (selected) Modifier.border(1.dp, c.divider, CircleShape) else Modifier)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = AplusType.label, color = text, maxLines = 1)
    }
}
