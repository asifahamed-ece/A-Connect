package com.aplusconnect.companion.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        val outcome = incoming.finish("id-1")
        assertTrue("expected complete outcome", outcome is IncomingFile.Outcome.Complete)
        val received = (outcome as IncomingFile.Outcome.Complete).received
        assertEquals("hello", received.file.readText())
        assertEquals("photo.jpg", received.name)
    }

    @Test
    fun `mismatched size is rejected as broken`() {
        val incoming = IncomingFile(tempFolder())
        incoming.start("id-1", "app.apk", 100, "application/vnd.android.package-archive")
        incoming.chunk("id-1", "short".toByteArray())

        val outcome = incoming.finish("id-1")
        assertTrue("expected broken outcome for size mismatch", outcome is IncomingFile.Outcome.Broken)
    }

    @Test
    fun `cancel deletes the partial file`() {
        val folder = tempFolder()
        val incoming = IncomingFile(folder)
        incoming.start("id-1", "app.apk", 100, "application/vnd.android.package-archive")
        incoming.chunk("id-1", "partial data".toByteArray())

        assertTrue(incoming.cancel("id-1"))
        assertTrue(folder.listFiles()?.isEmpty() ?: true)
    }
}
