package com.aplusconnect.companion.net

import java.util.concurrent.ConcurrentHashMap

object LiveTransfers {

    enum class Direction { Sending, Receiving }

    data class Transfer(
        val id: String,
        val name: String,
        val direction: Direction,
        val totalBytes: Long,
        val doneBytes: Long = 0,
        val startedAt: Long = System.currentTimeMillis(),
    ) {
        val progress: Float?
            get() = if (totalBytes > 0) (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null

        fun speed(now: Long = System.currentTimeMillis()): Long {
            val ms = (now - startedAt).coerceAtLeast(1)
            return doneBytes * 1000 / ms
        }
    }

    private val live = ConcurrentHashMap<String, Transfer>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    var version: Int = 0
        private set

    fun start(id: String, name: String, direction: Direction, totalBytes: Long) {
        cancelled.remove(id)
        live[id] = Transfer(id, name, direction, totalBytes)
        version++
    }

    fun progress(id: String, doneBytes: Long) {
        val t = live[id] ?: return
        live[id] = t.copy(doneBytes = doneBytes)
        version++
    }

    fun finish(id: String) {
        live.remove(id)
        cancelled.remove(id)
        version++
    }

    fun cancel(id: String): Transfer? {
        val t = live.remove(id) ?: return null
        cancelled.add(id)
        version++
        return t
    }

    fun isCancelled(id: String): Boolean = id in cancelled
    fun get(id: String): Transfer? = live[id]
    fun all(): List<Transfer> = live.values.sortedByDescending { it.startedAt }
    fun clear() {
        live.clear()
        cancelled.clear()
        version++
    }
}