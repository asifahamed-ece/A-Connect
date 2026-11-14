package com.aplusconnect.companion

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.aplusconnect.companion.data.CompanionSettings
import com.aplusconnect.companion.data.Direction
import com.aplusconnect.companion.data.FileHistory
import com.aplusconnect.companion.data.SharedFileEntry
import com.aplusconnect.companion.file.PhoneFileBrowser
import com.aplusconnect.companion.protocol.PairingPayload
import com.aplusconnect.companion.service.CompanionService
import com.aplusconnect.companion.ui.ClipboardActions
import com.aplusconnect.companion.ui.ClipboardScreen
import com.aplusconnect.companion.ui.ClipboardUi
import com.aplusconnect.companion.ui.ConfirmDialog
import com.aplusconnect.companion.ui.ConnectActions
import com.aplusconnect.companion.ui.ConnectScreen
import com.aplusconnect.companion.ui.ConnectUi
import com.aplusconnect.companion.ui.FilesActions
import com.aplusconnect.companion.ui.FilesScreen
import com.aplusconnect.companion.ui.FilesUi
import com.aplusconnect.companion.ui.HistoryActions
import com.aplusconnect.companion.ui.HistoryScreen
import com.aplusconnect.companion.ui.HistoryUi
import com.aplusconnect.companion.ui.ManualPairingSheet
import com.aplusconnect.companion.ui.PhoneVitals
import com.aplusconnect.companion.ui.RenameDialog
import com.aplusconnect.companion.ui.ScannerOverlay
import com.aplusconnect.companion.ui.SendTextDialog
import com.aplusconnect.companion.ui.SettingsActions
import com.aplusconnect.companion.ui.SettingsScreen
import com.aplusconnect.companion.ui.SettingsUi
import com.aplusconnect.companion.ui.TransferItem
import com.aplusconnect.companion.ui.components.AmbientCanvas
import com.aplusconnect.companion.ui.components.TransferState
import com.aplusconnect.companion.ui.components.glassSource
import com.aplusconnect.companion.ui.components.FloatingNavBar
import com.aplusconnect.companion.ui.components.NavDestination
import com.aplusconnect.companion.ui.components.rememberGlassState
import com.aplusconnect.companion.ui.connectionPhase
import com.aplusconnect.companion.ui.hostPort
import com.aplusconnect.companion.ui.theme.AplusTheme
import com.aplusconnect.companion.ui.theme.AplusType
import com.aplusconnect.companion.ui.theme.Radius
import com.aplusconnect.companion.ui.theme.ThemeMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
        enableEdgeToEdge()
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
            var themeMode by remember { mutableStateOf(ThemeMode.fromKey(settings.themeMode)) }
            var reduceTransparency by remember { mutableStateOf(settings.reduceTransparency) }
            var reduceMotion by remember { mutableStateOf(settings.reduceMotion) }
            var barClarity by remember { mutableIntStateOf(settings.barClarity) }

            AplusTheme(
                mode = themeMode,
                reduceTransparency = reduceTransparency,
                reduceMotion = reduceMotion,
                barClarity = barClarity,
            ) {
                MainScreen(
                    settings = settings,
                    appearance = SettingsAppearance(
                        themeMode = themeMode,
                        reduceTransparency = reduceTransparency,
                        reduceMotion = reduceMotion,
                        onThemeMode = { themeMode = it; settings.themeMode = it.key },
                        onReduceTransparency = { reduceTransparency = it; settings.reduceTransparency = it },
                        onReduceMotion = { reduceMotion = it; settings.reduceMotion = it },
                        barClarity = barClarity,
                        onBarClarity = { barClarity = it },
                        onBarClarityDone = { settings.barClarity = barClarity },
                    ),
                )
            }
        }
    }

    private companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_CODE = "code"
    }
}

/** Appearance lives above the theme, so changing it re-themes the whole app at once. */
private class SettingsAppearance(
    val themeMode: ThemeMode,
    val reduceTransparency: Boolean,
    val reduceMotion: Boolean,
    val onThemeMode: (ThemeMode) -> Unit,
    val onReduceTransparency: (Boolean) -> Unit,
    val onReduceMotion: (Boolean) -> Unit,
    val barClarity: Int,
    val onBarClarity: (Int) -> Unit,
    val onBarClarityDone: () -> Unit,
)

private enum class Tab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    Connect("Connect", Icons.Outlined.Link, Icons.Filled.Link),
    Files("Files", Icons.Outlined.Folder, Icons.Filled.Folder),
    Clipboard("Clipboard", Icons.Outlined.ContentPaste, Icons.Filled.ContentPaste),
    Settings("Settings", Icons.Outlined.Settings, Icons.Filled.Settings),
}

private val navDestinations = Tab.entries.map { NavDestination(it.label, it.icon, it.selectedIcon) }

private enum class Confirm { None, Forget, ClearHistory, ClearFileHistory }

@Composable
private fun MainScreen(settings: CompanionSettings, appearance: SettingsAppearance) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val glass = rememberGlassState()
    val c = AplusTheme.colors

    fun notify(message: String, error: Boolean = false, action: String? = null, onAction: (() -> Unit)? = null) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(
                message = message,
                actionLabel = action,
                withDismissAction = error,
                duration = if (error) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) onAction?.invoke()
        }
    }

    var tab by remember { mutableStateOf(Tab.Connect) }

    var deviceName by remember { mutableStateOf(resolveDeviceName(context, settings)) }
    var statusText by remember { mutableStateOf(CompanionService.Status.state) }
    var isConnected by remember { mutableStateOf(CompanionService.Status.isConnected) }
    var mirroring by remember { mutableStateOf(CompanionService.Status.mirroring) }
    var askedToConnect by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf(settings.host) }
    var port by remember { mutableIntStateOf(settings.port) }
    var paired by remember { mutableStateOf(settings.pairing != null) }

    var lastSyncedText by remember { mutableStateOf(CompanionService.Status.lastSyncedText) }
    var lastDirection by remember { mutableStateOf(CompanionService.Status.lastDirection) }
    var lastSyncedTime by remember { mutableStateOf(CompanionService.Status.lastSyncedTime) }
    var historyItems by remember { mutableStateOf(settings.getHistory()) }

    // Files shared with the computer, sent and received. The History page
    // shows them; the Files page only shows sends that are still moving.
    val fileHistory = remember { FileHistory(context) }
    var fileHistoryEntries by remember { mutableStateOf(fileHistory.entries()) }
    var fileHistorySeen by remember { mutableIntStateOf(CompanionService.Status.fileHistoryVersion) }
    var showHistory by remember { mutableStateOf(false) }
    var historyDirection by remember { mutableStateOf(Direction.Sent) }

    var keyboardActive by remember { mutableStateOf(isDefaultKeyboard(context)) }
    var batteryOptimized by remember { mutableStateOf(isBatteryOptimized(context)) }
    var notificationAccess by remember { mutableStateOf(hasNotificationAccess(context)) }
    var allFilesGranted by remember { mutableStateOf(hasAllFilesAccess(context)) }
    var clipboardSync by remember { mutableStateOf(settings.clipboardSync) }
    var companionEnabled by remember { mutableStateOf(settings.enabled) }
    var downloadFolder by remember { mutableStateOf(settings.downloadFolderUri) }
    var vitals by remember { mutableStateOf(readVitals(context)) }

    val transfers = remember { mutableStateListOf<TransferItem>() }
    val pendingUris = remember { mutableMapOf<Long, Uri>() }

    var showScanner by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showSendText by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(Confirm.None) }

    fun refreshPermissions() {
        allFilesGranted = hasAllFilesAccess(context)
        notificationAccess = hasNotificationAccess(context)
        keyboardActive = isDefaultKeyboard(context)
        batteryOptimized = isBatteryOptimized(context)
        vitals = readVitals(context)
    }

    // Permissions are granted on system pages; re-read them whenever the app returns.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshPermissions()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Live state from the service.
    LaunchedEffect(Unit) {
        var tick = 0
        while (true) {
            statusText = CompanionService.Status.state
            val connected = CompanionService.Status.isConnected
            isConnected = connected
            mirroring = CompanionService.Status.mirroring
            if (connected) askedToConnect = false
            lastSyncedText = CompanionService.Status.lastSyncedText
            lastDirection = CompanionService.Status.lastDirection
            lastSyncedTime = CompanionService.Status.lastSyncedTime
            // The service bumps the version when a file arrives.
            val version = CompanionService.Status.fileHistoryVersion
            if (version != fileHistorySeen) {
                fileHistorySeen = version
                fileHistoryEntries = fileHistory.entries()
            }
            historyItems = settings.getHistory()
            paired = settings.pairing != null
            keyboardActive = isDefaultKeyboard(context)
            batteryOptimized = isBatteryOptimized(context)
            if (tick++ % 20 == 0) vitals = readVitals(context)
            delay(500L)
        }
    }

    fun connectNow() {
        askedToConnect = true
        CompanionService.reconnect(context)
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                // Some providers do not offer a persistable permission; the
                // folder still works for this session.
            }
            settings.downloadFolderUri = uri.toString()
            downloadFolder = uri.toString()
        }
    }

    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showScanner = true
        else notify("The camera is needed to scan the QR code.", error = true, action = "Enter code") { showManual = true }
    }

    fun startSend(uri: Uri, existingId: Long? = null) {
        val id = existingId ?: System.nanoTime()
        val (name, size) = queryFile(context, uri)
        pendingUris[id] = uri
        val item = TransferItem(id, name, size, TransferState.Queued, null, null)
        val index = transfers.indexOfFirst { it.id == id }
        if (index >= 0) transfers[index] = item else transfers.add(0, item)

        fun update(block: (TransferItem) -> TransferItem) {
            val i = transfers.indexOfFirst { it.id == id }
            if (i >= 0) transfers[i] = block(transfers[i])
        }
        CompanionService.sendFile(
            context = context,
            uri = uri,
            onProgress = { p -> update { it.copy(state = TransferState.Active, progress = p) } },
            onComplete = { success, error ->
                if (success) {
                    // Done: it moves from Files to History.
                    transfers.removeAll { it.id == id }
                    pendingUris.remove(id)
                    fileHistory.add(
                        SharedFileEntry(
                            id = "sent-$id",
                            name = name,
                            direction = Direction.Sent,
                            sizeBytes = size,
                            timestamp = System.currentTimeMillis(),
                            uri = uri.toString(),
                        )
                    )
                    fileHistoryEntries = fileHistory.entries()
                    notify("Sent $name to Downloads", action = "History") {
                        tab = Tab.Files
                        historyDirection = Direction.Sent
                        showHistory = true
                    }
                } else {
                    update { it.copy(state = TransferState.Failed, message = error) }
                    notify("$name didn't send", error = true, action = "Retry") { startSend(uri, id) }
                }
            },
        )
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) startSend(uri)
    }

    val phase = connectionPhase(
        enabled = companionEnabled,
        hasHost = host.isNotEmpty(),
        isConnected = isConnected,
        mirroring = mirroring,
        userAskedToConnect = askedToConnect,
        state = statusText,
    )
    val computerLabel = hostPort(host, port).ifEmpty { "your computer" }

    AmbientCanvas {
        Scaffold(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = {
                SnackbarHost(snackbar) { data ->
                    Snackbar(
                        snackbarData = data,
                        shape = RoundedCornerShape(Radius.md),
                        containerColor = c.surfaceElevated,
                        contentColor = c.textPrimary,
                        actionColor = c.accent,
                        dismissActionContentColor = c.textSecondary,
                        modifier = Modifier.border(1.dp, c.divider, RoundedCornerShape(Radius.md)),
                    )
                }
            },
            bottomBar = {
                FloatingNavBar(
                    items = navDestinations,
                    selected = tab.ordinal,
                    onSelect = {
                        tab = Tab.entries[it]
                        showHistory = false
                    },
                    glass = glass,
                )
            },
        ) { innerPadding ->
            val padding = PaddingValues(
                top = statusBarsTop(),
                bottom = innerPadding.calculateBottomPadding(),
            )
            val motion = AplusTheme.motion
            Box(Modifier.fillMaxSize().glassSource(glass)) {
                AnimatedContent(
                    targetState = if (tab == Tab.Files && showHistory) null else tab,
                    transitionSpec = { fadeIn(motion.enter()) togetherWith fadeOut(motion.fade()) },
                    label = "tab",
                ) { current ->
                    when (current) {
                        // History: a page inside Files, opened from its header.
                        null -> HistoryScreen(
                            ui = HistoryUi(entries = fileHistoryEntries, direction = historyDirection),
                            actions = HistoryActions(
                                onBack = { showHistory = false },
                                onDirection = { historyDirection = it },
                                onOpen = { entry -> openSharedFile(context, entry) { notify(it, error = true) } },
                                onOpenFolder = { openReceivedFolder(context, downloadFolder) { notify(it, error = true) } },
                                onClear = { confirm = Confirm.ClearFileHistory },
                            ),
                            contentPadding = padding,
                        )
                        Tab.Connect -> ConnectScreen(
                            ui = ConnectUi(
                                phase = phase,
                                statusDetail = statusText,
                                deviceName = deviceName,
                                hostPort = hostPort(host, port),
                                paired = paired,
                                companionEnabled = companionEnabled,
                                keyboardActive = keyboardActive,
                                batteryKeepAlive = !batteryOptimized,
                                notificationAccess = notificationAccess,
                                vitals = vitals,
                            ),
                            actions = ConnectActions(
                                onScanQr = { cameraPermission.launch(android.Manifest.permission.CAMERA) },
                                onEnterCode = { showManual = true },
                                onReconnect = { connectNow() },
                                onCancelConnect = {
                                    askedToConnect = false
                                    CompanionService.stop(context)
                                },
                                onToggleCompanion = { wanted ->
                                    companionEnabled = wanted
                                    CompanionService.setEnabled(context, wanted)
                                },
                                onRename = { showRename = true },
                                onForget = { confirm = Confirm.Forget },
                                onStopMirroring = {
                                    context.startService(
                                        Intent(context, CompanionService::class.java).setAction(CompanionService.ACTION_STOP_MIRROR)
                                    )
                                },
                                onSetKeyboard = { openKeyboardPicker(context) { notify(it, error = true) } },
                                onAllowBattery = { requestIgnoreBatteryOptimizations(context) },
                                onAllowNotifications = { openNotificationAccess(context) },
                            ),
                            contentPadding = padding,
                        )
                        Tab.Files -> FilesScreen(
                            ui = FilesUi(
                                connected = isConnected,
                                computerLabel = computerLabel,
                                transfers = transfers.toList(),
                                historyCount = fileHistoryEntries.size,
                                receivedFolder = folderLabel(downloadFolder),
                                browseGranted = allFilesGranted,
                                storageFreeBytes = vitals.storageFreeBytes,
                            ),
                            actions = FilesActions(
                                onPickFile = { filePicker.launch("*/*") },
                                onPickMedia = { filePicker.launch("image/*,video/*") },
                                onRetry = { t -> pendingUris[t.id]?.let { startSend(it, t.id) } },
                                onDismiss = { t ->
                                    transfers.removeAll { it.id == t.id }
                                    pendingUris.remove(t.id)
                                },
                                onOpenHistory = { showHistory = true },
                                onChangeFolder = { folderPicker.launch(null) },
                                onGrantBrowse = { context.startActivity(PhoneFileBrowser(context).grantIntent()) },
                                onGoToConnect = { tab = Tab.Connect },
                            ),
                            contentPadding = padding,
                        )
                        Tab.Clipboard -> ClipboardScreen(
                            ui = ClipboardUi(
                                connected = isConnected,
                                syncOn = clipboardSync,
                                keyboardActive = keyboardActive,
                                lastText = lastSyncedText,
                                lastDirection = lastDirection,
                                lastTime = lastSyncedTime,
                                history = historyItems,
                            ),
                            actions = ClipboardActions(
                                onToggleSync = { clipboardSync = it; settings.clipboardSync = it },
                                onChooseKeyboard = { openKeyboardPicker(context) { notify(it, error = true) } },
                                onSendText = { showSendText = true },
                                onSendCurrent = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                                    if (clip.isNotEmpty()) {
                                        CompanionService.deliverClipboard(context, clip)
                                        notify("Sent this phone's clipboard to the computer")
                                    } else {
                                        notify("This phone's clipboard is empty.")
                                    }
                                },
                                onCopy = { entry ->
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("A+ Connect", entry.text))
                                    // Android 13+ shows its own copy confirmation; avoid a double message.
                                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) notify("Copied")
                                },
                                onClearHistory = { confirm = Confirm.ClearHistory },
                                onGoToConnect = { tab = Tab.Connect },
                            ),
                            contentPadding = padding,
                        )
                        Tab.Settings -> SettingsScreen(
                            ui = SettingsUi(
                                themeMode = appearance.themeMode,
                                reduceTransparency = appearance.reduceTransparency,
                                reduceMotion = appearance.reduceMotion,
                                barClarity = appearance.barClarity,
                                deviceName = deviceName,
                                version = appVersion(context),
                                systemVersion = systemVersion(),
                            ),
                            actions = SettingsActions(
                                onThemeMode = appearance.onThemeMode,
                                onReduceTransparency = appearance.onReduceTransparency,
                                onReduceMotion = appearance.onReduceMotion,
                                onBarClarity = appearance.onBarClarity,
                                onBarClarityDone = appearance.onBarClarityDone,
                                onRename = { showRename = true },
                            ),
                            contentPadding = padding,
                        )
                    }
                }
            }
        }
    }

    if (showScanner) {
        ScannerOverlay(
            onScanned = { text ->
                showScanner = false
                val info = PairingPayload.fromJson(text)
                if (info == null) {
                    notify("That isn't an A+ Connect pairing code.", error = true, action = "Try again") {
                        cameraPermission.launch(android.Manifest.permission.CAMERA)
                    }
                } else {
                    if (info.fingerprint.isNotEmpty() && info.pcId.isNotEmpty()) {
                        // The QR carries the computer's certificate, so it
                        // can be pinned before the first connection.
                        settings.remember(info.pcId, info.fingerprint)
                    }
                    settings.host = info.host
                    settings.port = info.port
                    settings.pairingCode = info.code
                    host = info.host
                    port = info.port
                    tab = Tab.Connect
                    connectNow()
                    notify("Pairing with ${info.pcName}…")
                }
            },
            onClose = { showScanner = false },
            onEnterCode = { showScanner = false; showManual = true },
        )
    }

    if (showManual) {
        ManualPairingSheet(
            initialHost = host,
            initialPort = port,
            initialCode = settings.pairingCode,
            onConnect = { h, p, code ->
                settings.host = h
                settings.port = p
                settings.pairingCode = code
                host = h
                port = p
                showManual = false
                tab = Tab.Connect
                connectNow()
            },
            onDismiss = { showManual = false },
        )
    }

    if (showRename) {
        RenameDialog(
            current = deviceName,
            onSave = { name ->
                if (name.isNotEmpty()) {
                    settings.deviceName = name
                    deviceName = name
                    CompanionService.reconnect(context)
                    notify("Renamed to $name")
                }
                showRename = false
            },
            onDismiss = { showRename = false },
        )
    }

    if (showSendText) {
        SendTextDialog(
            onSend = { text ->
                if (text.isNotEmpty()) {
                    CompanionService.deliverClipboard(context, text)
                    notify("Sent to the computer's clipboard")
                }
                showSendText = false
            },
            onDismiss = { showSendText = false },
        )
    }

    when (confirm) {
        Confirm.Forget -> ConfirmDialog(
            title = "Forget pairing?",
            body = "This phone stops trusting the computer. To connect again you'll scan a new QR code.",
            confirmLabel = "Forget",
            onConfirm = {
                settings.forget()
                paired = false
                confirm = Confirm.None
                notify("Pairing forgotten")
            },
            onDismiss = { confirm = Confirm.None },
        )
        Confirm.ClearHistory -> ConfirmDialog(
            title = "Clear clipboard history?",
            body = "Removes ${historyItems.size} saved clips from this phone. The computer keeps its own history.",
            confirmLabel = "Clear",
            onConfirm = {
                settings.clearHistory()
                historyItems = emptyList()
                confirm = Confirm.None
                notify("Clipboard history cleared")
            },
            onDismiss = { confirm = Confirm.None },
        )
        Confirm.ClearFileHistory -> ConfirmDialog(
            title = "Clear file history?",
            body = "Removes ${fileHistoryEntries.size} files from this list. The files themselves stay where they are.",
            confirmLabel = "Clear",
            onConfirm = {
                fileHistory.clear()
                fileHistoryEntries = emptyList()
                confirm = Confirm.None
                notify("File history cleared")
            },
            onDismiss = { confirm = Confirm.None },
        )
        Confirm.None -> {}
    }

    // Back from History returns to Files rather than leaving the app.
    BackHandler(enabled = tab == Tab.Files && showHistory) { showHistory = false }
}

/* ---------- opening shared files ---------- */

/**
 * Opens a file from History in whatever app handles it.
 *
 * A received file in the app's own folder is a file:// address, which another
 * app may not read, so it goes out through the FileProvider. A sent file is
 * the document the picker handed over; that grant can expire, and then the
 * row says so instead of doing nothing.
 */
private fun openSharedFile(context: Context, entry: SharedFileEntry, onError: (String) -> Unit) {
    val uri = try {
        val parsed = Uri.parse(entry.uri)
        if (parsed.scheme == "file") {
            FileProvider.getUriForFile(context, "${context.packageName}.files", java.io.File(parsed.path ?: ""))
        } else parsed
    } catch (_: Exception) {
        onError("${entry.name} isn't available any more.")
        return
    }
    val extension = entry.name.substringAfterLast('.', "").lowercase()
    val mime = context.contentResolver.getType(uri)
        ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        ?: "*/*"
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        onError("No app on this phone opens ${entry.name}.")
    } catch (_: SecurityException) {
        onError("${entry.name} can't be opened from here any more. Find it in your Files app.")
    } catch (_: Exception) {
        onError("${entry.name} isn't available any more.")
    }
}

/** Opens the folder received files go to: the chosen one, else Downloads. */
private fun openReceivedFolder(context: Context, treeUri: String, onError: (String) -> Unit) {
    val intent = if (treeUri.isNotEmpty()) {
        try {
            val tree = Uri.parse(treeUri)
            val folder = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(folder, DocumentsContract.Document.MIME_TYPE_DIR)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
            Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
        }
    } else {
        Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
    }
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        onError("Couldn't open the folder.")
    }
}

/* ---------- system reads ---------- */

@Composable
private fun statusBarsTop(): androidx.compose.ui.unit.Dp {
    val insets = androidx.compose.foundation.layout.WindowInsets.statusBars
    val density = androidx.compose.ui.platform.LocalDensity.current
    return with(density) { insets.getTop(density).toDp() }
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

private fun queryFile(context: Context, uri: Uri): Pair<String, Long> {
    var name = "file"
    var size = 0L
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val n = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val s = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (n != -1) cursor.getString(n)?.let { name = it }
                if (s != -1) size = cursor.getLong(s)
            }
        }
    } catch (_: Exception) {}
    return name to size
}

private fun readVitals(context: Context): PhoneVitals {
    val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
    val charging = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) == BatteryManager.BATTERY_STATUS_CHARGING
    val free = try { StatFs(Environment.getDataDirectory().path).availableBytes } catch (_: Exception) { null }
    return PhoneVitals(level, charging, Build.VERSION.RELEASE ?: "—", free)
}

private fun folderLabel(uri: String): String =
    if (uri.isEmpty()) "This app's Downloads folder"
    else Uri.decode(uri).substringAfterLast(':').substringAfterLast('/').ifEmpty { "Chosen folder" }

private fun appVersion(context: Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
} catch (_: Exception) { "" }

/** The ROM's own version, shown as data (DESIGN.md §1.1), never as branding. */
private fun systemVersion(): String = Build.DISPLAY ?: Build.VERSION.RELEASE

/**
 * Opens the keyboard picker so the user can choose the companion keyboard.
 *
 * This is deliberately not the accessibility settings: an accessibility
 * service makes Android block contactless payments, so the app does not use
 * one and does not ask for one.
 */
private fun openKeyboardPicker(context: Context, onError: (String) -> Unit) {
    try {
        context.getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
    } catch (e: Exception) {
        onError("Couldn't open the keyboard picker.")
    }
}

private fun hasAllFilesAccess(context: Context): Boolean = PhoneFileBrowser(context).isGranted()

private fun hasNotificationAccess(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

private fun openNotificationAccess(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
private fun isDefaultKeyboard(context: Context): Boolean {
    val current = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: return false
    return com.aplusconnect.companion.clip.KeyboardBridge.PACKAGES.any { current.startsWith(it) }
}

private fun isBatteryOptimized(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return !pm.isIgnoringBatteryOptimizations(context.packageName)
}

private fun requestIgnoreBatteryOptimizations(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        )
    } catch (_: Exception) {
        try {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
        } catch (_: Exception) {}
    }
}
