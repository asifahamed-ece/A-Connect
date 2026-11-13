package com.aplusconnect.companion.mirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The capture size, and what to do when the link is busy.
 *
 * Both are pure so they can be tested here rather than by watching a phone:
 * a wrong size makes an encoder fail to configure, and a wrong drop decision
 * makes the picture lag behind reality instead of getting choppy.
 */
class MirrorConfigTest {

    @Test
    fun `a tall phone is capped on its long side and keeps its shape`() {
        // The test phone: 1256x2760. Capped to 1280 on the long side.
        val size = MirrorConfig.fit(1256, 2760)

        assertEquals(1280, size.height)
        assertEquals("the aspect ratio was not kept", 582, size.width)
        assertTrue("an odd width would break the encoder's chroma blocks", size.width % 2 == 0)
    }

    @Test
    fun `a landscape screen is capped on its width instead`() {
        val size = MirrorConfig.fit(2400, 1080)
        assertEquals(1280, size.width)
        assertEquals(576, size.height)
    }

    @Test
    fun `a small screen is never upscaled`() {
        // Inventing pixels costs the encoder and gains the viewer nothing.
        val size = MirrorConfig.fit(480, 800)
        assertEquals(480, size.width)
        assertEquals(800, size.height)
    }

    @Test
    fun `odd dimensions are made even`() {
        val size = MirrorConfig.fit(481, 801, maxLongSide = 2000)
        assertEquals(480, size.width)
        assertEquals(800, size.height)
    }

    @Test
    fun `a screen smaller than the cap is left alone`() {
        val size = MirrorConfig.fit(1280, 720, maxLongSide = 1280)
        assertEquals(1280, size.width)
        assertEquals(720, size.height)
    }

    @Test
    fun `nothing ever produces a zero dimension`() {
        val size = MirrorConfig.fit(1, 1)
        assertTrue(size.width >= 2 && size.height >= 2)
    }
}

/**
 * The drop policy: keep the picture live, never lose the keyframe.
 */
class FrameGateTest {

    @Test
    fun `the first frame goes straight out`() {
        val gate = FrameGate()
        assertEquals(FrameGate.Action.SEND, gate.offer(keyframe = true))
        assertTrue(gate.busy)
    }

    @Test
    fun `only one frame waits, and a newer one replaces it`() {
        val gate = FrameGate()
        gate.offer(keyframe = false) // sent
        assertEquals(FrameGate.Action.HOLD, gate.offer(keyframe = false))
        // A third delta arrives while one is already waiting: the waiting one
        // is now stale, so the newer frame takes its place and the older one is
        // counted as dropped.
        assertEquals(FrameGate.Action.HOLD, gate.offer(keyframe = false))
        assertEquals(1, gate.dropped)

        assertTrue("the waiting frame was not handed back", gate.onWritten())
        assertFalse("the link should be idle after the last frame", gate.onWritten())
    }

    @Test
    fun `a keyframe takes the slot from a waiting delta frame`() {
        val gate = FrameGate()
        gate.offer(keyframe = false)                       // sent
        gate.offer(keyframe = false)                       // waits
        assertEquals(FrameGate.Action.HOLD, gate.offer(keyframe = true)) // keyframe wins
        assertEquals("the displaced delta frame was not counted", 1, gate.dropped)
        assertTrue(gate.onWritten())
    }

    @Test
    fun `a delta frame never displaces a waiting keyframe`() {
        val gate = FrameGate()
        gate.offer(keyframe = false)  // sent
        gate.offer(keyframe = true)   // keyframe waits
        assertEquals(FrameGate.Action.DROP, gate.offer(keyframe = false))
    }

    @Test
    fun `a reset forgets a half-finished session`() {
        val gate = FrameGate()
        gate.offer(keyframe = true)
        gate.reset()
        assertFalse("reset left the gate busy", gate.busy)
        assertEquals("reset left a frame waiting", FrameGate.Action.SEND, gate.offer(keyframe = true))
    }
}