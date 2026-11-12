package com.aplusconnect.companion.protocol

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class FrameCodecTest {

    @Test
    fun `binary frames round trip`() {
        // 0xFA is 250 unsigned; a Kotlin Byte literal cannot hold it, so it is
        // spelled 0xFA.toByte() and the fixture hex "000102fa" matches it.
        val payload = byteArrayOf(0, 1, 2, 0xFA.toByte())

        val frame = FrameCodec.encodeBinary(payload)

        assertArrayEquals(payload, FrameCodec.decodeBinary(frame))
    }

    @Test
    fun `a JSON frame is not a binary frame`() {
        val json = FrameCodec.encode(
            ProtocolMessage(Protocol.TYPE_PING, "1", 1L, JSONObject())
        )

        assertNull(FrameCodec.decodeBinary(json))
    }

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

    @Test
    fun `the shared binary fixture frames to the same bytes as the node side`() {
        // fixtures/frames.json is the one file both implementations read: the
        // Node side asserts the identical literal frame in protocol-check.js.
        val text = javaClass.getResourceAsStream("/fixtures/frames.json")!!
            .bufferedReader().use { it.readText() }
        val fixture = JSONObject(text)
        val payload = hexToBytes(fixture.getString("hex"))

        val frame = FrameCodec.encodeBinary(payload)

        assertEquals("0000000501000102fa", toHex(frame))
        assertArrayEquals(payload, FrameCodec.decodeBinary(frame))
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun toHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}
