package com.aplusconnect.companion.protocol

import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Length-prefixed JSON framing: 4 byte big endian length, then UTF-8 JSON.
 */
object FrameCodec {
    /**
     * Two mebibytes, so that the worst case of a clipboard payload fits: the
     * clipboard budget is 256 KiB of UTF-8, and a control character escapes to
     * six bytes, which is 1.5 MiB of JSON plus the envelope.
     */
    const val MAX_FRAME_BYTES = 2 * 1024 * 1024

    fun encode(message: ProtocolMessage): ByteArray {
        val body = message.toJson().toString().toByteArray(StandardCharsets.UTF_8)
        require(body.size <= MAX_FRAME_BYTES) { "frame too large: ${body.size}" }
        val buffer = ByteBuffer.allocate(4 + body.size)
        buffer.putInt(body.size)
        buffer.put(body)
        return buffer.array()
    }

    fun decode(frame: ByteArray): ProtocolMessage {
        require(frame.size >= 4) { "frame too short" }
        val buffer = ByteBuffer.wrap(frame)
        val length = buffer.int
        require(length in 0..MAX_FRAME_BYTES) { "illegal frame length: $length" }
        require(frame.size >= 4 + length) { "truncated frame" }
        val body = String(frame, 4, length, StandardCharsets.UTF_8)
        val json = org.json.JSONObject(body)
        return ProtocolMessage.fromJson(json)
    }

    fun readFrom(input: InputStream): ProtocolMessage {
        val header = ByteArray(4)
        DataInputStream(input).readFully(header)
        val length = ByteBuffer.wrap(header).int
        require(length in 0..MAX_FRAME_BYTES) { "illegal frame length: $length" }
        val body = ByteArray(length)
        DataInputStream(input).readFully(body)
        return decode(header + body)
    }
}
