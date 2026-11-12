package com.aplusconnect.companion.protocol

import org.json.JSONObject
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

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

    /** A fingerprint is 32 colon separated uppercase hex bytes. */
    private val FINGERPRINT = Regex("^([0-9A-F]{2}:){31}[0-9A-F]{2}$")

    fun buildHello(
        deviceId: String,
        deviceName: String,
        code: String,
        nonce: String,
        secret: ByteArray? = null,
        proof: String? = null,
    ): ProtocolMessage {
        val payload = JSONObject()
            .put("deviceId", deviceId)
            .put("deviceName", deviceName)
            .put("code", code)
            .put("nonce", nonce)
            .put("protocolVersion", Protocol.VERSION)

        // The first contact carries the secret; every later one carries a proof
        // that only the holder of the secret can produce.
        if (secret != null) {
            payload.put("secret", java.util.Base64.getEncoder().encodeToString(secret))
        }
        if (proof != null) {
            payload.put("proof", proof)
        }

        return ProtocolMessage(
            type = Protocol.TYPE_HELLO,
            id = java.util.UUID.randomUUID().toString(),
            ts = System.currentTimeMillis(),
            payload = payload,
        )
    }

    /**
     * The proof a returning device presents: an HMAC over its id and the
     * nonce of this connection. The computer stores the secret from the first
     * pairing and can therefore tell the real phone from anyone who merely
     * knows its id.
     */
    fun proofFor(secret: ByteArray, deviceId: String, nonce: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal("$deviceId:$nonce".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun verifyProof(secret: ByteArray, deviceId: String, nonce: String, proof: String): Boolean {
        val expected = proofFor(secret, deviceId, nonce)
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            proof.toByteArray(Charsets.UTF_8),
        )
    }

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
        if (!Protocol.isSupportedVersion(message.version)) {
            return HandshakeResult.Failed(Protocol.REASON_PROTOCOL_VERSION)
        }
        if (expectedNonce != null && message.payload.optString("nonce") != expectedNonce) {
            return HandshakeResult.Failed(Protocol.REASON_NONCE_MISMATCH)
        }

        val pcId = message.payload.optString("pcId")
        val fingerprint = message.payload.optString("fingerprint")

        // A missing or malformed fingerprint must never become the pin: an
        // empty pin would wedge the phone, refusing every later connection.
        if (pcId.isEmpty()) {
            return HandshakeResult.Failed(Protocol.REASON_BAD_FINGERPRINT)
        }
        if (!FINGERPRINT.matches(fingerprint)) {
            return HandshakeResult.Failed(Protocol.REASON_BAD_FINGERPRINT)
        }

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
