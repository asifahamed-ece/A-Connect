package com.aplusconnect.companion.clip

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.atomic.AtomicInteger

/**
 * Spots the moment the user copies something, so the app knows it is worth
 * reading the clipboard.
 *
 * The clipboard's own change listener cannot be used for this: the platform
 * gates it behind the same access check as the content, so a background app is
 * never told. What an accessibility service does get is the window activity of
 * the system UI, and the clipboard preview that appears when the clipboard
 * changes is drawn there.
 *
 * This service reads no screen content and never looks at what was copied - it
 * only decides *when* to look.
 */
class ClipboardEventWatcher : AccessibilityService() {

    private val events = AtomicInteger(0)

    override fun onServiceConnected() {
        Log.i(TAG, "copy event watcher connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val count = events.incrementAndGet()
        val pkg = event.packageName?.toString() ?: "?"
        val type = event.eventType
        Log.i(TAG, "event #$count type=$type pkg=$pkg")

        // The clipboard preview chip is drawn by the system UI.
        if (pkg != SYSTEM_UI) return
        if (type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastTriggerAt < DEBOUNCE_MS) return
        lastTriggerAt = now

        Log.i(TAG, "system ui activity, reading the clipboard")
        ClipboardReadActivity.request(this)
    }

    override fun onInterrupt() {
    }

    companion object {
        const val TAG = "AplusConnect"
        private const val SYSTEM_UI = "com.android.systemui"
        private const val DEBOUNCE_MS = 1500L

        private var lastTriggerAt = 0L
    }
}
