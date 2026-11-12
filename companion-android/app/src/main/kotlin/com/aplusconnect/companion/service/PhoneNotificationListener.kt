package com.aplusconnect.companion.service

import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.aplusconnect.companion.protocol.FeaturePayloads
import java.util.concurrent.ConcurrentHashMap

/**
 * The phone's notifications, forwarded to the computer.
 *
 * This is a NotificationListenerService, which needs the user to grant
 * notification access once, in Settings. It is not an accessibility service:
 * Android blocks contactless payments while any accessibility service is
 * enabled, so this app never registers one. If the user has not granted
 * notification access, this service simply never runs and everything else
 * keeps working.
 */
class PhoneNotificationListener : NotificationListenerService() {

    /**
     * The live notifications by our stable id, so a dismiss from the computer
     * can find the one it means. Filled when a notification is posted and
     * emptied when it is removed.
     */
    private val live = ConcurrentHashMap<String, StatusBarNotification>()

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val id = FeaturePayloads.notificationId(sbn.packageName, sbn.id, sbn.tag)
        live[id] = sbn

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            )?.toString().orEmpty()

        val actions = sbn.notification.actions?.mapIndexedNotNull { index, action ->
            val label = action.title?.toString() ?: return@mapIndexedNotNull null
            FeaturePayloads.NotificationAction(index.toString(), label)
        }.orEmpty()

        val payload = FeaturePayloads.notification(
            id = id,
            pkg = sbn.packageName,
            app = appLabel(sbn.packageName),
            title = title,
            text = text,
            ts = sbn.postTime,
            ongoing = sbn.isOngoing,
            actions = actions,
        )

        CompanionService.deliverNotification(this, id, payload)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val id = FeaturePayloads.notificationId(sbn.packageName, sbn.id, sbn.tag)
        live.remove(id)
        CompanionService.deliverNotificationRemoved(this, id)
    }

    /**
     * Dismisses the notification with this id, if it is still live. An unknown
     * id is ignored rather than treated as an error: the user may have swiped
     * it away a moment ago.
     */
    fun dismiss(id: String): Boolean {
        val sbn = live[id] ?: return false
        return try {
            cancelNotification(sbn.key)
            true
        } catch (e: Exception) {
            Log.w(TAG, "could not dismiss $id: ${e.message}")
            false
        }
    }

    private fun appLabel(pkg: String): String = try {
        val info = packageManager.getApplicationInfo(pkg, 0)
        packageManager.getApplicationLabel(info).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }

    companion object {
        private const val TAG = "AplusConnect"
    }
}
