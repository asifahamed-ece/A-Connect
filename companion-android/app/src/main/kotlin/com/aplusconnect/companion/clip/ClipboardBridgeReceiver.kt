package com.aplusconnect.companion.clip

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.aplusconnect.companion.service.CompanionService

/**
 * The way the phone's clipboard reaches this app.
 *
 * Android only lets the *default keyboard* read the clipboard in the
 * background (ClipboardService.isDefaultIme), so this app cannot watch it from
 * here. The keyboard that holds that role is the A+ Connect keyboard - a
 * patched HeliBoard - and it broadcasts what was copied.
 *
 * The broadcast is protected by a signature-level permission, so only an app
 * signed with this app's key can feed it: another clipboard-sharing app cannot
 * inject text into someone's computer.
 */
class ClipboardBridgeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CLIPBOARD) return

        // A dormant phone stays dormant: the kill switch has to mean it, not
        // "until something wakes the service up".
        if (!CompanionService.isEnabled(context)) {
            android.util.Log.i("AplusConnect", "ignoring the clipboard: the switch is off")
            return
        }

        val text = intent.getStringExtra(EXTRA_TEXT)
        android.util.Log.i("AplusConnect", "the keyboard handed us ${text?.length ?: 0} characters")
        if (text.isNullOrEmpty()) return

        val forward = Intent(context, CompanionService::class.java).apply {
            action = CompanionService.ACTION_CONNECT
            putExtra(CompanionService.EXTRA_CLIPBOARD, text)
        }

        try {
            ContextCompat.startForegroundService(context, forward)
        } catch (e: Exception) {
            android.util.Log.w("AplusConnect", "could not forward the keyboard's clipboard: ${e.message}")
        }
    }

    companion object {
        const val ACTION_CLIPBOARD = "com.aplusconnect.companion.action.CLIPBOARD"
        const val EXTRA_TEXT = "text"

        /** Held by the A+ Connect keyboard, checked by this receiver. */
        const val PERMISSION = "com.aplusconnect.companion.permission.CLIPBOARD_BRIDGE"
    }
}

/**
 * The identities the A+ Connect keyboard may appear under.
 *
 * `com.aplusconnect.keyboard` is the fork's applicationId. The stock HeliBoard
 * package is accepted too, so a build that has not been renamed yet still
 * counts as capable of holding the clipboard privilege.
 */
object KeyboardBridge {
    val PACKAGES = listOf(
        "com.aplusconnect.keyboard",
        "helium314.keyboard",
    )
}
