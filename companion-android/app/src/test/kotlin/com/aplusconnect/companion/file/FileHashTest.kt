package com.aplusconnect.companion.file

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FileHashTest {

    @Test
    fun `the hex formatting matches what the computer's cache keys by`() {
        // SHA256("hello") is a cross-language constant: node's crypto.digest
        // prints exactly this, and the desktop uses it as an opaque key. A
        // signed-byte slip here would make every preview a cache miss and
        // nobody would see why.
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            FileHash.hex(java.security.MessageDigest.getInstance("SHA-256").digest("hello".toByteArray(Charsets.UTF_8))),
        )
        // A byte with its sign bit set must print as two hex digits, not "-1".
        assertEquals("ff00", FileHash.hex(byteArrayOf(0xFF.toByte(), 0x00)))
    }

    @Test
    fun `hashing a real file matches the digest of its bytes`() {
        val file = File.createTempFile("hash-test", ".bin")
        try {
            // Two chunks' worth, so the read loop is exercised more than once.
            val payload = ByteArray(64 * 1024 * 2 + 17) { (it % 251).toByte() }
            file.writeBytes(payload)

            val digest = FileHash.sha256Of(file)

            assertNotNull(digest)
            // Independently computed with the JVM's own hex formatting: the
            // bytes agree even if the string formatting is this class's own.
            val expected = java.security.MessageDigest.getInstance("SHA-256").digest(payload)
            assertEquals(expected.joinToString("") { "%02x".format(it) }, digest!!)
            assertTrue(digest == digest.lowercase())
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a file that cannot be read answers null rather than throwing`() {
        // A directory is readable as a path but not as a stream to hash.
        assertEquals(null, FileHash.sha256Of(File("/dev/null_does_not_exist_anywhere")))
    }
}
