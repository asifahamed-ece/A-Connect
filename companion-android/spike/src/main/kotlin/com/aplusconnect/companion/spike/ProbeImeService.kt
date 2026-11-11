package com.aplusconnect.companion.spike

import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo

/**
 * Spike only: Android grants the default IME privileged access to the
 * clipboard, and the measurement showed that privilege extends to the whole
 * app UID - so the background service can read the clipboard while the app
 * holds the IME role.
 *
 * This service is a measuring tool, not a keyboard: it renders nothing and
 * must never be left as the user's default input method, because a keyboard
 * that renders nothing makes the phone unusable. The shipping app has to
 * either ship a real keyboard or not use this route at all.
 */
class ProbeImeService : InputMethodService() {

    override fun onCreate() {
        super.onCreate()
        Log.i(ProbeRelayService.TAG, "ime service created")
    }

    override fun onCreateInputView(): View {
        return View(this)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        Log.i(ProbeRelayService.TAG, "ime input view started")
    }
}
