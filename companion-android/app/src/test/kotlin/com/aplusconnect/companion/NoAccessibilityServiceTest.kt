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
    fun `the shipping manifest keeps the clipboard bridge the keyboard feeds`() {
        val text = manifest().readText()

        assertTrue(
            "the clipboard bridge receiver is how the copied text reaches this " +
                "app: the A+ Connect keyboard holds the default-keyboard role and " +
                "broadcasts what was copied",
            text.contains("ClipboardBridgeReceiver"),
        )
        assertTrue(
            "the bridge must stay behind a signature-level permission, so that " +
                "no other app can inject text into the user's computer",
            text.contains("android:protectionLevel=\"signature\"") &&
                text.contains("com.aplusconnect.companion.permission.CLIPBOARD_BRIDGE"),
        )
    }

    @Test
    fun `the shipping manifest does not claim the keyboard role itself`() {
        val text = manifest().readText()

        assertFalse(
            "this app must not declare an input method any more: the keyboard is " +
                "the patched HeliBoard fork, which reaches this app over the bridge",
            text.contains("BIND_INPUT_METHOD"),
        )
    }
}
