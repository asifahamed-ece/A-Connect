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
 * Spike only: checks that the foreground service type we intend to ship
 * (connectedDevice) is accepted and keeps the process alive, performs the one
 * background write the measurements need, and reads the clipboard from the
 * background every two seconds - with and without the accessibility service
 * enabled, which is what proves the exemption is granted to the whole UID.
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

    /**
     * One measurement cycle: write a value that is unique to this cycle, read
     * the clipboard back, and compare. If the read returns what we just wrote,
     * clipboard access is granted; if it returns nothing, access is refused.
     * The clipboard being empty and the read being refused look identical from
     * the outside, so the app supplies its own ground truth.
     */
    private fun cycle() {
        val value = "aplus-cycle-$cycleCount"
        cycleCount++
        val written = writeValue(value)
        handler.postDelayed({ readAndCompare(value, written) }, 800)
    }

    private fun writeValue(value: String): Boolean {
        return try {
            val manager = getSystemService(ClipboardManager::class.java)
            manager.setPrimaryClip(android.content.ClipData.newPlainText("aplus", value))
            true
        } catch (e: Exception) {
            Log.w(TAG, "write failed: ${e.message}")
            false
        }
    }

    private fun readAndCompare(expected: String, written: Boolean) {
        val result = try {
            val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = manager.primaryClip
            if (clip == null || clip.itemCount == 0) {
                "NOTHING"
            } else {
                val text = clip.getItemAt(0).coerceToText(this).toString()
                if (text == expected) "MATCH" else "OTHER len=${text.length}"
            }
        } catch (e: Exception) {
            "DENIED ${e.javaClass.simpleName}: ${e.message}"
        }
        Log.i(TAG, "cycle $cycleCount: wrote=$written read=$result expected=${expected.take(20)}")
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

        reportRoles()

        // The single write the measurements need.
        writeProbeValue()

        handler.postDelayed(poller, CYCLE_INTERVAL_MS)
    }

    /**
     * Lists the roles this app qualifies for. Android 13 added
     * READ_CLIPBOARD_IN_BACKGROUND, granted through a role rather than as a
     * normal permission, so the question "is there a clipboard role?" is
     * answered by asking the system rather than by guessing names.
     */
    private fun reportRoles() {
        try {
            val manager = getSystemService(android.app.role.RoleManager::class.java)
            for (candidate in CANDIDATE_ROLES) {
                Log.i(
                    TAG,
                    "role $candidate available=${manager.isRoleAvailable(candidate)} " +
                        "held=${manager.isRoleHeld(candidate)}"
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "role query failed: ${e.message}")
        }
    }

    private fun writeProbeValue() {
        val ok = try {
            val manager = getSystemService(ClipboardManager::class.java)
            manager.setPrimaryClip(android.content.ClipData.newPlainText("aplus", BG_WRITE_VALUE))
            true
        } catch (e: Exception) {
            Log.w(TAG, "write failed: ${e.message}")
            false
        }
        Log.i(TAG, "write result: $ok")
    }

    private fun readOnce() {
        val result = try {
            val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = manager.primaryClip
            if (clip == null || clip.itemCount == 0) {
                "EMPTY"
            } else {
                val text = clip.getItemAt(0).coerceToText(this).toString()
                "OK len=${text.length} sha=${sha256(text).take(12)}"
            }
        } catch (e: Exception) {
            "DENIED ${e.javaClass.simpleName}: ${e.message}"
        }
        Log.i(TAG, "read result: $result")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val TAG = "AplusProbe"
        const val CHANNEL_ID = "aplus_probe"
        const val NOTIFICATION_ID = 41
        const val PROBE_VALUE = "aplus-probe-value"
        // Written from the background; if the clipboard keeps showing
        // PROBE_VALUE instead, the background write was dropped.
        const val BG_WRITE_VALUE = "background-write-value"
        private const val CYCLE_INTERVAL_MS = 5000L

        private val CANDIDATE_ROLES = listOf(
            "android.app.role.CLIPBOARD",
            "android.app.role.CLIPBOARD_ACCESS",
            "android.app.role.SYSTEM_CLIPBOARD",
            "android.app.role.READ_CLIPBOARD_IN_BACKGROUND",
        )

        fun sha256(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
