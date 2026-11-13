package com.aplusconnect.companion.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.graphics.drawable.Icon
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import com.aplusconnect.companion.file.PhoneFileBrowser
import com.aplusconnect.companion.file.StorageSearch
import com.aplusconnect.companion.net.CompanionClient
import com.aplusconnect.companion.net.DownloadDestination
import com.aplusconnect.companion.net.IncomingFile
import com.aplusconnect.companion.protocol.ClipboardPayload
import com.aplusconnect.companion.protocol.ClipboardPayloads
import com.aplusconnect.companion.protocol.FeaturePayloads
import com.aplusconnect.companion.protocol.Protocol
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
    }

    private lateinit var settings: CompanionSettings
    private lateinit var incomingFile: IncomingFile
    private lateinit var destination: DownloadDestination
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

    override fun onCreate() {
        super.onCreate()
        settings = CompanionSettings(this)
        incomingFile = IncomingFile(java.io.File(cacheDir, "incoming"))
        destination = DownloadDestination(this)
        fileBrowser = PhoneFileBrowser(this)
        instance = this

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
        startForeground(NOTIFICATION_ID, buildNotification("Connecting…"))

        connect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
        mediaBridge?.stop()
        mediaBridge = null
        val activeClient = client
        client = null
        activeClient?.close()
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
                    val saved = destination.save(
                        received.file,
                        received.name,
                        settings.downloadFolderUri,
                    )
                    received.file.delete()
                    if (saved != null) {
                        Status.lastReceivedFile = received.name
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
                    } else {
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
            onFileSendRequest = { path ->
                fileThread.execute { answerFileSend(path) }
            },
            onFileSearchRequest = { query, fresh ->
                searchThread.execute { answerFileSearch(query, fresh) }
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
        reconnectAt = now + RECONNECT_DELAY_MS

        val runnable = Runnable {
            reconnectRunnable = null
            connect()
        }
        reconnectRunnable = runnable
        android.os.Handler(mainLooper).postDelayed(runnable, RECONNECT_DELAY_MS)
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

        val entries = fileBrowser.list(path) ?: run {
            Log.i(TAG, "nothing to list: no access, or the folder cannot be read")
            emptyList()
        }

        if (!c.sendMessage(Protocol.TYPE_FILE_LIST, FeaturePayloads.fileList(path, entries))) {
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
     * Sends one file the computer asked for.
     *
     * The streaming is the existing phone-to-computer transfer, so both
     * directions keep using the same file-start/chunk/done messages and the
     * computer needs no new file code.
     */
    private fun answerFileSend(path: String) {
        val file = fileBrowser.resolve(path)
        if (file == null || !file.isFile) {
            Log.w(TAG, "the computer asked for a file outside the phone's storage")
            return
        }
        sendFile(
            this,
            file,
            PhoneFileBrowser.mimeOf(file.name),
            onProgress = {},
            onComplete = { ok, error ->
                if (ok) {
                    Log.i(TAG, "sent the requested file to the computer")
                } else {
                    Log.w(TAG, "could not send the requested file: $error")
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

    private fun buildNotification(text: String): Notification = Notification.Builder(this, CHANNEL_ID)        .setContentTitle("A+ Connect")
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
        .build()

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
        manager?.notify(NOTIFICATION_ID, buildNotification(text))
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
        private const val RECONNECT_DELAY_MS = 5000L

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
        fun start(context: Context) {
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
        ) {
            val active = instance?.client
            if (active == null || !active.isReady) {
                onComplete(false, "Not connected to PC")
                return
            }

            Thread {
                try {
                    val inputStream = open() ?: throw Exception("Could not open file")
                    val transferId = "${idPrefix}_${UUID.randomUUID().toString().take(8)}"

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
