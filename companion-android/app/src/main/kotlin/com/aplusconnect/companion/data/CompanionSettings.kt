package com.aplusconnect.companion.data

import android.content.Context
import android.content.SharedPreferences
import com.aplusconnect.companion.protocol.Pairing
import org.json.JSONArray
import org.json.JSONObject

/** One item in the phone's synced clipboard history. */
data class ClipHistoryEntry(
    val id: String,
    val text: String,
    val direction: String, // "phone_to_pc" or "pc_to_phone"
    val timestamp: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("text", text)
        .put("dir", direction)
        .put("ts", timestamp)

    companion object {
        fun fromJson(obj: JSONObject): ClipHistoryEntry = ClipHistoryEntry(
            id = obj.optString("id"),
            text = obj.optString("text"),
            direction = obj.optString("dir"),
            timestamp = obj.optLong("ts"),
        )
    }
}

/**
 * What the phone remembers about the computer it is paired with.
 *
 * The pairing is the computer's stable id and the fingerprint of its
 * certificate. After the first successful handshake both are stored, and
 * every later connection must present the same certificate.
 */
class CompanionSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("companion", Context.MODE_PRIVATE)

    var host: String
        get() = prefs.getString(KEY_HOST, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HOST, value).apply()

    var port: Int
        get() = prefs.getInt(KEY_PORT, 24443)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    /** The one time code the computer showed when pairing. */
    var pairingCode: String
        get() = prefs.getString(KEY_CODE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CODE, value).apply()

    var deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DEVICE_ID, value).apply()

    /** Optional custom device name chosen by the user. */
    var deviceName: String
        get() = prefs.getString(KEY_DEVICE_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DEVICE_NAME, value).apply()

    /**
     * The secret this phone shares with the computer, used to prove on every
     * later connection that it is the same phone. It is sent once, during the
     * pairing, and never again.
     */
    var sharedSecret: String
        get() = prefs.getString(KEY_SECRET, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SECRET, value).apply()

    /**
     * The folder the user picked for files received from the computer, as a
     * document tree the app keeps a permission for. Empty means the app's own
     * downloads folder.
     */
    var downloadFolderUri: String
        get() = prefs.getString(KEY_DOWNLOAD_FOLDER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DOWNLOAD_FOLDER, value).apply()

    /**
     * The folder the user shared so the computer can browse it, as a document
     * tree the app keeps a read permission for. Empty means nothing is shared
     * and the computer's file browser stays empty.
     */
    var browseRootUri: String
        get() = prefs.getString(KEY_BROWSE_ROOT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_BROWSE_ROOT, value).apply()

    /** True once the phone has been paired and holds the secret. */
    val isPaired: Boolean
        get() = sharedSecret.isNotEmpty()

    /** Forgets the pairing, so the next connection pairs again with the code. */
    fun clearSecret() {
        prefs.edit().remove(KEY_SECRET).apply()
    }

    /** The paired computer, or null when this phone is not paired yet. */
    var pairing: Pairing?
        get() {
            val pcId = prefs.getString(KEY_PC_ID, null) ?: return null
            val fingerprint = prefs.getString(KEY_FINGERPRINT, null) ?: return null
            return Pairing(pcId, fingerprint)
        }
        set(value) {
            val editor = prefs.edit()
            if (value == null) {
                editor.remove(KEY_PC_ID).remove(KEY_FINGERPRINT)
            } else {
                editor.putString(KEY_PC_ID, value.pcId)
                editor.putString(KEY_FINGERPRINT, value.fingerprint)
            }
            editor.apply()
        }

    /** Remembers the computer after the first handshake. */
    fun remember(pcId: String, fingerprint: String) {
        pairing = Pairing(pcId, fingerprint)
    }

    fun forget() {
        pairing = null
    }

    fun getHistory(): List<ClipHistoryEntry> {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                ClipHistoryEntry.fromJson(array.getJSONObject(i))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun addHistory(entry: ClipHistoryEntry) {
        val current = getHistory().toMutableList()
        // Deduplicate recent if same text
        if (current.isNotEmpty() && current.first().text == entry.text) return
        current.add(0, entry)
        while (current.size > 50) current.removeAt(current.size - 1)
        val array = JSONArray()
        for (item in current) array.put(item.toJson())
        prefs.edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    @Synchronized
    fun clearHistory() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    companion object {
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_CODE = "code"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_SECRET = "shared_secret"
        private const val KEY_DOWNLOAD_FOLDER = "download_folder"
        private const val KEY_BROWSE_ROOT = "browse_root"
        private const val KEY_PC_ID = "pc_id"
        private const val KEY_FINGERPRINT = "fingerprint"
        private const val KEY_HISTORY = "clip_history"
    }
}
