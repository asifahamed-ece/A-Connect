package com.aplusconnect.companion

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * No socket write may run on the caller's thread.
 *
 * Three different callers reach CompanionService from the main thread: the
 * keyboard's clipboard broadcast, the notification listener, and the Send Text
 * button. A socket write from there throws NetworkOnMainThreadException and
 * closes the session, which the user sees as the connection dropping for no
 * reason. Every such call must be wrapped in `sendThread.execute { ... }`.
 *
 * This is a source-text guard because CompanionService is a Service: it needs
 * an Android runtime to instantiate, and this project has no Robolectric. The
 * Android-boxed guard is exactly what the accessibility and keyboard-role
 * guards already use.
 */
class NoMainThreadSendTest {

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
    fun `no socket write happens on the caller's thread`() {
        val lines = companionServiceSource().readLines()
        val writePattern = Regex("running\\.send(Notification|NotificationRemoved)?\\(")

        val offenders = lines.mapIndexed { index, line -> index to line }
            .filter { (index, line) ->
                writePattern.containsMatchIn(line) &&
                    !line.contains("sendThread.execute") &&
                    !(index > 0 && lines[index - 1].contains("sendThread.execute"))
            }
            .map { (index, _) -> "line ${index + 1}" }

        assertTrue(
            "a socket write runs on the caller's thread, which throws " +
                "NetworkOnMainThreadException and disconnects the phone: " +
                offenders.joinToString(),
            offenders.isEmpty()
        )
    }
}