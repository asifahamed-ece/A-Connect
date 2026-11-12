package com.aplusconnect.companion.protocol

import org.json.JSONArray
import org.json.JSONObject

/**
 * The payload builders and parsers for the four features that share the
 * companion channel - notifications, playback, the file browser and the screen
 * mirror.
 *
 * The wire keys live here once so the phone and the computer cannot drift
 * apart without a test noticing. Each payload carries its own `type` as well
 * as the envelope's, because `notification` and `notification-removed` share
 * an envelope shape and the receiver dispatches on the payload.
 */
object FeaturePayloads {

    /** One button on a notification, in the order the phone shows them. */
    data class NotificationAction(val id: String, val title: String)

    data class Notification(
        val id: String,
        val pkg: String,
        val app: String,
        val title: String,
        val text: String,
        val ts: Long,
        val ongoing: Boolean,
        val actions: List<NotificationAction>,
    )

    data class MediaState(
        val app: String,
        val title: String,
        val artist: String,
        val album: String,
        val playing: Boolean,
        val positionMs: Long,
        val durationMs: Long,
    )

    data class FileEntry(
        val name: String,
        val size: Long,
        val isDir: Boolean,
        val mime: String,
    )

    data class FileList(val path: String, val entries: List<FileEntry>)

    fun notification(
        id: String,
        pkg: String,
        app: String,
        title: String,
        text: String,
        ts: Long,
        ongoing: Boolean,
        actions: List<NotificationAction>,
    ): JSONObject = JSONObject()
        .put("type", Protocol.TYPE_NOTIFICATION)
        .put("id", id)
        .put("pkg", pkg)
        .put("app", app)
        .put("title", title)
        .put("text", text)
        .put("ts", ts)
        .put("ongoing", ongoing)
        .put("actions", actionsToJson(actions))

    /** The payload of a notification that has gone away. */
    fun notificationRemoved(id: String): JSONObject = JSONObject()
        .put("type", Protocol.TYPE_NOTIFICATION_REMOVED)
        .put("id", id)

    fun mediaState(
        app: String,
        title: String,
        artist: String,
        album: String,
        playing: Boolean,
        positionMs: Long,
        durationMs: Long,
    ): JSONObject = JSONObject()
        .put("type", Protocol.TYPE_MEDIA_STATE)
        .put("app", app)
        .put("title", title)
        .put("artist", artist)
        .put("album", album)
        .put("playing", playing)
        .put("positionMs", positionMs)
        .put("durationMs", durationMs)

    fun fileList(path: String, entries: List<FileEntry>): JSONObject = JSONObject()
        .put("type", Protocol.TYPE_FILE_LIST)
        .put("path", path)
        .put("entries", JSONArray().apply {
            entries.forEach { entry ->
                put(
                    JSONObject()
                        .put("name", entry.name)
                        .put("size", entry.size)
                        .put("isDir", entry.isDir)
                        .put("mime", entry.mime)
                )
            }
        })

    /**
     * A stable id for one notification.
     *
     * Android identifies a notification by its package, its id and its tag;
     * the tag is often null, so it is spelled out rather than left to a
     * surprise. The same notification always produces the same string, which
     * is what lets a dismiss find it again.
     */
    fun notificationId(pkg: String, id: Int, tag: String?): String =
        "$pkg:$id:${tag ?: "null"}"

    /** Parses a notification payload, or null when it is not one. */    fun parseNotification(json: JSONObject): Notification? {
        if (!json.has("id")) return null
        val actions = json.optJSONArray("actions") ?: JSONArray()
        return Notification(
            id = json.getString("id"),
            pkg = json.optString("pkg"),
            app = json.optString("app"),
            title = json.optString("title"),
            text = json.optString("text"),
            ts = json.optLong("ts"),
            ongoing = json.optBoolean("ongoing"),
            actions = (0 until actions.length()).mapNotNull { index ->
                actions.optJSONObject(index)?.let {
                    NotificationAction(it.optString("id"), it.optString("title"))
                }
            },
        )
    }

    fun parseMediaState(json: JSONObject): MediaState? {
        if (!json.has("title")) return null
        return MediaState(
            app = json.optString("app"),
            title = json.getString("title"),
            artist = json.optString("artist"),
            album = json.optString("album"),
            playing = json.optBoolean("playing"),
            positionMs = json.optLong("positionMs"),
            durationMs = json.optLong("durationMs"),
        )
    }

    fun parseFileList(json: JSONObject): FileList? {
        val entries = json.optJSONArray("entries") ?: return null
        return FileList(
            path = json.optString("path"),
            entries = (0 until entries.length()).mapNotNull { index ->
                entries.optJSONObject(index)?.let {
                    FileEntry(
                        name = it.optString("name"),
                        size = it.optLong("size"),
                        isDir = it.optBoolean("isDir"),
                        mime = it.optString("mime"),
                    )
                }
            },
        )
    }

    private fun actionsToJson(actions: List<NotificationAction>): JSONArray {
        val array = JSONArray()
        actions.forEach { array.put(JSONObject().put("id", it.id).put("title", it.title)) }
        return array
    }
}
