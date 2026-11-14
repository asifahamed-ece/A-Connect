package com.aplusconnect.companion.ui

import com.aplusconnect.companion.data.ClipHistoryEntry
import com.aplusconnect.companion.ui.components.TransferState

/** The phone's own vitals for the Connect page. Read locally; no protocol change. */
internal data class PhoneVitals(
    val batteryPercent: Int?,
    val charging: Boolean,
    val androidVersion: String,
    val storageFreeBytes: Long?,
)

/** Everything the Connect page draws. */
internal data class ConnectUi(
    val phase: ConnectionPhase,
    val statusDetail: String,
    val deviceName: String,
    val hostPort: String,
    val paired: Boolean,
    val companionEnabled: Boolean,
    val keyboardActive: Boolean,
    val batteryKeepAlive: Boolean,
    val notificationAccess: Boolean,
    val vitals: PhoneVitals,
)

internal class ConnectActions(
    val onScanQr: () -> Unit,
    val onEnterCode: () -> Unit,
    val onReconnect: () -> Unit,
    val onCancelConnect: () -> Unit,
    val onToggleCompanion: (Boolean) -> Unit,
    val onRename: () -> Unit,
    val onForget: () -> Unit,
    val onStopMirroring: () -> Unit,
    val onSetKeyboard: () -> Unit,
    val onAllowBattery: () -> Unit,
    val onAllowNotifications: () -> Unit,
)

/** One send to the computer, shown from the moment the file is picked (optimistic, §8.11). */
internal data class TransferItem(
    val id: Long,
    val name: String,
    val sizeBytes: Long,
    val state: TransferState,
    val progress: Float?,
    val message: String?,
)

internal data class FilesUi(
    val connected: Boolean,
    val computerLabel: String,
    /** Only sends in flight or failed. Finished ones are in History. */
    val transfers: List<TransferItem>,
    val historyCount: Int,
    val receivedFolder: String,
    val browseGranted: Boolean,
    val storageFreeBytes: Long?,
)

internal class FilesActions(
    val onPickFile: () -> Unit,
    val onPickMedia: () -> Unit,
    val onRetry: (TransferItem) -> Unit,
    val onDismiss: (TransferItem) -> Unit,
    val onOpenHistory: () -> Unit,
    val onChangeFolder: () -> Unit,
    val onGrantBrowse: () -> Unit,
    val onGoToConnect: () -> Unit,
)

/** Whether a send still belongs on the Files page: moving, waiting, or failed (so it can be retried). */
internal fun TransferItem.isLive(): Boolean =
    state == TransferState.Queued || state == TransferState.Active || state == TransferState.Failed

internal data class ClipboardUi(
    val connected: Boolean,
    val syncOn: Boolean,
    val keyboardActive: Boolean,
    val lastText: String?,
    val lastDirection: String?,
    val lastTime: Long,
    val history: List<ClipHistoryEntry>,
)

internal class ClipboardActions(
    val onToggleSync: (Boolean) -> Unit,
    val onChooseKeyboard: () -> Unit,
    val onSendText: () -> Unit,
    val onSendCurrent: () -> Unit,
    val onCopy: (ClipHistoryEntry) -> Unit,
    val onClearHistory: () -> Unit,
    val onGoToConnect: () -> Unit,
)

/** Clipboard sync status (§10.3). */
internal fun clipboardStatus(ui: ClipboardUi): Pair<String, Tone> = when {
    !ui.syncOn -> "Off" to Tone.Neutral
    !ui.connected -> "Waiting for computer" to Tone.Neutral
    !ui.keyboardActive -> "Needs keyboard" to Tone.Warning
    else -> "Syncing" to Tone.Success
}
