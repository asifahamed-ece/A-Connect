package com.aplusconnect.companion.file

import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * The walk thread is a daemon: the index is a cache, and a walk that is still
 * running must never keep the phone's service alive.
 */
private fun daemonExecutor(): ExecutorService =
    Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "storage-search").apply { isDaemon = true }
    }

/**
 * A name index of everything under the phone's storage root.
 *
 * Walking a phone's storage is slow. Android's FUSE storage charges a stat per
 * entry, so a phone with ~34,000 files takes tens of seconds the first time.
 * The computer asks for a search on every keystroke, so the walk happens once
 * and every later search filters the remembered names in memory.
 *
 * Three rules keep that cost away from the user:
 *
 *  - The index is warmed as soon as the computer connects, so the first search
 *    is normally answered from a ready index rather than from a walk.
 *  - Every walk runs on [background], and only ever one at a time: a warm-up
 *    and a search that arrive together share the same walk instead of paying
 *    for it twice.
 *  - A stale index is still used. A search answers from it immediately and
 *    refreshes in the background, so typing never waits for a re-walk.
 *
 * The only search that waits is the very first one on a cold service, and it
 * waits for the walk already in flight rather than starting another.
 */
class StorageSearch(
    private val root: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val background: ExecutorService = daemonExecutor(),
    /**
     * How the storage is walked.
     *
     * A parameter rather than a direct call so a test can hold a walk open and
     * prove that a search is answered without it - the whole point of the
     * index. Production always uses [build].
     */
    private val walk: (File, Int) -> List<Hit> = { dir, limit -> build(dir, limit) },
) {

    /** One searchable file or folder, with its storage-relative path. */
    data class Hit(
        val id: String,
        val name: String,
        val size: Long,
        val isDir: Boolean,
    )

    private val lock = Any()
    private var index: List<Hit>? = null
    private var indexedAt = 0L
    private var pending: Future<List<Hit>>? = null

    /** Called whenever a walk finished, for the log. */
    var onRebuild: ((Int) -> Unit)? = null

    /**
     * Names matching [query].
     *
     * An empty query matches nothing rather than everything: the caller shows
     * the folder listing until the user types something.
     */
    fun results(query: String, limit: Int = MAX_RESULTS): List<Hit> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return emptyList()

        val entries = current()
        val matches = ArrayList<Hit>(minOf(limit, 32))
        for (entry in entries) {
            if (entry.name.lowercase().contains(needle)) {
                matches.add(entry)
                if (matches.size >= limit) break
            }
        }
        return matches
    }

    /**
     * Starts the first walk without waiting for it.
     *
     * Called when the computer connects. It returns at once; the walk continues
     * on [background] while the phone stays responsive.
     */
    fun warm() {
        startWalker()
    }

    /** How many entries the current index holds, for the log and the tests. */
    fun size(): Int = synchronized(lock) { index?.size ?: 0 }

    /**
     * The best index available, waiting only when there is nothing at all.
     *
     * A fresh index is returned as is. A stale one is returned too, with a
     * refresh started behind it. Only a null index blocks, and then only for
     * the walk that may already be running.
     */
    private fun current(): List<Hit> {
        synchronized(lock) {
            val cached = index
            if (cached != null) {
                if (now() - indexedAt >= TTL_MS) {
                    startWalker()
                }
                return cached
            }
        }
        return awaitFirstWalk()
    }

    /**
     * The single walk in flight, started if there is none.
     *
     * Guarded so a warm-up and a search that arrive together share one walk
     * rather than queueing two, and so a failed walk clears the slot for the
     * next attempt instead of pinning a dead future forever.
     */
    private fun startWalker(): Future<List<Hit>> = synchronized(lock) {
        pending ?: background.submit<List<Hit>> {
            try {
                val built = walk(root, MAX_ENTRIES)
                synchronized(lock) {
                    index = built
                    indexedAt = now()
                    pending = null
                }
                onRebuild?.invoke(built.size)
                built
            } catch (e: Exception) {
                synchronized(lock) { pending = null }
                // A walk that failed answers nothing rather than pretending
                // the phone is empty; the next search tries again.
                emptyList()
            }
        }.also { pending = it }
    }

    private fun awaitFirstWalk(): List<Hit> {
        val walk = startWalker()
        return try {
            walk.get(COLD_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            // Timed out or interrupted: answer honestly rather than blocking
            // the file channel, which also answers folder listings. The walk
            // keeps running and the next search finds it finished.
            synchronized(lock) { index } ?: emptyList()
        }
    }

    companion object {
        /** Enough to choose from; the desktop draws them in one list. */
        const val MAX_RESULTS = 200

        /** A stale index is still useful; it is refreshed behind a search. */
        const val TTL_MS = 5 * 60 * 1000L

        /** A hard stop so a phone with an enormous storage cannot exhaust memory. */
        const val MAX_ENTRIES = 100_000

        /**
         * How long the first search of a session may wait for the walk.
         *
         * Comfortably longer than a warm phone needs (tens of seconds on the
         * worst device) and shorter than the desktop's own patience, so the
         * user sees either an answer or a clear "did not answer", never a
         * hung window.
         */
        const val COLD_WAIT_MS = 25_000L

        /**
         * Folders whose contents another app owns.
         *
         * `Android/data` and `Android/obb` are unreadable even with all-files
         * access, and walking them would only produce permission errors.
         */
        private val SKIPPED = setOf("Android/data", "Android/obb")

        /** Hidden folders are skipped: caches and thumbnails are noise in search. */
        fun isHidden(name: String): Boolean = name.startsWith(".")

        /**
         * Walks [root] breadth-first and records what it finds.
         *
         * Breadth-first on purpose: the top of the storage is what a user
         * means, so a large tree consumed by the entry cap trims the deepest
         * folders rather than the common ones.
         */
        fun build(root: File, limit: Int): List<Hit> {
            val found = ArrayList<Hit>()
            val queue = ArrayDeque<Pair<File, String>>()
            queue.add(root to "")
            var visited = 0

            while (queue.isNotEmpty() && found.size < limit && visited < limit) {
                val (dir, dirId) = queue.removeFirst()
                val children = try {
                    dir.listFiles()
                } catch (e: Exception) {
                    null
                } ?: continue

                for (child in children) {
                    visited++
                    val name = child.name
                    if (isHidden(name)) continue

                    val id = if (dirId.isEmpty()) name else "$dirId/$name"
                    val isDir = child.isDirectory
                    if (isDir && SKIPPED.contains(id)) continue

                    found.add(
                        Hit(
                            id = id,
                            name = name,
                            size = if (isDir) 0L else child.length(),
                            isDir = isDir,
                        )
                    )
                    if (found.size >= limit) break
                    if (isDir) queue.add(child to id)
                }
            }

            return found
        }
    }
}
