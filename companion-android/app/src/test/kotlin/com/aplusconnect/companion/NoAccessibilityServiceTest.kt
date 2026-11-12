package com.aplusconnect.companion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The shipping app must never register an accessibility service.
 *
 * Android blocks contactless payments while any accessibility service is
 * enabled - Google Wallet calls
 * `AccessibilityManager.getEnabledAccessibilityServiceList()` and refuses to pay
 * if the list is not empty. It does not matter that the service cannot read the
 * screen: a service with `canRetrieveWindowContent="false"` and
 * `capabilities=0` still trips the guard.
 *
 * The app gets its privileged clipboard access from the default keyboard role
 * instead, which the platform grants to the default IME alone.
 */
class NoAccessibilityServiceTest {

    private fun manifest(): File {
        var dir = File("").absoluteFile
        while (dir.parentFile != null) {
            val candidate = File(dir, "src/main/AndroidManifest.xml")
            if (candidate.exists()) return candidate
            dir = dir.parentFile ?: break
        }
        throw AssertionError("could not find src/main/AndroidManifest.xml from ${File("").absolutePath}")
    }

    @Test
    fun `the shipping manifest declares no accessibility service`() {
        val text = manifest().readText()

        val declaresService = text.contains("android.accessibilityservice.AccessibilityService")
        val extendsService = text.contains("extends AccessibilityService")

        assertFalse(
            "the shipping app registers an accessibility service; Android blocks " +
                "contactless payments while one is enabled",
            declaresService,
        )
        assertFalse(
            "the shipping app has a class extending AccessibilityService",
            extendsService,
        )
    }

    @Test
    fun `the shipping manifest keeps the input method that carries the privilege`() {
        val text = manifest().readText()

        assertTrue(
            "the app must keep its input method: holding the default keyboard " +
                "role is how it reads the clipboard without an accessibility service",
            text.contains("BIND_INPUT_METHOD"),
        )
    }
}
