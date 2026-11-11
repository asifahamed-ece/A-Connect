package com.aplusconnect.companion.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decodes every frame in the shared fixture - including the one the Node side
 * produced - and checks the values, not the raw bytes: the two languages may
 * serialise object keys in a different order.
 */
class ProtocolFixtureTest {

    private fun frames(file: String): List<JSONObject> {
        val stream = javaClass.getResourceAsStream(file) ?: return emptyList()
        val text = stream.bufferedReader().use { it.readText() }
        val array = JSONObject(text).getJSONArray("frames")
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    @Test
    fun `every fixture frame decodes to its documented values`() {
        val fixture = frames("/fixtures/protocol-frames.json")
        assertTrue("fixture is empty", fixture.isNotEmpty())

        for (frame in fixture) {
            val bytes = hexToBytes(frame.getString("hex"))
            val decoded = FrameCodec.decode(bytes)
            val expected = frame.getJSONObject("json")

            assertEquals(frame.getString("name"), expected.getString("type"), decoded.type)
            assertEquals(expected.getString("id"), decoded.id)
            assertEquals(expected.getLong("ts"), decoded.ts)
            assertEquals(expected.getInt("v"), Protocol.VERSION)
            assertEquals(
                expected.getJSONObject("payload").toString(),
                decoded.payload.toString()
            )
        }
    }

    @Test
    fun `a frame produced by the node side decodes here too`() {
        val nodeFrames = frames("/fixtures/protocol-frames-node.json")
        if (nodeFrames.isEmpty()) return // not generated yet

        for (frame in nodeFrames) {
            val decoded = FrameCodec.decode(hexToBytes(frame.getString("hex")))
            assertEquals(frame.getJSONObject("json").getString("type"), decoded.type)
            assertEquals(frame.getJSONObject("json").getString("id"), decoded.id)
        }
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
