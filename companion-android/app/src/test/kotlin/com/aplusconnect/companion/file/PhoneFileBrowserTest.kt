package com.aplusconnect.companion.file

import com.aplusconnect.companion.protocol.FeaturePayloads.FileEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The parts of the phone's file browser that can be tested without a device.
 *
 * The listing itself is a directory read, which needs an Android runtime this
 * project does not have (no Robolectric). What is testable, and what actually
 * goes wrong in practice, is how a row becomes an entry, how the rows are
 * ordered, and whether a path the computer sends can escape the storage root.
 */
class PhoneFileBrowserTest {

    private fun tempRoot(): File =
        Files.createTempDirectory("phone-storage").toFile().also { it.deleteOnExit() }

    @Test
    fun `a file entry keeps its size and mime type`() {
        val entry = PhoneFileBrowser.row(
            name = "photo.jpg",
            size = 1024L,
            mime = "image/jpeg",
            id = "DCIM/photo.jpg",
        )

        assertEquals("photo.jpg", entry.name)
        assertEquals(1024L, entry.size)
        assertEquals("image/jpeg", entry.mime)
        assertFalse(entry.isDir)
        // The relative path is what the computer sends back to ask for this
        // file; without it a listing is a list of names nobody can open.
        assertEquals("DCIM/photo.jpg", entry.id)
    }

    @Test
    fun `a folder entry has no size`() {
        val entry = PhoneFileBrowser.row(
            id = "Pictures",
            name = "Pictures",
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
        assertEquals("unnamed", PhoneFileBrowser.row(null, null, 0L, null).name)
        assertEquals("unnamed", PhoneFileBrowser.row(null, "", 0L, null).name)
    }

    @Test
    fun `folders sort before files and files sort by name`() {
        val sorted = PhoneFileBrowser.sortEntries(
            listOf(
                FileEntry("zeta.txt", "zeta.txt", 10L, false, "text/plain"),
                FileEntry("Music", "Music", 0L, true, ""),
                FileEntry("alpha.txt", "alpha.txt", 20L, false, "text/plain"),
                FileEntry("Download", "Download", 0L, true, ""),
            )
        )

        assertEquals(
            listOf("Download", "Music", "alpha.txt", "zeta.txt"),
            sorted.map { it.name },
        )
    }

    // ---- paths the computer sends ----------------------------------------

    @Test
    fun `a relative path resolves inside the root`() {
        val root = tempRoot()
        val nested = File(root, "Download/notes.txt")
        nested.parentFile!!.mkdirs()
        nested.writeText("hi")

        val resolved = PhoneFileBrowser.within(root, "Download/notes.txt")
        assertNotNull("a plain relative path was refused", resolved)
        assertEquals(nested.canonicalPath, resolved!!.canonicalPath)
    }

    @Test
    fun `the empty path is the root itself`() {
        val root = tempRoot()
        assertEquals(
            root.canonicalPath,
            PhoneFileBrowser.within(root, "")!!.canonicalPath,
        )
        assertEquals(
            root.canonicalPath,
            PhoneFileBrowser.within(root, "/")!!.canonicalPath,
        )
    }

    @Test
    fun `a path with dot dot cannot climb out of the root`() {
        val root = tempRoot()
        // A sibling of the root: the classic `../../etc` escape.
        val outside = File(root.parentFile, "outside.txt")
        outside.writeText("secret")

        assertNull(
            "a path climbing out of the storage root was accepted",
            PhoneFileBrowser.within(root, "../outside.txt"),
        )
        assertNull(
            PhoneFileBrowser.within(root, "Download/../../outside.txt"),
        )
    }

    @Test
    fun `an absolute path is read as relative to the root`() {
        val root = tempRoot()
        val file = File(root, "hello.txt")
        file.writeText("hi")

        // Leading slashes are stripped rather than honoured: the computer only
        // ever speaks storage-relative paths.
        assertEquals(
            file.canonicalPath,
            PhoneFileBrowser.within(root, "/hello.txt")!!.canonicalPath,
        )
    }

    @Test
    fun `the id of a child is its path below the root`() {
        val root = tempRoot()
        val child = File(root, "Download/notes.txt")
        child.parentFile!!.mkdirs()
        child.writeText("hi")

        assertEquals("Download/notes.txt", PhoneFileBrowser.idOf(root, child))
        assertEquals("", PhoneFileBrowser.idOf(root, root))
    }

    @Test
    fun `the browser has no way to change anything on the phone`() {
        // Browsing is read-only by design: the desktop may look and may ask
        // for a copy, but nothing here may delete, rename, create or write.
        // This is a source guard because those are platform calls, and this
        // project has no Robolectric to intercept them.
        val forbidden = listOf(
            "deleteRecursively",
            ".delete()",
            "renameTo",
            "createNewFile",
            ".mkdir()",
            ".mkdirs()",
            "FileOutputStream",
            "openOutputStream",
            "setWritable",
            "setLastModified",
        )
        listOf(
            "src/main/kotlin/com/aplusconnect/companion/file/PhoneFileBrowser.kt",
            "src/main/kotlin/com/aplusconnect/companion/file/StorageSearch.kt",
        ).forEach { relative ->
            val source = source(relative).readText()
            forbidden.forEach { call ->
                assertFalse(
                    "$relative must stay read-only, but it mentions $call",
                    source.contains(call),
                )
            }
        }
    }

    private fun source(relative: String): File {
        var dir = File("").absoluteFile
        while (dir.parentFile != null) {
            val candidate = File(dir, relative)
            if (candidate.exists()) return candidate
            dir = dir.parentFile ?: break
        }
        throw AssertionError("could not find $relative from ${File("").absolutePath}")
    }
}