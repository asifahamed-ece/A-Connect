package com.aplusconnect.companion.spike

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.WindowManager

/**
 * Spike only: a transparent, non-interactive activity whose only job is to
 * take focus for a moment.
 *
 * The platform grants clipboard access to "the default IME or the app that has
 * focus" - and the check is on the *uid*, so any window of ours being focused is
 * enough. This is the technique KDE Connect uses. The activity reads the
 * clipboard in onWindowFocusChanged and finishes immediately.
 */
class ClipboardReadActivity : Activity() {

    private var read = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // A transparent window, no animation, not in recents.
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

        val result = try {
            val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = manager.primaryClip
            if (clip == null || clip.itemCount == 0) {
                "NOTHING"
            } else {
                val text = clip.getItemAt(0).coerceToText(this).toString()
                "OK len=${text.length} sha=${ProbeRelayService.sha256(text).take(12)}"
            }
        } catch (e: Exception) {
            "DENIED ${e.javaClass.simpleName}: ${e.message}"
        }

        Log.i(ProbeRelayService.TAG, "focused read result: $result")
        finish()
    }
}
