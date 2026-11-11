package com.aplusconnect.companion.protocol

import org.json.JSONObject
import java.security.MessageDigest
import java.security.cert.X509Certificate

/** The computer this phone is paired with. */
data class Pairing(val pcId: String, val fingerprint: String)

/** Outcome of verifying the PC's reply to our hello. */
sealed class HandshakeResult {
    data class Success(val sessionId: String, val pcId: String, val fingerprint: String) : HandshakeResult()
    data class Failed(val reason: String) : HandshakeResult()
}

/**
 * The pairing handshake. The phone pins the PC's certificate fingerprint on
 * first contact and refuses any later certificate that differs.
 */
object Handshake {

    fun buildHello(deviceId: String, deviceName: String, code: String, nonce: String): ProtocolMessage =
        ProtocolMessage(
            type = Protocol.TYPE_HELLO,
            id = java.util.UUID.randomUUID().toString(),
            ts = System.currentTimeMillis(),
            payload = JSONObject()
                .put("deviceId", deviceId)
                .put("deviceName", deviceName)
                .put("code", code)
                .put("nonce", nonce)
                .put("protocolVersion", Protocol.VERSION),
        )

    fun verifyHelloAck(
        message: ProtocolMessage,
        pinned: Pairing?,
        expectedNonce: String? = null,
    ): HandshakeResult {
        if (message.type == Protocol.TYPE_HELLO_ERROR) {
            return HandshakeResult.Failed(
                message.payload.optString("reason", Protocol.REASON_BAD_CODE)
            )
        }
        if (message.type != Protocol.TYPE_HELLO_ACK) {
            return HandshakeResult.Failed(Protocol.REASON_PROTOCOL_VERSION)
        }
        if (expectedNonce != null && message.payload.optString("nonce") != expectedNonce) {
            return HandshakeResult.Failed(Protocol.REASON_NONCE_MISMATCH)
        }

        val pcId = message.payload.optString("pcId")
        val fingerprint = message.payload.optString("fingerprint")

        if (pinned != null) {
            if (pcId != pinned.pcId) return HandshakeResult.Failed(Protocol.REASON_ALREADY_PAIRED)
            if (fingerprint != pinned.fingerprint) {
                return HandshakeResult.Failed(Protocol.REASON_FINGERPRINT_MISMATCH)
            }
        }

        return HandshakeResult.Success(
            sessionId = message.payload.optString("sessionId"),
            pcId = pcId,
            fingerprint = fingerprint,
        )
    }

    /** SHA-256 of the DER encoded certificate, uppercase colon separated hex. */
    fun fingerprintOf(certificate: X509Certificate): String = fingerprintOfHex(certificate.encoded)

    fun fingerprintOfHex(der: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(der)
        return digest.joinToString(":") { String.format("%02X", it) }
    }
}
