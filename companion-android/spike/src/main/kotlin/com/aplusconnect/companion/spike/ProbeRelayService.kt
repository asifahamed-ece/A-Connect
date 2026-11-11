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
import android.os.IBinder
import android.util.Log

/**
 * Spike only: proves that a clipboard *change* is signalled to a background
 * app, and that a transparent activity can then read the clipboard by taking
 * focus.
 *
 * It never writes to the clipboard, so it cannot destroy what the user
 * copied.
 */
class ProbeRelayService : Service() {

    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        Log.i(TAG, "clipboard change signalled to the background app")
        // The content is not readable yet; a focused window can read it.
        startActivity(
            Intent(this, ClipboardReadActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
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

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.addPrimaryClipChangedListener(listener)
        Log.i(TAG, "clipboard change listener registered")
    }

    override fun onDestroy() {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.removePrimaryClipChangedListener(listener)
        } catch (e: Exception) {
            Log.w(TAG, "could not remove the listener: ${e.message}")
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val TAG = "AplusProbe"
        const val CHANNEL_ID = "aplus_probe"
        const val NOTIFICATION_ID = 41

        /** SHA-256 of the value, truncated for the log. Never the content. */
        fun sha256(value: String): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
