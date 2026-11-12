#!/usr/bin/env python3
"""Turn a HeliBoard checkout into the A+ Connect keyboard.

What it changes, all of it:

  1. applicationId -> com.aplusconnect.keyboard, so it installs beside the real
     HeliBoard instead of replacing it (the namespace is untouched: renaming
     packages across 2,500 files would make every upstream rebase a nightmare).
  2. adds helium314/keyboard/aplus/ClipboardBridge.kt, which broadcasts what was
     copied to the companion app.
  3. calls it from ClipboardHistoryManager.onPrimaryClipChanged(), which is the
     one place a default keyboard is told the clipboard changed.
  4. declares the companion app's signatures-only permission in the manifest, so
     the system refuses the broadcast from anything not signed with our key.

Idempotent: run it twice and the second run reports "already patched".

Usage:
    python3 keyboard/patch-heliboard.py [path-to-heliboard-checkout]
"""

import sys
from pathlib import Path

# The checkout must live in a path WITHOUT SPACES: the NDK's ndk-build cannot
# parse "A+ Connect" and fails with "APP_BUILD_SCRIPT points to an unknown file".
# So the checkout lives outside this repository; the script finds it here.
CANDIDATES = [
    Path(__file__).resolve().parent / "heliboard",
    Path.home() / "Projects" / "heliboard-aplus",
]


def default_checkout() -> Path:
    import os

    override = os.environ.get("APLUS_KEYBOARD_DIR")
    if override:
        return Path(override).expanduser()
    for candidate in CANDIDATES:
        if (candidate / "app" / "build.gradle.kts").exists():
            return candidate
    return CANDIDATES[0]

BRIDGE_KT = '''// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.aplus

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.text.TextUtils
import android.util.Log

/**
 * Hands the clipboard to A+ Connect.
 *
 * Android 10 and later only let the *default keyboard* read the clipboard in
 * the background (ClipboardService.clipboardAccessAllowed() falls back to
 * isDefaultIme(), which reads Settings.Secure.DEFAULT_INPUT_METHOD). This fork
 * is that keyboard, so this is the only place on the phone where a copy can be
 * noticed at all.
 *
 * The companion app cannot watch the clipboard itself, so the text goes there
 * over a broadcast that only an app signed with the companion app's key may
 * receive (the receiver declares the permission; we hold it).
 *
 * The broadcast is deliberately fire-and-forget: the companion app queues what
 * it cannot send yet, so nothing here has to care whether the computer is
 * connected.
 */
object ClipboardBridge {

    /** Where the companion app listens. */
    private const val COMPANION_PACKAGE = "com.aplusconnect.companion"
    private const val ACTION = "com.aplusconnect.companion.action.CLIPBOARD"
    private const val EXTRA_TEXT = "text"

    /** Declared by the companion app's receiver; enforced by the system. */
    private const val PERMISSION = "com.aplusconnect.companion.permission.CLIPBOARD_BRIDGE"

    private const val TAG = "AplusBridge"

    fun forward(context: Context) {
        val text = readClipboard(context)
        if (text == null) {
            Log.i(TAG, "the clipboard changed but there was no text to forward")
            return
        }
        Log.i(TAG, "clipboard changed, forwarding ${text.length} characters")
        try {
            // The receiver declares the permission, which is what makes the
            // system check that we hold it. Passing it here as well makes the
            // framework check the sender against its own demand, and on some
            // builds that check fails even though the grant is there.
            context.sendBroadcast(
                Intent(ACTION)
                    .setPackage(COMPANION_PACKAGE)
                    .putExtra(EXTRA_TEXT, text),
            )
        } catch (e: Exception) {
            // Never let this break typing: a missing companion app is normal.
            Log.w(TAG, "could not hand the clipboard to A+ Connect: ${e.message}")
            return
        }
        Log.i(TAG, "handed the clipboard to A+ Connect")
    }

    private fun readClipboard(context: Context): String? {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return null
        val clip = manager.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val text = clip.getItemAt(0).coerceToText(context).toString()
        return text.takeIf { !TextUtils.isEmpty(it) }
    }
}
'''

CALL_SITE = """    override fun onPrimaryClipChanged() {
        // A+ Connect: the companion app cannot read the clipboard in the
        // background - only the default keyboard may - so the copy is handed to
        // it here, before anything the keyboard itself wants to do with it.
        ClipboardBridge.forward(latinIME)

"""

MANIFEST_PERMISSION = """    <!-- The companion app's receiver declares this permission and we hold it,
         so only a build signed with the same key can send the clipboard. -->
    <uses-permission android:name="com.aplusconnect.companion.permission.CLIPBOARD_BRIDGE" />
"""


def patch_build_gradle(root: Path) -> str:
    path = root / "app" / "build.gradle.kts"
    text = path.read_text()
    if 'applicationId = "com.aplusconnect.keyboard"' in text:
        return "build.gradle.kts: already patched"
    old = 'applicationId = "helium314.keyboard"'
    if old not in text:
        raise SystemExit(f"could not find {old} in {path}")
    text = text.replace(old, 'applicationId = "com.aplusconnect.keyboard"', 1)
    path.write_text(text)
    return "build.gradle.kts: applicationId renamed"


def patch_clipboard_manager(root: Path) -> str:
    path = root / "app" / "src" / "main" / "java" / "helium314" / "keyboard" / "latin" / "ClipboardHistoryManager.kt"
    text = path.read_text()
    if "ClipboardBridge.forward" in text:
        return "ClipboardHistoryManager: already patched"

    anchor = "    override fun onPrimaryClipChanged() {\n"
    if anchor not in text:
        raise SystemExit(f"could not find the clipboard callback in {path}")
    text = text.replace(anchor, CALL_SITE, 1)

    if "import helium314.keyboard.aplus.ClipboardBridge" not in text:
        text = text.replace(
            "import helium314.keyboard.compat.ClipboardManagerCompat",
            "import helium314.keyboard.aplus.ClipboardBridge\nimport helium314.keyboard.compat.ClipboardManagerCompat",
            1,
        )
    path.write_text(text)
    return "ClipboardHistoryManager: forwards copies to A+ Connect"


def write_bridge(root: Path) -> str:
    path = root / "app" / "src" / "main" / "java" / "helium314" / "keyboard" / "aplus" / "ClipboardBridge.kt"
    if path.exists() and path.read_text() == BRIDGE_KT:
        return "ClipboardBridge.kt: already patched"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(BRIDGE_KT)
    return "ClipboardBridge.kt: written"


def patch_manifest(root: Path) -> str:
    path = root / "app" / "src" / "main" / "AndroidManifest.xml"
    text = path.read_text()
    if "com.aplusconnect.companion.permission.CLIPBOARD_BRIDGE" in text:
        return "AndroidManifest.xml: already patched"

    anchor = "    <uses-permission"
    index = text.find(anchor)
    if index < 0:
        raise SystemExit(f"could not find a uses-permission block in {path}")
    text = text[:index] + MANIFEST_PERMISSION + text[index:]
    path.write_text(text)
    return "AndroidManifest.xml: declares the companion permission"


def main() -> None:
    root = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else default_checkout()
    if not (root / "app" / "build.gradle.kts").exists():
        raise SystemExit(f"{root} does not look like a HeliBoard checkout")

    print(f"patching {root}")
    for step in (patch_build_gradle, write_bridge, patch_clipboard_manager, patch_manifest):
        print("  " + step(root))


if __name__ == "__main__":
    main()
