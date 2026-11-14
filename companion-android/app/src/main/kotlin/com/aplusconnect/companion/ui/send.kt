package com.aplusconnect.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Tab 1: Send File (LocalSend Selection Grid + Target PC)
 */
@Composable
internal fun SendTab(
    isConnected: Boolean,
    host: String,
    pcId: String,
    isUploading: Boolean,
    uploadFileName: String,
    progress: Float,
    statusMessage: String?,
    onPickFile: () -> Unit,
    onPickMedia: () -> Unit,
    onSendClipboard: () -> Unit,
    onSendTextClick: () -> Unit,
    surfaceColor: Color,
    elevatedColor: Color,
    borderColor: Color,
    textPrimary: Color,
    textSecondary: Color,
    textMuted: Color
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "SELECT CONTENT",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = textMuted,
            letterSpacing = 0.8.sp,
            modifier = Modifier.padding(start = 4.dp)
        )

        // Selection Grid (Inspired directly by LocalSend!)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SelectionCard(
                icon = Icons.Default.Description,
                label = "File",
                onClick = onPickFile,
                modifier = Modifier.weight(1f),
                surfaceColor = surfaceColor,
                borderColor = borderColor,
                textPrimary = textPrimary
            )
            SelectionCard(
                icon = Icons.Default.Image,
                label = "Media",
                onClick = onPickMedia,
                modifier = Modifier.weight(1f),
                surfaceColor = surfaceColor,
                borderColor = borderColor,
                textPrimary = textPrimary
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SelectionCard(
                icon = Icons.Default.ContentPaste,
                label = "Paste",
                onClick = onSendClipboard,
                modifier = Modifier.weight(1f),
                surfaceColor = surfaceColor,
                borderColor = borderColor,
                textPrimary = textPrimary
            )
            SelectionCard(
                icon = Icons.Default.TextFields,
                label = "Text",
                onClick = onSendTextClick,
                modifier = Modifier.weight(1f),
                surfaceColor = surfaceColor,
                borderColor = borderColor,
                textPrimary = textPrimary
            )
        }

        // Live Uploading Status Card
        if (isUploading || statusMessage != null) {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = surfaceColor),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, borderColor, RoundedCornerShape(18.dp))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isUploading) "Transferring $uploadFileName" else "Transfer Status",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (isUploading) {
                            Text("${(progress * 100).toInt()}%", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ColorOSGreen)
                        }
                    }

                    if (isUploading) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = ColorOSGreen
                        )
                    } else if (statusMessage != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(
                                imageVector = if (statusMessage.contains("failed", true)) Icons.Default.Warning else Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = if (statusMessage.contains("failed", true)) ColorOSError else ColorOSGreen,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = statusMessage,
                                fontSize = 13.sp,
                                color = if (statusMessage.contains("failed", true)) ColorOSError else ColorOSGreen
                            )
                        }
                    }
                }
            }
        }

        // Target PC Card
        Text(
            text = "TARGET PC",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = textMuted,
            letterSpacing = 0.8.sp,
            modifier = Modifier.padding(start = 4.dp, top = 6.dp)
        )

        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = surfaceColor),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, borderColor, RoundedCornerShape(20.dp))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(elevatedColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Laptop, contentDescription = null, tint = textPrimary, modifier = Modifier.size(24.dp))
                    }
                    Column {
                        Text(pcId, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = textPrimary)
                        Text(if (host.isNotEmpty()) "$host:24443" else "Not configured", fontSize = 12.sp, color = textSecondary)
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isConnected) ColorOSGreen.copy(alpha = 0.15f) else ColorOSWarning.copy(alpha = 0.15f))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = if (isConnected) "READY" else "OFFLINE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isConnected) ColorOSGreen else ColorOSWarning
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectionCard(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    surfaceColor: Color,
    borderColor: Color,
    textPrimary: Color
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = surfaceColor),
        modifier = modifier
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .clickable { onClick() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(icon, contentDescription = label, tint = ColorOSGreen, modifier = Modifier.size(28.dp))
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = textPrimary)
        }
    }
}
