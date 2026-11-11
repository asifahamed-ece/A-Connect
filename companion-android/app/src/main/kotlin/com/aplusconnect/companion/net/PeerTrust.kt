package com.aplusconnect.companion.net

import android.util.Log
import com.aplusconnect.companion.protocol.Handshake
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * Trust manager that pins the computer's certificate.
 *
 * On the first connection the fingerprint is remembered (trust on first use,
 * confirmed by the 6 digit pairing code the user reads off the PC). After
 * that only that exact certificate is accepted, so nobody else on the network
 * can impersonate the computer.
 */
class PeerTrust(
    private val expectedFingerprint: String?,
    private val observed: (String) -> Unit,
) : X509TrustManager {

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("the client certificate is not expected")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val certificate = chain?.firstOrNull() ?: throw CertificateException("no certificate")
        val fingerprint = Handshake.fingerprintOf(certificate)

        if (expectedFingerprint == null) {
            Log.i(TAG, "first contact, remembering the certificate fingerprint")
            observed(fingerprint)
            return
        }

        if (fingerprint != expectedFingerprint) {
            throw CertificateException(
                "the computer's certificate changed: expected $expectedFingerprint, got $fingerprint"
            )
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    companion object {
        const val TAG = "AplusConnect"
    }
}

/** The fingerprint of a certificate, as the phone stores it. */
fun fingerprintOf(certificate: X509Certificate): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
    return digest.joinToString(":") { String.format("%02X", it) }
}
