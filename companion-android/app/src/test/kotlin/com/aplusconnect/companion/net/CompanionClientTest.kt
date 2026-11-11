package com.aplusconnect.companion.net

import org.junit.Assert.assertFalse
import org.junit.Test

class CompanionClientTest {

    @Test
    fun `sendClipboard returns false when not connected`() {
        val client = CompanionClient(
            host = "127.0.0.1",
            port = 24443,
            deviceId = "test-device",
            deviceName = "test-name",
            code = "123456",
            pairing = null,
            onPaired = { _, _ -> },
            onReady = {},
            onClosed = {},
        )

        assertFalse(client.isReady)
        val sent = client.sendClipboard("sample text")
        assertFalse(sent)
    }

    @Test
    fun `sendClipboard returns false after close`() {
        val client = CompanionClient(
            host = "127.0.0.1",
            port = 24443,
            deviceId = "test-device",
            deviceName = "test-name",
            code = "123456",
            pairing = null,
            onPaired = { _, _ -> },
            onReady = {},
            onClosed = {},
        )

        client.close()
        assertFalse(client.isReady)
        val sent = client.sendClipboard("sample text")
        assertFalse(sent)
    }
}

