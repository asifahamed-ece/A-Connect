package com.aplusconnect.companion.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HandshakeTest {

    private fun helloAck(
        pcId: String = "pc-1",
        fingerprint: String = "AA:BB:CC",
        nonce: String = "nonce-1",
        type: String = Protocol.TYPE_HELLO_ACK,
    ) = ProtocolMessage(
        type = type,
        id = "ack",
        ts = 1L,
        payload = JSONObject()
            .put("sessionId", "session-1")
            .put("pcId", pcId)
            .put("fingerprint", fingerprint)
            .put("nonce", nonce),
    )

    @Test
    fun `hello carries the device identity code and nonce`() {
        val hello = Handshake.buildHello("phone-1", "OPPO Reno 14 5G", "123456", "nonce-1")

        assertEquals(Protocol.TYPE_HELLO, hello.type)
        assertEquals("phone-1", hello.payload.getString("deviceId"))
        assertEquals("OPPO Reno 14 5G", hello.payload.getString("deviceName"))
        assertEquals("123456", hello.payload.getString("code"))
        assertEquals("nonce-1", hello.payload.getString("nonce"))
        assertEquals(Protocol.VERSION, hello.payload.getInt("protocolVersion"))
    }

    @Test
    fun `first pairing accepts any fingerprint and reports it`() {
        val result = Handshake.verifyHelloAck(helloAck(), pinned = null)

        assertTrue(result is HandshakeResult.Success)
        result as HandshakeResult.Success
        assertEquals("AA:BB:CC", result.fingerprint)
        assertEquals("pc-1", result.pcId)
    }

    @Test
    fun `the paired computer reconnects with the same certificate`() {
        val result = Handshake.verifyHelloAck(
            helloAck(),
            pinned = Pairing("pc-1", "AA:BB:CC")
        )

        assertTrue(result is HandshakeResult.Success)
    }

    @Test
    fun `a changed certificate is refused`() {
        val result = Handshake.verifyHelloAck(
            helloAck(fingerprint = "11:22:33"),
            pinned = Pairing("pc-1", "AA:BB:CC")
        )

        assertEquals(
            Protocol.REASON_FINGERPRINT_MISMATCH,
            (result as HandshakeResult.Failed).reason
        )
    }

    @Test
    fun `a second pairing is refused`() {
        val result = Handshake.verifyHelloAck(
            helloAck(pcId = "pc-2", fingerprint = "11:22:33"),
            pinned = Pairing("pc-1", "AA:BB:CC")
        )

        assertEquals(
            Protocol.REASON_ALREADY_PAIRED,
            (result as HandshakeResult.Failed).reason
        )
    }

    @Test
    fun `a nonce that does not match the hello is refused`() {
        val result = Handshake.verifyHelloAck(
            helloAck(nonce = "other-nonce"),
            pinned = Pairing("pc-1", "AA:BB:CC"),
            expectedNonce = "nonce-1"
        )

        assertEquals(
            Protocol.REASON_NONCE_MISMATCH,
            (result as HandshakeResult.Failed).reason
        )
    }

    @Test
    fun `an error reply carries its reason`() {
        val result = Handshake.verifyHelloAck(
            helloAck(type = Protocol.TYPE_HELLO_ERROR),
            pinned = null
        )

        assertEquals(
            Protocol.REASON_BAD_CODE,
            (result as HandshakeResult.Failed).reason
        )
    }

    @Test
    fun `a fingerprint is the sha256 of the certificate in uppercase colon separated hex`() {
        val fingerprint = Handshake.fingerprintOfHex(ByteArray(3) { 0x0a.toByte() })

        assertEquals(
            "6A:3C:F5:19:23:54:F7:16:15:AC:51:03:4B:3E:97:C2:0E:DA:99:" +
                "64:3F:CA:F5:BB:E6:D4:1A:D5:9B:D1:21:67",
            fingerprint
        )
    }
}
