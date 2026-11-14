package com.aplusconnect.companion.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aplusconnect.companion.data.CompanionSettings

/**
 * Tab 0: Interconnect (Hero Connection Card + Shared Clipboard + Permissions)
 */
@Composable
internal fun InterconnectTab(
    settings: CompanionSettings,
    isConnected: Boolean,
    isConnecting: Boolean,
    statusText: String,
    deviceName: String,
    onRenameClick: () -> Unit,
    lastSyncedText: String?,
    lastDirection: String?,
    clipboardSyncEnabled: Boolean,
    onClipboardToggle: (Boolean) -> Unit,
    companionEnabled: Boolean,
    onCompanionToggle: (Boolean) -> Unit,
    accessibilityGranted: Boolean,
    batteryOptimized: Boolean,
    onOpenAccessibility: () -> Unit,
    onAllowBattery: () -> Unit,
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
        // Hero Interconnection Card
        HeroConnectionCard(
            isConnected = isConnected,
            isConnecting = isConnecting,
            statusText = statusText,
            host = settings.host,
            deviceName = deviceName,
            onRenameClick = onRenameClick,
            pairing = settings.pairing,
            surfaceColor = surfaceColor,
            elevatedColor = elevatedColor,
            borderColor = borderColor,
            textPrimary = textPrimary,
            textSecondary = textSecondary
        )

        // The kill switch.
        //
        // The first card, because it governs everything below it: off, and
        // this phone sends nothing at all until the user says otherwise.
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
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text("Companion", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = textPrimary)
                    Text(
                        if (companionEnabled) {
                            "On — the computer can reach this phone."
                        } else {
                            "Off — nothing is sent. The computer is told why, so it comes back on its own."
                        },
                        fontSize = 12.sp,
                        color = textSecondary
                    )
                }

                Switch(
                    checked = companionEnabled,
                    onCheckedChange = onCompanionToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = ColorOSGreen,
                        uncheckedThumbColor = textMuted,
                        uncheckedTrackColor = elevatedColor
                    )
                )
            }
        }

        // Shared Clipboard Card
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = surfaceColor),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, borderColor, RoundedCornerShape(20.dp))
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(ColorOSGreen.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.ContentPaste, contentDescription = null, tint = ColorOSGreen, modifier = Modifier.size(20.dp))
                        }
                        Column {
                            Text("Shared Clipboard", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = textPrimary)
                            Text("Instant PC ⟷ Phone copy & paste", fontSize = 12.sp, color = textSecondary)
                        }
                    }

                    Switch(
                        checked = clipboardSyncEnabled,
                        onCheckedChange = onClipboardToggle,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = ColorOSGreen,
                            uncheckedThumbColor = textMuted,
                            uncheckedTrackColor = elevatedColor
                        )
                    )
                }

                // Symmetrical preview box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(elevatedColor)
                        .padding(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("LAST SYNCED", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = textMuted, letterSpacing = 0.8.sp)
                            if (lastDirection != null) {
                                val isToPhone = lastDirection == "pc_to_phone"
                                Text(
                                    text = if (isToPhone) "PC ➔ Phone" else "Phone ➔ PC",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isToPhone) ColorOSBlue else ColorOSGreen
                                )
                            }
                        }

                        if (!lastSyncedText.isNullOrEmpty()) {
                            Text(
                                text = lastSyncedText,
                                fontSize = 13.sp,
                                color = textPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        } else {
                            Text(
                                text = "Copy text on phone or PC to test live sync",
                                fontSize = 12.sp,
                                color = textMuted
                            )
                        }
                    }
                }
            }
        }

        // System Permissions Group
        Text(
            text = "PERMISSIONS & SERVICES",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = textMuted,
            letterSpacing = 0.8.sp,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp)
        )

        // The keyboard is what carries the clipboard privilege.
        PermissionTile(
            icon = Icons.Default.Bolt,
            title = "A+ Connect Keyboard",
            subtitle = if (accessibilityGranted) {
                "Active - copies sync to the computer with no tap"
            } else {
                "Pick it as your keyboard so copies sync with no tap"
            },
            isGranted = accessibilityGranted,
            actionLabel = if (accessibilityGranted) "ACTIVE" else "SET",
            onAction = onOpenAccessibility,
            surfaceColor = surfaceColor,
            elevatedColor = elevatedColor,
            borderColor = borderColor,
            textPrimary = textPrimary,
            textSecondary = textSecondary
        )

        // Battery Tile
        if (batteryOptimized) {
            PermissionTile(
                icon = Icons.Default.BatteryChargingFull,
                title = "Background Keep-Alive",
                subtitle = "Allow app to stay alive when locked",
                isGranted = false,
                actionLabel = "ALLOW",
                onAction = onAllowBattery,
                surfaceColor = surfaceColor,
                elevatedColor = elevatedColor,
                borderColor = borderColor,
                textPrimary = textPrimary,
                textSecondary = textSecondary
            )
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
private fun HeroConnectionCard(
    isConnected: Boolean,
    isConnecting: Boolean,
    statusText: String,
    host: String,
    deviceName: String,
    onRenameClick: () -> Unit,
    pairing: com.aplusconnect.companion.protocol.Pairing?,
    surfaceColor: Color,
    elevatedColor: Color,
    borderColor: Color,
    textPrimary: Color,
    textSecondary: Color
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val cardBorderBrush = if (isConnected) {
        Brush.verticalGradient(listOf(ColorOSGreen.copy(alpha = 0.6f), borderColor))
    } else {
        Brush.verticalGradient(listOf(borderColor, borderColor))
    }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = surfaceColor),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.2.dp, cardBorderBrush, RoundedCornerShape(24.dp))
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header Row: Status Chip & Security Tag
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                if (isConnected) ColorOSGreen.copy(alpha = pulseAlpha)
                                else ColorOSWarning
                            )
                    )
                    Text(
                        text = if (isConnected) "CONNECTED" else if (isConnecting) "CONNECTING…" else "CONNECTING",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isConnected) ColorOSGreen else ColorOSWarning,
                        letterSpacing = 0.8.sp
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(elevatedColor)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Pinned",
                            tint = ColorOSGreen,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = "TLS 1.3 Pinned",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = textSecondary
                        )
                    }
                }
            }

            // Central Device Interconnection Graphic
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Phone Node
                DeviceNode(
                    icon = Icons.Default.Smartphone,
                    name = deviceName,
                    sub = "ColorOS",
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    elevatedColor = elevatedColor,
                    onClick = onRenameClick
                )

                // Sync Wave Animation
                SyncWave(isConnected = isConnected, pulseAlpha = pulseAlpha)

                // Laptop Node
                DeviceNode(
                    icon = Icons.Default.Laptop,
                    name = pairing?.pcId ?: "Linux PC",
                    sub = if (host.isNotEmpty()) host else "LAN",
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    elevatedColor = elevatedColor
                )
            }

            // Status Detail Footnote
            Text(
                text = statusText,
                fontSize = 13.sp,
                color = textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun DeviceNode(
    icon: ImageVector,
    name: String,
    sub: String,
    textPrimary: Color,
    textSecondary: Color,
    elevatedColor: Color,
    onClick: (() -> Unit)? = null
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = if (onClick != null) Modifier.clickable { onClick() } else Modifier
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(elevatedColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = textPrimary, modifier = Modifier.size(24.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = name,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (onClick != null) {
                Spacer(modifier = Modifier.width(3.dp))
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Rename",
                    tint = textSecondary,
                    modifier = Modifier.size(11.dp)
                )
            }
        }
        Text(
            text = sub,
            fontSize = 11.sp,
            color = textSecondary,
            maxLines = 1
        )
    }
}

@Composable
private fun SyncWave(isConnected: Boolean, pulseAlpha: Float) {
    val waveColor = if (isConnected) ColorOSGreen else ColorOSWarning
    Canvas(modifier = Modifier.width(70.dp).height(24.dp)) {
        val y = size.height / 2
        drawLine(
            color = waveColor.copy(alpha = if (isConnected) pulseAlpha else 0.3f),
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawCircle(
            color = waveColor,
            radius = 4.dp.toPx(),
            center = Offset(size.width / 2, y)
        )
    }
}

@Composable
private fun PermissionTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isGranted: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
    surfaceColor: Color,
    elevatedColor: Color,
    borderColor: Color,
    textPrimary: Color,
    textSecondary: Color
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = surfaceColor),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(18.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(elevatedColor),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = textPrimary, modifier = Modifier.size(20.dp))
                }
                Column {
                    Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = textPrimary)
                    Text(subtitle, fontSize = 12.sp, color = textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            if (isGranted) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(ColorOSGreen.copy(alpha = 0.15f))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text("ACTIVE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ColorOSGreen)
                }
            } else {
                Button(
                    onClick = onAction,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ColorOSBlue),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(actionLabel, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }
}
