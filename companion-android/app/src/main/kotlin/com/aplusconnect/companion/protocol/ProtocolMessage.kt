package com.aplusconnect.companion.protocol

import org.json.JSONObject

/** Wire constants for the A+ Connect companion protocol. */
object Protocol {
    const val VERSION = 2

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

    // The four features that ride the same channel as the clipboard.
    const val TYPE_NOTIFICATION = "notification"
    const val TYPE_NOTIFICATION_REMOVED = "notification-removed"
    const val TYPE_NOTIFICATION_ACTION = "notification-action"
    const val TYPE_MEDIA_STATE = "media-state"
    const val TYPE_MEDIA_COMMAND = "media-command"
    const val TYPE_FILE_LIST_REQUEST = "file-list-request"
    const val TYPE_FILE_LIST = "file-list"
    const val TYPE_FILE_SEND_REQUEST = "file-send-request"

    /**
     * A search across the whole phone, rather than one folder.
     *
     * The answer is an ordinary `file-list` whose `path` is [searchKey] of the
     * query, so the desktop matches it with the same map it uses for folders
     * and needs no separate parser.
     */
    const val TYPE_FILE_SEARCH_REQUEST = "file-search-request"
    const val SEARCH_KEY_PREFIX = "search:"

    fun searchKey(query: String): String = SEARCH_KEY_PREFIX + query
    const val TYPE_MIRROR_START = "mirror-start"
    const val TYPE_MIRROR_STOP = "mirror-stop"

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
