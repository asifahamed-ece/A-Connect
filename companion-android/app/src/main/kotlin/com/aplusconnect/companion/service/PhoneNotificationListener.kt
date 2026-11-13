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

        // A chat posts one notification per message and a group summary that
        // says "2 new messages". Forwarding both makes every message pop up
        // twice on the computer.
        val notification = sbn.notification
        val isSummary = notification != null &&
            (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0
        if (isRedundantSummary(isSummary, notification?.group, groupsWithMessages())) {
            Log.i(TAG, "not forwarding a group summary; its messages are already on their way")
            return
        }

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
            Log.i(TAG, "dismissed $id at the computer's request")
            true
        } catch (e: Exception) {
            Log.w(TAG, "could not dismiss $id: ${e.message}")
            false
        }
    }

    /** Fires one of the notification's own buttons, by its index. */
    private fun fireAction(id: String, action: String): Boolean {
        val sbn = live[id] ?: return false
        val index = action.toIntOrNull() ?: return false
        val actions = sbn.notification?.actions ?: return false
        if (index !in actions.indices) return false
        return try {
            actions[index].actionIntent?.send()
            true
        } catch (e: Exception) {
            Log.w(TAG, "could not fire action $action on $id: ${e.message}")
            false
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    private fun appLabel(pkg: String): String = try {
        val info = packageManager.getApplicationInfo(pkg, 0)
        packageManager.getApplicationLabel(info).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }

    /**
     * The groups that have a message of their own right now.
     *
     * Read from the system's live list rather than tracked here, so a message
     * the user swiped away stops counting immediately and a summary that
     * arrives afterwards is still forwarded.
     */
    private fun groupsWithMessages(): Set<String> {
        val groups = HashSet<String>()
        try {
            activeNotifications?.forEach { other ->
                val notification = other.notification ?: return@forEach
                if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return@forEach
                notification.group?.let { groups.add(it) }
            }
        } catch (e: Exception) {
            // Without the live list, nothing is suppressed: a duplicate is
            // better than a message that never arrives.
            Log.w(TAG, "could not read the live notifications: ${e.message}")
        }
        return groups
    }

    companion object {
        private const val TAG = "AplusConnect"

        /**
         * Whether this notification is a summary that only repeats what its
         * own group's messages already say.
         *
         * A summary with no messages of its own is kept: some apps post only
         * the summary, and dropping it would lose the text.
         */
        fun isRedundantSummary(
            isSummary: Boolean,
            group: String?,
            groupsWithMessages: Set<String>,
        ): Boolean = isSummary && group != null && groupsWithMessages.contains(group)

        /** A command to remove the notification, rather than press a button. */
        const val ACTION_DISMISS = "dismiss"

        /**
         * The running listener, when the user has granted notification access.
         *
         * A NotificationListenerService is bound by the system, so this is how
         * the connection reaches the one instance the system created; a null
         * instance simply means notification access is not granted.
         */
        @Volatile
        private var instance: PhoneNotificationListener? = null

        /**
         * Handles a command from the computer. A false answer is ordinary: the
         * listener may not be running, or the notification may already be gone.
         */
        fun handleRemote(id: String, action: String): Boolean {
            val listener = instance ?: return false
            return if (action == ACTION_DISMISS) listener.dismiss(id)
            else listener.fireAction(id, action)
        }
    }
}
