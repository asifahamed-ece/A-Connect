package com.aplusconnect.companion.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class IncomingFileTest {

    private fun tempFolder(): File = Files.createTempDirectory("incoming-test").toFile()

    @Test
    fun `the received file is handed over with its content`() {
        val folder = tempFolder()
        val incoming = IncomingFile(folder)

        assertTrue(incoming.start("id-1", "photo.jpg", 5, "image/jpeg"))
        incoming.chunk("id-1", "hello".toByteArray())

        val received = incoming.finish("id-1")

        assertTrue("the file was not handed over", received != null)
        assertEquals("hello", received!!.file.readText())
        assertEquals("photo.jpg", received.name)
    }

    @Test
    fun `the file survives until the caller has moved it`() {
        // The bug this pins: the assembled file was deleted before it was
        // handed over, so nothing was ever saved.
        val incoming = IncomingFile(tempFolder())
        incoming.start("id-1", "note.txt", 2, "text/plain")
        incoming.chunk("id-1", "ok".toByteArray())

        val received = incoming.finish("id-1")

        assertTrue(received != null)
        assertTrue(received!!.file.exists())
    }

    @Test
    fun `chunks that belong to another transfer are ignored`() {
        val incoming = IncomingFile(tempFolder())
        incoming.start("id-1", "a.txt", 1, "text/plain")
        incoming.chunk("id-2", "wrong".toByteArray())

        // Nothing of this transfer arrived, so there is nothing to hand over.
        assertNull(incoming.finish("id-1"))
    }

    @Test
    fun `finishing an unknown transfer gives nothing`() {
        val incoming = IncomingFile(tempFolder())
        incoming.start("id-1", "a.txt", 1, "text/plain")

        assertNull(incoming.finish("id-2"))
    }

    @Test
    fun `a transfer with no content is not handed over`() {
        val incoming = IncomingFile(tempFolder())
        incoming.start("id-1", "empty.txt", 0, "text/plain")

        assertNull(incoming.finish("id-1"))
    }

    @Test
    fun `only one transfer runs at a time`() {
        val incoming = IncomingFile(tempFolder())

        assertTrue(incoming.start("id-1", "a.txt", 1, "text/plain"))
        assertFalse(incoming.start("id-2", "b.txt", 1, "text/plain"))
    }

    @Test
    fun `reset throws the partial file away`() {
        val folder = tempFolder()
        val incoming = IncomingFile(folder)
        incoming.start("id-1", "a.txt", 5, "text/plain")
        incoming.chunk("id-1", "half".toByteArray())

        incoming.reset()

        assertTrue(folder.listFiles()?.isEmpty() ?: true)
    }
}
