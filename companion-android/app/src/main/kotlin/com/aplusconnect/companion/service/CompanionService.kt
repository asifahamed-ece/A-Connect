package com.aplusconnect.companion.service

import com.aplusconnect.companion.data.Direction
import com.aplusconnect.companion.data.FileHistory
import com.aplusconnect.companion.data.SharedFileEntry
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.graphics.drawable.Icon
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.aplusconnect.companion.R
import com.aplusconnect.companion.clip.ClipboardReadActivity
import com.aplusconnect.companion.clip.ClipboardBridgeReceiver
import com.aplusconnect.companion.clip.KeyboardBridge
import com.aplusconnect.companion.data.CompanionSettings
import com.aplusconnect.companion.file.FileHash
import com.aplusconnect.companion.file.PhoneFileBrowser
import com.aplusconnect.companion.file.StorageSearch
import com.aplusconnect.companion.mirror.ScreenMirror
import com.aplusconnect.companion.net.CompanionClient
import com.aplusconnect.companion.net.DownloadDestination
import com.aplusconnect.companion.net.IncomingFile
import com.aplusconnect.companion.net.ReconnectPolicy
import com.aplusconnect.companion.net.VideoClient
import com.aplusconnect.companion.protocol.ClipboardPayload
import com.aplusconnect.companion.protocol.ClipboardPayloads
import com.aplusconnect.companion.protocol.FeaturePayloads
import com.aplusconnect.companion.protocol.Protocol
import com.aplusconnect.companion.protocol.VideoFrameHeader
import org.json.JSONObject
import java.util.UUID

/**
 * Keeps the connection to the computer alive and carries the clipboard across.
 *
 * It runs as a foreground service because that is what keeps the process
 * alive while the phone is in the background - and a visible notification is
 * also what the platform wants to see from an app that is always running.
 */
class CompanionService : Service() {

    /** What the user sees: the live connection state. */
    object Status {
        @Volatile
        var state: String = "Not connected"

        @Volatile
        var isConnected: Boolean = false

        @Volatile
        var lastSyncedText: String? = null

        /** The last file that arrived from the computer. */
        @Volatile
        var lastReceivedFile: String? = null

        /** Bumped whenever the file history changes, so the UI re-reads it. */
        @Volatile
        var fileHistoryVersion: Int = 0

        /** The last text the computer copied, for the keyboard to offer. */
        @Volatile
        var lastPcClipboard: String? = null

        /** The newest notification the phone forwarded, for the app UI. */
        @Volatile
        var lastNotification: String? = null

        /** A short history of what the computer copied. */
        @Volatile
        var pcClipboardHistory: List<String> = emptyList()

        @Volatile
        var lastSyncedTime: Long = 0L

        @Volatile
        var lastDirection: String? = null // "phone_to_pc" or "pc_to_phone"

        /** Whether the screen is being mirrored right now, for the app's own UI. */
        @Volatile
        var mirroring: Boolean = false
    }

    private lateinit var settings: CompanionSettings
    private lateinit var incomingFile: IncomingFile
    private lateinit var destination: DownloadDestination
    private lateinit var fileHistory: FileHistory
    private lateinit var fileBrowser: PhoneFileBrowser
    /** Built on first search; the file thread is the only thread that touches it. */
    private var searcher: StorageSearch? = null
    private var client: CompanionClient? = null
    private var reconnectAt = 0L
    private var reconnectRunnable: Runnable? = null

    /**
     * Set when the computer hung up on purpose.
     *
     * The reconnect loop exists for network drops. Retrying after a deliberate
     * hang-up would put the phone back within five seconds and make the
     * computer's Disconnect button look broken, so the loop stays off until
     * the user taps Reconnect on the phone.
     */
    private var hungUpByComputer = false

    /**
     * How the phone dials: fast for a dropped link, slow for a computer that
     * is switched off. See ReconnectPolicy.
     */
    private val reconnectPolicy = ReconnectPolicy()

    /**
     * Screen mirroring state.
     *
     * [mirrorRequest] is what the computer asked for, held while the user is
     * being asked for permission; [screenMirror] is the capture once it is
     * running; [videoSocket] is the second connection the frames leave on.
     */
    private var mirrorRequest: FeaturePayloads.MirrorRequest? = null
    private var screenMirror: ScreenMirror? = null
    private var videoSocket: VideoClient? = null

    /**
     * Where a mirror starts and stops.
     *
     * Not [sendThread]: starting an encoder and creating a virtual display are
     * binder calls that can take a moment, and the clipboard must not wait
     * behind them. Not the reader thread either, which has to keep answering
     * the computer's pings.
     */
    private val mirrorThread = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "companion-mirror").apply { isDaemon = true }
    }

    /** Stops mirroring when the screen goes dark, with an honest reason. */
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                Log.i(TAG, "the screen went off")
                stopMirror("screen-slept", tellComputer = true)
            }
        }
    }

    /** Guards the pending clipboard and the last sent hash. */
    private val stateLock = Any()

    /**
     * Where clipboard sends happen.
     *
     * The keyboard's broadcast is delivered on the main thread, so the send
     * cannot run there (NetworkOnMainThreadException closes the session), and
     * it must not run on the reader thread either, which is blocked reading.
     */
    private val sendThread = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "companion-send").apply { isDaemon = true }
    }
    private var pendingPayload: ClipboardPayload? = null

    /**
     * Notifications waiting for the connection, oldest first.
     *
     * A notification that arrives while the computer is offline must still
     * reach it, once: the queue keeps the order, and a notification that is
     * updated replaces its own queued copy rather than piling up.
     */
    private val notificationQueue = ArrayDeque<Pair<String, JSONObject>>()

    /** What the phone is playing, and the controls for it. */
    private var mediaBridge: MediaSessionBridge? = null

    /**
     * Where the computer's file requests are served.
     *
     * Not the send thread: reading a folder, and especially streaming a whole
     * file, can take a long time, and the clipboard must not wait behind it.
     * Not the reader thread either, which must keep answering the computer's
     * pings or the computer drops the link.
     */
    private val fileThread = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "companion-files").apply { isDaemon = true }
    }

    /**
     * Searches have their own thread.
     *
     * The first search of a session may wait for the storage walk, and that
     * must never make a folder listing - which shares [fileThread] - sit behind
     * it. The index itself is shared and locked, so two threads are safe.
     */
    private val searchThread = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "companion-search").apply { isDaemon = true }
    }

    /**
     * Watches the phone's media database for files this app did not write.
     *
     * A download finishing in another app, a photo just taken, a document
     * saved by an editor - none of those go through this app, so the search
     * index would keep answering from names it gathered earlier. The database
     * change is the phone telling us something arrived.
     */
    private val mediaObserver = object : android.database.ContentObserver(
        // Looper.getMainLooper(), not mainLooper: property initialisers run
        // before the Service has a context attached, and this one would crash
        // the app on startup with a null Context.
        android.os.Handler(android.os.Looper.getMainLooper())
    ) {
        override fun onChange(selfChange: Boolean, uri: android.net.Uri?) {
            // A download posts hundreds of changes as it grows. Dropping the
            // walking of the whole storage after every one of them would keep
            // the CPU busy for the length of the download, so the rebuild waits
            // for the changes to settle.
            mediaSettled.removeCallbacks(mediaRebuild)
            mediaSettled.postDelayed(mediaRebuild, MEDIA_SETTLE_MS)
        }
    }

    /** Rebuilds the search index once the media database has stopped churning. */
    private val mediaRebuild = Runnable {
        searcher?.invalidate()
    }

    private val mediaSettled = android.os.Handler(android.os.Looper.getMainLooper())

    /** Publishes the phone's vitals every 60 s while connected. */
    private var deviceInfoTimer: java.util.concurrent.ScheduledExecutorService? = null

    override fun onCreate() {
        super.onCreate()
        settings = CompanionSettings(this)
        incomingFile = IncomingFile(java.io.File(cacheDir, "incoming"))
        destination = DownloadDestination(this)
        fileHistory = FileHistory(this)
        fileBrowser = PhoneFileBrowser(this)
        instance = this
        watchMediaDatabase()

        // Anything that arrived while the service was not running goes to the
        // front of the queue, so it still reaches the computer.
        val early = synchronized(earlyNotifications) {
            val copy = earlyNotifications.toList()
            earlyNotifications.clear()
            copy
        }
        synchronized(stateLock) { notificationQueue.addAll(early) }

        // Playback control needs notification access. Without that grant the
        // bridge's start() is a no-op and the feature simply stays absent.
        mediaBridge = MediaSessionBridge(
            context = this,
            listener = android.content.ComponentName(this, PhoneNotificationListener::class.java),
            // Synchronous, so the bridge knows whether the cover really went
            // out. Every caller of publish() is a background thread (the
            // ticker, the session callbacks, the send thread), which is what
            // makes a socket write legal here.
            send = { payload -> sendMediaState(payload) },
        ).also { it.start() }

        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "A+ Connect", NotificationManager.IMPORTANCE_LOW)
        )
        // The foreground type is named explicitly, and it is the connection
        // type only. Declaring mediaProjection in the manifest is necessary
        // but not enough: Android refuses to start a mediaProjection service
        // until the user has actually granted a projection, so asking for that
        // type here crashes the app on launch (SecurityException from
        // ActiveServices.validateForegroundServiceType). The type is raised
        // when a capture begins, in beginMirroring().
        raiseForegroundWith(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            "Connecting…",
        )

        connect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_MIRROR) {
            // The Stop sharing action on the foreground notification. It goes
            // through the same teardown as every other trigger.
            mirrorThread.execute { stopMirror("requested", tellComputer = true) }
        }
        if (intent?.action == ACTION_CONNECT) {
            if (client == null || client?.isReady != true) {
                connect()
            }
        }
        val text = intent?.getStringExtra(EXTRA_CLIPBOARD)
        if (text != null) {
            // The broadcast arrives on the main thread, and Android refuses a
            // socket write there: it would kill the connection. Every send
            // happens on this thread instead.
            sendThread.execute { send(text) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        // A projection left running with no service underneath it would keep
        // the phone's status bar chip up for ever.
        stopMirror("requested", tellComputer = true)
        try {
            contentResolver.unregisterContentObserver(mediaObserver)
        } catch (e: Exception) {
            Log.w(TAG, "could not stop watching the media database: ${e.message}")
        }
        mediaSettled.removeCallbacks(mediaRebuild)
        mediaBridge?.stop()
        mediaBridge = null
        val activeClient = client
        client = null
        // Off the main thread, like every other socket close in this service:
        // closing one on the main thread throws NetworkOnMainThreadException
        // and leaves the connection half open.
        sendThread.execute {
            try {
                activeClient?.close()
            } catch (e: Exception) {
                Log.w(TAG, "closing the connection failed: ${e.message}")
            }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun connect() {
        if (client != null) return

        // Connecting is the user's word (Reconnect, or the service starting),
        // so a previous hang-up no longer applies.
        hungUpByComputer = false

        val host = settings.host
        if (host.isEmpty()) {
            Log.i(TAG, "no computer configured yet")
            Status.state = "No computer configured"
            return
        }

        Status.state = "Connecting to $host…"

        val oldClient = client
        client = null
        oldClient?.close()

        val customName = settings.deviceName
        val resolvedName = if (customName.isNotEmpty()) {
            customName
        } else {
            val systemName = try {
                android.provider.Settings.Global.getString(contentResolver, "device_name")
            } catch (_: Exception) { null }
            systemName?.ifEmpty { null } ?: android.os.Build.MODEL
        }

        var thisClient: CompanionClient? = null
        thisClient = CompanionClient(
            host = host,
            port = settings.port,
            deviceId = settings.deviceId.ifEmpty {
                UUID.randomUUID().toString().also { settings.deviceId = it }
            },
            deviceName = resolvedName,
            code = settings.pairingCode,
            pairing = settings.pairing,
            sharedSecret = settings.sharedSecret,
            clipboardAccess = isDefaultKeyboard(),
            onSecretCreated = { secret -> settings.sharedSecret = secret },
            onFileStart = { id, name, size, mime ->
                incomingFile.start(id, name, size, mime)
            },
            onFileChunk = { id, index, bytes ->
                incomingFile.chunk(id, bytes)
            },
            onFileDone = { id ->
                val received = incomingFile.finish(id)
                if (received != null) {
                    val receivedBytes = received.file.length()
                    val saved = destination.save(
                        received.file,
                        received.name,
                        settings.downloadFolderUri,
                    )
                    received.file.delete()
                    if (saved != null) {
                        Status.lastReceivedFile = received.name
                        fileHistory.add(
                            SharedFileEntry(
                                id = "received-$id",
                                name = received.name,
                                direction = Direction.Received,
                                sizeBytes = receivedBytes,
                                timestamp = System.currentTimeMillis(),
                                uri = saved.toString(),
                            )
                        )
                        Status.fileHistoryVersion++
                        Log.i(TAG, "saved ${received.name}")
                        // The search index was built before this file existed,
                        // so without this the file is invisible to search until
                        // the index ages out or the user reconnects.
                        searcher?.invalidate()
                    } else {
                        Log.w(TAG, "could not save ${received.name}")
                    }
                }
            },
            onPaired = { pcId, fingerprint ->
                if (pcId.isNotEmpty()) settings.remember(pcId, fingerprint)
            },
            onReady = {
                if (client === thisClient) {
                    Log.i(TAG, "connected to the computer")
                    // The computer is there: whatever the phone assumed while
                    // it was away is wrong now.
                    reconnectPolicy.onConnected()
                    Status.isConnected = true
                    Status.state = "Connected to ${settings.host}"
                    updateNotification("Connected to ${settings.host}")
                    sendThread.execute { flushPending(); flushNotifications() }
                    // A desktop that just connected wants the current track,
                    // not one that appears at the next state change.
                    mediaBridge?.publish()
                    // The storage walk is slow, so it starts now rather than on
                    // the user's first keystroke in the search box.
                    searchThread.execute { searcher()?.warm() }
                    // The computer's device cards want our vitals right away,
                    // and from then on every 60 s.
                    sendThread.execute { publishDeviceInfo() }
                    startDeviceInfoTimer()
                }
            },
            onClosed = { reason ->
                if (reason.contains("auth-failed")) {
                    // The computer does not know this phone any more: forget
                    // the secret so the next attempt pairs with the code.
                    settings.clearSecret()
                    Log.w(TAG, "the computer refused our proof, pairing again")
                }
                if (client === thisClient) {
                    Log.i(TAG, "disconnected: $reason")
                    stopDeviceInfoTimer()
                    // Clearing the slot is what lets the reconnect loop (and
                    // the Reconnect button) actually connect again: connect()
                    // refuses to run while a client is still recorded.
                    client = null
                    Status.isConnected = false
                    if (reason == CompanionClient.REASON_BYE) {
                        // A hang-up, not a drop. Retrying would undo the
                        // user's Disconnect on the computer within five
                        // seconds, so this waits for the phone's Reconnect.
                        hungUpByComputer = true
                        Status.state = "Disconnected by the computer"
                        updateNotification("Disconnected by the computer")
                    } else if (reason == CompanionClient.REASON_DORMANT) {
                        // The computer is switched off, not gone: keep looking
                        // for it, slowly, so it comes back on its own when the
                        // user switches it on.
                        reconnectPolicy.onDormant()
                        Status.state = "The computer is off · waiting"
                        updateNotification("The computer is off · waiting")
                        scheduleReconnect()
                    } else {
                        // A dropped link. If the computer keeps refusing, the
                        // policy slows the pace on its own - it is probably
                        // switched off and the phone never heard it say so.
                        reconnectPolicy.onFailure()
                        Status.state = "Disconnected · retrying"
                        updateNotification("Disconnected · retrying")
                        scheduleReconnect()
                    }
                }
            },
            onClipboardReceived = { text ->
                applyIncomingClipboard(text)
            },
            onNotificationAction = { id, action ->
                PhoneNotificationListener.handleRemote(id, action)
            },
            onMediaCommand = { command, positionMs ->
                mediaBridge?.command(command, positionMs)
            },
            onFileListRequest = { path ->
                fileThread.execute { answerFileList(path) }
            },
            onFileSendRequest = { path, requestId ->
                fileThread.execute { answerFileSend(path, requestId) }
            },
            onFileSearchRequest = { query, fresh ->
                searchThread.execute { answerFileSearch(query, fresh) }
            },
            onFileHashRequest = { path, requestId ->
                // Hashing can walk a whole large file, so it runs on the file
                // thread rather than the reader thread that must stay free.
                fileThread.execute { answerFileHash(path, requestId) }
            },
            onMirrorStart = { request ->
                // Off the reader thread: starting an encoder is a binder call
                // that must not delay the answers the computer is waiting for.
                mirrorThread.execute { handleMirrorStart(request) }
            },
            onMirrorStop = {
                mirrorThread.execute { stopMirror("requested", tellComputer = false) }
            },
        )
        client = thisClient
        thisClient.connect()
    }

    /**
     * The clipboard change listener.
     *
     * The platform only delivers this callback to an app that may actually
     * read the clipboard - which, for us, means while the app is the default
     * keyboard. So this is the seamless path: no accessibility service, no
     * tap, no focus steal.
     */
    private fun applyIncomingClipboard(text: String) {
        if (text.isEmpty()) return
        val payload = ClipboardPayloads.build(text)
        synchronized(stateLock) { lastSentHash = payload.hash }

        // The keyboard offers what the computer copied.
        Status.lastPcClipboard = payload.text
        Status.pcClipboardHistory = (listOf(payload.text) + Status.pcClipboardHistory)
            .distinct()
            .take(8)

        Status.lastSyncedText = payload.text
        Status.lastSyncedTime = System.currentTimeMillis()
        Status.lastDirection = "pc_to_phone"
        settings.addHistory(
            com.aplusconnect.companion.data.ClipHistoryEntry(
                id = UUID.randomUUID().toString(),
                text = payload.text,
                direction = "pc_to_phone",
                timestamp = System.currentTimeMillis()
            )
        )

        Handler(Looper.getMainLooper()).post {
            try {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("A+ Connect", payload.text)
                cm.setPrimaryClip(clip)
                Log.i(TAG, "applied clipboard from PC (${payload.text.length} chars)")
            } catch (e: Exception) {
                Log.w(TAG, "failed to apply incoming clipboard: ${e.message}")
            }
        }
    }

    private fun scheduleReconnect() {
        // A deliberate hang-up is not a drop: the loop stays off until the
        // user taps Reconnect.
        if (hungUpByComputer) return
        if (reconnectRunnable != null) return
        val now = System.currentTimeMillis()
        if (now < reconnectAt) return
        // Fast for a dropped link, slow for a computer that is switched off.
        val delay = reconnectPolicy.delayMs()
        reconnectAt = now + delay

        val runnable = Runnable {
            reconnectRunnable = null
            connect()
        }
        reconnectRunnable = runnable
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(runnable, delay)
    }

    /** Sends the clipboard to the computer, or queues it if not connected yet. */
    private fun send(text: String) {
        val payload = ClipboardPayloads.build(text)
        if (payload.text.isEmpty()) return

        // The same content is only sent once. Without this, the write the
        // phone makes when the computer sends something comes straight back.
        if (payload.hash == lastSentHash) return

        val c = client
        if (c == null || !c.isReady) {
            synchronized(stateLock) { pendingPayload = payload }
            Log.i(TAG, "connection not ready; queued clipboard (${payload.text.length} chars)")
            return
        }

        // The write happens outside the lock: it can block, and holding the
        // lock would stall the rest of the service.
        val sent = try {
            c.sendClipboard(payload.text)
        } catch (e: Exception) {
            Log.w(TAG, "could not send the clipboard: ${e.message}")
            false
        }

        synchronized(stateLock) {
            if (sent) {
                lastSentHash = payload.hash
                pendingPayload = null
            } else {
                pendingPayload = payload
            }
        }

        if (sent) recordHistory(payload)
    }

    private fun recordHistory(payload: ClipboardPayload) {
        Status.lastSyncedText = payload.text
        Status.lastSyncedTime = System.currentTimeMillis()
        Status.lastDirection = "phone_to_pc"
        settings.addHistory(
            com.aplusconnect.companion.data.ClipHistoryEntry(
                id = UUID.randomUUID().toString(),
                text = payload.text,
                direction = "phone_to_pc",
                timestamp = System.currentTimeMillis()
            )
        )
    }

    private fun flushPending() {
        val pending = synchronized(stateLock) { pendingPayload } ?: return
        val c = client
        if (c == null || !c.isReady) return

        if (pending.hash == lastSentHash) {
            synchronized(stateLock) { pendingPayload = null }
            return
        }

        val sent = try {
            c.sendClipboard(pending.text)
        } catch (e: Exception) {
            Log.w(TAG, "could not flush the queued clipboard: ${e.message}")
            false
        }

        if (sent) {
            Log.i(TAG, "flushed queued clipboard (${pending.text.length} chars)")
            synchronized(stateLock) {
                lastSentHash = pending.hash
                pendingPayload = null
            }
        }
    }

    /** Forwards one notification, or queues it until the connection is up. */
    private fun sendNotification(id: String, payload: JSONObject) {
        Status.lastNotification = payload.optString("title")
        val c = client
        if (c == null || !c.isReady) {
            queueNotification(id, payload)
            Log.i(TAG, "connection not ready; queued notification $id")
            return
        }
        if (!c.sendMessage(Protocol.TYPE_NOTIFICATION, payload)) {
            queueNotification(id, payload)
        }
    }

    /** Says a notification has gone away, or queues that too. */
    private fun sendNotificationRemoved(id: String) {
        val payload = FeaturePayloads.notificationRemoved(id)
        val c = client
        if (c == null || !c.isReady) {
            queueNotification(id, payload)
            return
        }
        if (!c.sendMessage(Protocol.TYPE_NOTIFICATION_REMOVED, payload)) {
            queueNotification(id, payload)
        }
    }

    /**
     * Adds a notification to the queue, replacing any copy of it that is
     * already waiting: a notification that is updated twice while the computer
     * is offline must arrive once, with its latest text.
     */
    private fun queueNotification(id: String, payload: JSONObject) {
        synchronized(stateLock) {
            notificationQueue.removeAll { it.first == id }
            notificationQueue.addLast(id to payload)
        }
    }

    /** Sends the now-playing state, or drops it when the session is down. */
    private fun sendMediaState(payload: JSONObject): Boolean {
        val c = client
        if (c == null || !c.isReady) return false
        return c.sendMessage(Protocol.TYPE_MEDIA_STATE, payload)
    }

    /**
     * Publishes the phone's vitals: battery, OS version, storage, model.
     *
     * Sent right after pairing and then every 60 s, so the desktop's device
     * cards stay current.
     */
    private fun publishDeviceInfo() {
        val c = client
        if (c == null || !c.isReady) return

        try {
            val batteryManager = getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
            val batteryLevel = batteryManager?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0
            val isCharging = batteryManager?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_STATUS) == android.os.BatteryManager.BATTERY_STATUS_CHARGING

            val androidVersion = Build.VERSION.RELEASE ?: "Unknown"
            val colorOsVersion = colorOsVersion()
            val model = Build.MODEL ?: "Unknown"

            val storagePath = android.os.Environment.getExternalStorageDirectory()
            val stat = if (storagePath != null) android.os.StatFs(storagePath.path) else null
            val blockSize = stat?.blockSizeLong ?: 0L
            val availableBlocks = stat?.availableBlocksLong ?: 0L
            val totalBlocks = stat?.blockCountLong ?: 0L
            val freeGb = if (blockSize > 0) (availableBlocks * blockSize) / (1024 * 1024 * 1024) else 0L
            val totalGb = if (blockSize > 0) (totalBlocks * blockSize) / (1024 * 1024 * 1024) else 0L

            val payload = FeaturePayloads.deviceInfo(
                batteryLevel = batteryLevel,
                isCharging = isCharging,
                androidVersion = androidVersion,
                colorOsVersion = colorOsVersion,
                storageFree = if (freeGb > 0) "$freeGb GB" else "Unknown",
                storageTotal = if (totalGb > 0) "$totalGb GB" else "Unknown",
                model = model,
            )

            c.sendMessage(Protocol.TYPE_DEVICE_INFO, payload)
        } catch (e: Exception) {
            Log.w(TAG, "could not publish device info: ${e.message}")
        }
    }

    /** Starts the device-info timer: publishes vitals every 60 s. */
    private fun startDeviceInfoTimer() {
        stopDeviceInfoTimer()
        deviceInfoTimer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "companion-device-info").apply { isDaemon = true }
        }
        deviceInfoTimer?.scheduleAtFixedRate(
            { publishDeviceInfo() },
            60L,
            60L,
            java.util.concurrent.TimeUnit.SECONDS
        )
    }

    /** Stops the device-info timer. */
    private fun stopDeviceInfoTimer() {
        deviceInfoTimer?.shutdownNow()
        deviceInfoTimer = null
    }

    /**
     * The ColorOS version, the way the OPPO build reports it ("V16.1.0").
     *
     * It lives in the system property `ro.build.version.oplusrom`, which has no
     * public API: reflection is the only way an ordinary app can read it, and a
     * device that blocks hidden APIs simply throws. When that happens the build
     * id still carries the major version - `CPH2737_16.0.10.601(EX01)` - so we
     * read that instead of showing a bare "ColorOS".
     */
    private fun colorOsVersion(): String {
        try {
            val cls = Class.forName("android.os.SystemProperties")
            val get = cls.getMethod("get", String::class.java, String::class.java)
            val value = get.invoke(null, "ro.build.version.oplusrom", "") as? String
            if (!value.isNullOrBlank()) return value
        } catch (_: Throwable) {
            // Hidden API blocked, or the class moved. The fallback below is
            // still honest, so this is not worth a log every minute.
        }

        val match = Regex("_(\\d+)\\.(\\d+)").find(Build.DISPLAY ?: "")
        if (match != null) return "ColorOS ${match.groupValues[1]}"
        return "ColorOS"
    }

    /**
     * Answers the computer's request for one folder.
     *
     * The computer asks with a relative path this phone handed out before, or
     * the empty string for the storage root. It always gets an answer: a
     * folder that cannot be read, or no access at all, answers with an empty
     * list rather than leaving the computer waiting.
     */
    private fun answerFileList(path: String) {
        val c = client
        if (c == null || !c.isReady) return

        val entries = fileBrowser.list(path)
        val safeEntries = entries ?: run {
            Log.i(TAG, "nothing to list: no access, or the folder cannot be read")
            emptyList()
        }

        if (!c.sendMessage(Protocol.TYPE_FILE_LIST, FeaturePayloads.fileList(path, safeEntries))) {
            Log.w(TAG, "could not send the file list")
        }
    }

    /**
     * Answers a search with names matching the query.
     *
     * The answer is an ordinary file list whose path marks it as a search, so
     * the computer resolves it with the same map it uses for folders. An empty
     * list is the honest answer for "nothing matches" and for "no access yet".
     *
     * [fresh] comes from the computer's Refresh button: the user has asked to
     * see what is on the phone now, so the storage is walked again before the
     * answer instead of being filtered from remembered names. A keystroke never
     * sets it, because a keystroke must not pay for a walk.
     */
    private fun answerFileSearch(query: String, fresh: Boolean) {
        val c = client
        if (c == null || !c.isReady) return

        val entries = if (query.isBlank() || !fileBrowser.isGranted()) {
            emptyList()
        } else {
            val search = searcher()
            if (fresh) search?.refreshNow()
            search?.results(query)?.map { hit ->
                PhoneFileBrowser.row(
                    id = hit.id,
                    name = hit.name,
                    size = hit.size,
                    mime = if (hit.isDir) "" else PhoneFileBrowser.mimeOf(hit.name),
                    isDir = hit.isDir,
                )
            } ?: emptyList()
        }

        val key = Protocol.searchKey(query)
        if (!c.sendMessage(Protocol.TYPE_FILE_LIST, FeaturePayloads.fileList(key, entries))) {
            Log.w(TAG, "could not send the search results")
        }
    }

    /**
     * The one search index, built on first use.
     *
     * Both the warm-up when the computer connects and the searches themselves
     * come through here, so the index is created exactly once. Null when this
     * phone has no shared storage to walk.
     */
    private fun searcher(): StorageSearch? = searcher ?: run {
        val root = fileBrowser.root() ?: return null
        StorageSearch(root).also { fresh ->
            fresh.onRebuild = { count -> Log.i(TAG, "indexed $count entries for search") }
            searcher = fresh
        }
    }

    /**
     * Starts watching the media database, so a file another app writes is
     * findable without the user pressing Refresh.
     *
     * MediaStore is the phone's own record of what is on its storage: a
     * download, a screenshot, a photo or a document saved by an editor all
     * land there. Watching it costs nothing while nothing changes, and the
     * rebuild it triggers is the same one [StorageSearch.invalidate] already
     * provides.
     */
    private fun watchMediaDatabase() {
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            android.provider.MediaStore.Files.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL)
        } else {
            android.net.Uri.parse("content://media/external/file")
        }
        try {
            contentResolver.registerContentObserver(uri, true, mediaObserver)
            Log.i(TAG, "watching the media database for new files")
        } catch (e: Exception) {
            // Without the observer search simply behaves as it did before:
            // the user presses Refresh. Not worth failing the service over.
            Log.w(TAG, "could not watch the media database: ${e.message}")
        }
    }

    // ---- screen mirroring ---------------------------------------------------

    /**
     * The computer asked to see the screen.
     *
     * Nothing captures yet: Android insists the user allows each session, and
     * that dialog can only be raised by an Activity. In the foreground it opens
     * at once; in the background the platform forbids it, so the phone posts a
     * notification that does exactly the same thing when tapped - which is the
     * only reliable route there is.
     */
    private fun handleMirrorStart(payload: JSONObject) {
        val request = FeaturePayloads.parseMirrorRequest(payload)
        if (request == null) {
            sendMirrorFailed("no-encoder")
            return
        }
        if (screenMirror != null) {
            sendMirrorFailed("already-mirroring")
            return
        }

        mirrorRequest = request
        Status.state = "Waiting for screen-share permission"
        askForScreenCapture()
    }

    /** Puts the permission request in front of the user, by both routes. */
    private fun askForScreenCapture() {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(MIRROR_NOTIFICATION_ID, buildMirrorRequestNotification())

        // Works when the app is in the foreground; silently does nothing when
        // the platform blocks a background activity start, and then the
        // notification is the path the user takes.
        try {
            startActivity(
                Intent(this, com.aplusconnect.companion.MainActivity::class.java)
                    .setAction(ACTION_MIRROR)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            Log.i(TAG, "could not raise the app for the projection dialog: ${e.message}")
        }
    }

    /**
     * The user allowed (or refused) the capture.
     *
     * The foreground service must already be running with the projection type
     * before the system hands out a projection; getting that order wrong throws
     * on Android 14 and later.
     */
    private fun beginMirroring(resultCode: Int, data: Intent) {
        val request = mirrorRequest
        if (request == null) return

        val manager = getSystemService(android.media.projection.MediaProjectionManager::class.java)
        raiseForegroundWith(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val granted = try {
            manager?.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            Log.w(TAG, "the projection was not granted: ${e.message}")
            null
        }
        if (granted == null) {
            mirrorRequest = null
            sendMirrorFailed("consent-refused")
            lowerForeground()
            return
        }

        lateinit var socket: VideoClient
        socket = VideoClient(
            host = settings.host,
            port = request.videoPort,
            deviceId = settings.deviceId,
            deviceName = resolvedDeviceName(),
            pairing = settings.pairing,
            sharedSecret = settings.sharedSecret,
            onClosed = { reason ->
                // The picture has nowhere to go: stop capturing rather than
                // encode for nobody.
                Log.i(TAG, "the video socket closed: $reason")
                if (videoSocket === socket) stopMirror("link-lost", tellComputer = false)
            },
        )
        socket.connect()

        if (!socket.awaitReady(VIDEO_READY_TIMEOUT_MS)) {
            socket.close()
            videoSocket = null
            mirrorRequest = null
            sendMirrorFailed("video-connect-failed")
            lowerForeground()
            return
        }

        lateinit var capturing: ScreenMirror
        capturing = ScreenMirror(
            context = this,
            onFrame = { payload, keyframe, pts ->
                socket.sendFrame(VideoFrameHeader.encode(keyframe, pts, payload), keyframe)
            },
            onStopped = { reason ->
                // The system ends the projection for its own reasons: the user
                // tapped the status-bar chip, or the screen locked.
                if (screenMirror === capturing) stopMirror(reason, tellComputer = true)
            },
        )

        val started = capturing.start(
            granted = granted,
            fps = request.fps,
            bitrate = request.bitrate,
            keyframeSeconds = request.keyframeSeconds,
            maxLongSide = request.maxLongSide,
        )
        if (!started) {
            socket.close()
            videoSocket = null
            mirrorRequest = null
            sendMirrorFailed("no-encoder")
            lowerForeground()
            return
        }

        videoSocket = socket
        screenMirror = capturing

        val manager2 = getSystemService(NotificationManager::class.java)
        manager2?.cancel(MIRROR_NOTIFICATION_ID)
        raiseForegroundWith(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            "Mirroring the screen",
            withStop = true,
        )
        registerScreenOff()
        Status.mirroring = true
        Status.state = "Mirroring the screen"

        sendThread.execute {
            client?.sendMessage(
                Protocol.TYPE_MIRROR_STARTED,
                FeaturePayloads.mirrorStarted(capturing.width, capturing.height, capturing.encoderName),
            )
        }
        Log.i(TAG, "mirroring started: ${capturing.width}x${capturing.height}")
    }

    /** The user said no, or dismissed the dialog. */
    private fun mirrorRefused() {
        mirrorRequest = null
        getSystemService(NotificationManager::class.java)?.cancel(MIRROR_NOTIFICATION_ID)
        sendMirrorFailed("consent-refused")
        lowerForeground()
    }

    /**
     * Ends mirroring, from any of the eight triggers.
     *
     * Idempotent and serialized. The triggers really do run on different
     * threads - the mirror thread, the video socket's read thread, the
     * projection callback, the main thread for a screen-off broadcast - and
     * without this lock two of them can both find `Status.mirroring` true and
     * tear down twice, double-lowering the foreground service and sending the
     * desktop two stop messages.
     */
    @Synchronized
    private fun stopMirror(reason: String, tellComputer: Boolean) {
        val capturing = screenMirror
        val socket = videoSocket
        val wasMirroring = Status.mirroring
        if (!wasMirroring && capturing == null && socket == null) return

        // State is cleared first, so a callback fired by any release below
        // finds nothing to re-enter.
        screenMirror = null
        videoSocket = null
        mirrorRequest = null
        Status.mirroring = false

        // Queue the reason before the video socket is touched. The desktop
        // learns *why* on the control channel; closing the picture first makes
        // it report a link loss for a stop the user asked for.
        if (tellComputer && wasMirroring) sendMirrorStopped(reason)

        capturing?.stop(reason)
        socket?.close()
        unregisterScreenOff()

        getSystemService(NotificationManager::class.java)?.cancel(MIRROR_NOTIFICATION_ID)

        if (wasMirroring) {
            Status.state = "Connected to ${settings.host}"
            lowerForeground()
            Log.i(TAG, "mirroring stopped: $reason")
        }
    }

    private fun sendMirrorFailed(reason: String) {
        sendThread.execute {
            client?.sendMessage(Protocol.TYPE_MIRROR_FAILED, FeaturePayloads.mirrorFailed(reason))
        }
    }

    private fun sendMirrorStopped(reason: String) {
        sendThread.execute {
            client?.sendMessage(Protocol.TYPE_MIRROR_STOPPED, FeaturePayloads.mirrorStopped(reason))
        }
    }

    /**
     * Raises the foreground notification with a given service type.
     *
     * Android 14 and later refuse a projection unless the service is already
     * running with the media projection type, so this is called *before*
     * `getMediaProjection`, not after.
     */
    private fun raiseForegroundWith(
        type: Int,
        text: String = "Connected to ${settings.host}",
        withStop: Boolean = false,
    ) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, buildNotification(text, withStop), type)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification(text, withStop))
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not raise the notification: ${e.message}")
        }
    }

    /** Back to the ordinary connected-only service. */
    private fun lowerForeground() {
        raiseForegroundWith(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }

    private fun registerScreenOff() {
        try {
            registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        } catch (e: Exception) {
            Log.w(TAG, "could not watch for the screen going off: ${e.message}")
        }
    }

    private fun unregisterScreenOff() {
        try {
            unregisterReceiver(screenOffReceiver)
        } catch (e: Exception) {
            // Not registered.
        }
    }

    /** The notification that asks for permission, when the app is not in front. */
    private fun buildMirrorRequestNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            3,
            Intent(this, com.aplusconnect.companion.MainActivity::class.java)
                .setAction(ACTION_MIRROR)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen sharing requested")
            .setContentText("Tap to let ${settings.host} see this screen")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
    }

    private fun resolvedDeviceName(): String =
        settings.deviceName.ifEmpty { android.os.Build.MODEL }

    /**
     * Stops everything, because the user switched this phone off.
     *
     * The mirror goes with it: a dormant phone must not be capturing anyone's
     * screen, and a projection nobody is watching is a privacy problem, not
     * a background service.
     */
    private fun goDormant() {
        stopMirror("requested", tellComputer = false)

        Status.isConnected = false
        Status.mirroring = false
        Status.state = "Off"

        // The socket is closed off the main thread. Closing one there throws
        // NetworkOnMainThreadException, which would leave the connection half
        // open - the desktop would see the phone linger until the garbage
        // collector got round to it.
        val activeClient = client
        client = null
        sendThread.execute {
            try {
                activeClient?.close()
            } catch (e: Exception) {
                Log.w(TAG, "closing the connection failed: ${e.message}")
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    /**
     * Answers the computer's request for a file's SHA256.
     *
     * The desktop checks its preview cache against this before downloading, so
     * a file the user looked at before opens instantly instead of being pulled
     * again. A file that cannot be read answers with the reason.
     */
    private fun answerFileHash(path: String, requestId: String) {
        val file = fileBrowser.resolve(path)
        if (file == null || !file.isFile) {
            sendThread.execute {
                client?.sendFileHashError(path, "that file is not on the phone any more", requestId)
            }
            return
        }

        val digest = FileHash.sha256Of(file)
        if (digest == null) {
            Log.w(TAG, "could not hash ${file.name}")
            sendThread.execute {
                client?.sendFileHashError(path, "the phone could not read that file", requestId)
            }
            return
        }

        sendThread.execute {
            client?.sendFileHash(path, digest, file.length(), requestId)
        }
    }

    private fun answerFileSend(path: String, requestId: String) {
        // The transfer carries the request's own id, so the desktop can tie
        // the file that arrives to the click that asked for it.
        val transferId = requestId.ifEmpty { "" }

        val file = fileBrowser.resolve(path)
        if (file == null || !file.isFile) {
            Log.w(TAG, "the computer asked for a file we cannot read: $path")
            val name = path.substringAfterLast('/').ifEmpty { "the file" }
            sendThread.execute {
                client?.sendFileError(transferId, name, "that file is not on the phone any more")
            }
            return
        }

        sendFile(
            this,
            file,
            PhoneFileBrowser.mimeOf(file.name),
            transferId = transferId,
            onProgress = {},
            onComplete = { ok, error ->
                if (ok) {
                    Log.i(TAG, "sent the requested file to the computer")
                } else {
                    Log.w(TAG, "could not send the requested file: $error")
                    sendThread.execute {
                        client?.sendFileError(transferId, file.name, error ?: "the phone could not send it")
                    }
                }
            }
        )
    }

    /** Sends the queued notifications, oldest first, stopping on the first failure. */
    private fun flushNotifications() {
        val c = client
        if (c == null || !c.isReady) return
        while (true) {
            val next = synchronized(stateLock) {
                if (notificationQueue.isEmpty()) null else notificationQueue.removeFirst()
            } ?: return

            val type = next.second.optString("type", Protocol.TYPE_NOTIFICATION)
            if (!c.sendMessage(type, next.second)) {
                synchronized(stateLock) { notificationQueue.addFirst(next) }
                return
            }
        }
    }

    private fun buildNotification(text: String, withStop: Boolean = false): Notification {
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("A+ Connect")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_aplus_clipboard),
                    "Copy to PC",
                    copyActionIntent(),
                ).build()
            )

        // While the screen is shared, the notification says so and stops it.
        // Android requires a notification for the projection service anyway;
        // this is that notification, doing something useful.
        if (withStop) {
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Stop sharing",
                    stopMirrorActionIntent(),
                ).build()
            )
        }
        return builder.build()
    }

    /**
     * The "Stop sharing" action.
     *
     * A service intent rather than an activity: stopping must not need a window,
     * and it must work while the phone is locked or the app is in the
     * background.
     */
    private fun stopMirrorActionIntent(): PendingIntent {
        val intent = Intent(this, CompanionService::class.java).setAction(ACTION_STOP_MIRROR)
        return PendingIntent.getService(
            this,
            2,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /**
     * The "Copy to PC" action.
     *
     * Tapping it raises the transparent reader, which takes focus for a moment
     * and can therefore read the clipboard. This is the path that needs no
     * accessibility service - and an accessibility service is what makes
     * Android block contactless payments.
     */
    private fun copyActionIntent(): PendingIntent {
        val intent = Intent(this, ClipboardReadActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        return PendingIntent.getActivity(
            this,
            1,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(text, withStop = screenMirror != null))
    }

    /**
     * Whether the app is the default keyboard.
     *
     * That role is what lets it read the clipboard in the background, so it
     * is also what the computer is told - otherwise the desktop looks
     * connected while nothing can sync.
     */
    /**
     * Whether the phone's clipboard can actually reach the computer.
     *
     * Only the default keyboard may read the clipboard in the background
     * (ClipboardService.isDefaultIme reads
     * Settings.Secure.DEFAULT_INPUT_METHOD), so the clipboard can sync only
     * while the A+ Connect keyboard is the default one. The computer is told
     * this, so it can say why instead of looking broken.
     */
    private fun isDefaultKeyboard(): Boolean {
        val current = android.provider.Settings.Secure.getString(
            contentResolver,
            android.provider.Settings.Secure.DEFAULT_INPUT_METHOD,
        ) ?: return false
        return KeyboardBridge.PACKAGES.any { current.startsWith(it) }
    }

    companion object {
        const val TAG = "AplusConnect"
        private const val CHANNEL_ID = "aplus_connect"
        private const val NOTIFICATION_ID = 7
        const val EXTRA_CLIPBOARD = "clipboard"
        const val ACTION_CONNECT = "com.aplusconnect.companion.CONNECT"

        /** The notification says the computer wants to see this screen. */
        const val ACTION_MIRROR = "com.aplusconnect.companion.MIRROR"

        /** The "Stop sharing" action on the foreground notification. */
        const val ACTION_STOP_MIRROR = "com.aplusconnect.companion.STOP_MIRROR"

        /** Separate from the service notification, so it can be cancelled alone. */
        private const val MIRROR_NOTIFICATION_ID = 8

        /** How long to wait for the video socket's handshake before giving up. */
        private const val VIDEO_READY_TIMEOUT_MS = 6000L

        /**
         * The user granted capture. Called by MainActivity, which is the only
         * component that can raise the platform's dialog.
         */
        fun onProjectionGranted(context: Context, resultCode: Int, data: Intent) {
            val running = instance ?: return
            running.mirrorThread.execute { running.beginMirroring(resultCode, data) }
        }

        /** The user refused, or dismissed the dialog. */
        fun onProjectionRefused(context: Context) {
            val running = instance ?: return
            running.mirrorThread.execute { running.mirrorRefused() }
        }

        /** Whether a capture is waiting for the user to allow it. */
        fun hasPendingMirrorRequest(): Boolean = instance?.mirrorRequest != null
        private const val RECONNECT_DELAY_MS = 5000L

        /**
         * How long the media database must stay quiet before the index is
         * rebuilt. A download posts a change per chunk; walking 34,000 entries
         * after each one would cost more than the download.
         */
        private const val MEDIA_SETTLE_MS = 1500L

        private var instance: CompanionService? = null
        private var lastSentHash: String? = null

        /** Notifications that arrived before the service was up. */
        private val earlyNotifications = ArrayDeque<Pair<String, JSONObject>>()

        /**
         * Hands one notification to the service, starting it if it is not
         * running yet so that the queue has somewhere to live and flush from.
         */
        fun deliverNotification(context: Context, id: String, payload: JSONObject) {
            val running = instance
            if (running != null) {
                // onNotificationPosted is delivered on the main thread, and a
                // socket write there throws NetworkOnMainThreadException and
                // closes the session - the same trap the clipboard hit. Every
                // write goes through the send thread.
                running.sendThread.execute { running.sendNotification(id, payload) }
                return
            }
            synchronized(earlyNotifications) {
                earlyNotifications.removeAll { it.first == id }
                earlyNotifications.addLast(id to payload)
            }
            start(context)
        }

        /** Hands a notification removal to the service. */
        fun deliverNotificationRemoved(context: Context, id: String) {
            val running = instance
            if (running != null) {
                running.sendThread.execute { running.sendNotificationRemoved(id) }
                return
            }
            start(context)
        }

        /** Starts the service, so it connects to the computer. */
    /**
     * The kill switch, on the phone.
     *
     * Off means dormant: the service stops, the connection is closed, and
     * nothing is forwarded. The choice is remembered, so the phone stays off
     * until the user turns it back on - across a restart too.
     */
    fun setEnabled(context: Context, enabled: Boolean) {
        val settings = CompanionSettings(context)
        settings.enabled = enabled
        if (enabled) {
            Log.i(TAG, "switched on")
            start(context)
        } else {
            Log.i(TAG, "switched off")
            instance?.goDormant()
        }
    }

    /** Whether this phone is allowed to talk to the computer at all. */
    fun isEnabled(context: Context): Boolean = CompanionSettings(context).enabled

        /**
         * Starts the service, unless the kill switch says this phone is off.
         *
         * A dormant phone does not run the service at all: no connection, no
         * foreground notification, nothing a user could mistake for "working".
         */
        fun start(context: Context) {
            if (!CompanionSettings(context).enabled) {
                Log.i(TAG, "not starting: the companion switch is off")
                return
            }
            val intent = Intent(context, CompanionService::class.java).apply {
                action = ACTION_CONNECT
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "could not start the service: ${e.message}")
            }
        }

        /**
         * Hands the clipboard to the service, starting it if it is not
         * running yet.
         */
        fun deliverClipboard(context: Context, text: String) {
            val running = instance
            if (running != null) {
                // The Send Text and Paste buttons call this from the UI thread,
                // and a socket write there throws NetworkOnMainThreadException
                // and drops the connection. Sends run on the send thread.
                running.sendThread.execute { running.send(text) }
                return
            }
            val intent = Intent(context, CompanionService::class.java)
                .putExtra(EXTRA_CLIPBOARD, text)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "could not start the service: ${e.message}")
            }
        }

        /** Manually re-establishes the connection to the computer. */
        fun reconnect(context: Context) {
            val running = instance
            if (running != null) {
                running.client?.close()
                running.client = null
                running.connect()
            } else {
                start(context)
            }
        }

        /** Disconnects and stops the background service. */
        fun stop(context: Context) {
            val running = instance
            if (running != null) {
                running.client?.close()
                running.client = null
                Status.isConnected = false
                Status.state = "Stopped"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    running.stopForeground(STOP_FOREGROUND_REMOVE)
                }
                running.stopSelf()
            }
        }

        /** Streams a chosen file to the PC's Downloads directory over TLS. */
        fun sendFile(
            context: Context,
            uri: android.net.Uri,
            onProgress: (Float) -> Unit,
            onComplete: (Boolean, String?) -> Unit
        ) {
            val cr = context.contentResolver
            var fileName = "file_${System.currentTimeMillis()}"
            var fileSize = 0L
            val mimeType = cr.getType(uri) ?: "application/octet-stream"

            try {
                cr.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIndex != -1) cursor.getString(nameIndex)?.let { fileName = it }
                        if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex)
                    }
                }
            } catch (e: Exception) {
                // A provider that cannot be queried still streams; only the
                // name and the size in the header are lost.
            }

            stream("file", fileName, fileSize, mimeType, { cr.openInputStream(uri) }, onProgress, onComplete)
        }

        /**
         * Streams a file from the phone's storage to the PC.
         *
         * Used by the file browser, where the file has a real path and no
         * content provider is involved.
         */
        fun sendFile(
            context: Context,
            file: java.io.File,
            mime: String,
            transferId: String = "",
            onProgress: (Float) -> Unit,
            onComplete: (Boolean, String?) -> Unit
        ) {
            stream(
                "file",
                file.name,
                file.length(),
                mime.ifEmpty { "application/octet-stream" },
                { java.io.FileInputStream(file) },
                onProgress,
                onComplete,
                transferId,
            )
        }

        /** The one place a file becomes file-start/chunk/done on the wire. */
        private fun stream(
            idPrefix: String,
            fileName: String,
            fileSize: Long,
            mimeType: String,
            open: () -> java.io.InputStream?,
            onProgress: (Float) -> Unit,
            onComplete: (Boolean, String?) -> Unit,
            transferIdOverride: String = "",
        ) {
            val active = instance?.client
            if (active == null || !active.isReady) {
                onComplete(false, "Not connected to PC")
                return
            }

            Thread {
                try {
                    val inputStream = open() ?: throw Exception("Could not open file")
                    val transferId = transferIdOverride.ifEmpty {
                        "${idPrefix}_${UUID.randomUUID().toString().take(8)}"
                    }

                    if (!active.sendFileStart(transferId, fileName, fileSize, mimeType)) {
                        inputStream.close()
                        onComplete(false, "PC rejected file transfer")
                        return@Thread
                    }

                    val buffer = ByteArray(128 * 1024)
                    var bytesRead: Int
                    var totalSent = 0L
                    var chunkIndex = 0

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        val chunkBase64 = android.util.Base64.encodeToString(
                            buffer,
                            0,
                            bytesRead,
                            android.util.Base64.NO_WRAP
                        )
                        if (!active.sendFileChunk(transferId, chunkIndex++, chunkBase64)) {
                            onComplete(false, "Connection broke while sending")
                            return@Thread
                        }
                        totalSent += bytesRead
                        val progress = if (fileSize > 0) totalSent.toFloat() / fileSize else 0f
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            onProgress(progress)
                        }
                    }
                    inputStream.close()

                    active.sendFileDone(transferId)
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        onProgress(1f)
                        onComplete(true, null)
                    }
                } catch (e: Exception) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        onComplete(false, e.message)
                    }
                }
            }.start()
        }
    }
}
