package com.aplusconnect.companion.file

import java.security.MessageDigest

/**
 * The SHA256 of a file, the way the preview cache keys by it.
 *
 * The computer asks for this before it downloads anything, so a file the user
 * has looked at before opens from its cache instead of crossing the network
 * again. That only works if the string is a real, lowercase, hex SHA256: the
 * computer never re-derives it, it just uses it as an opaque key, so a
 * formatting slip here is invisible until two different builds disagree.
 *
 * The file is read in chunks rather than loaded whole, because the same call
 * runs on a 2 GB video as on a note.
 */
object FileHash {

    /** The lowercase hex SHA256 of [file], or null if it cannot be read. */
    fun sha256Of(file: java.io.File): String? = try {
        val md = MessageDigest.getInstance("SHA-256")
        java.io.FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                md.update(buffer, 0, read)
            }
        }
        hex(md.digest())
    } catch (e: Exception) {
        null
    }

    /**
     * Lowercase hex, two digits per byte.
     *
     * `%02x` on a signed byte is the whole trick: a byte of 0xFF must print
     * "ff", not "-1". A unit test pins this against a known digest so the
     * formatting cannot drift from what the computer expects.
     */
    fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            append(HEX_DIGITS[v shr 4])
            append(HEX_DIGITS[v and 0x0F])
        }
    }

    private val HEX_DIGITS = "0123456789abcdef".toCharArray()
}
