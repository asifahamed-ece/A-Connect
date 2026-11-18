package com.aplusconnect.companion.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.aplusconnect.companion.service.CompanionService

/**
 * Android share-sheet target: "Send to PC via A+ Connect".
 *
 * Other apps (Gallery, Files, WhatsApp, …) hand us the shared file URIs through
 * Intent.EXTRA_STREAM. We forward each one to CompanionService via deliverSendFile,
 * which runs the transfer on the send thread (never on the main thread, which would
 * throw NetworkOnMainThreadException). The temporary read grant travels with each
 * URI so the background service can open the content:// URI after this activity
 * finishes. We then finish() ourselves so nothing lingers in recents.
 *
 * If no phone is connected, we show a toast instead of silently failing.
 */
class ShareReceiver : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val uris = extractUris(intent)

        if (uris.isEmpty()) {
            Toast.makeText(applicationContext, "A+ Connect: Nothing to send", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // No paired phone is connected right now. Say so instead of letting
        // the transfer fail with "Not connected to PC".
        if (!CompanionService.Status.isConnected) {
            Toast.makeText(applicationContext, "A+ Connect: PC not connected", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Forward every shared URI to the service. deliverSendFile starts the
        // foreground service if it is not running yet, and the transfer runs
        // on the send thread with the read grant attached.
        for (uri in uris) {
            CompanionService.deliverSendFile(this, uri)
        }

        finish()
    }

    /**
     * Pulls the shared file URIs out of Intent.EXTRA_STREAM, which is what
     * Gallery, Files, WhatsApp and the rest actually populate (intent.data is
     * for ACTION_VIEW and is normally null here).
     */
    private fun extractUris(intent: Intent): List<Uri> {
        val action = intent.action
        return when (action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                } ?: intent.clipData?.getItemAt(0)?.uri ?: intent.data
                listOfNotNull(uri)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                } ?: emptyList<Uri>()
                list.filterNotNull()
            }
            else -> emptyList()
        }
    }

    companion object {
        const val TAG = "ShareReceiver"
    }
}
