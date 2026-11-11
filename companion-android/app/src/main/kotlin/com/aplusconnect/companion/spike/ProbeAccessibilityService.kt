package com.aplusconnect.companion.spike

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Spike only: this service exists so it can be *enabled*, and it also reads
 * the clipboard itself. That distinguishes two very different outcomes:
 *
 *  - if the accessibility service can read but the foreground service cannot,
 *    the exemption is scoped to the accessibility service component and the
 *    shipping app has to do its reading there;
 *  - if neither can read, an enabled accessibility service is not enough on
 *    this Android version.
 */
class ProbeAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    private val poller = object : Runnable {
        override fun run() {
            readOnce()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onServiceConnected() {
        Log.i(ProbeRelayService.TAG, "a11y service connected")
        handler.postDelayed(poller, POLL_INTERVAL_MS)
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        // Not used by the spike.
    }

    override fun onInterrupt() {
    }

    private fun readOnce() {
        val result = try {
            val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = manager.primaryClip
            if (clip == null || clip.itemCount == 0) {
                "EMPTY (refused)"
            } else {
                val text = clip.getItemAt(0).coerceToText(this).toString()
                "OK len=${text.length} sha=${ProbeRelayService.sha256(text).take(12)}"
            }
        } catch (e: Exception) {
            "DENIED ${e.javaClass.simpleName}: ${e.message}"
        }
        Log.i(ProbeRelayService.TAG, "a11y read result: $result")
    }

    companion object {
        private const val POLL_INTERVAL_MS = 2000L
    }
}
