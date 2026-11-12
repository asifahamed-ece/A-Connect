package com.aplusconnect.companion

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.text.TextUtils
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aplusconnect.companion.data.ClipHistoryEntry
import com.aplusconnect.companion.data.CompanionSettings
import com.aplusconnect.companion.protocol.PairingPayload
import com.aplusconnect.companion.qr.QrScannerView
import com.aplusconnect.companion.service.CompanionService
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ColorOS & LocalSend-inspired Aquamorphic Palette
 */
private val ColorOSGreen = Color(0xFF00C37B)
private val ColorOSGreenDark = Color(0xFF009B60)
private val ColorOSBlue = Color(0xFF1D68FF)
private val ColorOSWarning = Color(0xFFFF9E0B)
private val ColorOSError = Color(0xFFFF4D4F)

// Dark Theme Surfaces
private val DarkBg = Color(0xFF0E1114)
private val DarkSurface = Color(0xFF161A1F)
private val DarkSurfaceElevated = Color(0xFF20262E)
private val DarkBorder = Color(0xFF2B333E)
private val DarkTextPrimary = Color(0xFFF7F9FC)
private val DarkTextSecondary = Color(0xFF8F98A8)
private val DarkTextMuted = Color(0xFF5B6373)

// Light Theme Surfaces
private val LightBg = Color(0xFFF4F6F9)
private val LightSurface = Color(0xFFFFFFFF)
private val LightSurfaceElevated = Color(0xFFEDF2F7)
private val LightBorder = Color(0xFFE2E7EF)
private val LightTextPrimary = Color(0xFF15181E)
private val LightTextSecondary = Color(0xFF6A7382)
private val LightTextMuted = Color(0xFF98A2B3)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = CompanionSettings(this)

        intent?.getStringExtra(EXTRA_HOST)?.let { settings.host = it }
        intent?.getIntExtra(EXTRA_PORT, 0)?.takeIf { it > 0 }?.let { settings.port = it }
        intent?.getStringExtra(EXTRA_CODE)?.let { settings.pairingCode = it }
        if (settings.host.isNotEmpty()) {
            CompanionService.start(applicationContext)
        }

        setContent {
            val isDark = isSystemInDarkTheme()
            val colorScheme = if (isDark) {
                darkColorScheme(
                    primary = ColorOSGreen,
                    background = DarkBg,
                    surface = DarkSurface,
                    onPrimary = Color.White,
                    onBackground = DarkTextPrimary,
                    onSurface = DarkTextPrimary
                )
            } else {
                lightColorScheme(
                    primary = ColorOSGreen,
                    background = LightBg,
                    surface = LightSurface,
                    onPrimary = Color.White,
                    onBackground = LightTextPrimary,
                    onSurface = LightTextPrimary
                )
            }

            MaterialTheme(colorScheme = colorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = if (isDark) DarkBg else LightBg
                ) {
                    MainScreen(settings)
                }
            }
        }
    }

    private companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_CODE = "code"
    }
}

@Composable
fun MainScreen(settings: CompanionSettings) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()

    val surfaceColor = if (isDark) DarkSurface else LightSurface
    val elevatedColor = if (isDark) DarkSurfaceElevated else LightSurfaceElevated
    val borderColor = if (isDark) DarkBorder else LightBorder
    val textPrimary = if (isDark) DarkTextPrimary else LightTextPrimary
    val textSecondary = if (isDark) DarkTextSecondary else LightTextSecondary
    val textMuted = if (isDark) DarkTextMuted else LightTextMuted

    var selectedTab by remember { mutableIntStateOf(0) }

    var deviceName by remember { mutableStateOf(resolveDeviceName(context, settings)) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var tempDeviceName by remember { mutableStateOf(deviceName) }

    var statusText by remember { mutableStateOf(CompanionService.Status.state) }
    var isConnected by remember { mutableStateOf(CompanionService.Status.isConnected) }
    var isConnecting by remember { mutableStateOf(false) }

    var lastSyncedText by remember { mutableStateOf(CompanionService.Status.lastSyncedText) }
    var lastDirection by remember { mutableStateOf(CompanionService.Status.lastDirection) }

    var historyItems by remember { mutableStateOf(settings.getHistory()) }
    var showHistoryDialog by remember { mutableStateOf(false) }

    var accessibilityGranted by remember { mutableStateOf(isDefaultKeyboard(context)) }
    var batteryOptimized by remember { mutableStateOf(isBatteryOptimized(context)) }
    var clipboardSyncEnabled by remember { mutableStateOf(true) }

    // File transfer state
    var isUploadingFile by remember { mutableStateOf(false) }
    var uploadingFileName by remember { mutableStateOf("") }
    var uploadProgress by remember { mutableFloatStateOf(0f) }
    var uploadStatusMessage by remember { mutableStateOf<String?>(null) }

    // Pairing by QR code
    var showScanner by remember { mutableStateOf(false) }

    // Quick text send dialog
    var showSendTextDialog by remember { mutableStateOf(false) }
    var customTextToSend by remember { mutableStateOf("") }

    // Where files arriving from the computer are stored. The system folder
    // picker hands back a document tree the app keeps a permission for.
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                // Some providers do not offer a persistable permission; the
                // folder still works for this session.
            }
            settings.downloadFolderUri = uri.toString()
            Toast.makeText(context, "Received files will save to the chosen folder", Toast.LENGTH_SHORT).show()
        }
    }

    // The camera is only used while the pairing screen is open.
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showScanner = true
        } else {
            Toast.makeText(context, "The camera is needed to scan the pairing code", Toast.LENGTH_SHORT).show()
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val name = queryFileName(context, uri)
            uploadingFileName = name
            isUploadingFile = true
            uploadProgress = 0f
            uploadStatusMessage = "Sending $name to PC…"

            CompanionService.sendFile(
                context = context,
                uri = uri,
                onProgress = { p -> uploadProgress = p },
                onComplete = { success, error ->
                    isUploadingFile = false
                    if (success) {
                        uploadStatusMessage = "Saved to PC Downloads folder"
                        Toast.makeText(context, "$name sent to PC!", Toast.LENGTH_LONG).show()
                    } else {
                        uploadStatusMessage = "Failed: ${error ?: "Unknown error"}"
                        Toast.makeText(context, "Transfer failed: ${error ?: "Unknown error"}", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }
    }

    // Live state updates
    LaunchedEffect(Unit) {
        while (true) {
            statusText = CompanionService.Status.state
            val connected = CompanionService.Status.isConnected
            isConnected = connected
            if (connected) isConnecting = false

            lastSyncedText = CompanionService.Status.lastSyncedText
            lastDirection = CompanionService.Status.lastDirection
            historyItems = settings.getHistory()

            accessibilityGranted = isDefaultKeyboard(context)
            batteryOptimized = isBatteryOptimized(context)
            delay(500L)
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = surfaceColor,
                tonalElevation = 6.dp
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Wifi, contentDescription = "Interconnect") },
                    label = { Text("Interconnect", fontSize = 12.sp, fontWeight = FontWeight.Medium) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = ColorOSGreen,
                        selectedTextColor = ColorOSGreen,
                        indicatorColor = ColorOSGreen.copy(alpha = 0.15f),
                        unselectedIconColor = textMuted,
                        unselectedTextColor = textMuted
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Send, contentDescription = "Send") },
                    label = { Text("Send File", fontSize = 12.sp, fontWeight = FontWeight.Medium) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = ColorOSGreen,
                        selectedTextColor = ColorOSGreen,
                        indicatorColor = ColorOSGreen.copy(alpha = 0.15f),
                        unselectedIconColor = textMuted,
                        unselectedTextColor = textMuted
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text("Settings", fontSize = 12.sp, fontWeight = FontWeight.Medium) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = ColorOSGreen,
                        selectedTextColor = ColorOSGreen,
                        indicatorColor = ColorOSGreen.copy(alpha = 0.15f),
                        unselectedIconColor = textMuted,
                        unselectedTextColor = textMuted
                    )
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // App Bar
            TopBar(
                title = when (selectedTab) {
                    0 -> "A+ Connect"
                    1 -> "File Transfer"
                    else -> "Settings"
                },
                historyCount = historyItems.size,
                onHistoryClick = { showHistoryDialog = true },
                onReconnectClick = {
                    CompanionService.reconnect(context)
                    Toast.makeText(context, "Reconnecting…", Toast.LENGTH_SHORT).show()
                },
                surfaceColor = elevatedColor,
                textPrimary = textPrimary,
                textSecondary = textSecondary
            )

            // Body Content based on Tab
            when (selectedTab) {
                0 -> InterconnectTab(
                    settings = settings,
                    isConnected = isConnected,
                    isConnecting = isConnecting,
                    statusText = statusText,
                    deviceName = deviceName,
                    onRenameClick = {
                        tempDeviceName = deviceName
                        showRenameDialog = true
                    },
                    lastSyncedText = lastSyncedText,
                    lastDirection = lastDirection,
                    onViewHistoryClick = { showHistoryDialog = true },
                    clipboardSyncEnabled = clipboardSyncEnabled,
                    onClipboardToggle = { clipboardSyncEnabled = it },
                    accessibilityGranted = accessibilityGranted,
                    batteryOptimized = batteryOptimized,
                    onOpenAccessibility = { openKeyboardPicker(context) },
                    onAllowBattery = { requestIgnoreBatteryOptimizations(context) },
                    isDark = isDark,
                    surfaceColor = surfaceColor,
                    elevatedColor = elevatedColor,
                    borderColor = borderColor,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    textMuted = textMuted
                )
                1 -> SendTab(
                    isConnected = isConnected,
                    host = settings.host,
                    pcId = settings.pairing?.pcId ?: "Linux PC",
                    isUploading = isUploadingFile,
                    uploadFileName = uploadingFileName,
                    progress = uploadProgress,
                    statusMessage = uploadStatusMessage,
                    onPickFile = { filePicker.launch("*/*") },
                    onPickMedia = { filePicker.launch("image/*,video/*") },
                    onSendClipboard = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clipText = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                        if (clipText.isNotEmpty()) {
                            CompanionService.deliverClipboard(context, clipText)
                            Toast.makeText(context, "Current clipboard sent to PC!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Phone clipboard is empty", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onSendTextClick = {
                        customTextToSend = ""
                        showSendTextDialog = true
                    },
                    isDark = isDark,
                    surfaceColor = surfaceColor,
                    elevatedColor = elevatedColor,
                    borderColor = borderColor,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    textMuted = textMuted
                )
                2 -> SettingsTab(
                    settings = settings,
                    deviceName = deviceName,
                    onRenameClick = {
                        tempDeviceName = deviceName
                        showRenameDialog = true
                    },
                    isConnected = isConnected,
                    isConnecting = isConnecting,
                    onSaveAndConnect = { host, port, code ->
                        isConnecting = true
                        settings.host = host.trim()
                        settings.port = port.toIntOrNull() ?: 24443
                        settings.pairingCode = code.trim()
                        CompanionService.reconnect(context)
                        Toast.makeText(context, "Connecting to PC…", Toast.LENGTH_SHORT).show()
                    },
                    onForgetPairing = {
                        settings.forget()
                        Toast.makeText(context, "Pairing reset", Toast.LENGTH_SHORT).show()
                    },
                    historyCount = historyItems.size,
                    onClearHistory = {
                        settings.clearHistory()
                        historyItems = emptyList()
                        Toast.makeText(context, "Clipboard history cleared", Toast.LENGTH_SHORT).show()
                    },
                    downloadFolder = settings.downloadFolderUri,
                    onPickDownloadFolder = { folderPicker.launch(null) },
                    onScanQr = {
                        cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                    },
                    surfaceColor = surfaceColor,
                    elevatedColor = elevatedColor,
                    borderColor = borderColor,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    textMuted = textMuted
                )
            }
        }
    }

    // Pairing by QR code: the scanner fills the address, the port and the
    // one time code, and pins the computer's certificate straight away.
    if (showScanner) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            QrScannerView(
                onScanned = { text ->
                    showScanner = false
                    val info = PairingPayload.fromJson(text)
                    if (info == null) {
                        Toast.makeText(context, "That is not an A+ Connect pairing code", Toast.LENGTH_SHORT).show()
                    } else {
                        if (info.fingerprint.isNotEmpty() && info.pcId.isNotEmpty()) {
                            // The QR carries the computer's certificate, so it
                            // can be pinned before the first connection.
                            settings.remember(info.pcId, info.fingerprint)
                        }
                        settings.host = info.host
                        settings.port = info.port
                        settings.pairingCode = info.code
                        isConnecting = true
                        CompanionService.reconnect(context)
                        Toast.makeText(context, "Pairing with ${info.pcName}…", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            IconButton(
                onClick = { showScanner = false },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(20.dp)
                    .size(44.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(22.dp))
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close the scanner",
                    tint = Color.White
                )
            }
        }
    }

    // Rename Dialog
    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Device Name", fontWeight = FontWeight.Bold, color = textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("How this device appears on your PC:", fontSize = 13.sp, color = textSecondary)
                    OutlinedTextField(
                        value = tempDeviceName,
                        onValueChange = { tempDeviceName = it },
                        singleLine = true,
                        colors = outlinedFieldColors(borderColor, textPrimary, textSecondary),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = tempDeviceName.trim()
                        if (trimmed.isNotEmpty()) {
                            settings.deviceName = trimmed
                            deviceName = trimmed
                            CompanionService.reconnect(context)
                        }
                        showRenameDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ColorOSGreen)
                ) {
                    Text("Save", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancel", color = textSecondary)
                }
            },
            containerColor = surfaceColor
        )
    }

    // Quick Text Send Dialog
    if (showSendTextDialog) {
        AlertDialog(
            onDismissRequest = { showSendTextDialog = false },
            title = { Text("Send Text to PC", fontWeight = FontWeight.Bold, color = textPrimary) },
            text = {
                OutlinedTextField(
                    value = customTextToSend,
                    onValueChange = { customTextToSend = it },
                    placeholder = { Text("Type or paste message, note, or URL…") },
                    colors = outlinedFieldColors(borderColor, textPrimary, textSecondary),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(120.dp)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val text = customTextToSend.trim()
                        if (text.isNotEmpty()) {
                            CompanionService.deliverClipboard(context, text)
                            Toast.makeText(context, "Sent to PC!", Toast.LENGTH_SHORT).show()
                        }
                        showSendTextDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ColorOSGreen)
                ) {
                    Text("Send", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSendTextDialog = false }) {
                    Text("Cancel", color = textSecondary)
                }
            },
            containerColor = surfaceColor
        )
    }

    // Clipboard History Dialog
    if (showHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showHistoryDialog = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Clipboard History", fontWeight = FontWeight.Bold, color = textPrimary)
                    if (historyItems.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                settings.clearHistory()
                                historyItems = emptyList()
                                Toast.makeText(context, "History cleared", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Text("Clear All", color = ColorOSError, fontSize = 13.sp)
                        }
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (historyItems.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize().padding(top = 40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("No synced clips yet", color = textMuted, fontSize = 14.sp)
                        }
                    } else {
                        val sdf = remember { SimpleDateFormat("h:mm a, dd MMM", Locale.getDefault()) }
                        historyItems.forEach { entry ->
                            val isToPhone = entry.direction == "pc_to_phone"
                            Card(
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = elevatedColor),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        cm.setPrimaryClip(ClipData.newPlainText("A+ Connect", entry.text))
                                        Toast.makeText(context, "Copied to phone clipboard!", Toast.LENGTH_SHORT).show()
                                    }
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(if (isToPhone) ColorOSBlue.copy(alpha = 0.15f) else ColorOSGreen.copy(alpha = 0.15f))
                                                .padding(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = if (isToPhone) "PC ➔ Phone" else "Phone ➔ PC",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isToPhone) ColorOSBlue else ColorOSGreen
                                            )
                                        }

                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(
                                                text = sdf.format(Date(entry.timestamp)),
                                                fontSize = 11.sp,
                                                color = textMuted
                                            )
                                            Icon(
                                                imageVector = Icons.Default.ContentCopy,
                                                contentDescription = "Copy",
                                                tint = textMuted,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = entry.text,
                                        fontSize = 13.sp,
                                        color = textPrimary,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showHistoryDialog = false }) {
                    Text("Close", color = ColorOSGreen)
                }
            },
            containerColor = surfaceColor
        )
    }
}

@Composable
fun TopBar(
    title: String,
    historyCount: Int,
    onHistoryClick: () -> Unit,
    onReconnectClick: () -> Unit,
    surfaceColor: Color,
    textPrimary: Color,
    textSecondary: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = textPrimary,
                    letterSpacing = (-0.5).sp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(ColorOSGreen.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "ColorOS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ColorOSGreen
                    )
                }
            }
            Text(
                text = "Seamless PC Interconnection",
                fontSize = 12.sp,
                color = textSecondary
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(
                onClick = onHistoryClick,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(surfaceColor)
                    .size(38.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = "History",
                    tint = textPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }

            IconButton(
                onClick = onReconnectClick,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(surfaceColor)
                    .size(38.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Reconnect",
                    tint = textPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * Tab 0: Interconnect (Hero Connection Card + Shared Clipboard + Permissions)
 */
@Composable
fun InterconnectTab(
    settings: CompanionSettings,
    isConnected: Boolean,
    isConnecting: Boolean,
    statusText: String,
    deviceName: String,
    onRenameClick: () -> Unit,
    lastSyncedText: String?,
    lastDirection: String?,
    onViewHistoryClick: () -> Unit,
    clipboardSyncEnabled: Boolean,
    onClipboardToggle: (Boolean) -> Unit,
    accessibilityGranted: Boolean,
    batteryOptimized: Boolean,
    onOpenAccessibility: () -> Unit,
    onAllowBattery: () -> Unit,
    isDark: Boolean,
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
            isDark = isDark,
            surfaceColor = surfaceColor,
            elevatedColor = elevatedColor,
            borderColor = borderColor,
            textPrimary = textPrimary,
            textSecondary = textSecondary
        )

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
            title = "Clipboard Keyboard",
            subtitle = if (accessibilityGranted) {
                "Set as your keyboard - the clipboard syncs in the background"
            } else {
                "Set as your keyboard so copies sync in the background"
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

/**
 * Tab 1: Send File (LocalSend Selection Grid + Target PC)
 */
@Composable
fun SendTab(
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
    isDark: Boolean,
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

/**
 * Tab 2: Settings (LocalSend Grouped Layout)
 */
@Composable
fun SettingsTab(
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
    onScanQr: () -> Unit,
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
                    onClick = onScanQr,
                    colors = ButtonDefaults.buttonColors(containerColor = elevatedColor),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text("Scan pairing QR", fontWeight = FontWeight.Bold, color = textPrimary)
                }

                Spacer(modifier = Modifier.height(8.dp))

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

        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
fun SelectionCard(
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

@Composable
fun HeroConnectionCard(
    isConnected: Boolean,
    isConnecting: Boolean,
    statusText: String,
    host: String,
    deviceName: String,
    onRenameClick: () -> Unit,
    pairing: com.aplusconnect.companion.protocol.Pairing?,
    isDark: Boolean,
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
fun DeviceNode(
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
fun SyncWave(isConnected: Boolean, pulseAlpha: Float) {
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
fun PermissionTile(
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

@Composable
private fun outlinedFieldColors(borderColor: Color, textPrimary: Color, textSecondary: Color) =
    OutlinedTextFieldDefaults.colors(
        focusedBorderColor = ColorOSGreen,
        unfocusedBorderColor = borderColor,
        focusedLabelColor = ColorOSGreen,
        unfocusedLabelColor = textSecondary,
        cursorColor = ColorOSGreen,
        focusedTextColor = textPrimary,
        unfocusedTextColor = textPrimary
    )

private fun resolveDeviceName(context: Context, settings: CompanionSettings): String {
    val custom = settings.deviceName
    if (custom.isNotEmpty()) return custom

    try {
        val systemName = Settings.Global.getString(context.contentResolver, "device_name")
        if (!systemName.isNullOrBlank()) return systemName
    } catch (_: Exception) {}

    try {
        val btName = Settings.Secure.getString(context.contentResolver, "bluetooth_name")
        if (!btName.isNullOrBlank()) return btName
    } catch (_: Exception) {}

    return Build.MODEL
}

private fun queryFileName(context: Context, uri: Uri): String {
    var name = "file"
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && index != -1) {
                cursor.getString(index)?.let { name = it }
            }
        }
    } catch (_: Exception) {}
    return name
}

/**
 * Whether this app is the default keyboard.
 *
 * That role is what Android grants privileged clipboard access on, and it is
 * the only route that needs no accessibility service - an accessibility
 * service makes the phone block contactless payments.
 */
/**
 * Opens the keyboard picker so the user can choose the companion keyboard.
 *
 * This is deliberately not the accessibility settings: an accessibility
 * service makes Android block contactless payments, so the app does not use
 * one and does not ask for one.
 */
private fun openKeyboardPicker(context: Context) {
    try {
        val imm = context.getSystemService(InputMethodManager::class.java)
        imm?.showInputMethodPicker()
    } catch (e: Exception) {
        Toast.makeText(context, "Could not open the keyboard picker", Toast.LENGTH_SHORT).show()
    }
}

private fun isDefaultKeyboard(context: Context): Boolean {
    val current = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.DEFAULT_INPUT_METHOD
    ) ?: return false
    return current.contains(context.packageName)
}

private fun isBatteryOptimized(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        !pm.isIgnoringBatteryOptimizations(context.packageName)
    } else {
        false
    }
}

private fun openAccessibilitySettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "Could not open Accessibility settings", Toast.LENGTH_SHORT).show()
    }
}

private fun requestIgnoreBatteryOptimizations(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(fallbackIntent)
            } catch (e: Exception) {
                Toast.makeText(context, "Please disable battery optimization in system settings", Toast.LENGTH_LONG).show()
            }
        }
    }
}
