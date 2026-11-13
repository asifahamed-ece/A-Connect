package com.aplusconnect.companion.file

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The phone's search index.
 *
 * The desktop searches on every keystroke, so what matters is that the walk
 * finds what a user means, does not wander into folders that cannot be read,
 * and - the part that decides whether search feels instant - does not walk the
 * storage on every search or make a search wait for one it did not need.
 */
class StorageSearchTest {

    private fun tempRoot(): File =
        Files.createTempDirectory("phone-search").toFile().also { it.deleteOnExit() }

    private fun touch(root: File, path: String, content: String = "x"): File {
        val file = File(root, path)
        file.parentFile!!.mkdirs()
        file.writeText(content)
        return file
    }

    @Test
    fun `a file in a nested folder is found by part of its name`() {
        val root = tempRoot()
        touch(root, "Download/books/Dune.epub")
        touch(root, "Download/books/Notes.txt")
        touch(root, "Pictures/holiday.jpg")

        val search = StorageSearch(root)
        val hits = search.results("dune")

        assertEquals(1, hits.size)
        assertEquals("Dune.epub", hits[0].name)
        assertEquals("Download/books/Dune.epub", hits[0].id)
        assertFalse(hits[0].isDir)
    }

    @Test
    fun `matching ignores case and matches folders too`() {
        val root = tempRoot()
        touch(root, "Documents/tax/2024.pdf")

        val hits = StorageSearch(root).results("TAX")

        assertEquals(1, hits.size)
        assertEquals("tax", hits[0].name)
        assertTrue(hits[0].isDir)
    }

    @Test
    fun `an empty query matches nothing`() {
        val root = tempRoot()
        touch(root, "a.txt")

        // Anything else would make the desktop draw the whole phone on an
        // empty search box.
        assertTrue(StorageSearch(root).results("  ").isEmpty())
    }

    @Test
    fun `hidden folders are not searched`() {
        val root = tempRoot()
        touch(root, ".thumbnails/cache.jpg")
        touch(root, "visible.jpg")

        val hits = StorageSearch(root).results("cache")

        assertTrue("a hidden folder leaked into the results", hits.isEmpty())
    }

    @Test
    fun `folders another app owns are not walked`() {
        val root = tempRoot()
        touch(root, "Android/data/com.example/cache/blob.bin")
        touch(root, "Android/obb/com.example/main.obb")
        touch(root, "Android/media/com.example/keep.txt")

        val search = StorageSearch(root)

        assertTrue("Android/data was walked", search.results("blob").isEmpty())
        assertTrue("Android/obb was walked", search.results("main").isEmpty())
        // Android/media is readable shared storage and must still be found.
        assertEquals(1, search.results("keep").size)
    }

    @Test
    fun `results stop at the limit`() {
        val root = tempRoot()
        repeat(30) { touch(root, "lots/match-$it.txt") }

        assertEquals(5, StorageSearch(root).results("match", limit = 5).size)
    }

    // ---- the walk is amortised, never paid per keystroke -------------------

    @Test
    fun `a second search filters, it does not walk again`() {
        val root = tempRoot()
        touch(root, "first.txt")

        var clock = 1_000L
        var walks = 0
        val search = StorageSearch(root, now = { clock })
        search.onRebuild = { walks++ }

        search.results("first")
        assertEquals(1, walks)

        clock += 1_000
        search.results("first")
        assertEquals("a search moments later re-walked the storage", 1, walks)
    }

    @Test
    fun `a stale index answers at once, and refreshes afterwards`() {
        val root = tempRoot()
        touch(root, "first.txt")

        // A walk that takes a real phone's time. If a search waits for this,
        // the test below takes seconds instead of milliseconds and fails its
        // own deadline - which is exactly the bug this guards.
        val slowWalk: (File, Int) -> List<StorageSearch.Hit> = { dir, limit ->
            Thread.sleep(700)
            StorageSearch.build(dir, limit)
        }

        var clock = 1_000L
        var walks = 0
        val search = StorageSearch(root, now = { clock }, walk = slowWalk)
        search.onRebuild = { walks++ }

        // First search: no index yet, so it waits for the walk by definition.
        search.results("first")
        assertEquals(1, walks)

        // A file saved after the index aged out must become findable, but the
        // search that notices the age must not pay for the walk itself.
        touch(root, "second.txt")
        clock += StorageSearch.TTL_MS + 1

        val startedAt = System.currentTimeMillis()
        val stale = search.results("first")
        val waited = System.currentTimeMillis() - startedAt

        assertEquals("the stale index stopped answering", 1, stale.size)
        assertTrue(
            "a search waited ${waited}ms for the refresh; it must answer from the stale index",
            waited < 400,
        )

        // The refresh still happened, on the index's own thread.
        val deadline = System.currentTimeMillis() + 5_000
        while (search.results("second").isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertEquals("the index was never refreshed", 1, search.results("second").size)
    }

    @Test
    fun `a cold search waits for the one walk already running`() {
        val root = tempRoot()
        touch(root, "only.txt")

        var walks = 0
        val countingWalk: (File, Int) -> List<StorageSearch.Hit> = { dir, limit ->
            walks++
            StorageSearch.build(dir, limit)
        }

        val search = StorageSearch(root, walk = countingWalk)
        // The connection warms the index; the first keystroke arrives while
        // that walk is still going. They must share it, not queue a second.
        search.warm()
        Thread.sleep(50)

        assertEquals(1, search.results("only").size)
        assertEquals("the warm-up and the first search walked twice", 1, walks)
    }

    @Test
    fun `warming builds the index before anyone searches`() {
        val root = tempRoot()
        touch(root, "ready.txt")

        var walks = 0
        val search = StorageSearch(root)
        search.onRebuild = { walks++ }
        search.warm()

        // The warm-up runs on the index's thread; wait for it rather than
        // assuming it finished in time.
        val deadline = System.currentTimeMillis() + 5_000
        while (search.size() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }

        assertEquals(1, search.size())
        assertEquals(1, walks)
        // Already indexed, so this must not trigger a second walk.
        assertEquals(1, search.results("ready").size)
        assertEquals("a search after the warm-up walked again", 1, walks)
    }
}
