package com.aplusconnect.companion.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeaturePayloadsTest {

    @Test
    fun `a notification payload round trips and keeps its actions in order`() {
        val actions = listOf(
            FeaturePayloads.NotificationAction("reply", "Reply"),
            FeaturePayloads.NotificationAction("archive", "Archive"),
        )

        val payload = FeaturePayloads.notification(
            id = "com.example:7:null",
            pkg = "com.example",
            app = "Example",
            title = "Ada",
            text = "hello",
            ts = 1700000000000L,
            ongoing = false,
            actions = actions,
        )

        val parsed = FeaturePayloads.parseNotification(payload)!!
        assertEquals("com.example:7:null", parsed.id)
        assertEquals("Ada", parsed.title)
        assertEquals("hello", parsed.text)
        assertEquals(actions, parsed.actions)
    }

    @Test
    fun `a media state payload keeps playing and the position`() {
        val payload = FeaturePayloads.mediaState(
            app = "Music",
            title = "Song",
            artist = "Band",
            album = "Album",
            playing = true,
            positionMs = 1_000L,
            durationMs = 180_000L,
        )

        val parsed = FeaturePayloads.parseMediaState(payload)!!
        assertEquals(true, parsed.playing)
        assertEquals("Song", parsed.title)
        assertEquals(1_000L, parsed.positionMs)
        assertEquals(180_000L, parsed.durationMs)
    }

    @Test
    fun `a notification without an id does not parse`() {
        assertNull(FeaturePayloads.parseNotification(JSONObject().put("pkg", "com.example")))
    }

    @Test
    fun `a notification id is stable for the same notification`() {
        val first = FeaturePayloads.notificationId("com.example", 7, null)
        val again = FeaturePayloads.notificationId("com.example", 7, null)
        val tagged = FeaturePayloads.notificationId("com.example", 7, "x")

        assertEquals(first, again)
        assertNotEquals(first, tagged)
    }

    @Test
    fun `the shared payload fixtures match the kotlin builders`() {
        // fixtures/feature-payloads.json is the one file both implementations
        // read; protocol-check.js asserts the identical objects on the Node side.
        val text = javaClass.getResourceAsStream("/fixtures/feature-payloads.json")!!
            .bufferedReader().use { it.readText() }
        val fixture = JSONObject(text)

        val notification = FeaturePayloads.notification(
            id = "com.example:7:null",
            pkg = "com.example",
            app = "Example",
            title = "Ada",
            text = "hello",
            ts = 1700000000000L,
            ongoing = false,
            actions = listOf(
                FeaturePayloads.NotificationAction("reply", "Reply"),
                FeaturePayloads.NotificationAction("archive", "Archive"),
            ),
        )
        assertEquals(fixture.getJSONObject("notification").toString(), notification.toString())

        val media = FeaturePayloads.mediaState(
            app = "Music",
            title = "Song",
            artist = "Band",
            album = "Album",
            playing = true,
            positionMs = 1_000L,
            durationMs = 180_000L,
        )
        assertEquals(fixture.getJSONObject("mediaState").toString(), media.toString())

        val files = FeaturePayloads.fileList(
            path = "root",
            entries = listOf(
                FeaturePayloads.FileEntry("doc:pictures", "Pictures", 0L, true, ""),
                FeaturePayloads.FileEntry("doc:photo", "photo.jpg", 1024L, false, "image/jpeg"),
            ),
        )
        assertEquals(fixture.getJSONObject("fileList").toString(), files.toString())
    }
}
