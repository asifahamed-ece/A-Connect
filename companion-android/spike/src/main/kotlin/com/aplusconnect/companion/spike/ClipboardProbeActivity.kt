package com.aplusconnect.companion.spike

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * Spike only: starts the probe service and shows what it reports.
 *
 * It never writes to the clipboard itself - the service only fills an empty
 * clipboard, so opening this screen cannot destroy what the user copied.
 */
class ClipboardProbeActivity : ComponentActivity() {

    private val notificationPermissionRequest = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val status = TextView(this).apply {
            text = "probe running - read results are in logcat (tag AplusProbe)"
        }
        val start = Button(this).apply { text = "START PROBE" }
        start.setOnClickListener { startProbe() }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(status)
                addView(start)
            }
        )
    }

    private fun startProbe() {
        // Without a visible notification the foreground service is not
        // "visible" to the system, so ask for the permission first.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                notificationPermissionRequest
            )
            return
        }

        val intent = Intent(this, ProbeRelayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != notificationPermissionRequest) return

        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startProbe()
        } else {
            android.util.Log.i(ProbeRelayService.TAG, "notifications denied, probe not started")
        }
    }
}
