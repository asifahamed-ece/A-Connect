package com.aplusconnect.companion.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The 10 bytes that travel in front of every video frame.
 *
 * The stream id is first so a second kind of stream (audio, a later build) can
 * share the envelope without a format change. The flags byte carries only the
 * keyframe bit today. The timestamp is microseconds from the encoder, unsigned
 * and 64-bit, which is where `MediaCodec` starts counting and what WebCodecs
 * wants back.
 *
 * Pure on purpose: the format is the one thing both ends must agree on exactly,
 * and it is cheap to test without a phone.
 */
object VideoFrameHeader {

    /** One here, two reserved for audio. */
    const val STREAM_VIDEO = 1

    const val HEADER_BYTES = 10

    private const val FLAG_KEYFRAME = 1

    /** One frame, ready to go into a binary envelope. */
    fun encode(keyframe: Boolean, ptsMicros: Long, payload: ByteArray): ByteArray {
        val out = ByteBuffer.allocate(HEADER_BYTES + payload.size).order(ByteOrder.BIG_ENDIAN)
        out.put(STREAM_VIDEO.toByte())
        out.put((if (keyframe) FLAG_KEYFRAME else 0).toByte())
        out.putLong(ptsMicros)
        out.put(payload)
        return out.array()
    }

    data class Frame(val keyframe: Boolean, val ptsMicros: Long, val payload: ByteArray) {
        // Data classes compare their arrays by identity, which would make two
        // equal frames unequal in a test. The payload is what matters here.
        override fun equals(other: Any?): Boolean =
            other is Frame &&
                keyframe == other.keyframe &&
                ptsMicros == other.ptsMicros &&
                payload.contentEquals(other.payload)

        override fun hashCode(): Int =
            (keyframe.hashCode() * 31 + ptsMicros.hashCode()) * 31 + payload.contentHashCode()
    }

    /**
     * Reads one frame, or null when the bytes are not one.
     *
     * Null rather than an exception: a reader that cannot understand one frame
     * has to be able to drop it and carry on, or a single bad packet takes the
     * stream down.
     */
    fun parse(bytes: ByteArray): Frame? {
        if (bytes.size < HEADER_BYTES) return null
        if (bytes[0].toInt() != STREAM_VIDEO) return null
        val keyframe = (bytes[1].toInt() and FLAG_KEYFRAME) != 0
        val pts = ByteBuffer.wrap(bytes, 2, 8).order(ByteOrder.BIG_ENDIAN).long
        return Frame(keyframe, pts, bytes.copyOfRange(HEADER_BYTES, bytes.size))
    }
}
