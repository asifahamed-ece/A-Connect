package com.aplusconnect.companion.protocol

import org.json.JSONObject

/** Wire constants for the A+ Connect companion protocol. */
object Protocol {
    const val VERSION = 1

    const val TYPE_HELLO = "hello"
    const val TYPE_HELLO_ACK = "hello-ack"
    const val TYPE_HELLO_ERROR = "hello-error"
    const val TYPE_CLIPBOARD_SET = "clipboard-set"
    const val TYPE_PING = "ping"
    const val TYPE_PONG = "pong"
    const val TYPE_BYE = "bye"

    const val MAX_CLIPBOARD_CHARS = 256 * 1024

    const val REASON_BAD_CODE = "bad-code"
    const val REASON_FINGERPRINT_MISMATCH = "fingerprint-mismatch"
    const val REASON_ALREADY_PAIRED = "already-paired"
    const val REASON_NONCE_MISMATCH = "nonce-mismatch"
    const val REASON_PROTOCOL_VERSION = "protocol-version"
}

/** One protocol envelope. */
data class ProtocolMessage(
    val type: String,
    val id: String,
    val ts: Long,
    val payload: JSONObject,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("v", Protocol.VERSION)
        .put("type", type)
        .put("id", id)
        .put("ts", ts)
        .put("payload", payload)

    companion object {
        fun fromJson(json: JSONObject): ProtocolMessage = ProtocolMessage(
            type = json.getString("type"),
            id = json.getString("id"),
            ts = json.getLong("ts"),
            payload = json.optJSONObject("payload") ?: JSONObject(),
        )
    }
}
