package com.aplusconnect.companion.protocol

import org.json.JSONObject

/**
 * What the computer shows as a QR code for the phone to scan.
 *
 * It carries everything needed to pair in one scan - the address, the port,
 * the one time code and the certificate fingerprint - so the user never types
 * an address, and the phone can pin the certificate straight away.
 */
data class PairingInfo(
    val pcName: String,
    val host: String,
    val port: Int,
    val code: String,
    val fingerprint: String,
    val pcId: String = "",
)

object PairingPayload {

    const val TYPE = "aplus-pairing"
    const val VERSION = 1

    fun toJson(info: PairingInfo): JSONObject = JSONObject()
        .put("v", VERSION)
        .put("type", TYPE)
        .put("pcName", info.pcName)
        .put("host", info.host)
        .put("port", info.port)
        .put("code", info.code)
        .put("fingerprint", info.fingerprint)
        .put("pcId", info.pcId)

    /** Parses a scanned payload, or null when it is not one of ours. */
    fun fromJson(text: String): PairingInfo? {
        return try {
            val json = JSONObject(text)
            if (json.optString("type") != TYPE) return null
            if (json.optInt("v") != VERSION) return null

            val host = json.optString("host")
            val port = json.optInt("port")
            val code = json.optString("code")
            if (host.isEmpty() || port <= 0 || code.length != 6) return null

            PairingInfo(
                pcName = json.optString("pcName", "Computer"),
                host = host,
                port = port,
                code = code,
                fingerprint = json.optString("fingerprint"),
                pcId = json.optString("pcId"),
            )
        } catch (e: Exception) {
            null
        }
    }
}
