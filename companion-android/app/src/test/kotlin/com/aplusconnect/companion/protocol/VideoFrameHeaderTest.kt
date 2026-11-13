package com.aplusconnect.companion.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ten bytes in front of every video frame.
 *
 * This is the one thing both ends must agree on exactly. A wrong timestamp only
 * wobbles the clock, but a wrong stream id or a wrong length makes the desktop
 * throw frames away, and the symptom - a blank canvas - looks nothing like the
 * cause.
 */
class VideoFrameHeaderTest {

    private val payload = byteArrayOf(0, 0, 0, 1, 0x65, 0x88.toByte(), 0x84.toByte())

    @Test
    fun `a keyframe survives the round trip`() {
        val encoded = VideoFrameHeader.encode(keyframe = true, ptsMicros = 123_456, payload = payload)

        assertEquals("the header is not ten bytes", 10, VideoFrameHeader.HEADER_BYTES)
        assertEquals("the frame is the wrong length", 10 + payload.size, encoded.size)

        val frame = VideoFrameHeader.parse(encoded)!!
        assertTrue("the keyframe flag was lost", frame.keyframe)
        assertEquals(123_456L, frame.ptsMicros)
        assertArrayEquals("the payload changed", payload, frame.payload)
    }

    @Test
    fun `a delta frame says so, and a big timestamp still fits`() {
        val pts = 9_000_000_000_000L
        val frame = VideoFrameHeader.parse(
            VideoFrameHeader.encode(keyframe = false, ptsMicros = pts, payload = payload)
        )!!

        assertFalse("a delta frame was marked as a keyframe", frame.keyframe)
        assertEquals("a 64-bit timestamp did not survive", pts, frame.ptsMicros)
    }

    @Test
    fun `an empty payload is legal`() {
        val frame = VideoFrameHeader.parse(
            VideoFrameHeader.encode(keyframe = true, ptsMicros = 1, payload = ByteArray(0))
        )!!
        assertEquals(0, frame.payload.size)
    }

    @Test
    fun `too few bytes is not a frame`() {
        assertNull("a 3-byte frame parsed", VideoFrameHeader.parse(ByteArray(3)))
        assertNull("an empty buffer parsed", VideoFrameHeader.parse(ByteArray(0)))
    }

    @Test
    fun `a frame for another stream is refused`() {
        // Audio, or a future stream. The reader has to drop it and keep going.
        val bytes = VideoFrameHeader.encode(keyframe = true, ptsMicros = 5, payload = payload)
        bytes[0] = 2
        assertNull("a frame from another stream was accepted as video", VideoFrameHeader.parse(bytes))
    }

    @Test
    fun `the stream id is one, and the flags byte only carries the keyframe`() {
        val bytes = VideoFrameHeader.encode(keyframe = false, ptsMicros = 0, payload = payload)
        assertEquals("the stream id moved", VideoFrameHeader.STREAM_VIDEO.toByte(), bytes[0])
        assertEquals("a delta frame set flags it should not", 0.toByte(), bytes[1])

        val key = VideoFrameHeader.encode(keyframe = true, ptsMicros = 0, payload = payload)
        assertEquals("a keyframe did not set the keyframe bit", 1.toByte(), key[1])
    }
}