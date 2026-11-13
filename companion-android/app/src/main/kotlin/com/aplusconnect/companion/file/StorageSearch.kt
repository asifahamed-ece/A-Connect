package com.aplusconnect.companion.file

import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

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
 *  - A change this app made itself is not stale-but-useful: [invalidate] drops
 *    the index, so the next search cannot answer with a file list that is
 *    missing the file that just arrived.
 *
 * The only search that waits is one with nothing usable to answer from, and it
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

    /**
     * How many times the caller has declared the storage out of date.
     *
     * A walk records the generation it started at. If the generation moved on
     * while it ran - the user received another file, then another - the walk
     * is finished but already stale, and running it again is the only honest
     * answer.
     */
    private var generation = 0L
    private var walkedGeneration = -1L

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

    /**
     * Says the storage has changed, without waiting for a walk.
     *
     * Called when this app writes a file - a transfer from the computer - so
     * the file is findable at the next search instead of up to [TTL_MS] later.
     * The index is dropped rather than kept: an answer from names that are
     * known to be out of date is exactly the bug this exists to prevent, and
     * the replacement walk is already running by the time the search gets here.
     */
    fun invalidate() {
        synchronized(lock) {
            generation += 1
            index = null
        }
        startWalker()
    }

    /**
     * Re-reads the storage and waits for it.
     *
     * The Refresh button's request: the user has asked to see what is there
     * now, so answering from an index of any age would be a lie. Bounded like
     * every other wait, because a search may not hang forever.
     */
    fun refreshNow() {
        synchronized(lock) { generation += 1 }
        awaitFreshWalk()
    }

    /** How many entries the current index holds, for the log and the tests. */
    fun size(): Int = synchronized(lock) { index?.size ?: 0 }

    /**
     * The best index available, waiting only when there is nothing usable.
     *
     * A current index is returned as is. A stale one is returned too, with a
     * refresh started behind it, so typing never waits for a re-walk. An index
     * that does not match the current generation is not usable at all - it was
     * built before something changed - so the search waits for the walk that
     * is already running, or starts one.
     */
    private fun current(): List<Hit> {
        synchronized(lock) {
            val cached = index
            if (cached != null && walkedGeneration == generation) {
                if (now() - indexedAt >= TTL_MS) {
                    startWalker()
                }
                return cached
            }
        }
        return awaitFreshWalk()
    }

    /**
     * Waits, bounded, for a walk that observed the current [generation].
     *
     * Polling rather than a future because the walk that will satisfy this
     * request may not have been submitted yet: a walk in flight at an older
     * generation is followed by the one that counts. Each turn also re-asks
     * for a walk, so a walk that finished without noticing the change cannot
     * leave the search waiting for one that was never started.
     */
    private fun awaitFreshWalk(): List<Hit> {
        val deadline = System.currentTimeMillis() + COLD_WAIT_MS
        while (true) {
            synchronized(lock) {
                if (walkedGeneration == generation) return index ?: emptyList()
            }
            startWalker()
            if (System.currentTimeMillis() >= deadline) {
                // Timed out: answer with the newest names there are rather
                // than hanging the search, and let the walk finish in peace.
                return synchronized(lock) { index } ?: emptyList()
            }
            try {
                Thread.sleep(POLL_MS)
            } catch (e: InterruptedException) {
                return synchronized(lock) { index } ?: emptyList()
            }
        }
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
                val startedAt = synchronized(lock) { generation }
                val built = walk(root, MAX_ENTRIES)
                val outraced = synchronized(lock) {
                    index = built
                    indexedAt = now()
                    walkedGeneration = startedAt
                    pending = null
                    startedAt != generation
                }
                onRebuild?.invoke(built.size)
                // The storage changed while this walk ran. The names it found
                // are already out of date, so walk again rather than leave a
                // fresh-looking index that is missing what just arrived.
                if (outraced) startWalker()
                built
            } catch (e: Exception) {
                synchronized(lock) { pending = null }
                // A walk that failed answers nothing rather than pretending
                // the phone is empty; the next search tries again.
                emptyList()
            }
        }.also { pending = it }
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
         * How often a waiting search checks whether its walk has landed.
         *
         * Only a search that has nothing usable to answer from waits at all,
         * so this is the granularity of a rare delay, not a cost on typing.
         */
        private const val POLL_MS = 25L

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
