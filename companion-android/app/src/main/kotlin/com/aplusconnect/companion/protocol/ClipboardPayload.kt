package com.aplusconnect.companion.protocol

import org.json.JSONObject
import java.security.MessageDigest

/** What actually crosses the wire for one clipboard change. */
data class ClipboardPayload(
    val text: String,
    val hash: String,
    val truncated: Boolean,
)

/** The rules both sides apply, so the two can never disagree. */
object ClipboardPayloads {

    fun build(text: String): ClipboardPayload {
        val truncated = text.length > Protocol.MAX_CLIPBOARD_CHARS
        val body = if (truncated) text.substring(0, Protocol.MAX_CLIPBOARD_CHARS) else text
        return ClipboardPayload(body, sha256(body), truncated)
    }

    /** False when this content is empty or is the same content we already sent. */
    fun shouldSend(payload: ClipboardPayload, lastSentHash: String?): Boolean {
        if (payload.text.isEmpty()) return false
        return payload.hash != lastSentHash
    }

    fun toJson(payload: ClipboardPayload): JSONObject = JSONObject()
        .put("text", payload.text)
        .put("hash", payload.hash)
        .put("truncated", payload.truncated)

    fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
