package com.aplusconnect.companion.ui.ime

import android.content.Context
import org.json.JSONObject

/**
 * What the keyboard remembers between sessions.
 *
 * Its own preference file, separate from the pairing settings: the keyboard is
 * a piece of UI and nothing here is needed by the connection.
 */
class KeyboardPrefs(context: Context) {

    private val prefs =
        context.getSharedPreferences("aplus_keyboard", Context.MODE_PRIVATE)

    var theme: KeyboardTheme
        get() = KeyboardTheme.byId(prefs.getString(KEY_THEME, null))
        set(value) {
            prefs.edit().putString(KEY_THEME, value.id).apply()
        }

    /** The row of digits above the letters, like Gboard offers. */
    var numberRow: Boolean
        get() = prefs.getBoolean(KEY_NUMBER_ROW, false)
        set(value) {
            prefs.edit().putBoolean(KEY_NUMBER_ROW, value).apply()
        }

    /** Whether the clipboard strip sits between the suggestions and the keys. */
    var clipboardStrip: Boolean
        get() = prefs.getBoolean(KEY_CLIPBOARD_STRIP, true)
        set(value) {
            prefs.edit().putBoolean(KEY_CLIPBOARD_STRIP, value).apply()
        }

    /** How tall the clipboard strip is, in rows of chips. */
    var clipboardRows: Int
        get() = prefs.getInt(KEY_CLIPBOARD_ROWS, 1).coerceIn(1, 5)
        set(value) {
            prefs.edit().putInt(KEY_CLIPBOARD_ROWS, value.coerceIn(1, 5)).apply()
        }

    var haptics: Boolean
        get() = prefs.getBoolean(KEY_HAPTICS, true)
        set(value) {
            prefs.edit().putBoolean(KEY_HAPTICS, value).apply()
        }

    /** Sound on key press, off by default: a keyboard that clicks is a choice. */
    var sound: Boolean
        get() = prefs.getBoolean(KEY_SOUND, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SOUND, value).apply()
        }

    /** Capitalise the first letter of a sentence. */
    var autoCapitalise: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CAPITALISE, true)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_CAPITALISE, value).apply()
        }

    /** The last emoji the user picked, newest first. */
    var recentEmoji: List<String>
        get() = prefs.getString(KEY_RECENT_EMOJI, null)
            ?.split(SEPARATOR).orEmpty()
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        set(value) {
            prefs.edit()
                .putString(KEY_RECENT_EMOJI, value.distinct().take(24).joinToString(SEPARATOR))
                .apply()
        }

    /** Words the user types that the bundled dictionary does not have. */
    fun learnedWords(): Map<String, Int> {
        val raw = prefs.getString(KEY_LEARNED, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            buildMap {
                for (key in obj.keys()) put(key, obj.optInt(key))
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun learn(word: String) {
        val clean = word.lowercase().trim()
        if (clean.length < 3 || clean.length > 24) return
        if (clean.any { !it.isLetter() && it != '\'' }) return

        val words = learnedWords().toMutableMap()
        words[clean] = (words[clean] ?: 0) + 1

        // Only the words this user actually uses, and only so many of them.
        val trimmed = words.entries
            .sortedByDescending { it.value }
            .take(MAX_LEARNED)
            .associate { it.key to it.value }

        val obj = JSONObject()
        for ((word, count) in trimmed) obj.put(word, count)
        prefs.edit().putString(KEY_LEARNED, obj.toString()).apply()
    }

    fun forgetLearned() {
        prefs.edit().remove(KEY_LEARNED).apply()
    }

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_NUMBER_ROW = "number_row"
        const val KEY_CLIPBOARD_STRIP = "clipboard_strip"
        const val KEY_CLIPBOARD_ROWS = "clipboard_rows"
        const val KEY_HAPTICS = "haptics"
        const val KEY_SOUND = "sound"
        const val KEY_AUTO_CAPITALISE = "auto_capitalise"
        const val KEY_RECENT_EMOJI = "recent_emoji"
        const val KEY_LEARNED = "learned_words"
        const val SEPARATOR = "\u001F"
        const val MAX_LEARNED = 500
    }
}