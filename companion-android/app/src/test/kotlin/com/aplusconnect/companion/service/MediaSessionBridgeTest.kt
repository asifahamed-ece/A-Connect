package com.aplusconnect.companion.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of the media bridge: turning what a session describes into the
 * payload the computer shows. No Android runtime is needed, which is the point:
 * the platform half (MediaSessionManager, MediaController) cannot run here.
 */
class MediaSessionBridgeTest {

    @Test
    fun `a playing session maps to a media state`() {
        val state = MediaSessionBridge.toMediaState(
            app = "Music",
            title = "Song",
            artist = "Band",
            album = "Album",
            playing = true,
            position = 1_000L,
            duration = 180_000L,
        )!!

        assertEquals("Music", state.app)
        assertEquals("Song", state.title)
        assertEquals("Band", state.artist)
        assertEquals("Album", state.album)
        assertTrue(state.playing)
        assertEquals(1_000L, state.positionMs)
        assertEquals(180_000L, state.durationMs)
    }

    @Test
    fun `a paused session is still worth showing`() {
        val state = MediaSessionBridge.toMediaState(
            app = "Music",
            title = "Song",
            artist = "Band",
            album = "Album",
            playing = false,
            position = 2_000L,
            duration = 180_000L,
        )!!

        assertFalse(state.playing)
    }

    @Test
    fun `a session with no metadata maps to null`() {
        // A session that has never played (a game, a browser tab) describes no
        // track. Showing an empty "now playing" card is worse than showing none.
        assertNull(
            MediaSessionBridge.toMediaState(
                app = "Music",
                title = "",
                artist = "",
                album = "",
                playing = false,
                position = 0L,
                duration = 0L,
            )
        )
    }
}