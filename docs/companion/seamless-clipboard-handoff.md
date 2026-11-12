# Seamless clipboard: handoff document

**Project:** A+ Connect — a Linux desktop app that mirrors and controls an Android phone
**Date:** 2026-11-11
**Branch:** `feature/companion-foundation` (head: `ebae2a9`)
**Device:** CPH2737 (OPPO Reno 14 5G), Android 16 / API 36, ColorOS, not rooted
**PC:** Arch/KDE, 192.168.1.39 · **Phone:** 192.168.1.38

---

## 1. The goal

Bidirectional clipboard sync between the PC and the phone that **does not expose the
phone's debugging interface on the network**.

Today the phone runs `adbd` and the PC reaches it over TCP 5555. Every network the phone
joins — college Wi-Fi, a hotel, a café — can see that port and attempt to connect to it.
The user does not want that.

The companion Android app replaces the ADB transport with one where:

- the phone opens **zero listening sockets** (it is a pure TLS *client*),
- traffic is TLS 1.3 with a **pinned certificate** (trust-on-first-use, confirmed by a
  6-digit pairing code the user reads off the PC),
- the clipboard still syncs **seamlessly — no "Send", no "Okay", no tap**.

The ADB transport stays as-is for mirroring, files and notifications.

---

## 2. Platform rules (source-verified, not guessed)

Taken from AOSP `frameworks/base/services/core/java/com/android/server/clipboard/ClipboardService.java`.
The `android16-release` branch and `main` (Android 17) were downloaded and diffed: **the
clipboard access policy is identical in both.**

```java
// ClipboardService.clipboardAccessAllowed(...) — Android 16 and Android 17
if (mPm.checkPermission(READ_CLIPBOARD_IN_BACKGROUND, callingPackage) == GRANTED) allowed = true;
...
allowed = isDefaultIme(userId, callingPackage);
case AppOpsManager.OP_READ_CLIPBOARD:
    allowed = isDefaultDeviceAndUidFocused(intendingDeviceId, uid);   // mWm.isUidFocused(uid)
    allowed = mContentCaptureInternal.isContentCaptureServiceForUser(uid, userId);
    allowed = mAutofillInternal.isAugmentedAutofillServiceForUser(uid, userId);
```

Four consequences, each of which was measured on the device:

1. **There is no accessibility-service exemption.** Six configurations were tested —
   service disabled, enabled, enabled+bound, `canRetrieveWindowContent` false and true,
   with `POST_NOTIFICATIONS` granted and a visible foreground-service notification, screen
   on — every one returned `NOTHING` (refused). The AOSP source agrees: an accessibility
   service is not in the list above.
2. **The clipboard change listener is gated by the same check.** `sendClipChangedBroadcast()`
   calls `clipboardAccessAllowed()` *before* dispatching to each listener, so a background
   app is never told that the clipboard changed. A poll or a listener cannot be the trigger.
3. **Background clipboard *writes* work** with no privilege at all. So the PC → phone
   direction is free.
4. **Only two doors exist for a background read:** be the default keyboard, or have focus.
   `isUidFocused(uid)` is a **uid** check, so *any* window of ours being focused is enough.

There is no clipboard role: AOSP issue 135471818 ("offer a clipboard manager role") was
closed as *Won't Fix*, and `READ_CLIPBOARD_IN_BACKGROUND` is `signature|role` — only
platform-signed apps get it (`cmd role add-role-holder android.app.role.CLIPBOARD` →
`Unknown role`).

---

## 3. What works, with the evidence

| Step | Status | Evidence from the device log |
|---|---|---|
| Copy-event detection | **WORKS** | `event #174 type=32 pkg=com.android.systemui` → `system ui activity, reading the clipboard`. Fired once per copy (22:56:15 and 22:56:17 for two copies). `type=32` is `TYPE_WINDOW_STATE_CHANGED`; the clipboard preview chip lives in SystemUI. |
| Privileged clipboard read | **WORKS** | `read 166 characters` — the transparent activity takes focus and the clipboard read succeeds. |
| TLS + certificate pinning | **WORKS** | `first contact, remembering the certificate fingerprint` (TOFU), then the pin is enforced on later connections. |
| Pairing handshake | **WORKS** | Phone: `paired with pc-test`. PC: `paired with CPH2737 (8e4f0ac2-b905-4e00-a3cb-d757c5ac2c02)`. |
| Wire protocol | **WORKS** | 30 Kotlin unit tests; `node scripts/protocol-check.js` → `4 frames decoded, round tripped and matched` (both directions, Kotlin ↔ Node). |
| **Sending the clipboard to the PC** | **BROKEN** | `sending failed: Socket is closed` — the connection had died and was never re-established. |

The chain that is proven end to end: **copy on the phone → event detected → clipboard read
(166 characters) → ready to send.** Only the last hop is broken.

---

## 4. The bugs, precisely

### Bug 1 — an established connection that drops is never reported (blocking)

`companion-android/app/src/main/kotlin/com/aplusconnect/companion/net/CompanionClient.kt`,
in `readLoop()`:

```kotlin
} finally {
    close()
    if (!established) onClosed("not connected")   // <-- only when the handshake never completed
}
```

When a **paired** connection drops, `onClosed` never fires, so
`CompanionService.scheduleReconnect()` is never called. The service keeps the dead client
and every later clipboard is lost. This is what happened: the server logged
`disconnected (paired: true)` and the phone never came back.

**Fix:** report the closure unconditionally (`onClosed(...)` for any exit from the read
loop) and let the service decide whether to reconnect.

### Bug 2 — the client writes to a closed stream instead of skipping

`CompanionClient.close()` nulls `socket` but leaves `input`/`output` set. Then:

```kotlin
fun sendClipboard(text: String) {
    val stream = output ?: return     // the stale, closed stream is still there
    ...
    stream.write(...)                 // -> "Socket is closed", the text is dropped
}
```

**Fix:** null `input`/`output` in `close()`, and have `sendClipboard` return `false` when
there is no live socket, so the caller can queue it.

### Bug 3 — a copy that arrives before the handshake completes is dropped without a trace

`CompanionService.send()` calls `client?.sendClipboard(...)`, but the client connects
asynchronously. A copy in the first seconds after the app starts is dropped with no
trace.

**Fix:** hold the pending payload in the service and flush it from the `onReady` callback.
Dedupe by the payload hash so the same content is never sent twice.

### Bug 4 — the reader activity brings the app to the front

The user saw the Companion app open when they copied. The reader activity starts in the
app's own task (default `taskAffinity`), so the whole task is brought forward.

**Fix:** `android:taskAffinity=""`, `android:excludeFromRecents="true"`,
`android:noHistory="true"`, a translucent theme with `windowAnimationStyle=@null`, and
`FLAG_ACTIVITY_NO_ANIMATION` (already set). The app the user was in must stay visually on
top.

### Bug 5 (tuning, not blocking) — the trigger is loose

The watcher currently triggers on **any** SystemUI window event, debounced 1.5 s. That
includes unrelated SystemUI activity (the shade opening, a notification arriving). A false
trigger is mostly harmless — the hash dedupe means the same content is not re-sent — but
each trigger takes focus for a moment. Worth narrowing, or at least measuring how often
it fires when nothing was copied.

---

## 5. How to reproduce

```bash
# 1. the PC side (a stand-in for the real Electron server)
cd "/home/shadow/Projects/A+ Connect"
node scripts/companion-server.js 24443
#    -> prints the pairing code and the certificate fingerprint

# 2. the phone
cd companion-android
export JAVA_HOME="$HOME/.local/opt/jdk17/usr/lib/jvm/java-17-openjdk"
export ANDROID_HOME="$HOME/.local/opt/android-sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
./gradlew :app:assembleDebug
adb push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/app.apk
adb shell pm install -r /data/local/tmp/app.apk

# 3. enable the accessibility watcher (the app asks for this in its UI)
adb shell settings put secure enabled_accessibility_services \
  com.aplusconnect.companion/.clip.ClipboardEventWatcher
adb shell settings put secure accessibility_enabled 1

# 4. point the app at the PC and connect
adb shell am start -n com.aplusconnect.companion/.MainActivity \
  --es host 192.168.1.39 --ei port 24443 --es code <CODE> --ez connect true

# 5. watch both sides
adb logcat -s AplusConnect:I -v time
tail -f /tmp/opencode/companion-server.log
```

Then **copy some text on the phone**. The phone log should show
`system ui activity, reading the clipboard` → `read N characters` → `sent N characters`,
and the server should print `[server] clipboard: "…"`.

---

## 6. Where everything lives

**Phone** (`companion-android/app/`)

| File | Responsibility |
|---|---|
| `protocol/ProtocolMessage.kt` | envelope, message types, version |
| `protocol/FrameCodec.kt` | 4-byte length-prefixed JSON framing, 2 MiB limit |
| `protocol/ClipboardPayload.kt` | byte budget, code-point-safe truncation, surrogate normalisation, SHA-256 |
| `protocol/Handshake.kt` | hello/hello-ack, fingerprint validation and pinning |
| `clip/ClipboardEventWatcher.kt` | the accessibility service — **detects** the copy, reads nothing |
| `clip/ClipboardReadActivity.kt` | the transparent window that takes focus and reads |
| `net/CompanionClient.kt` | the TLS client, the handshake, sending (**Bug 1, 2**) |
| `net/PeerTrust.kt` | the pinning trust manager (TOFU) |
| `service/CompanionService.kt` | the foreground service, reconnect, queueing (**Bug 3**) |
| `data/CompanionSettings.kt` | the paired computer, the address, the code |
| `MainActivity.kt` | the setup screen (a bare form; the real design system is not applied yet) |

**PC** (`services/`, `scripts/`)

| File | Responsibility |
|---|---|
| `services/companion-protocol.js` | the Node mirror of the wire protocol |
| `services/companion-cert.js` | the self-signed certificate and its fingerprint |
| `scripts/protocol-check.js` | the cross-implementation conformance check |
| `scripts/companion-server.js` | **a stand-in** for the real server, used to test the phone |

**Not built yet (phase P3):** the real PC server (`services/companion.js`), its
integration with `services/clipboard.js`, the IPC surface, and the status row in the
desktop UI. `scripts/companion-server.js` exists only so the phone could be tested
without the Electron app in the loop.

**Also not built yet:** the professional UI for the phone app (it currently uses the
default Material theme), and the polished setup screen.

---

## 7. Device state right now

- `com.aplusconnect.companion` (the app) and `com.aplusconnect.companion.spike` (the
  throwaway measuring tool) are installed.
- The enabled accessibility service is `com.aplusconnect.companion/.clip.ClipboardEventWatcher`.
- The default keyboard is Gboard (`com.google.android.inputmethod.latin/…LatinIME`) — it was
  restored after an experiment set the probe's blank IME as the default. **Do not set a
  keyboard with no UI as the default again; it makes the phone unusable.**
- The app's `POST_NOTIFICATIONS` has **not** been granted, so its foreground notification
  is not visible (it still runs).
- ADB is over Wi-Fi at 192.168.1.38:5555 (also available over USB).

---

## 8. Notes for whoever picks this up

- **Do not experiment on the phone without asking.** The user's phone is worth more than
  the experiment; every device-touching step should be announced first. The user has
  already granted the accessibility service once and is happy to grant it again.
- `adb install` hangs on this device (an OPPO install guide sits on screen waiting for an
  unlocked screen). Install with `adb push <apk> /data/local/tmp/<name>.apk` followed by
  `adb shell pm install -r /data/local/tmp/<name>.apk`, and use a **unique** file name per
  install.
- `pm install` fails with `INSTALL_FAILED_VERIFICATION_FAILURE` while an accessibility
  service of that package is **enabled**. Disable it first, install, then re-enable:
  ```bash
  adb shell settings put secure enabled_accessibility_services null
  adb shell settings put secure accessibility_enabled 0
  # ... install ...
  adb shell settings put secure enabled_accessibility_services <service>
  adb shell settings put secure accessibility_enabled 1
  ```
- The device's logcat ring buffer is small and the system is noisy — a stream to a file
  (`adb logcat -s AplusProbe:I -v time > file &`) is the only reliable way to keep a
  measurement.
- `pkill -f "logcat -s ..."` kills the calling shell too (the pattern matches its own
  command line). Kill by PID instead.
- The repo has **no test runner for the PC side**; `node scripts/protocol-check.js` is the
  equivalent, and it exits non-zero on failure. The Android side uses
  `./gradlew testDebugUnitTest` (30 tests).
