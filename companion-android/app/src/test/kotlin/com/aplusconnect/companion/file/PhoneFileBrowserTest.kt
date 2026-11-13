package com.aplusconnect.companion.file

import com.aplusconnect.companion.protocol.FeaturePayloads.FileEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The parts of the phone's file browser that can be tested without a device.
 *
 * The listing itself is a ContentResolver call, which needs an Android
 * runtime this project does not have (no Robolectric). What is testable, and
 * what actually goes wrong in practice, is how a row becomes an entry and how
 * the rows are ordered.
 */
class PhoneFileBrowserTest {

    @Test
    fun `a file entry keeps its size and mime type`() {
        val entry = PhoneFileBrowser.entryFor(
            displayName = "photo.jpg",
            size = 1024L,
            mime = "image/jpeg",
            id = "doc:photo",
        )

        assertEquals("photo.jpg", entry.name)
        assertEquals(1024L, entry.size)
        assertEquals("image/jpeg", entry.mime)
        assertFalse(entry.isDir)
        // The document id is what the computer sends back to ask for this
        // file; without it a listing is a list of names nobody can open.
        assertEquals("doc:photo", entry.id)
    }

    @Test
    fun `a folder entry has no size`() {
        val entry = PhoneFileBrowser.entryFor(
            id = "doc:pictures",
            displayName = "Pictures",
            size = 4096L,
            mime = null,
            isDir = true,
        )

        assertEquals("Pictures", entry.name)
        assertTrue(entry.isDir)
        // A folder's reported size is meaningless, and the desktop would draw
        // it as a byte count next to the name.
        assertEquals(0L, entry.size)
        assertEquals("", entry.mime)
    }

    @Test
    fun `a nameless document gets a readable name`() {
        assertEquals("unnamed", PhoneFileBrowser.entryFor(null, null, 0L, null).name)
        assertEquals("unnamed", PhoneFileBrowser.entryFor(null, "", 0L, null).name)
    }

    @Test
    fun `folders sort before files and files sort by name`() {
        val sorted = PhoneFileBrowser.sortEntries(
            listOf(
                FileEntry("doc:1", "zeta.txt", 10L, false, "text/plain"),
                FileEntry("doc:2", "Music", 0L, true, ""),
                FileEntry("doc:3", "alpha.txt", 20L, false, "text/plain"),
                FileEntry("doc:4", "Download", 0L, true, ""),
            )
        )

        assertEquals(
            listOf("Download", "Music", "alpha.txt", "zeta.txt"),
            sorted.map { it.name },
        )
    }

    @Test
    fun `the browser has no way to change anything on the phone`() {
        // Browsing is read-only by design: the desktop may look and may ask
        // for a copy, but nothing here may delete, rename or create a
        // document. This is a source guard because the calls are platform
        // calls, and this project has no Robolectric to intercept them.
        val source = browserSource().readText()
        listOf("deleteDocument", "renameDocument", "createDocument", "moveDocument")
            .forEach { forbidden ->
                assertFalse(
                    "PhoneFileBrowser must stay read-only, but it mentions $forbidden",
                    source.contains(forbidden),
                )
            }
    }

    private fun browserSource(): File {
        var dir = File("").absoluteFile
        while (dir.parentFile != null) {
            val candidate = File(
                dir,
                "src/main/kotlin/com/aplusconnect/companion/file/PhoneFileBrowser.kt"
            )
            if (candidate.exists()) return candidate
            dir = dir.parentFile ?: break
        }
        throw AssertionError("could not find PhoneFileBrowser.kt from ${File("").absolutePath}")
    }
}
