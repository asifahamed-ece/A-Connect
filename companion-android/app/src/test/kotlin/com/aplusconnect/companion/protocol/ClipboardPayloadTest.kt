package com.aplusconnect.companion.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardPayloadTest {

    @Test
    fun `short text is not truncated`() {
        val payload = ClipboardPayloads.build("hello")

        assertEquals("hello", payload.text)
        assertFalse(payload.truncated)
    }

    @Test
    fun `long text is truncated and flagged`() {
        val payload = ClipboardPayloads.build("x".repeat(300_000))

        assertEquals(Protocol.MAX_CLIPBOARD_CHARS, payload.text.length)
        assertTrue(payload.truncated)
    }

    @Test
    fun `the hash is the sha256 of the transmitted text`() {
        val payload = ClipboardPayloads.build("abc")

        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            payload.hash
        )
    }

    @Test
    fun `the hash covers the truncated text`() {
        val payload = ClipboardPayloads.build("x".repeat(300_000))
        val expected = ClipboardPayloads.build("x".repeat(Protocol.MAX_CLIPBOARD_CHARS)).hash

        assertEquals(expected, payload.hash)
    }

    @Test
    fun `the same content is not sent twice`() {
        val payload = ClipboardPayloads.build("same")

        assertTrue(ClipboardPayloads.shouldSend(payload, lastSentHash = null))
        assertFalse(ClipboardPayloads.shouldSend(payload, lastSentHash = payload.hash))
    }

    @Test
    fun `different content is sent`() {
        val payload = ClipboardPayloads.build("new")

        assertTrue(ClipboardPayloads.shouldSend(payload, lastSentHash = "some-other-hash"))
    }

    @Test
    fun `empty text is never sent`() {
        val payload = ClipboardPayloads.build("")

        assertFalse(ClipboardPayloads.shouldSend(payload, lastSentHash = null))
    }

    @Test
    fun `the json payload carries the text hash and truncation flag`() {
        val json = ClipboardPayloads.toJson(ClipboardPayloads.build("hello"))

        assertEquals("hello", json.getString("text"))
        assertFalse(json.getBoolean("truncated"))
        assertTrue(json.getString("hash").isNotEmpty())
    }
}
