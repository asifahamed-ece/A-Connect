package com.aplusconnect.companion.clip

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import com.aplusconnect.companion.protocol.ClipboardPayloads
import com.aplusconnect.companion.service.CompanionService

/**
 * A transparent window whose only job is to take focus for a moment.
 *
 * The platform grants clipboard access to "the default keyboard or the app that
 * has focus", and the check is on the uid, so any window of ours being focused
 * is enough. The activity reads the clipboard in `onWindowFocusChanged` and
 * closes immediately - the user sees nothing.
 */
class ClipboardReadActivity : Activity() {

    private var read = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setBackgroundDrawable(null)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || read) return
        read = true

        val payload = readClipboard()
        if (payload != null) {
            Log.i(TAG, "read ${payload.text.length} characters")
            CompanionService.deliverClipboard(this, payload.text)
        }
        finish()
    }

    /** The clipboard content, or null when there is none or it cannot be read. */
    private fun readClipboard(): com.aplusconnect.companion.protocol.ClipboardPayload? = try {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = manager.primaryClip
        if (clip == null || clip.itemCount == 0) {
            null
        } else {
            ClipboardPayloads.build(clip.getItemAt(0).coerceToText(this).toString())
        }
    } catch (e: Exception) {
        Log.w(TAG, "could not read the clipboard: ${e.message}")
        null
    }

    companion object {
        const val TAG = "AplusConnect"

        /** Raises the transparent reader. */
        fun request(context: Context) {
            val intent = Intent(context, ClipboardReadActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "could not raise the reader: ${e.message}")
            }
        }
    }
}
