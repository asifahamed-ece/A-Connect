package com.aplusconnect.companion.spike

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.security.MessageDigest

/**
 * Spike only: measures whether the app can read the clipboard in the
 * background, and proves it by writing a value of its own and reading it
 * back.
 *
 * It never overwrites a clipboard that already has content: the probe only
 * fills an empty one, so it cannot destroy what the user just copied.
 */
class ProbeRelayService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var cycleCount = 0

    private val poller = object : Runnable {
        override fun run() {
            cycle()
            handler.postDelayed(this, CYCLE_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "A+ Connect probe", NotificationManager.IMPORTANCE_LOW)
        )

        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("A+ Connect probe")
            .setContentText("Measuring clipboard access")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        Log.i(TAG, "probe foreground service started")

        handler.postDelayed(poller, CYCLE_INTERVAL_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /**
     * One measurement cycle: write a value that is unique to this cycle, read
     * the clipboard back, and compare. If the read returns what we just wrote,
     * clipboard access is granted; if it returns nothing, access is refused.
     * The clipboard being empty and the read being refused look identical from
     * the outside, so the app supplies its own ground truth.
     */
    private fun cycle() {
        cycleCount++
        val current = readClipboard()

        // Only ever fill an empty clipboard, so the probe cannot destroy what
        // the user copied.
        if (current == null) {
            val value = "aplus-cycle-$cycleCount"
            val written = writeValue(value)
            handler.postDelayed({ readAndCompare(value, written) }, 800)
            return
        }

        Log.i(TAG, "cycle $cycleCount: clipboard already has content, left alone")
    }

    /** The clipboard content, or null when there is none or it cannot be read. */
    private fun readClipboard(): String? = try {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = manager.primaryClip
        if (clip == null || clip.itemCount == 0) {
            null
        } else {
            clip.getItemAt(0).coerceToText(this).toString()
        }
    } catch (e: Exception) {
        null
    }

    private fun writeValue(value: String): Boolean = try {
        val manager = getSystemService(ClipboardManager::class.java)
        manager.setPrimaryClip(android.content.ClipData.newPlainText("aplus", value))
        true
    } catch (e: Exception) {
        Log.w(TAG, "write failed: ${e.message}")
        false
    }

    private fun readAndCompare(expected: String, written: Boolean) {
        val text = readClipboard()
        val result = when {
            text == null -> "NOTHING"
            text == expected -> "MATCH"
            else -> "OTHER len=${text.length}"
        }
        Log.i(TAG, "cycle $cycleCount: wrote=$written read=$result expected=${expected.take(20)}")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val TAG = "AplusProbe"
        const val CHANNEL_ID = "aplus_probe"
        const val NOTIFICATION_ID = 41
        private const val CYCLE_INTERVAL_MS = 5000L

        fun sha256(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
