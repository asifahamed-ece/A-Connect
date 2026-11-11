package com.aplusconnect.companion.protocol

import org.json.JSONObject

/** Wire constants for the A+ Connect companion protocol. */
object Protocol {
    const val VERSION = 1

    const val TYPE_HELLO = "hello"
    const val TYPE_HELLO_ACK = "hello-ack"
    const val TYPE_HELLO_ERROR = "hello-error"
    const val TYPE_CLIPBOARD_SET = "clipboard-set"
    const val TYPE_FILE_START = "file-start"
    const val TYPE_FILE_CHUNK = "file-chunk"
    const val TYPE_FILE_DONE = "file-done"
    const val TYPE_PING = "ping"
    const val TYPE_PONG = "pong"
    const val TYPE_BYE = "bye"

    /**
     * The clipboard cap is a byte budget, not a character budget: 256 KiB of
     * UTF-8. A character cap would let a 256 KiB payload of control characters
     * escape to six bytes each and blow past the frame limit.
     */
    const val MAX_CLIPBOARD_BYTES = 256 * 1024

    const val REASON_BAD_CODE = "bad-code"
    const val REASON_BAD_FINGERPRINT = "bad-fingerprint"
    const val REASON_FINGERPRINT_MISMATCH = "fingerprint-mismatch"
    const val REASON_ALREADY_PAIRED = "already-paired"
    const val REASON_NONCE_MISMATCH = "nonce-mismatch"
    const val REASON_PROTOCOL_VERSION = "protocol-version"

    /** Only the current major version is understood. */
    fun isSupportedVersion(version: Int): Boolean = version == VERSION
}

/** One protocol envelope. */
data class ProtocolMessage(
    val type: String,
    val id: String,
    val ts: Long,
    val payload: JSONObject,
    val version: Int = Protocol.VERSION,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("v", version)
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
            version = json.optInt("v", Protocol.VERSION),
        )
    }
}
