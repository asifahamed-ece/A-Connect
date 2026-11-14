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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.aplusconnect.companion.data.CompanionSettings
import com.aplusconnect.companion.file.PhoneFileBrowser
import com.aplusconnect.companion.protocol.PairingPayload
import com.aplusconnect.companion.qr.QrScannerView
import com.aplusconnect.companion.service.CompanionService
import com.aplusconnect.companion.ui.ColorOSBlue
import com.aplusconnect.companion.ui.ColorOSError
import com.aplusconnect.companion.ui.ColorOSGreen
import com.aplusconnect.companion.ui.DarkBg
import com.aplusconnect.companion.ui.DarkColorScheme
import com.aplusconnect.companion.ui.InterconnectTab
import com.aplusconnect.companion.ui.LightBg
import com.aplusconnect.companion.ui.LightColorScheme
import com.aplusconnect.companion.ui.SendTab
import com.aplusconnect.companion.ui.SettingsTab
import com.aplusconnect.companion.ui.outlinedFieldColors
import com.aplusconnect.companion.ui.themeColors
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    /**
     * The platform's screen-capture dialog.
     *
     * The result is handed to the service, which is the thing that owns the
     * projection and the encoder. Registered here because a dialog needs an
     * Activity, and there is no way around that on any Android version.
     */
    private val projectionConsent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            CompanionService.onProjectionGranted(applicationContext, result.resultCode, data)
        } else {
            CompanionService.onProjectionRefused(applicationContext)
        }
    }

    /** Raises the platform's screen-capture dialog for the pending request. */
    private fun requestScreenCapture() {
        val manager = getSystemService(android.media.projection.MediaProjectionManager::class.java)
        if (manager == null) {
            CompanionService.onProjectionRefused(applicationContext)
            return
        }
        try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Whole screen, not "one app": this is a mirror, and the system
                // would otherwise offer a single window and letterbox it.
                manager.createScreenCaptureIntent(
                    android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()
                )
            } else {
                manager.createScreenCaptureIntent()
            }
            projectionConsent.launch(intent)
        } catch (e: Exception) {
            CompanionService.onProjectionRefused(applicationContext)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == CompanionService.ACTION_MIRROR) {
            requestScreenCapture()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = CompanionSettings(this)

        intent?.getStringExtra(EXTRA_HOST)?.let { settings.host = it }
        intent?.getIntExtra(EXTRA_PORT, 0)?.takeIf { it > 0 }?.let { settings.port = it }
        intent?.getStringExtra(EXTRA_CODE)?.let { settings.pairingCode = it }
        if (settings.host.isNotEmpty()) {
            CompanionService.start(applicationContext)
        }

        if (intent?.action == CompanionService.ACTION_MIRROR) {
            requestScreenCapture()
        }

        setContent {
            val isDark = isSystemInDarkTheme()
            val colorScheme = if (isDark) DarkColorScheme else LightColorScheme

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
private fun MainScreen(settings: CompanionSettings) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()

    val theme = themeColors(isDark)
    val surfaceColor = theme.surface
    val elevatedColor = theme.elevated
    val borderColor = theme.border
    val textPrimary = theme.textPrimary
    val textSecondary = theme.textSecondary
    val textMuted = theme.textMuted

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

    /**
     * The kill switch.
     *
     * Off means this phone is dormant: nothing is sent, the computer is told
     * why, and it will find the phone again when it is switched back on.
     */
    var companionEnabled by remember { mutableStateOf(settings.enabled) }

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

    // All files access, for the computer's file browser. It has no runtime
    // dialog by design, so the card sends the user to the system page and the
    // state is re-read whenever the app comes back to the front.
    var allFilesGranted by remember { mutableStateOf(hasAllFilesAccess(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                allFilesGranted = hasAllFilesAccess(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
                    clipboardSyncEnabled = clipboardSyncEnabled,
                    onClipboardToggle = { clipboardSyncEnabled = it },
                    companionEnabled = companionEnabled,
                    onCompanionToggle = { wanted ->
                        companionEnabled = wanted
                        CompanionService.setEnabled(context, wanted)
                    },
                    accessibilityGranted = accessibilityGranted,
                    batteryOptimized = batteryOptimized,
                    onOpenAccessibility = { openKeyboardPicker(context) },
                    onAllowBattery = { requestIgnoreBatteryOptimizations(context) },
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
                    // The settings tab shows how many clipboard entries are
                    // kept on the phone. The top bar never showed it, so that
                    // parameter went with the split.
                    historyCount = historyItems.size,
                        onClearHistory = {
                        settings.clearHistory()
                        historyItems = emptyList()
                        Toast.makeText(context, "Clipboard history cleared", Toast.LENGTH_SHORT).show()
                    },
                    downloadFolder = settings.downloadFolderUri,
                    onPickDownloadFolder = { folderPicker.launch(null) },
                    allFilesGranted = allFilesGranted,
                    onGrantAllFiles = { context.startActivity(allFilesIntent(context)) },
                    onScanQr = {
                        cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                    },
                    keyboardActive = accessibilityGranted,
                    onOpenKeyboardPicker = { openKeyboardPicker(context) },
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
private fun TopBar(
    title: String,
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

/**
 * Whether the A+ Connect keyboard is the default keyboard.
 *
 * The keyboard is its own app (a patched HeliBoard), and *it* is what Android
 * lets read the clipboard in the background. This app only receives what the
 * keyboard hands over, so this is the state that decides whether copies sync
 * with no tap.
 */
/**
 * Whether the computer's file browser may read this phone.
 *
 * All files access is the only way to reach the storage root, the SD card and
 * Download; the system folder picker refuses all three. It has no runtime
 * dialog, so the Settings card sends the user to the system page instead.
 */
private fun hasAllFilesAccess(context: Context): Boolean =
    PhoneFileBrowser(context).isGranted()

/** The system page where the user grants all-files access. */
private fun allFilesIntent(context: Context): Intent =
    PhoneFileBrowser(context).grantIntent()

private fun isDefaultKeyboard(context: Context): Boolean {
    val current = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.DEFAULT_INPUT_METHOD
    ) ?: return false
    return com.aplusconnect.companion.clip.KeyboardBridge.PACKAGES.any { current.startsWith(it) }
}

private fun isBatteryOptimized(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        !pm.isIgnoringBatteryOptimizations(context.packageName)
    } else {
        false
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
