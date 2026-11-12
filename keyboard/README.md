# The A+ Connect keyboard (a patched HeliBoard)

The phone's clipboard reaches the computer with no tap only because the **default
keyboard** is the one app Android lets read the clipboard in the background. So
the keyboard the user types on has to be ours-by-patch: see
`docs/companion/keyboard-fork.md` for the reasoning, the measurements and the
licensing.

## Layout

```
keyboard/
  README.md            this file
  patch-heliboard.py   turns a HeliBoard checkout into the A+ Connect keyboard
  heliboard/           the checkout (not committed: 75 MB of upstream source)
```

## Getting a build

```bash
# once: the source, at the tag we track
git clone --depth 1 --branch v4.2 https://github.com/HeliBorg/HeliBoard.git keyboard/heliboard

# the patch (idempotent)
python3 keyboard/patch-heliboard.py

# build (Gradle 9.7.1 by path: the wrapper re-downloads the distribution and
# times out on the redirect from this machine)
cd keyboard/heliboard
echo "sdk.dir=$HOME/.local/opt/android-sdk" > local.properties
JAVA_HOME=$HOME/.local/opt/jdk17/usr/lib/jvm/java-17-openjdk \
ANDROID_HOME=$HOME/.local/opt/android-sdk \
/tmp/opencode/toolchain/gradle9/gradle-9.7.1/bin/gradle :app:assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/` as
`com.aplusconnect.keyboard.debug` (or `com.aplusconnect.keyboard` for a release
build), which installs beside a stock HeliBoard rather than replacing it.

## What the patch changes

1. `applicationId` → `com.aplusconnect.keyboard` (the `helium314.keyboard`
   namespace is left alone, so upstream rebases stay cheap).
2. `aplus/ClipboardBridge.kt` — broadcasts the clipboard to the companion app.
3. one call to it from `latin/ClipboardHistoryManager.onPrimaryClipChanged()`.
4. the companion app's signature-level permission in the manifest.

Nothing else. Typing, suggestions, glide typing, themes and HeliBoard's own
clipboard history are upstream code.

## Licence

The fork is GPL-3.0, like HeliBoard, and ships as its own APK. The companion app
and the desktop app talk to it only over a signed broadcast, which is what keeps
them MIT.
