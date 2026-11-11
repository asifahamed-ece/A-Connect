package com.aplusconnect.companion.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class FrameCodecTest {

    @Test
    fun `encode prefixes a 4 byte big endian length`() {
        val message = ProtocolMessage(
            type = Protocol.TYPE_PING,
            id = "abc",
            ts = 1700000000000L,
            payload = JSONObject()
        )

        val frame = FrameCodec.encode(message)

        val declared = ((frame[0].toInt() and 0xFF) shl 24) or
            ((frame[1].toInt() and 0xFF) shl 16) or
            ((frame[2].toInt() and 0xFF) shl 8) or
            (frame[3].toInt() and 0xFF)
        assertEquals(frame.size - 4, declared)
    }

    @Test
    fun `decode round trips the envelope`() {
        val message = ProtocolMessage(
            type = Protocol.TYPE_CLIPBOARD_SET,
            id = "id-1",
            ts = 1700000000000L,
            payload = JSONObject().put("text", "hello").put("hash", "abc")
        )

        val decoded = FrameCodec.decode(FrameCodec.encode(message))

        assertEquals(Protocol.TYPE_CLIPBOARD_SET, decoded.type)
        assertEquals("id-1", decoded.id)
        assertEquals(1700000000000L, decoded.ts)
        assertEquals("hello", decoded.payload.getString("text"))
        assertEquals("abc", decoded.payload.getString("hash"))
    }

    @Test
    fun `decode rejects a frame longer than the maximum`() {
        val oversized = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x00, 0x20, 0x00, 0x00))
            write(ByteArray(1024))
        }.toByteArray()

        assertThrows(IllegalArgumentException::class.java) {
            FrameCodec.decode(oversized)
        }
    }

    @Test
    fun `readFrom reads consecutive frames from a stream`() {
        val first = FrameCodec.encode(ProtocolMessage(Protocol.TYPE_PING, "1", 1L, JSONObject()))
        val second = FrameCodec.encode(ProtocolMessage(Protocol.TYPE_PONG, "2", 2L, JSONObject()))
        val stream = ByteArrayInputStream(first + second)

        assertEquals("1", FrameCodec.readFrom(stream).id)
        assertEquals("2", FrameCodec.readFrom(stream).id)
    }
}
