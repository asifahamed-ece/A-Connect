# The keyboard fork

The phone's clipboard has to reach the computer with no tap, and Android only
lets the **default keyboard** read the clipboard in the background
(`ClipboardService.clipboardAccessAllowed()` → `isDefaultIme()`, which reads
`Settings.Secure.DEFAULT_INPUT_METHOD`). An accessibility service has the same
access, but any enabled accessibility service makes Google Wallet refuse
contactless payments, so the keyboard role is the only route that keeps
payments working.

Two consequences follow, and they decide this document:

1. The role cannot be delegated. Switching to another keyboard writes
   `DEFAULT_INPUT_METHOD` (`InputMethodManagerService.setInputMethodLocked()` →
   `InputMethodSelector.putSelectedInputMethod()`), so a keyboard that hands
   typing to Gboard loses the privilege as it hands it over.
2. Therefore the keyboard the user types on, every day, is the one that must
   carry the privilege. A hand-written keyboard is not good enough for that
   (we built one on 2026-11-12 and retired it the same day; it is in
   `companion-android/dump/ime-prototype/`).

So: patch a keyboard that is already good, instead of writing one.

## Which keyboard, and why

| Keyboard | License | Why not / why yes |
|---|---|---|
| **HeliBoard** (chosen) | GPL-3.0 | Gboard-like: glide typing, word suggestions, bundled dictionaries, clipboard history, number row, themes, one-handed mode. It also already listens to the clipboard, so the hook exists. |
| AnySoftKeyboard | Apache-2.0 | Mature, but an older UI, and the clipboard listener would be ours to write. The licence would have been easier. |
| FlorisBoard | Apache-2.0 | Word suggestions and spell checking are not in any release yet (a v0.6 goal), so it is not a daily driver. |

## Measured, end to end

Verified on the target phone (CPH2737, ColorOS, Android 16) with the USB cable
still in but the **debugging clipboard path turned off**, so the companion
channel was the only route:

```
AplusBridge: clipboard changed, forwarding 12 characters      the fork
AplusBridge: handed the clipboard to A+ Connect               the broadcast
AplusConnect: the keyboard handed us 12 characters            the receiver
AplusConnect: sent 12 characters                              TLS, off the main thread
Clipboard phone-to-desktop: "192.168.1.39"                    the desktop
```

Three things had to be fixed for this to work, and all three are easy to
reintroduce:

1. **The grant is fixed at install time.** A signature permission is granted to
   a requesting app only if the permission already exists when that app is
   installed. The fork was installed before the companion app that defines the
   permission, so every broadcast was refused with "Permission Denial ... due to
   sender com.aplusconnect.keyboard.debug". Reinstalling the fork after the
   companion app granted it. Install the companion app first.
2. **Do not pass `receiverPermission` to `sendBroadcast`.** Demanding it at send
   time makes the framework check the sender against its own demand, which
   failed on this build even though the grant was present. The receiver's
   manifest `android:permission` is the gate, and it works.
3. **The send must not run on the main thread.** The broadcast is delivered on
   the main thread, so the service's intent handler ran there, and the socket
   write threw `NetworkOnMainThreadException`, which closed the TLS session on
   every copy. Sends now run on their own thread (`companion-send`).

## The patch

HeliBoard's `ClipboardHistoryManager` already implements
`ClipboardManager.OnPrimaryClipChangedListener` and registers itself in
`onCreate()` (as the default keyboard it is allowed to). The fork adds one
route out of that callback:

1. **Keyboard → app.** `onPrimaryClipChanged()` broadcasts the text,
   `com.aplusconnect.companion.action.CLIPBOARD` with a `text` extra, to the
   companion app.
2. **App → keyboard.** The app already writes what the computer copied into the
   phone's clipboard, which needs no privilege at all; HeliBoard then shows it
   in its own clipboard panel and the normal paste gesture works.

The broadcast is guarded by a **signature-level permission**
(`com.aplusconnect.companion.permission.CLIPBOARD_BRIDGE`): the receiver in the
companion app declares it, so only an app signed with the same key can send it.
Both APKs are built and signed with the same key, so this holds in debug and
would hold in release.

`ClipboardBridgeReceiver` in the companion app forwards the text to
`CompanionService` (the same `EXTRA_CLIPBOARD` path the "Copy to PC"
notification action uses), so queueing, reconnection and the wire protocol are
unchanged.

## Build

HeliBoard v4.2 needs a newer toolchain than the companion app:

| Piece | Version | Where it is |
|---|---|---|
| Gradle | 9.7.1 | downloaded to `/tmp/opencode/toolchain/gradle9` — **run the build with this binary, not `./gradlew`**: the wrapper re-downloads the distribution and times out on the redirect |
| AGP / Kotlin | 9.1.1 / 2.4.0 | declared by the fork, fetched by Gradle |
| Android SDK platform | 37.2 | `sdkmanager "platforms;android-37.2"` |
| Build tools | 37.0.0 | `sdkmanager "build-tools;37.0.0"` |
| NDK | 28.0.13004108 | `sdkmanager "ndk;28.0.13004108"` (HeliBoard compiles its dictionary JNI) |
| JDK | 17 | `~/.local/opt/jdk17` |

The checkout must live in a path **without spaces**: `ndk-build` cannot parse
`A+ Connect` and fails with "APP_BUILD_SCRIPT points to an unknown file". The
checkout therefore lives outside this repository (`~/Projects/heliboard-aplus`);
`keyboard/patch-heliboard.py` finds it, or takes a path, or honours
`APLUS_KEYBOARD_DIR`.

```
cd ~/Projects/heliboard-aplus
echo "sdk.dir=$HOME/.local/opt/android-sdk" > local.properties
JAVA_HOME=~/.local/opt/jdk17/usr/lib/jvm/java-17-openjdk \
ANDROID_HOME=~/.local/opt/android-sdk \
/tmp/opencode/toolchain/gradle9/gradle-9.7.1/bin/gradle :app:assembleDebug
```

## Licensing

HeliBoard is GPL-3.0. The fork stays under GPL-3.0 and ships as its **own
APK**: the companion app talks to it only over a signed broadcast, so the
companion app and the desktop app stay MIT. Keeping the two programs separate
in this way is the usual reading of the GPL's "separate programs" boundary, but
it is a judgement call — if the two ever share code or processes, the licensing
question has to be revisited.

## Maintenance

The fork tracks upstream HeliBoard tags. Our changes are deliberately small
(one callback in `ClipboardHistoryManager`, the applicationId, the permission
and the broadcast), so rebasing onto a new upstream release should be a
conflict-free diff of two or three hunks.
