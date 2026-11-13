package com.aplusconnect.companion.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Group summaries must not be forwarded as well as the messages they summarise.
 *
 * Android posts two notifications for a chat: one per message, and a group
 * summary that says "2 new messages". Forwarding both makes every message pop
 * up twice on the computer, which is exactly what a mirror should not do.
 */
class PhoneNotificationListenerTest {

    @Test
    fun `a summary is redundant when its group already has a message`() {
        assertTrue(
            PhoneNotificationListener.isRedundantSummary(
                isSummary = true,
                group = "group_key_messages",
                groupsWithMessages = setOf("group_key_messages"),
            )
        )
    }

    @Test
    fun `a summary with no messages of its own is forwarded`() {
        // Some apps post only the summary. Dropping it would lose the text.
        assertFalse(
            PhoneNotificationListener.isRedundantSummary(
                isSummary = true,
                group = "group_key_messages",
                groupsWithMessages = emptySet(),
            )
        )
        assertFalse(
            PhoneNotificationListener.isRedundantSummary(
                isSummary = true,
                group = null,
                groupsWithMessages = setOf("group_key_messages"),
            )
        )
    }

    @Test
    fun `a normal notification is never redundant`() {
        assertFalse(
            PhoneNotificationListener.isRedundantSummary(
                isSummary = false,
                group = "group_key_messages",
                groupsWithMessages = setOf("group_key_messages"),
            )
        )
    }

    @Test
    fun `the app does not mirror its own notification`() {
        // The foreground notification says "Connected to ...", which is about
        // this connection; sending it back to the computer is noise.
        assertTrue(
            PhoneNotificationListener.isOwnNotification(
                "com.aplusconnect.companion",
                "com.aplusconnect.companion",
            )
        )
        assertFalse(
            PhoneNotificationListener.isOwnNotification(
                "com.google.android.youtube",
                "com.aplusconnect.companion",
            )
        )
        // A notification with no package cannot be proven to be ours.
        assertFalse(PhoneNotificationListener.isOwnNotification(null, "com.aplusconnect.companion"))
    }
}
