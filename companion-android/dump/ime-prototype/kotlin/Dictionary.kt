package com.aplusconnect.companion.ui.ime

import android.content.Context
import java.util.Locale

/**
 * Word suggestions.
 *
 * Two sources, in this order: the words this user actually types (kept in the
 * keyboard's preferences), then a bundled list of the ten thousand most common
 * English words, ordered by how often they are used. That is enough for the
 * suggestion strip to be useful without shipping a language model.
 */
class Dictionary(
    private val context: Context,
    private val prefs: KeyboardPrefs,
) {

    private val common: List<String> by lazy { loadCommon() }

    private fun loadCommon(): List<String> = try {
        context.assets.open(FILE).bufferedReader().use { reader ->
            reader.readLines()
                .map { it.trim().lowercase(Locale.ROOT) }
                .filter { it.isNotEmpty() }
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** Remembers a word the user actually types. */
    fun learn(word: String) = prefs.learn(word)

    /** Suggestions for the word being typed, best first. */
    fun suggest(prefix: String, limit: Int = MAX_SUGGESTIONS): List<String> {
        val needle = prefix.trim().lowercase(Locale.ROOT)
        if (needle.isEmpty()) return emptyList()

        val found = LinkedHashSet<String>(limit * 3)

        // What this user types beats any dictionary.
        prefs.learnedWords().entries
            .filter { it.key.startsWith(needle) && it.key != needle }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.length })
            .take(limit)
            .forEach { found += it.key }

        if (found.size < limit) {
            for (word in common) {
                if (found.size >= limit) break
                if (word == needle) continue
                if (word.startsWith(needle) && word.length <= needle.length + MAX_GROWTH) {
                    found += word
                }
            }
        }

        return found.take(limit)
    }

    private companion object {
        const val FILE = "words.txt"
        const val MAX_SUGGESTIONS = 3
        const val MAX_GROWTH = 6
    }
}