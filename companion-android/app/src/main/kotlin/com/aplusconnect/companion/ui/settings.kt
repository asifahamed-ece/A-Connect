package com.aplusconnect.companion.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aplusconnect.companion.data.CompanionSettings

/**
 * Tab 2: Settings (LocalSend Grouped Layout)
 */
@Composable
internal fun SettingsTab(
    settings: CompanionSettings,
    deviceName: String,
    onRenameClick: () -> Unit,
    isConnected: Boolean,
    isConnecting: Boolean,
    onSaveAndConnect: (String, String, String) -> Unit,
    onForgetPairing: () -> Unit,
    historyCount: Int,
    onClearHistory: () -> Unit,
    downloadFolder: String,
    onPickDownloadFolder: () -> Unit,
    allFilesGranted: Boolean,
    onGrantAllFiles: () -> Unit,
    onScanQr: () -> Unit,
    keyboardActive: Boolean,
    onOpenKeyboardPicker: () -> Unit,
    surfaceColor: Color,
    elevatedColor: Color,
    borderColor: Color,
    textPrimary: Color,
    textSecondary: Color,
    textMuted: Color
) {
    var host by remember { mutableStateOf(settings.host) }
    var port by remember { mutableStateOf(settings.port.toString()) }
    var code by remember { mutableStateOf(settings.pairingCode) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Device Card
        Text("DEVICE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = textMuted, letterSpacing = 0.8.sp, modifier = Modifier.padding(start = 4.dp))

        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = surfaceColor),
            modifier = Modifier.fillMaxWidth().border(1.dp, borderColor, RoundedCornerShape(18.dp))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onRenameClick() }
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Device Name", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = textPrimary)
                    Text(deviceName, fontSize = 12.sp, color = textSecondary)
                }
                Icon(Icons.Default.Edit, contentDescription = "Edit", tint = textSecondary, modifier = Modifier.size(16.dp))
            }
        }

        // Network Setup Card
        Text("NETWORK & PAIRING", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = textMuted, letterSpacing = 0.8.sp, modifier = Modifier.padding(start = 4.dp))

        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = surfaceColor),
            modifier = Modifier.fillMaxWidth().border(1.dp, borderColor, RoundedCornerShape(20.dp))
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // The live state of the connection, so pairing shows what is
                // happening instead of looking like nothing happened.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isConnecting) {
                        CircularProgressIndicator(
                            color = ColorOSGreen,
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(
                                    if (isConnected) ColorOSGreen else textMuted,
                                    RoundedCornerShape(5.dp)
                                )
                        )
                    }
                    Text(
                        when {
                            isConnecting -> "Pairing with the computer…"
                            isConnected -> "Connected to the computer"
                            else -> "Not connected"
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isConnected) ColorOSGreen else textSecondary
                    )
                }

                // The scan comes first, and it is the primary button: one
                // scan fills in the address, the port and the code. Typing
                // three fields by hand is the fallback for when the camera
                // cannot see the screen.
                Button(
                    onClick = onScanQr,
                    colors = ButtonDefaults.buttonColors(containerColor = ColorOSGreen),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text("Scan pairing QR", fontWeight = FontWeight.Bold, color = Color.White)
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    "or enter the details by hand",
                    fontSize = 12.sp,
                    color = textSecondary,
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("PC IP Address") },
                    placeholder = { Text("e.g. 192.168.1.39") },
                    singleLine = true,
                    colors = outlinedFieldColors(borderColor, textPrimary, textSecondary),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter(Char::isDigit) },
                        label = { Text("Port") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = outlinedFieldColors(borderColor, textPrimary, textSecondary),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(0.4f)
                    )

                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.filter(Char::isDigit).take(6) },
                        label = { Text("6-Digit Code") },
                        placeholder = { Text("099368") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = outlinedFieldColors(borderColor, textPrimary, textSecondary),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(0.6f)
                    )
                }

                Button(
                    onClick = { onSaveAndConnect(host, port, code) },
                    colors = ButtonDefaults.buttonColors(containerColor = if (isConnected) ColorOSGreen else ColorOSGreenDark),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    if (isConnecting) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Connecting…", color = Color.White)
                    } else {
                        Text("Save & Reconnect", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }

                if (settings.pairing != null) {
                    OutlinedButton(
                        onClick = onForgetPairing,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ColorOSError),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Reset PC Certificate Pairing", fontSize = 13.sp)
                    }
                }
            }
        }

        // File Destination
        Text("FILE TRANSFER", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = textMuted, letterSpacing = 0.8.sp, modifier = Modifier.padding(start = 4.dp))

        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = surfaceColor),
            modifier = Modifier.fillMaxWidth().border(1.dp, borderColor, RoundedCornerShape(18.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("PC Destination Folder", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = textPrimary)
                Text("Sent files save automatically to PC ~/Downloads", fontSize = 12.sp, color = textSecondary)
            }
        }

        // Where files that arrive from the computer are stored.
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = surfaceColor),
            modifier = Modifier.fillMaxWidth().border(1.dp, borderColor, RoundedCornerShape(18.dp))
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Save received files to", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = textPrimary)
                    Text(
                        if (downloadFolder.isEmpty()) "The app's own downloads folder"
                        else downloadFolder.substringAfterLast('/').ifEmpty { "Chosen folder" },
                        fontSize = 12.sp,
                        color = textSecondary,
                        maxLines = 1
                    )
                }

                Button(
                    onClick = onPickDownloadFolder,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = elevatedColor),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("Change…", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = textPrimary)
                }
            }
        }

        // The whole storage the computer may look inside, read-only. This
        // needs Android's all-files access: the system folder picker refuses
        // the storage root, the SD card root and Download.
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = surfaceColor),
            modifier = Modifier.fillMaxWidth().border(1.dp, borderColor, RoundedCornerShape(18.dp))
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Files the PC can browse", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = textPrimary)
                    Text(
                        if (allFilesGranted) "Every folder on this phone" else "Not granted yet",
                        fontSize = 12.sp,
                        color = if (allFilesGranted) textSecondary else ColorOSError,
                        maxLines = 1
                    )
                    Text(
                        if (allFilesGranted) {
                            "Read only: the computer can look, search and copy, never change anything"
                        } else {
                            "Needs all files access in Settings. It is not an accessibility service, so payments keep working"
                        },
                        fontSize = 11.sp,
                        color = textMuted
                    )
                }

                Button(
                    onClick = onGrantAllFiles,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = elevatedColor),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        if (allFilesGranted) "Manage" else "Allow...",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = textPrimary
                    )
                }
            }
        }

        // History Management
        Text("CLIPBOARD HISTORY", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = textMuted, letterSpacing = 0.8.sp, modifier = Modifier.padding(start = 4.dp))

        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = surfaceColor),
            modifier = Modifier.fillMaxWidth().border(1.dp, borderColor, RoundedCornerShape(18.dp))
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Stored Clips", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = textPrimary)
                    Text("$historyCount items saved locally", fontSize = 12.sp, color = textSecondary)
                }

                Button(
                    onClick = onClearHistory,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = elevatedColor),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("Clear All", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ColorOSError)
                }
            }
        }

        // Clipboard access, and why there is no accessibility option.
        Text("CLIPBOARD ACCESS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = textMuted, letterSpacing = 0.8.sp, modifier = Modifier.padding(start = 4.dp))

        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = surfaceColor),
            modifier = Modifier.fillMaxWidth().border(1.dp, borderColor, RoundedCornerShape(18.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("A+ Connect Keyboard", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = textPrimary)
                        Text(
                            if (keyboardActive) "Active - copies sync with no tap"
                            else "Not selected - copies need an extra tap",
                            fontSize = 12.sp,
                            color = if (keyboardActive) ColorOSGreen else textSecondary
                        )
                    }

                    Button(
                        onClick = onOpenKeyboardPicker,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = elevatedColor),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            if (keyboardActive) "ACTIVE" else "SET",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = textPrimary
                        )
                    }
                }

                Text(
                    "Android lets only the default keyboard read the clipboard in the background, so the A+ Connect Keyboard is what carries a copy to the computer. There is deliberately no accessibility option: an accessibility service makes the phone refuse contactless payments, so this app never asks for one.",
                    fontSize = 11.sp,
                    color = textSecondary
                )

                Text(
                    "Updating this app can reset the default keyboard to the previous one. If copies stop syncing with no tap, set the A+ Connect Keyboard again here.",
                    fontSize = 11.sp,
                    color = textMuted
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}
