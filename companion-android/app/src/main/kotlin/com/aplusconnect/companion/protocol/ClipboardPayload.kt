package com.aplusconnect.companion.protocol

import org.json.JSONObject
import java.security.MessageDigest

/** What actually crosses the wire for one clipboard change. */
data class ClipboardPayload(
    val text: String,
    val hash: String,
    val truncated: Boolean,
)

/**
 * The rules both sides apply, so the phone and the PC cannot disagree about
 * what may cross the wire: the same byte budget, the same code point safe
 * truncation, the same normalisation of unpaired surrogates, the same hash.
 */
object ClipboardPayloads {

    fun build(text: String): ClipboardPayload {
        val normalized = normalize(text)
        val body = truncateToBytes(normalized, Protocol.MAX_CLIPBOARD_BYTES)
        return ClipboardPayload(body, sha256(body), body.length != normalized.length)
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

    /**
     * Cuts the text to a byte budget without splitting a code point. The JVM
     * and Node encode unpaired surrogates differently, so both sides replace
     * them with U+FFFD first and then agree on the hash.
     */
    fun truncateToBytes(text: String, maxBytes: Int): String {
        var bytes = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val width = Character.charCount(codePoint)
            val size = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
            if (bytes + size > maxBytes) break
            bytes += size
            index += width
        }
        return text.substring(0, index)
    }

    /** Replaces unpaired surrogates with U+FFFD, exactly as Node does. */
    fun normalize(text: String): String {
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (char.isHighSurrogate()) {
                val paired = index + 1 < text.length && text[index + 1].isLowSurrogate()
                if (paired) {
                    out.append(char).append(text[index + 1])
                    index += 2
                    continue
                }
                out.append('�')
                index += 1
                continue
            }
            if (char.isLowSurrogate()) {
                out.append('�')
                index += 1
                continue
            }
            out.append(char)
            index += 1
        }
        return out.toString()
    }
}
