# A+ Connect Companion — Foundation Plan (P0–P1)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove that an Android app can read and write the clipboard with the privileges it needs on Android 16, and build the tested wire protocol that both the phone app and the PC app will speak.

**Architecture:** A new Kotlin/Jetpack Compose project (`companion-android/`) plus a protocol module that is pure Kotlin with no Android dependencies, so it can be unit-tested on the JVM. The PC side gets a matching CommonJS module in `services/`. Both sides validate against one shared fixture file, so a divergence between the two implementations is a test failure rather than a runtime surprise.

**Tech Stack:** Kotlin 2.0.21, Jetpack Compose, Gradle 8.9 / AGP 8.7.3, JDK 17, Android SDK platform 35, JUnit 4; Node.js (CommonJS) on the PC side.

**Spec:** `docs/superpowers/specs/2026-11-11-companion-apk-design.md`

**Scope note:** This plan covers phases **P0 (spike)** and **P1 (protocol)** only. Phase P0's measurements decide how P2 (the phone app) detects clipboard changes, so the P2–P5 plan is written *after* P0 completes and is based on real numbers, not assumptions.

## Global Constraints

- The companion app must open **zero listening sockets** on the phone. No TCP server, no UDP discovery socket, ever.
- The phone **dials out** to the PC. The PC never opens a connection to the phone.
- Transport is TLS with a pinned certificate. The pin is the SHA-256 of the DER-encoded certificate, rendered as colon-separated uppercase hex.
- Frame format: 4-byte big-endian unsigned length prefix, then UTF-8 JSON. Maximum frame **1 MiB** (1048576 bytes); a longer length header closes the connection.
- Clipboard payloads are capped at **256 KiB** of text. Longer content is truncated and the truncation is explicit in the payload.
- Envelope: `{ "v": 1, "type": "...", "id": "<uuid>", "ts": <epoch ms>, "payload": { } }`. `v` is the protocol major version.
- Message types: `hello`, `hello-ack`, `hello-error`, `clipboard-set`, `ping`, `pong`, `bye`.
- The pairing code is **6 digits** and is valid for **5 minutes**. It is issued and validated
  by the PC (phase P3); in this plan the phone only transmits it.
- The envelope is JSON. **Key order is not significant** — the two implementations may
  serialise object keys in different orders, so conformance is checked on decoded values,
  never on raw bytes.
- Clipboard payload rules (cap, truncation flag, dedupe) live in the protocol layer on both
  sides so the PC and the phone cannot disagree about what may cross the wire.
- Android package name: `com.aplusconnect.companion`. `minSdk 26`, `targetSdk 35`, `compileSdk 35`, JVM target 17.
- Clipboard **content is never written to a log**. Logs carry length and SHA-256 hash only.
- PC-side code follows the repo conventions: `services/*.js` are CommonJS `EventEmitter`s, IPC channels are `namespace:action`, `preload.js` exposes `window.api`, renderer code is a plain script with no bundler.
- Commit messages: one line, imperative, ≤ 72 characters, no attribution trailers.
- The desktop app's existing ADB clipboard path must keep working unchanged throughout this plan.

## Review Focus

Five input classes the spec implies that the tasks below must each pin down with a test:

1. **A non-text clipboard** (an image, a file URI) must never be pushed through the transport as bytes — it is coerced to text, and if there is no text it is skipped. *Test: `ClipboardPayloadTest` — empty text is not sent. The coercion itself is the phone's job in phase P2.*
2. **A clipboard value larger than 256 KiB** must arrive truncated *and flagged*, never silently cut and never dropped whole. *Test: `ClipboardPayloadTest.truncatesAndFlags`.*
3. **The same content copied twice** must produce exactly one transfer; the app's own write must not echo back to the PC. *Test: `ClipboardPayloadTest.suppressesRepeats`.*
4. **A paired PC whose certificate changed** must be refused, not silently re-trusted; a phone that moves to a new IP must reconnect on its own. *Test: `HandshakeTest.a changed certificate is refused`; reconnection is phase P2.*
5. **A second PC must not be able to pair** with a phone that is already paired — the attempt is refused, not silently allowed to replace the first. *Test: `HandshakeTest.a second pairing is refused`.*

---

## File Structure

```
companion-android/                          # new Gradle project
├── settings.gradle.kts                     # module list, plugin repos
├── build.gradle.kts                        # root: plugin versions, no plugins applied
├── gradle.properties                       # jvmargs, android.useAndroidX
├── gradle/wrapper/gradle-wrapper.properties# pins Gradle 8.9
├── gradlew                                 # wrapper script (generated)
├── app/
│   ├── build.gradle.kts                    # android { } block, deps, test config
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── kotlin/com/aplusconnect/companion/
│       │   │   ├── protocol/
│       │   │   │   ├── ProtocolMessage.kt  # envelope + type constants (pure Kotlin)
│       │   │   │   ├── FrameCodec.kt       # length-prefix framing (pure Kotlin)
│       │   │   │   ├── ClipboardPayload.kt # cap, truncation flag, dedupe (pure Kotlin)
│       │   │   │   └── Handshake.kt        # hello/hello-ack, fingerprint pinning (pure Kotlin)
│       │   │   └── spike/
│       │   │       ├── ClipboardProbeActivity.kt
│       │   │       ├── ProbeAccessibilityService.kt
│       │   │       └── ProbeRelayService.kt
│       │   ├── res/values/{strings.xml,themes.xml}
│       │   └── res/xml/accessibility_service_config.xml
│       └── test/
│           ├── kotlin/com/aplusconnect/companion/protocol/
│           │   ├── FrameCodecTest.kt
│           │   ├── ClipboardPayloadTest.kt
│           │   ├── HandshakeTest.kt
│           │   └── ProtocolFixtureTest.kt
│           └── resources/fixtures/
│               ├── protocol-frames.json      # written by Kotlin, read by Node
│               └── protocol-frames-node.json # written by Node, read by Kotlin

services/
├── companion-protocol.js                   # Node mirror of FrameCodec + envelope
└── companion-cert.js                       # self-signed cert generation + fingerprint

scripts/
└── protocol-check.js                       # Node conformance script (no test runner in repo)

docs/companion/
└── spike-results.md                        # P0 measurements + the decision they force
```

---

### Task 1: Android toolchain

**Files:**
- Create: `/opt/android-sdk/` (via Google's command-line tools)
- Modify: none in the repo

**Interfaces:**
- Consumes: nothing.
- Produces: `ANDROID_HOME` pointing at a working SDK with platform 35 + build-tools 35; `jdk17` on `PATH`; `sdkmanager` and `adb` working. Every later task depends on these.

- [ ] **Step 1: Install JDK 17**

```bash
sudo pacman -S --needed jdk17-openjdk
```

Expected: install completes; `ls /usr/lib/jvm` now lists a `java-17-openjdk` directory.

- [ ] **Step 2: Select JDK 17 for this project**

```bash
sudo archlinux-java set 17
java -version
```

Expected: `openjdk version "17...`.

- [ ] **Step 3: Download the Android command-line tools**

```bash
mkdir -p /opt/android-sdk/cmdline-tools
cd /tmp
curl -O https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q commandlinetools-linux-11076708_latest.zip -d /opt/android-sdk/cmdline-tools
mv /opt/android-sdk/cmdline-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest
```

Expected: `/opt/android-sdk/cmdline-tools/latest/bin/sdkmanager` exists.

- [ ] **Step 4: Install the platform and build tools**

```bash
export ANDROID_HOME=/opt/android-sdk
yes | /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --licenses
/opt/android-sdk/cmdline-tools/latest/bin/sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

Expected: exit code 0; `ls /opt/android-sdk/platforms` shows `android-35`.

- [ ] **Step 5: Verify the toolchain end to end**

```bash
export ANDROID_HOME=/opt/android-sdk
export PATH="$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin"
adb version
sdkmanager --list_installed | grep -E "platforms;android-35|build-tools;35.0.0"
```

Expected: `adb version` prints `Android Debug Bridge version 1.0.41`; both packages are listed.

- [ ] **Step 6: Record the environment for later tasks**

```bash
cat >> ~/.bashrc <<'EOF'
export ANDROID_HOME=/opt/android-sdk
export PATH="$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin"
EOF
```

Expected: `grep ANDROID_HOME ~/.bashrc` prints the export line. No commit — this is machine setup, not repository content.

---

### Task 2: Gradle project skeleton and a buildable APK

**Files:**
- Create: `companion-android/settings.gradle.kts`
- Create: `companion-android/build.gradle.kts`
- Create: `companion-android/gradle.properties`
- Create: `companion-android/gradle/wrapper/gradle-wrapper.properties`
- Create: `companion-android/app/build.gradle.kts`
- Create: `companion-android/app/src/main/AndroidManifest.xml`
- Create: `companion-android/app/src/main/kotlin/com/aplusconnect/companion/MainActivity.kt`
- Create: `companion-android/app/src/main/res/values/strings.xml`
- Create: `companion-android/app/src/main/res/values/themes.xml`

**Interfaces:**
- Consumes: the toolchain from Task 1.
- Produces: a runnable debug APK at `companion-android/app/build/outputs/apk/debug/app-debug.apk` and a Gradle wrapper at `companion-android/gradlew`. Every later task builds through this wrapper.

- [ ] **Step 1: Write the Gradle build files**

`companion-android/settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "A+ConnectCompanion"
include(":app")
```

`companion-android/build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
```

`companion-android/gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
```

`companion-android/app/build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aplusconnect.companion"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aplusconnect.companion"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui:1.7.5")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
```

The `org.json:json` test dependency is required because `android.jar`'s `org.json` is a stub that throws in local unit tests; the real implementation must be on the test classpath.

- [ ] **Step 2: Write the manifest, theme and a minimal activity**

`companion-android/app/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:allowBackup="false"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.APlusConnectCompanion">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`companion-android/app/src/main/res/values/strings.xml`:

```xml
<resources>
    <string name="app_name">A+ Connect Companion</string>
</resources>
```

`companion-android/app/src/main/res/values/themes.xml`:

```xml
<resources>
    <style name="Theme.APlusConnectCompanion" parent="android:Theme.Material.Light.NoActionBar" />
</resources>
```

`companion-android/app/src/main/kotlin/com/aplusconnect/companion/MainActivity.kt`:

```kotlin
package com.aplusconnect.companion

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "A+ Connect Companion" })
    }
}
```

- [ ] **Step 3: Generate the Gradle wrapper**

```bash
cd companion-android
gradle wrapper --gradle-version 8.9
```

If `gradle` is not installed, install the wrapper JAR and script by hand from
`https://services.gradle.org/distributions/gradle-8.9-bin.zip` (unzip it, run
`gradle-8.9/bin/gradle wrapper --gradle-version 8.9`).

Expected: `companion-android/gradlew` exists and `companion-android/gradle/wrapper/gradle-wrapper.properties` contains `distributionUrl` with `gradle-8.9`.

- [ ] **Step 4: Build the debug APK**

```bash
cd companion-android
./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`; `app/build/outputs/apk/debug/app-debug.apk` exists.

- [ ] **Step 5: Install and launch it on the device**

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.aplusconnect.companion/.MainActivity
```

Expected: `Success` from `adb install`; the app is in the foreground.

- [ ] **Step 6: Commit**

```bash
git add companion-android
git commit -m "build: scaffold the companion Android app"
```

---

### Task 3: P0 spike — privileged clipboard on Android 16

**Files:**
- Create: `companion-android/app/src/main/kotlin/com/aplusconnect/companion/spike/ProbeAccessibilityService.kt`
- Create: `companion-android/app/src/main/kotlin/com/aplusconnect/companion/spike/ProbeRelayService.kt`
- Create: `companion-android/app/src/main/kotlin/com/aplusconnect/companion/spike/ClipboardProbeActivity.kt`
- Modify: `companion-android/app/src/main/AndroidManifest.xml`
- Modify: `companion-android/app/src/main/res/values/strings.xml`
- Create: `companion-android/app/src/main/res/xml/accessibility_service_config.xml`
- Create: `docs/companion/spike-results.md`

**Interfaces:**
- Consumes: the project from Task 2; the device `192.168.1.38:5555` (Android 16, `CPH2737`).
- Produces: `docs/companion/spike-results.md` recording four measured facts, which Task 4 onwards and the P2 plan depend on.

**Why the spike is log-driven:** every measurement is taken from `adb logcat`, so no
coordinate-tapping is needed and the results are reproducible. The probe reads the
clipboard from inside the accessibility service (a genuine background read) and writes it
once at startup.

- [ ] **Step 1: Write the probe accessibility service**

`companion-android/app/src/main/kotlin/com/aplusconnect/companion/spike/ProbeAccessibilityService.kt`:

```kotlin
package com.aplusconnect.companion.spike

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.security.MessageDigest

/**
 * Spike only: proves that an enabled accessibility service gives this app's
 * UID the clipboard exemption on the target Android version.
 *
 * It polls the clipboard from a background thread, which is exactly the read
 * the real app will perform.
 */
class ProbeAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastHash: String? = null

    private val poller = object : Runnable {
        override fun run() {
            readOnce()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onServiceConnected() {
        Log.i(TAG, "probe accessibility service connected")
        handler.postDelayed(poller, POLL_INTERVAL_MS)
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        // Not used by the spike.
    }

    override fun onInterrupt() {
    }

    private fun readOnce() {
        val result = try {
            val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = manager.primaryClip
            if (clip == null || clip.itemCount == 0) {
                "EMPTY"
            } else {
                val text = clip.getItemAt(0).coerceToText(this).toString()
                val hash = sha256(text)
                // Length and hash only - never the content.
                if (hash == lastHash) {
                    "SAME len=${text.length} sha=${hash.take(12)}"
                } else {
                    lastHash = hash
                    "OK len=${text.length} sha=${hash.take(12)}"
                }
            }
        } catch (e: Exception) {
            "DENIED ${e.javaClass.simpleName}: ${e.message}"
        }
        Log.i(TAG, "read result: $result")
    }

    companion object {
        const val TAG = "AplusProbe"
        private const val POLL_INTERVAL_MS = 2000L

        fun sha256(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
```

- [ ] **Step 2: Write the probe foreground service**

`companion-android/app/src/main/kotlin/com/aplusconnect/companion/spike/ProbeRelayService.kt`:

```kotlin
package com.aplusconnect.companion.spike

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Spike only: checks that the foreground service type we intend to ship
 * (connectedDevice) is accepted and keeps the process alive, and performs the
 * one background write the measurements need.
 */
class ProbeRelayService : Service() {

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "A+ Connect probe", NotificationManager.IMPORTANCE_LOW)
        )

        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("A+ Connect probe")
            .setContentText("Measuring clipboard access")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        Log.i(ProbeAccessibilityService.TAG, "probe foreground service started")

        // The single write the measurements need.
        writeProbeValue()
    }

    private fun writeProbeValue() {
        val ok = try {
            val manager = getSystemService(android.content.ClipboardManager::class.java)
            manager.setPrimaryClip(android.content.ClipData.newPlainText("aplus", PROBE_VALUE))
            true
        } catch (e: Exception) {
            Log.w(ProbeAccessibilityService.TAG, "write failed: ${e.message}")
            false
        }
        Log.i(ProbeAccessibilityService.TAG, "write result: $ok")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "aplus_probe"
        const val NOTIFICATION_ID = 41
        const val PROBE_VALUE = "aplus-probe-value"
    }
}
```

- [ ] **Step 3: Write the probe activity**

`companion-android/app/src/main/kotlin/com/aplusconnect/companion/spike/ClipboardProbeActivity.kt`:

```kotlin
package com.aplusconnect.companion.spike

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * Spike only: shows what the service is doing and starts the probe service.
 */
class ClipboardProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val status = TextView(this).apply {
            text = "probe running - read results are in logcat (tag AplusProbe)"
        }
        val start = Button(this).apply { text = "START PROBE" }
        start.setOnClickListener {
            val intent = Intent(this, ProbeRelayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(status)
                addView(start)
            }
        )
    }
}
```

- [ ] **Step 4: Register everything in the manifest**

Inside `<application>` in `companion-android/app/src/main/AndroidManifest.xml`:

```xml
        <activity
            android:name=".spike.ClipboardProbeActivity"
            android:exported="false" />

        <service
            android:name=".spike.ProbeAccessibilityService"
            android:exported="true"
            android:label="@string/probe_service_label"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService" />
            </intent-filter>
            <meta-data
                android:name="android.accessibilityservice"
                android:resource="@xml/accessibility_service_config" />
        </service>

        <service
            android:name=".spike.ProbeRelayService"
            android:exported="false"
            android:foregroundServiceType="connectedDevice" />
```

And in `<manifest>`, above `<application>`:

```xml
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
    <uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
```

`CHANGE_WIFI_STATE` is what qualifies the `connectedDevice` foreground-service type.

Create `companion-android/app/src/main/res/xml/accessibility_service_config.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:canRetrieveWindowContent="false"
    android:description="@string/probe_service_description"
    android:notificationTimeout="100" />
```

Add to `strings.xml`:

```xml
    <string name="probe_service_label">A+ Connect Clipboard Probe</string>
    <string name="probe_service_description">Reads and writes the clipboard so A+ Connect can sync it with your computer.</string>
```

- [ ] **Step 5: Build, install and start the probe**

```bash
cd companion-android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.aplusconnect.companion/.spike.ClipboardProbeActivity
adb shell am start-foreground-service com.aplusconnect.companion/.spike.ProbeRelayService
```

Expected: `BUILD SUCCESSFUL`, `Success` from the install, and no crash in the log. The
service writes the probe value once and starts logging read results every 2 s.

- [ ] **Step 6: Measure fact 1 — read with the accessibility service DISABLED**

Leave the accessibility service switched off and watch the log:

```bash
adb logcat -c
sleep 8
adb logcat -d -s AplusProbe:I | tail -5
```

Expected: the read result is `EMPTY`, `DENIED ...`, or the value the service itself wrote.
Record the exact string. **This is the baseline.**

- [ ] **Step 7: Measure fact 2 — read with the accessibility service ENABLED**

```bash
adb shell settings put secure enabled_accessibility_services com.aplusconnect.companion/.spike.ProbeAccessibilityService
adb shell settings put secure accessibility_enabled 1
adb logcat -c
sleep 8
adb logcat -d -s AplusProbe:I | tail -5
```

Expected: `read result: OK len=<n> sha=<hash>`. Record the length and hash.

- [ ] **Step 8: Measure fact 3 — does a background read produce a system toast?**

Copy a fresh value on the phone (any app), then leave the probe polling for a minute
while watching the phone screen:

```bash
adb logcat -c
sleep 60
adb logcat -d -s AplusProbe:I | grep -c "read result"
adb logcat -d | grep -iE "toast" | tail -10
```

Record how many reads happened, and whether a toast appeared on the phone screen while
the app was in the background. Note the exact wording if one appeared. (Watch the screen
yourself - the toast is not in logcat.)

- [ ] **Step 9: Measure fact 4 — does a background WRITE stick?**

The service writes the probe value once at startup. Read the clipboard back from the
shell:

```bash
adb shell cmd clipboard get-text 2>&1 | head -3
adb logcat -d -s AplusProbe:I | grep "write result" | tail -2
```

Expected: the clipboard holds `aplus-probe-value` and the log says `write result: true`.
Record both.

- [ ] **Step 10: Measure fact 5 — does the foreground service survive the screen going off**

```bash
adb shell input keyevent KEYCODE_SLEEP
sleep 60
adb logcat -d -s AplusProbe:I | grep -c "read result"
adb shell input keyevent KEYCODE_WAKEUP
```

Expected: the read count keeps rising while the screen was off, which means the
`connectedDevice` foreground service kept the process alive. Record the count before and
after.

- [ ] **Step 11: Write the spike results**

Create `docs/companion/spike-results.md` recording, in this exact structure:

```markdown
# Clipboard privilege spike — results

Device: CPH2737 (OPPO Reno 14 5G), Android 16 / API 36, not rooted.
Build: companion-android, minSdk 26 / targetSdk 35.

| Fact | Measurement |
|------|-------------|
| Read without the accessibility service | <exact result string> |
| Read with the accessibility service | <exact result string> |
| Toast on background read | <per-read / once / none — exact wording> |
| Background write | <succeeded / failed — exact evidence> |
| Foreground service survives screen off | <reads before / reads after> |

## Decision
- Detection mechanism: <event-driven accessibility events + N s poll | poll only | other>
- Poll interval chosen: <seconds> — reason: <...>
- canRetrieveWindowContent must stay false: <yes / no — evidence>
- Foreground service type: <connectedDevice / specialUse> — evidence: <...>
```

Fill every cell from the measurements above. No cell may say "unknown".

- [ ] **Step 12: Commit**

```bash
git add companion-android docs/companion/spike-results.md
git commit -m "feat: spike privileged clipboard access on Android 16"
```

### Task 4: Protocol framing and message envelope (Kotlin)

**Files:**
- Create: `companion-android/app/src/main/kotlin/com/aplusconnect/companion/protocol/ProtocolMessage.kt`
- Create: `companion-android/app/src/main/kotlin/com/aplusconnect/companion/protocol/FrameCodec.kt`
- Create: `companion-android/app/src/test/kotlin/com/aplusconnect/companion/protocol/FrameCodecTest.kt`
- Create: `companion-android/app/src/test/resources/fixtures/protocol-frames.json`

**Interfaces:**
- Consumes: nothing from earlier tasks except the Gradle project.
- Produces:
  - `object Protocol { const val VERSION = 1; const val TYPE_HELLO = "hello"; ... }`
  - `data class ProtocolMessage(val type: String, val id: String, val ts: Long, val payload: JSONObject)`
  - `object FrameCodec { const val MAX_FRAME_BYTES = 1048576; fun encode(message: ProtocolMessage): ByteArray; fun decode(frame: ByteArray): ProtocolMessage; fun readFrom(input: InputStream): ProtocolMessage }`
  - `companion-android/app/src/test/resources/fixtures/protocol-frames.json` — the fixture Task 7 consumes.

- [ ] **Step 1: Write the failing test**

`companion-android/app/src/test/kotlin/com/aplusconnect/companion/protocol/FrameCodecTest.kt`:

```kotlin
package com.aplusconnect.companion.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class FrameCodecTest {

    @Test
    fun `encode prefixes a 4 byte big endian length`() {
        val message = ProtocolMessage(
            type = Protocol.TYPE_PING,
            id = "abc",
            ts = 1700000000000L,
            payload = JSONObject()
        )

        val frame = FrameCodec.encode(message)

        val declared = ((frame[0].toInt() and 0xFF) shl 24) or
            ((frame[1].toInt() and 0xFF) shl 16) or
            ((frame[2].toInt() and 0xFF) shl 8) or
            (frame[3].toInt() and 0xFF)
        assertEquals(frame.size - 4, declared)
    }

    @Test
    fun `decode round trips the envelope`() {
        val message = ProtocolMessage(
            type = Protocol.TYPE_CLIPBOARD_SET,
            id = "id-1",
            ts = 1700000000000L,
            payload = JSONObject().put("text", "hello").put("hash", "abc")
        )

        val decoded = FrameCodec.decode(FrameCodec.encode(message))

        assertEquals(Protocol.TYPE_CLIPBOARD_SET, decoded.type)
        assertEquals("id-1", decoded.id)
        assertEquals(1700000000000L, decoded.ts)
        assertEquals("hello", decoded.payload.getString("text"))
        assertEquals("abc", decoded.payload.getString("hash"))
    }

    @Test
    fun `decode rejects a frame longer than the maximum`() {
        val oversized = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x00, 0x20, 0x00, 0x00))
            write(ByteArray(1024))
        }.toByteArray()

        assertThrows(IllegalArgumentException::class.java) {
            FrameCodec.decode(oversized)
        }
    }

    @Test
    fun `readFrom reads consecutive frames from a stream`() {
        val first = FrameCodec.encode(ProtocolMessage(Protocol.TYPE_PING, "1", 1L, JSONObject()))
        val second = FrameCodec.encode(ProtocolMessage(Protocol.TYPE_PONG, "2", 2L, JSONObject()))
        val stream = ByteArrayInputStream(first + second)

        assertEquals("1", FrameCodec.readFrom(stream).id)
        assertEquals("2", FrameCodec.readFrom(stream).id)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd companion-android
./gradlew testDebugUnitTest --tests '*FrameCodecTest*'
```

Expected: FAIL — "Unresolved reference: FrameCodec" / "Unresolved reference: Protocol".

- [ ] **Step 3: Implement the envelope**

`companion-android/app/src/main/kotlin/com/aplusconnect/companion/protocol/ProtocolMessage.kt`:

```kotlin
package com.aplusconnect.companion.protocol

import org.json.JSONObject

/** Wire constants for the A+ Connect companion protocol. */
object Protocol {
    const val VERSION = 1

    const val TYPE_HELLO = "hello"
    const val TYPE_HELLO_ACK = "hello-ack"
    const val TYPE_HELLO_ERROR = "hello-error"
    const val TYPE_CLIPBOARD_SET = "clipboard-set"
    const val TYPE_PING = "ping"
    const val TYPE_PONG = "pong"
    const val TYPE_BYE = "bye"

    const val MAX_CLIPBOARD_CHARS = 256 * 1024

    const val REASON_BAD_CODE = "bad-code"
    const val REASON_FINGERPRINT_MISMATCH = "fingerprint-mismatch"
    const val REASON_ALREADY_PAIRED = "already-paired"
    const val REASON_NONCE_MISMATCH = "nonce-mismatch"
    const val REASON_PROTOCOL_VERSION = "protocol-version"
}

/** One protocol envelope. */
data class ProtocolMessage(
    val type: String,
    val id: String,
    val ts: Long,
    val payload: JSONObject,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("v", Protocol.VERSION)
        .put("type", type)
        .put("id", id)
        .put("ts", ts)
        .put("payload", payload)

    companion object {
        fun fromJson(json: JSONObject): ProtocolMessage = ProtocolMessage(
            type = json.getString("type"),
            id = json.getString("id"),
            ts = json.getLong("ts"),
            payload = json.optJSONObject("payload") ?: JSONObject(),
        )
    }
}
```

- [ ] **Step 4: Implement the framing**

`companion-android/app/src/main/kotlin/com/aplusconnect/companion/protocol/FrameCodec.kt`:

```kotlin
package com.aplusconnect.companion.protocol

import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Length-prefixed JSON framing: 4 byte big endian length, then UTF-8 JSON.
 */
object FrameCodec {
    const val MAX_FRAME_BYTES = 1024 * 1024

    fun encode(message: ProtocolMessage): ByteArray {
        val body = message.toJson().toString().toByteArray(StandardCharsets.UTF_8)
        require(body.size <= MAX_FRAME_BYTES) { "frame too large: ${body.size}" }
        val buffer = ByteBuffer.allocate(4 + body.size)
        buffer.putInt(body.size)
        buffer.put(body)
        return buffer.array()
    }

    fun decode(frame: ByteArray): ProtocolMessage {
        require(frame.size >= 4) { "frame too short" }
        val buffer = ByteBuffer.wrap(frame)
        val length = buffer.int
        require(length in 0..MAX_FRAME_BYTES) { "illegal frame length: $length" }
        require(frame.size >= 4 + length) { "truncated frame" }
        val body = String(frame, 4, length, StandardCharsets.UTF_8)
        return ProtocolMessage.fromJson(org.json.JSONObject(body))
    }

    fun readFrom(input: InputStream): ProtocolMessage {
        val header = ByteArray(4)
        DataInputStream(input).readFully(header)
        val length = ByteBuffer.wrap(header).int
        require(length in 0..MAX_FRAME_BYTES) { "illegal frame length: $length" }
        val body = ByteArray(length)
        DataInputStream(input).readFully(body)
        return decode(header + body)
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

```bash
cd companion-android
./gradlew testDebugUnitTest --tests '*FrameCodecTest*'
```

Expected: 4 tests PASS.

- [ ] **Step 6: Add the shared fixture file**

Create `companion-android/app/src/test/resources/fixtures/protocol-frames.json` with this exact content — Task 7's Node script reads the same file:

```json
{
  "frames": [
    {
      "name": "hello",
      "hex": "",
      "json": {
        "v": 1,
        "type": "hello",
        "id": "11111111-1111-1111-1111-111111111111",
        "ts": 1700000000000,
        "payload": {
          "deviceId": "phone-1",
          "deviceName": "OPPO Reno 14 5G",
          "code": "123456",
          "nonce": "abcdef0123456789",
          "protocolVersion": 1
        }
      }
    },
    {
      "name": "hello-ack",
      "hex": "",
      "json": {
        "v": 1,
        "type": "hello-ack",
        "id": "22222222-2222-2222-2222-222222222222",
        "ts": 1700000000001,
        "payload": {
          "sessionId": "session-1",
          "fingerprint": "AA:BB:CC:DD:EE:FF",
          "nonce": "abcdef0123456789"
        }
      }
    },
    {
      "name": "clipboard-set",
      "hex": "",
      "json": {
        "v": 1,
        "type": "clipboard-set",
        "id": "33333333-3333-3333-3333-333333333333",
        "ts": 1700000000002,
        "payload": {
          "text": "hello from the phone",
          "hash": "9f86d081884c7d659a2feaa0c55ad015"
        }
      }
    },
    {
      "name": "ping",
      "hex": "",
      "json": {
        "v": 1,
        "type": "ping",
        "id": "44444444-4444-4444-4444-444444444444",
        "ts": 1700000000003,
        "payload": {}
      }
    }
  ]
}
```

The `hex` fields are filled in by Step 7.

- [ ] **Step 7: Fill the fixture hex values from the Kotlin encoder**

Add a temporary test `companion-android/app/src/test/kotlin/com/aplusconnect/companion/protocol/FixtureWriterTest.kt` that reads `fixtures/protocol-frames.json`, encodes each `json` envelope with `FrameCodec`, and writes the hex back into the file. Run it once:

```bash
cd companion-android
./gradlew testDebugUnitTest --tests '*FixtureWriterTest*'
```

Then delete `FixtureWriterTest.kt` — its only job was to fill the fixture. Verify the file now has a non-empty `hex` for each frame:

```bash
grep -c '"hex": "[0-9a-f]\{8\}' companion-android/app/src/test/resources/fixtures/protocol-frames.json
```

Expected: `4`.

- [ ] **Step 8: Write the permanent fixture test**

`companion-android/app/src/test/kotlin/com/aplusconnect/companion/protocol/ProtocolFixtureTest.kt`:

```kotlin
package com.aplusconnect.companion.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decodes every frame in the shared fixture - including the one the Node side
 * produced - and checks the values, not the raw bytes: the two languages may
 * serialise object keys in a different order.
 */
class ProtocolFixtureTest {

    private fun fixtures(): List<JSONObject> {
        val stream = javaClass.getResourceAsStream("/fixtures/protocol-frames.json")
            ?: error("protocol-frames.json is missing from the test resources")
        val text = stream.bufferedReader().use { it.readText() }
        val array = JSONObject(text).getJSONArray("frames")
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    @Test
    fun `every fixture frame decodes to its documented values`() {
        val frames = fixtures()
        assertTrue("fixture is empty", frames.isNotEmpty())

        for (frame in frames) {
            val bytes = hexToBytes(frame.getString("hex"))
            val decoded = FrameCodec.decode(bytes)
            val expected = frame.getJSONObject("json")

            assertEquals(frame.getString("name"), expected.getString("type"), decoded.type)
            assertEquals(expected.getString("id"), decoded.id)
            assertEquals(expected.getLong("ts"), decoded.ts)
            assertEquals(expected.getInt("v"), Protocol.VERSION)
            assertEquals(
                expected.getJSONObject("payload").toString(),
                decoded.payload.toString()
            )
        }
    }

    @Test
    fun `a frame produced by the node side decodes here too`() {
        val stream = javaClass.getResourceAsStream("/fixtures/protocol-frames-node.json")
        ?: return // generated by scripts/protocol-check.js --write, not yet present
        val array = JSONObject(stream.bufferedReader().use { it.readText() }).getJSONArray("frames")

        for (index in 0 until array.length()) {
            val frame = array.getJSONObject(index)
            val decoded = FrameCodec.decode(hexToBytes(frame.getString("hex")))
            assertEquals(frame.getJSONObject("json").getString("type"), decoded.type)
            assertEquals(frame.getJSONObject("json").getString("id"), decoded.id)
        }
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
```

- [ ] **Step 9: Run the protocol tests**

```bash
cd companion-android
./gradlew testDebugUnitTest --tests '*ProtocolFixtureTest*'
```

Expected: 2 tests PASS (the second skips its body while the Node fixture is absent).

- [ ] **Step 10: Commit**

```bash
git add companion-android
git commit -m "feat: add the companion wire protocol framing"
```

---

### Task 5: Clipboard payload rules

**Files:**
- Create: `companion-android/app/src/main/kotlin/com/aplusconnect/companion/protocol/ClipboardPayload.kt`
- Create: `companion-android/app/src/test/kotlin/com/aplusconnect/companion/protocol/ClipboardPayloadTest.kt`

**Interfaces:**
- Consumes: `Protocol.MAX_CLIPBOARD_CHARS` from Task 4.
- Produces:
  - `data class ClipboardPayload(val text: String, val hash: String, val truncated: Boolean)`
  - `object ClipboardPayloads { fun build(text: String): ClipboardPayload; fun shouldSend(payload: ClipboardPayload, lastSentHash: String?): Boolean; fun toJson(payload: ClipboardPayload): JSONObject }`

The rules live in the protocol layer so the phone and the PC cannot disagree about what may
cross the wire: both apply the same 256 KiB cap, the same truncation flag and the same
dedupe rule.

- [ ] **Step 1: Write the failing test**

`companion-android/app/src/test/kotlin/com/aplusconnect/companion/protocol/ClipboardPayloadTest.kt`:

```kotlin
package com.aplusconnect.companion.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardPayloadTest {

    @Test
    fun `short text is not truncated`() {
        val payload = ClipboardPayloads.build("hello")

        assertEquals("hello", payload.text)
        assertFalse(payload.truncated)
    }

    @Test
    fun `long text is truncated and flagged`() {
        val payload = ClipboardPayloads.build("x".repeat(300_000))

        assertEquals(Protocol.MAX_CLIPBOARD_CHARS, payload.text.length)
        assertTrue(payload.truncated)
    }

    @Test
    fun `the hash is the sha256 of the transmitted text`() {
        val payload = ClipboardPayloads.build("abc")

        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            payload.hash
        )
    }

    @Test
    fun `the hash covers the truncated text`() {
        val payload = ClipboardPayloads.build("x".repeat(300_000))
        val expected = ClipboardPayloads.build("x".repeat(Protocol.MAX_CLIPBOARD_CHARS)).hash

        assertEquals(expected, payload.hash)
    }

    @Test
    fun `the same content is not sent twice`() {
        val payload = ClipboardPayloads.build("same")

        assertTrue(ClipboardPayloads.shouldSend(payload, lastSentHash = null))
        assertFalse(ClipboardPayloads.shouldSend(payload, lastSentHash = payload.hash))
    }

    @Test
    fun `different content is sent`() {
        val payload = ClipboardPayloads.build("new")

        assertTrue(ClipboardPayloads.shouldSend(payload, lastSentHash = "some-other-hash"))
    }

    @Test
    fun `empty text is never sent`() {
        val payload = ClipboardPayloads.build("")

        assertFalse(ClipboardPayloads.shouldSend(payload, lastSentHash = null))
    }

    @Test
    fun `the json payload carries the text hash and truncation flag`() {
        val json = ClipboardPayloads.toJson(ClipboardPayloads.build("hello"))

        assertEquals("hello", json.getString("text"))
        assertFalse(json.getBoolean("truncated"))
        assertTrue(json.getString("hash").isNotEmpty())
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd companion-android
./gradlew testDebugUnitTest --tests '*ClipboardPayloadTest*'
```

Expected: FAIL — "Unresolved reference: ClipboardPayloads".

- [ ] **Step 3: Implement the payload rules**

`companion-android/app/src/main/kotlin/com/aplusconnect/companion/protocol/ClipboardPayload.kt`:

```kotlin
package com.aplusconnect.companion.protocol

import org.json.JSONObject
import java.security.MessageDigest

/** What actually crosses the wire for one clipboard change. */
data class ClipboardPayload(
    val text: String,
    val hash: String,
    val truncated: Boolean,
)

/** The rules both sides apply, so the two can never disagree. */
object ClipboardPayloads {

    fun build(text: String): ClipboardPayload {
        val truncated = text.length > Protocol.MAX_CLIPBOARD_CHARS
        val body = if (truncated) text.substring(0, Protocol.MAX_CLIPBOARD_CHARS) else text
        return ClipboardPayload(body, sha256(body), truncated)
    }

    /** False when this content is empty or is the same content we already sent. */
    fun shouldSend(payload: ClipboardPayload, lastSentHash: String?): Boolean {
        if (payload.text.isEmpty()) return false
        return payload.hash != lastSentHash
    }

    fun toJson(payload: ClipboardPayload): JSONObject = JSONObject()
        .put("text", payload.text)
        .put("hash", payload.hash)
        .put("truncated", payload.truncated)

    fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd companion-android
./gradlew testDebugUnitTest --tests '*ClipboardPayloadTest*'
```

Expected: 8 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add companion-android
git commit -m "feat: define the clipboard payload rules"
```

---

### Task 6: Handshake and certificate fingerprint pinning (Kotlin)

**Files:**
- Create: `companion-android/app/src/main/kotlin/com/aplusconnect/companion/protocol/Handshake.kt`
- Create: `companion-android/app/src/test/kotlin/com/aplusconnect/companion/protocol/HandshakeTest.kt`

**Interfaces:**
- Consumes: `Protocol`, `ProtocolMessage` from Task 4.
- Produces:
  - `data class Pairing(val pcId: String, val fingerprint: String)`
  - `sealed class HandshakeResult { data class Success(val sessionId: String, val pcId: String, val fingerprint: String); data class Failed(val reason: String) }`
  - `object Handshake { fun fingerprintOf(certificate: X509Certificate): String; fun fingerprintOfHex(der: ByteArray): String; fun buildHello(deviceId: String, deviceName: String, code: String, nonce: String): ProtocolMessage; fun verifyHelloAck(message: ProtocolMessage, pinned: Pairing?, expectedNonce: String? = null): HandshakeResult }`

The PC identifies itself with a stable `pcId` **and** its certificate fingerprint. That
distinction matters: a different `pcId` means another computer is trying to pair, while
the same `pcId` with a different fingerprint means the paired computer's certificate was
regenerated. The two failures need different messages, so they must be distinguishable.

- [ ] **Step 1: Write the failing test**

`companion-android/app/src/test/kotlin/com/aplusconnect/companion/protocol/HandshakeTest.kt`:

```kotlin
package com.aplusconnect.companion.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HandshakeTest {

    private fun helloAck(
        pcId: String = "pc-1",
        fingerprint: String = "AA:BB:CC",
        nonce: String = "nonce-1",
        type: String = Protocol.TYPE_HELLO_ACK,
    ) = ProtocolMessage(
        type = type,
        id = "ack",
        ts = 1L,
        payload = JSONObject()
            .put("sessionId", "session-1")
            .put("pcId", pcId)
            .put("fingerprint", fingerprint)
            .put("nonce", nonce),
    )

    @Test
    fun `hello carries the device identity code and nonce`() {
        val hello = Handshake.buildHello("phone-1", "OPPO Reno 14 5G", "123456", "nonce-1")

        assertEquals(Protocol.TYPE_HELLO, hello.type)
        assertEquals("phone-1", hello.payload.getString("deviceId"))
        assertEquals("OPPO Reno 14 5G", hello.payload.getString("deviceName"))
        assertEquals("123456", hello.payload.getString("code"))
        assertEquals("nonce-1", hello.payload.getString("nonce"))
        assertEquals(Protocol.VERSION, hello.payload.getInt("protocolVersion"))
    }

    @Test
    fun `first pairing accepts any fingerprint and reports it`() {
        val result = Handshake.verifyHelloAck(helloAck(), pinned = null)

        assertTrue(result is HandshakeResult.Success)
        result as HandshakeResult.Success
        assertEquals("AA:BB:CC", result.fingerprint)
        assertEquals("pc-1", result.pcId)
    }

    @Test
    fun `the paired computer reconnects with the same certificate`() {
        val result = Handshake.verifyHelloAck(
            helloAck(),
            pinned = Pairing("pc-1", "AA:BB:CC")
        )

        assertTrue(result is HandshakeResult.Success)
    }

    @Test
    fun `a changed certificate is refused`() {
        val result = Handshake.verifyHelloAck(
            helloAck(fingerprint = "11:22:33"),
            pinned = Pairing("pc-1", "AA:BB:CC")
        )

        assertEquals(
            Protocol.REASON_FINGERPRINT_MISMATCH,
            (result as HandshakeResult.Failed).reason
        )
    }

    @Test
    fun `a second pairing is refused`() {
        val result = Handshake.verifyHelloAck(
            helloAck(pcId = "pc-2", fingerprint = "11:22:33"),
            pinned = Pairing("pc-1", "AA:BB:CC")
        )

        assertEquals(
            Protocol.REASON_ALREADY_PAIRED,
            (result as HandshakeResult.Failed).reason
        )
    }

    @Test
    fun `a nonce that does not match the hello is refused`() {
        val result = Handshake.verifyHelloAck(
            helloAck(nonce = "other-nonce"),
            pinned = Pairing("pc-1", "AA:BB:CC"),
            expectedNonce = "nonce-1"
        )

        assertEquals(
            Protocol.REASON_NONCE_MISMATCH,
            (result as HandshakeResult.Failed).reason
        )
    }

    @Test
    fun `an error reply carries its reason`() {
        val result = Handshake.verifyHelloAck(
            helloAck(type = Protocol.TYPE_HELLO_ERROR),
            pinned = null
        )

        assertEquals(
            Protocol.REASON_BAD_CODE,
            (result as HandshakeResult.Failed).reason
        )
    }

    @Test
    fun `a fingerprint is uppercase colon separated hex`() {
        val fingerprint = Handshake.fingerprintOfHex(ByteArray(3) { 0x0a.toByte() })

        assertEquals("0A:0A:0A", fingerprint)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd companion-android
./gradlew testDebugUnitTest --tests '*HandshakeTest*'
```

Expected: FAIL — "Unresolved reference: Handshake".

- [ ] **Step 3: Implement the handshake**

`companion-android/app/src/main/kotlin/com/aplusconnect/companion/protocol/Handshake.kt`:

```kotlin
package com.aplusconnect.companion.protocol

import org.json.JSONObject
import java.security.MessageDigest
import java.security.cert.X509Certificate

/** The computer this phone is paired with. */
data class Pairing(val pcId: String, val fingerprint: String)

/** Outcome of verifying the PC's reply to our hello. */
sealed class HandshakeResult {
    data class Success(val sessionId: String, val pcId: String, val fingerprint: String) : HandshakeResult()
    data class Failed(val reason: String) : HandshakeResult()
}

/**
 * The pairing handshake. The phone pins the PC's certificate fingerprint on
 * first contact and refuses any later certificate that differs.
 */
object Handshake {

    fun buildHello(deviceId: String, deviceName: String, code: String, nonce: String): ProtocolMessage =
        ProtocolMessage(
            type = Protocol.TYPE_HELLO,
            id = java.util.UUID.randomUUID().toString(),
            ts = System.currentTimeMillis(),
            payload = JSONObject()
                .put("deviceId", deviceId)
                .put("deviceName", deviceName)
                .put("code", code)
                .put("nonce", nonce)
                .put("protocolVersion", Protocol.VERSION),
        )

    fun verifyHelloAck(
        message: ProtocolMessage,
        pinned: Pairing?,
        expectedNonce: String? = null,
    ): HandshakeResult {
        if (message.type == Protocol.TYPE_HELLO_ERROR) {
            return HandshakeResult.Failed(
                message.payload.optString("reason", Protocol.REASON_BAD_CODE)
            )
        }
        if (message.type != Protocol.TYPE_HELLO_ACK) {
            return HandshakeResult.Failed(Protocol.REASON_PROTOCOL_VERSION)
        }
        if (expectedNonce != null && message.payload.optString("nonce") != expectedNonce) {
            return HandshakeResult.Failed(Protocol.REASON_NONCE_MISMATCH)
        }

        val pcId = message.payload.optString("pcId")
        val fingerprint = message.payload.optString("fingerprint")

        if (pinned != null) {
            if (pcId != pinned.pcId) return HandshakeResult.Failed(Protocol.REASON_ALREADY_PAIRED)
            if (fingerprint != pinned.fingerprint) {
                return HandshakeResult.Failed(Protocol.REASON_FINGERPRINT_MISMATCH)
            }
        }

        return HandshakeResult.Success(
            sessionId = message.payload.optString("sessionId"),
            pcId = pcId,
            fingerprint = fingerprint,
        )
    }

    /** SHA-256 of the DER encoded certificate, uppercase colon separated hex. */
    fun fingerprintOf(certificate: X509Certificate): String = fingerprintOfHex(certificate.encoded)

    fun fingerprintOfHex(der: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(der)
        return digest.joinToString(":") { String.format("%02X", it) }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd companion-android
./gradlew testDebugUnitTest --tests '*HandshakeTest*'
```

Expected: 8 tests PASS.

- [ ] **Step 5: Run the whole protocol test suite**

```bash
cd companion-android
./gradlew testDebugUnitTest
```

Expected: all tests PASS, no failures.

- [ ] **Step 6: Commit**

```bash
git add companion-android
git commit -m "feat: pair phones with a pinned certificate fingerprint"
```

---

### Task 7: PC-side protocol mirror and cross-implementation conformance

**Files:**
- Create: `services/companion-protocol.js`
- Create: `services/companion-cert.js`
- Create: `scripts/protocol-check.js`
- Modify: none (the existing clipboard service is not touched in this plan)

**Interfaces:**
- Consumes: the fixture files from Task 4 (`companion-android/app/src/test/resources/fixtures/protocol-frames.json`).
- Produces:
  - `services/companion-protocol.js` exporting `{ PROTOCOL_VERSION, MAX_FRAME_BYTES, MAX_CLIPBOARD_CHARS, TYPES, REASONS, encode(message), decode(frame), buildClipboardPayload(text), shouldSendClipboard(payload, lastSentHash) }`
  - `services/companion-cert.js` exporting `{ ensureCertificate(dir), fingerprintOfDer(der) }` → `{ certPath, keyPath, fingerprint }`
  - `scripts/protocol-check.js` — exits 0 when every fixture frame decodes on the Node side, round-trips, and matches the clipboard payload rules. With `--write` it also emits `protocol-frames-node.json` for the Kotlin side to read.

**Why values and not bytes:** `org.json` on the JVM and `JSON.stringify` in Node serialise object keys in different orders, so byte-identical frames are not achievable — and not required. The two sides are conformant when they agree on the *values* of every field and can each read what the other wrote. The fixture proves both directions: the Kotlin side writes `protocol-frames.json`, Node writes `protocol-frames-node.json`, and each side decodes the other's file.

- [ ] **Step 1: Write the failing conformance script**

Create `scripts/protocol-check.js`:

```javascript
#!/usr/bin/env node
/**
 * Cross-implementation conformance check for the companion protocol.
 *
 * Decodes every frame in the shared fixture (produced by the Kotlin side),
 * compares the values, round-trips each frame, and checks the clipboard
 * payload rules. With --write it also emits the fixture that the Kotlin side
 * reads back, which is what proves the two implementations agree in both
 * directions.
 *
 * Usage: node scripts/protocol-check.js [--write]
 */
const fs = require('fs');
const path = require('path');
const assert = require('assert');

const protocol = require('../services/companion-protocol');

const FIXTURE_DIR = path.join(
  __dirname,
  '..',
  'companion-android',
  'app',
  'src',
  'test',
  'resources',
  'fixtures'
);
const KOTLIN_FIXTURE = path.join(FIXTURE_DIR, 'protocol-frames.json');
const NODE_FIXTURE = path.join(FIXTURE_DIR, 'protocol-frames-node.json');

function readFrames(file) {
  const frames = JSON.parse(fs.readFileSync(file, 'utf8')).frames;
  return frames;
}

function checkFrame(frame) {
  const bytes = Buffer.from(frame.hex, 'hex');
  const message = protocol.decode(bytes);
  const expected = frame.json;

  assert.strictEqual(message.version, expected.v, `${frame.name}: version mismatch`);
  assert.strictEqual(message.type, expected.type, `${frame.name}: type mismatch`);
  assert.strictEqual(message.id, expected.id, `${frame.name}: id mismatch`);
  assert.strictEqual(message.ts, expected.ts, `${frame.name}: ts mismatch`);
  assert.deepStrictEqual(message.payload, expected.payload, `${frame.name}: payload mismatch`);

  const roundTripped = protocol.decode(protocol.encode(message));
  assert.deepStrictEqual(roundTripped, message, `${frame.name}: round trip changed the message`);
}

function checkClipboardRules() {
  const long = 'x'.repeat(300000);
  const payload = protocol.buildClipboardPayload(long);
  assert.strictEqual(payload.text.length, protocol.MAX_CLIPBOARD_CHARS, 'clipboard was not truncated');
  assert.strictEqual(payload.truncated, true, 'truncation was not flagged');
  assert.strictEqual(
    payload.hash,
    protocol.buildClipboardPayload('x'.repeat(protocol.MAX_CLIPBOARD_CHARS)).hash,
    'hash does not cover the transmitted text'
  );

  const short = protocol.buildClipboardPayload('abc');
  assert.strictEqual(
    short.hash,
    'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad',
    'hash is not the sha256 of the text'
  );

  assert.strictEqual(protocol.shouldSendClipboard(short, null), true, 'first send was suppressed');
  assert.strictEqual(protocol.shouldSendClipboard(short, short.hash), false, 'repeat was not suppressed');
  assert.strictEqual(
    protocol.shouldSendClipboard(protocol.buildClipboardPayload(''), null),
    false,
    'empty clipboard was not suppressed'
  );
}

function writeNodeFixture() {
  const frames = readFrames(KOTLIN_FIXTURE).map((frame) => {
    const message = protocol.decode(Buffer.from(frame.hex, 'hex'));
    return {
      name: frame.name,
      hex: protocol.encode(message).toString('hex'),
      json: frame.json,
    };
  });
  fs.writeFileSync(NODE_FIXTURE, `${JSON.stringify({ frames }, null, 2)}\n`);
  console.log(`wrote ${frames.length} frames to ${NODE_FIXTURE}`);
}

function main() {
  const frames = readFrames(KOTLIN_FIXTURE);
  assert.ok(frames.length > 0, 'the fixture is empty');

  for (const frame of frames) checkFrame(frame);
  checkClipboardRules();

  if (process.argv.includes('--write')) writeNodeFixture();

  console.log(`protocol-check: ${frames.length} frames decoded, round tripped and matched`);
}

main();
```

- [ ] **Step 2: Run it to verify it fails**

```bash
node scripts/protocol-check.js
```

Expected: FAIL with `Cannot find module '../services/companion-protocol'`.

- [ ] **Step 3: Implement the Node protocol mirror**

Create `services/companion-protocol.js`:

```javascript
'use strict';

/**
 * Node side of the A+ Connect companion wire protocol.
 *
 * Mirrors companion-android/.../protocol/FrameCodec.kt exactly: a 4 byte big
 * endian length prefix followed by UTF-8 JSON. Keep the two in step - the
 * shared fixtures in the Android test resources are what prove it.
 */

const crypto = require('crypto');

const PROTOCOL_VERSION = 1;
const MAX_FRAME_BYTES = 1024 * 1024;
const MAX_CLIPBOARD_CHARS = 256 * 1024;

const TYPES = {
  HELLO: 'hello',
  HELLO_ACK: 'hello-ack',
  HELLO_ERROR: 'hello-error',
  CLIPBOARD_SET: 'clipboard-set',
  PING: 'ping',
  PONG: 'pong',
  BYE: 'bye',
};

const REASONS = {
  BAD_CODE: 'bad-code',
  FINGERPRINT_MISMATCH: 'fingerprint-mismatch',
  ALREADY_PAIRED: 'already-paired',
  NONCE_MISMATCH: 'nonce-mismatch',
  PROTOCOL_VERSION: 'protocol-version',
};

function encode(message) {
  const body = Buffer.from(JSON.stringify(toJson(message)), 'utf8');
  if (body.length > MAX_FRAME_BYTES) {
    throw new Error(`frame too large: ${body.length}`);
  }
  const frame = Buffer.allocUnsafe(4 + body.length);
  frame.writeInt32BE(body.length, 0);
  body.copy(frame, 4);
  return frame;
}

function decode(frame) {
  if (frame.length < 4) throw new Error('frame too short');
  const length = frame.readInt32BE(0);
  if (length < 0 || length > MAX_FRAME_BYTES) {
    throw new Error(`illegal frame length: ${length}`);
  }
  if (frame.length < 4 + length) throw new Error('truncated frame');
  const parsed = JSON.parse(frame.subarray(4, 4 + length).toString('utf8'));
  return {
    version: parsed.v,
    type: parsed.type,
    id: parsed.id,
    ts: parsed.ts,
    payload: parsed.payload || {},
  };
}

function toJson(message) {
  return {
    v: message.version !== undefined ? message.version : PROTOCOL_VERSION,
    type: message.type,
    id: message.id,
    ts: message.ts,
    payload: message.payload || {},
  };
}

/** The clipboard rules, identical to ClipboardPayloads.kt on the phone. */
function buildClipboardPayload(text) {
  const truncated = text.length > MAX_CLIPBOARD_CHARS;
  const body = truncated ? text.substring(0, MAX_CLIPBOARD_CHARS) : text;
  return {
    text: body,
    hash: crypto.createHash('sha256').update(body, 'utf8').digest('hex'),
    truncated,
  };
}

function shouldSendClipboard(payload, lastSentHash) {
  if (!payload.text) return false;
  return payload.hash !== lastSentHash;
}

module.exports = {
  PROTOCOL_VERSION,
  MAX_FRAME_BYTES,
  MAX_CLIPBOARD_CHARS,
  TYPES,
  REASONS,
  encode,
  decode,
  buildClipboardPayload,
  shouldSendClipboard,
};
```

- [ ] **Step 4: Run the conformance script to verify it passes**

```bash
node scripts/protocol-check.js
```

Expected: `protocol-check: 4 frames decoded, round tripped and matched`.

- [ ] **Step 5: Generate the Node fixture and read it back from Kotlin**

```bash
node scripts/protocol-check.js --write
cd companion-android && ./gradlew testDebugUnitTest --tests '*ProtocolFixtureTest*' && cd ..
```

Expected: `wrote 4 frames to .../protocol-frames-node.json` and the Kotlin fixture test PASSES with both files present (the second test no longer skips).

- [ ] **Step 6: Implement certificate generation**

Create `services/companion-cert.js`:

```javascript
'use strict';

/**
 * The PC's self-signed certificate for the companion TLS server, plus its
 * SHA-256 fingerprint. The phone pins that fingerprint, so it is generated
 * once and reused for every connection.
 */

const { execFileSync } = require('child_process');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const DEFAULT_DIR = path.join(
  process.env.HOME || process.env.USERPROFILE || '.',
  '.config',
  'aplus-connect',
  'companion'
);

function ensureCertificate(dir = DEFAULT_DIR) {
  fs.mkdirSync(dir, { recursive: true });

  const certPath = path.join(dir, 'cert.pem');
  const keyPath = path.join(dir, 'key.pem');

  if (!fs.existsSync(certPath) || !fs.existsSync(keyPath)) {
    execFileSync('openssl', [
      'req', '-x509', '-newkey', 'rsa:2048', '-nodes',
      '-keyout', keyPath,
      '-out', certPath,
      '-days', '3650',
      '-subj', '/CN=A+ Connect Companion',
    ], { stdio: 'ignore' });
  }

  return { certPath, keyPath, fingerprint: fingerprintOfFile(certPath) };
}

/** SHA-256 of the DER encoded certificate, uppercase colon separated hex. */
function fingerprintOfFile(certPath) {
  const pem = fs.readFileSync(certPath, 'utf8');
  const der = Buffer.from(
    pem.replace(/-----(BEGIN|END) CERTIFICATE-----/g, '').replace(/\s+/g, ''),
    'base64'
  );
  return fingerprintOfDer(der);
}

function fingerprintOfDer(der) {
  return crypto
    .createHash('sha256')
    .update(der)
    .digest('hex')
    .toUpperCase()
    .replace(/(..)(?=.)/g, '$1:');
}

module.exports = { ensureCertificate, fingerprintOfDer, DEFAULT_DIR };
```

- [ ] **Step 7: Verify the certificate helper produces a stable fingerprint**

```bash
node -e "
const { ensureCertificate } = require('./services/companion-cert');
const a = ensureCertificate();
const b = ensureCertificate();
if (a.fingerprint !== b.fingerprint) { throw new Error('fingerprint is not stable'); }
if (!/^[0-9A-F]{2}(:[0-9A-F]{2}){31}\$/.test(a.fingerprint)) { throw new Error('bad fingerprint format: ' + a.fingerprint); }
console.log('cert ok:', a.fingerprint);
"
```

Expected: `cert ok: AA:BB:...` — 64 hex characters, colon separated, identical on the second call.

- [ ] **Step 8: Run both sides together**

```bash
node scripts/protocol-check.js
cd companion-android && ./gradlew testDebugUnitTest && cd ..
```

Expected: the conformance script passes and all Kotlin tests PASS.

- [ ] **Step 9: Commit**

```bash
git add services/companion-protocol.js services/companion-cert.js scripts/protocol-check.js companion-android
git commit -m "feat: mirror the companion protocol on the PC side"
```

## Out of Scope for This Plan

- The `CompanionService` foreground service, the TLS client, the clipboard gateway and the
  Compose UI (phase P2).
- The PC's TLS server and its integration with the existing clipboard pipeline (phase P3).
- End-to-end verification over LAN and USB tethering (phase P4).
- Signing, packaging and delivery of the APK (phase P5).

Those follow in a second plan, written after the spike results in
`docs/companion/spike-results.md` are known.
