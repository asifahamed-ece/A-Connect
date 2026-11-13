package com.aplusconnect.companion

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * No Service property may touch the Service's context while it is being built.
 *
 * Android constructs the Component with `Class.newInstance()` and only then
 * attaches the context. A property initialiser that reads `mainLooper`,
 * `applicationContext`, `contentResolver` or `getSystemService` therefore runs
 * before there is a context at all, and the app dies on launch with
 * `NullPointerException ... on a null object reference`.
 *
 * That is not hypothetical: `ContentObserver(Handler(mainLooper))` added for
 * the media watcher did exactly this, and the app would not open at all.
 * `Looper.getMainLooper()` is static and safe anywhere; the context-bound
 * accessors belong inside `onCreate`.
 *
 * A source-text guard, like NoMainThreadSendTest: a Service cannot be
 * instantiated without an Android runtime, and this project has no Robolectric.
 */
class NoContextInServiceFieldsTest {

    private fun companionServiceSource(): File {
        var dir = File("").absoluteFile
        while (dir.parentFile != null) {
            val candidate = File(
                dir,
                "src/main/kotlin/com/aplusconnect/companion/service/CompanionService.kt"
            )
            if (candidate.exists()) return candidate
            dir = dir.parentFile ?: break
        }
        throw AssertionError("could not find CompanionService.kt from ${File("").absolutePath}")
    }

    @Test
    fun `no service field reads the context before onCreate`() {
        val text = companionServiceSource().readText()

        // Property initialisers run before onCreate; everything below the
        // first override is called later, when the context exists.
        val onCreate = text.indexOf("override fun onCreate()")
        assertTrue("onCreate is missing, so this guard cannot see the boundary", onCreate > 0)
        val fields = text.substring(0, onCreate)

        // Comments are stripped first: a comment explaining this very crash is
        // not the crash, and the words would otherwise trip the guard.
        val code = fields
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//.*"), "")

        val offenders = listOf("mainLooper", "applicationContext", "contentResolver", "getSystemService")
            .filter { code.contains(it) }

        assertTrue(
            "a Service property reads the context before Android has attached one, " +
                "which crashes the app on launch: " + offenders.joinToString(),
            offenders.isEmpty()
        )
    }
}