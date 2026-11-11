package com.aplusconnect.companion.spike

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Spike only: measures what an accessibility service can observe when the
 * user copies something.
 *
 * The clipboard change *listener* is gated by the same access check as the
 * clipboard itself, so it never fires for a background app. The accessibility
 * service is the remaining signal: it sees UI events, including the toast an
 * app shows when the user copies, without needing clipboard access.
 *
 * This logs what arrives so the trigger rule can be designed from a
 * measurement instead of a guess.
 */
class ProbeAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        Log.i(ProbeRelayService.TAG, "a11y service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val type = eventTypeName(event.eventType)
        val pkg = event.packageName?.toString() ?: "?"
        val cls = event.className?.toString() ?: "?"

        // Only the toast-ish text, and only a short prefix: this is a
        // measurement, not a clipboard log.
        val text = (event.text?.joinToString(" ") { it.toString() } ?: "").take(60)

        Log.i(
            ProbeRelayService.TAG,
            "event type=$type pkg=$pkg class=$cls text=[$text]"
        )
    }

    override fun onInterrupt() {
    }

    /** The event type as a name, so the log can be read by a human. */
    private fun eventTypeName(type: Int): String = when (type) {
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_STATE_CHANGED"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "WINDOW_CONTENT_CHANGED"
        AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> "VIEW_TEXT_SELECTION_CHANGED"
        AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> "NOTIFICATION_STATE_CHANGED"
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "VIEW_TEXT_CHANGED"
        AccessibilityEvent.TYPE_VIEW_CLICKED -> "VIEW_CLICKED"
        AccessibilityEvent.TYPE_VIEW_SELECTED -> "VIEW_SELECTED"
        else -> "TYPE_$type"
    }
}
