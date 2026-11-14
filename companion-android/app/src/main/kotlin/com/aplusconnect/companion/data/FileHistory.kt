package com.aplusconnect.companion.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Which way a file went, seen from this phone. */
enum class Direction(val key: String) {
    Sent("sent"),
    Received("received");

    companion object {
        fun fromKey(key: String?): Direction = entries.firstOrNull { it.key == key } ?: Received
    }
}

/**
 * One file shared between this phone and the computer.
 *
 * [uri] is where the file can be opened from: the saved copy for a received
 * file, the picked document for a sent one. It may stop working (the user
 * deleted the file, or a picker's one-time grant expired); the row stays, and
 * opening it says so.
 */
data class SharedFileEntry(
    val id: String,
    val name: String,
    val direction: Direction,
    val sizeBytes: Long,
    val timestamp: Long,
    val uri: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("dir", direction.key)
        .put("size", sizeBytes)
        .put("ts", timestamp)
        .put("uri", uri)

    companion object {
        fun fromJson(obj: JSONObject): SharedFileEntry = SharedFileEntry(
            id = obj.optString("id"),
            name = obj.optString("name"),
            direction = Direction.fromKey(obj.optString("dir")),
            sizeBytes = obj.optLong("size"),
            timestamp = obj.optLong("ts"),
            uri = obj.optString("uri"),
        )
    }
}

/** The list rules, apart from storage, so they can be unit tested. */
object FileHistoryRules {
    /** Enough to scroll back a few weeks; old rows fall off the end. */
    const val MAX = 200

    /** Newest first; an entry with the same id replaces its old row. */
    fun add(list: List<SharedFileEntry>, entry: SharedFileEntry, max: Int = MAX): List<SharedFileEntry> =
        (listOf(entry) + list.filter { it.id != entry.id }).take(max)

    fun of(list: List<SharedFileEntry>, direction: Direction): List<SharedFileEntry> =
        list.filter { it.direction == direction }.sortedByDescending { it.timestamp }

    fun count(list: List<SharedFileEntry>, direction: Direction): Int = list.count { it.direction == direction }

    fun encode(list: List<SharedFileEntry>): String {
        val array = JSONArray()
        for (item in list) array.put(item.toJson())
        return array.toString()
    }

    fun decode(raw: String?): List<SharedFileEntry> {
        if (raw.isNullOrEmpty()) return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length())
                .map { SharedFileEntry.fromJson(array.getJSONObject(it)) }
                .filter { it.name.isNotEmpty() }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

/**
 * Files sent and received, kept across restarts.
 *
 * Written by the service when a file arrives and by the UI when a send
 * finishes; read by the History page. The Files page no longer keeps a list of
 * its own, so a file is shown in one place only.
 */
class FileHistory(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun entries(): List<SharedFileEntry> = FileHistoryRules.decode(prefs.getString(KEY, null))

    @Synchronized
    fun add(entry: SharedFileEntry) {
        val next = FileHistoryRules.add(entries(), entry)
        prefs.edit().putString(KEY, FileHistoryRules.encode(next)).apply()
    }

    @Synchronized
    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private companion object {
        const val PREFS = "file_history"
        const val KEY = "entries"
    }
}
