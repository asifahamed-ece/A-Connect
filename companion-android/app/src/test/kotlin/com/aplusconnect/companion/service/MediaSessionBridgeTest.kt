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
            pkg = "com.example.music",
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
            pkg = "com.example.music",
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
                pkg = "com.example.music",
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

    // ---- which session to follow -------------------------------------------

    @Test
    fun `a playing session beats a paused one whatever the order`() {
        // The system hands sessions over in priority order, not in "which one
        // is actually playing" order. Taking the first paused one kept the
        // panel on a stopped player while a video ran.
        assertEquals(1, MediaSessionBridge.chooseIndex(listOf(STATE_PAUSED, STATE_PLAYING)))
        assertEquals(0, MediaSessionBridge.chooseIndex(listOf(STATE_PLAYING, STATE_PAUSED)))
    }

    @Test
    fun `a paused session is followed when nothing plays`() {
        assertEquals(1, MediaSessionBridge.chooseIndex(listOf(STATE_STOPPED, STATE_PAUSED)))
    }

    @Test
    fun `nothing is followed when nothing is playing or paused`() {
        assertNull(MediaSessionBridge.chooseIndex(emptyList()))
        assertNull(MediaSessionBridge.chooseIndex(listOf(STATE_STOPPED, STATE_NONE, null)))
    }

    @Test
    fun `a session with no text is not worth showing`() {
        // The app has to tell the computer "nothing is playing" in that case,
        // instead of sending a blank card that never clears.
        assertFalse(MediaSessionBridge.isWorthShowing(STATE_PLAYING, hasText = false))
        assertTrue(MediaSessionBridge.isWorthShowing(STATE_PLAYING, hasText = true))
    }

    @Test
    fun `a stopped session is not worth showing`() {
        assertFalse(MediaSessionBridge.isWorthShowing(STATE_STOPPED, hasText = true))
        assertFalse(MediaSessionBridge.isWorthShowing(STATE_NONE, hasText = true))
        assertFalse(MediaSessionBridge.isWorthShowing(null, hasText = true))
    }

    private companion object {
        const val STATE_NONE = 0
        const val STATE_STOPPED = 1
        const val STATE_PAUSED = 2
        const val STATE_PLAYING = 3
    }
}