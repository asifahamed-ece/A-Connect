package com.aplusconnect.companion.protocol

import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Length-prefixed framing: a 4 byte big endian length, then a 1 byte frame
 * kind, then the payload.
 *
 * The length counts the kind byte and the payload, so a reader can slice the
 * whole frame out of the stream before it knows what the frame is. Kind 0 is
 * UTF-8 JSON (every control message), kind 1 is opaque binary (the screen
 * mirror's H.264 frames).
 */
object FrameCodec {
    const val KIND_JSON: Byte = 0
    const val KIND_BINARY: Byte = 1

    /**
     * Two mebibytes, so that the worst case of a clipboard payload fits: the
     * clipboard budget is 256 KiB of UTF-8, and a control character escapes to
     * six bytes, which is 1.5 MiB of JSON plus the envelope.
     */
    const val MAX_FRAME_BYTES = 2 * 1024 * 1024

    fun encode(message: ProtocolMessage): ByteArray {
        val body = message.toJson().toString().toByteArray(StandardCharsets.UTF_8)
        return frame(KIND_JSON, body)
    }

    /** Wraps raw bytes - a mirror frame, for instance - as a binary frame. */
    fun encodeBinary(bytes: ByteArray): ByteArray = frame(KIND_BINARY, bytes)

    private fun frame(kind: Byte, body: ByteArray): ByteArray {
        val length = 1 + body.size
        require(length <= MAX_FRAME_BYTES) { "frame too large: ${body.size}" }
        val buffer = ByteBuffer.allocate(4 + length)
        buffer.putInt(length)
        buffer.put(kind)
        buffer.put(body)
        return buffer.array()
    }

    fun decode(frame: ByteArray): ProtocolMessage {
        val body = payloadOf(frame, KIND_JSON) ?: throw IllegalArgumentException("not a JSON frame")
        val json = org.json.JSONObject(String(body, StandardCharsets.UTF_8))
        return ProtocolMessage.fromJson(json)
    }

    /**
     * The payload of a binary frame, or null when the frame is anything else -
     * a JSON frame, a truncated frame, or bytes that are not a frame at all.
     * A caller that reads a whole frame and only sometimes expects binary must
     * be able to ask without catching an exception.
     */
    fun decodeBinary(frame: ByteArray): ByteArray? = payloadOf(frame, KIND_BINARY)

    /**
     * Validates the header of a whole frame and returns its payload if the
     * kind matches, or null if it does not.
     */
    private fun payloadOf(frame: ByteArray, kind: Byte): ByteArray? {
        if (frame.size < 4) return null
        val length = ByteBuffer.wrap(frame, 0, 4).int
        if (length < 1 || length > MAX_FRAME_BYTES) {
            if (kind == KIND_JSON) throw IllegalArgumentException("illegal frame length: $length")
            return null
        }
        if (frame.size < 4 + length) {
            if (kind == KIND_JSON) throw IllegalArgumentException("truncated frame")
            return null
        }
        if (frame[4] != kind) return null
        return frame.copyOfRange(5, 4 + length)
    }

    fun readFrom(input: InputStream): ProtocolMessage {
        val header = ByteArray(4)
        DataInputStream(input).readFully(header)
        val length = ByteBuffer.wrap(header).int
        require(length in 1..MAX_FRAME_BYTES) { "illegal frame length: $length" }
        val body = ByteArray(length)
        DataInputStream(input).readFully(body)
        require(body[0] == KIND_JSON) { "not a JSON frame (kind ${body[0]})" }
        val json = org.json.JSONObject(String(body, 1, length - 1, StandardCharsets.UTF_8))
        return ProtocolMessage.fromJson(json)
    }
}
