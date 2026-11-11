package com.aplusconnect.companion.spike

import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo

/**
 * Spike only: Android grants the default IME privileged access to the
 * clipboard even in the background. This service exists to test whether that
 * privilege extends to the whole app UID - which would let the foreground
 * service read the clipboard without the app ever being in the foreground.
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
