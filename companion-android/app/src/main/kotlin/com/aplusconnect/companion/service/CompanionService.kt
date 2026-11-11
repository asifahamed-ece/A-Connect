package com.aplusconnect.companion.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.aplusconnect.companion.data.CompanionSettings
import com.aplusconnect.companion.net.CompanionClient
import com.aplusconnect.companion.protocol.ClipboardPayloads
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
    }

    private lateinit var settings: CompanionSettings
    private var client: CompanionClient? = null
    private var reconnectAt = 0L

    override fun onCreate() {
        super.onCreate()
        settings = CompanionSettings(this)
        instance = this

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "A+ Connect", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(NOTIFICATION_ID, notification("Connecting…"))

        connect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_CLIPBOARD)
        if (text != null) send(text)
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        client?.close()
        client = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun connect() {
        val host = settings.host
        if (host.isEmpty()) {
            Log.i(TAG, "no computer configured yet")
            Status.state = "No computer configured"
            return
        }

        Status.state = "Connecting to $host…"

        client?.close()
        client = CompanionClient(
            host = host,
            port = settings.port,
            deviceId = settings.deviceId.ifEmpty {
                UUID.randomUUID().toString().also { settings.deviceId = it }
            },
            deviceName = android.os.Build.MODEL,
            code = settings.pairingCode,
            pairing = settings.pairing,
            onPaired = { pcId, fingerprint ->
                if (pcId.isNotEmpty()) settings.remember(pcId, fingerprint)
            },
            onReady = {
                Log.i(TAG, "connected to the computer")
                Status.state = "Connected to ${settings.host}"
                notification("Connected to ${settings.host}")
            },
            onClosed = { reason ->
                Log.i(TAG, "disconnected: $reason")
                Status.state = "Disconnected · retrying"
                notification("Disconnected · retrying")
                scheduleReconnect()
            },
        )
        client?.connect()
    }

    private fun scheduleReconnect() {
        val now = System.currentTimeMillis()
        if (now < reconnectAt) return
        reconnectAt = now + RECONNECT_DELAY_MS
        android.os.Handler(mainLooper).postDelayed({ connect() }, RECONNECT_DELAY_MS)
    }

    /** Sends the clipboard to the computer, if the content actually changed. */
    private fun send(text: String) {
        val payload = ClipboardPayloads.build(text)
        if (payload.text.isEmpty()) return
        if (payload.hash == lastSentHash) return
        lastSentHash = payload.hash
        client?.sendClipboard(payload.text)
    }

    private fun notification(text: String): Notification = Notification.Builder(this, CHANNEL_ID)
        .setContentTitle("A+ Connect")
        .setContentText(text)
        .setSmallIcon(android.R.drawable.ic_menu_edit)
        .setOngoing(true)
        .build()

    companion object {
        const val TAG = "AplusConnect"
        private const val CHANNEL_ID = "aplus_connect"
        private const val NOTIFICATION_ID = 7
        private const val EXTRA_CLIPBOARD = "clipboard"
        private const val RECONNECT_DELAY_MS = 5000L

        private var instance: CompanionService? = null
        private var lastSentHash: String? = null

        /** Starts the service, so it connects to the computer. */
        fun start(context: Context) {
            val intent = Intent(context, CompanionService::class.java)
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
                running.send(text)
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
    }
}
