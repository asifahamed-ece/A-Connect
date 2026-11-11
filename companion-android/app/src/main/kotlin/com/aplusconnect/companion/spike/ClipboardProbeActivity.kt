package com.aplusconnect.companion.spike

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * Spike only: shows what the service is doing and starts the probe service.
 */
class ClipboardProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Without a visible notification the foreground service does not make
        // the app "visible", and Android then refuses background clipboard
        // reads. The permission has to be requested, not assumed.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        // An app with focus may write the clipboard; a background app may not.
        // This gives the measurement a known value to read back.
        val write = try {
            val manager = getSystemService(android.content.ClipboardManager::class.java)
            manager.setPrimaryClip(
                android.content.ClipData.newPlainText("aplus", ProbeRelayService.PROBE_VALUE)
            )
            "OK"
        } catch (e: Exception) {
            "FAILED ${e.javaClass.simpleName}: ${e.message}"
        }
        android.util.Log.i(ProbeRelayService.TAG, "focused write result: $write")

        val status = TextView(this).apply {
            text = "probe running - read results are in logcat (tag AplusProbe)"
        }
        val start = Button(this).apply { text = "START PROBE" }
        start.setOnClickListener {
            val intent = Intent(this, ProbeRelayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(status)
                addView(start)
            }
        )
    }
}
