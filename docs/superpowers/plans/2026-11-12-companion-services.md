# A+ Connect without ADB — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship notifications, playback control, file transfer and screen mirroring
on the companion channel, so the phone needs no USB debugging, no wireless debugging
and no developer options — everything comes from the A+ Connect app on both sides.

**Architecture:** The phone dials out to the PC over TLS and holds no listening port.
Today the clipboard already rides that channel; the four features below reuse the
same transport, the same pairing, and the same "one transport per event" rule
(companion if connected, otherwise the ADB path — never both). The phone side is a
foreground service that owns the connection plus small, separate feature services
(a notification listener, a media-session bridge, a SAF file browser, a
MediaProjection capture) that hand their events to the connection. The desktop side
extends `services/companion.js` and the existing renderer panels.

**Tech Stack:** Kotlin (minSdk 26, compileSdk 35, Jetpack Compose for the app UI,
plain Android views/services for the feature services), Electron (renderer = plain
script, no bundler), Node for the desktop services, JUnit for the Android unit tests,
plain `node` scripts for the desktop checks (this repo has **no test runner**).

**Spec:** `docs/superpowers/specs/2026-11-11-companion-apk-design.md` (the original
design; the clipboard trigger in it is superseded — see `docs/companion/keyboard-fork.md`),
`docs/companion/keyboard-fork.md`, `docs/companion/payment-block-findings.md`,
`docs/companion/spike-results.md`.

## Global Constraints

- **No ADB, no developer options, no wireless debugging.** Every feature must work
  with the phone in its shipping state. ADB may be used by a developer to *check*
  something, never as a dependency of the feature.
- **No accessibility service** in the shipping app: Google Wallet refuses
  contactless payments while any accessibility service is enabled
  (`AccessibilityManager.getEnabledAccessibilityServiceList()`).
- **The phone never listens.** The companion client dials the PC; no inbound port,
  no listening socket, ever.
- **One transport per event.** If the companion is connected, the event comes over
  the companion channel; otherwise over the existing ADB path. Never both, never a
  duplicate.
- **Frame format:** 4-byte big-endian length prefix, then a 1-byte frame kind, then
  the payload. Kind `0` = UTF-8 JSON (as today), kind `1` = binary. The frame limit
  stays 2 MiB. `PROTOCOL_VERSION` goes to `2`; both sides ship together.
- **Permissions are optional and explained.** Notification access, screen projection
  and the file picker are each requested from the app's Settings tab, in plain
  English, with the reason. The app works without any of them.
- **No new runtime dependencies** on the phone unless a task says otherwise; the app
  already has Compose, CameraX and ZXing.
- **The keyboard fork is a separate APK** (GPL-3.0, `com.aplusconnect.keyboard`); the
  companion app stays MIT. Do not move keyboard code into this app.
- **Desktop verification is `node scripts/<name>.js`** — there is no test runner in
  this repo. Android verification is `./gradlew :app:testDebugUnitTest`.
- **Commit messages: one imperative line, ≤ 72 characters**, no body, no trailers.

## Review Focus

Five ways this can hurt the user even though every test passes:

1. **A notification that arrives while the PC is offline.** It must be queued and
   delivered once, not dropped and not replayed as a duplicate.
2. **Notification content is private.** It must never be written to a file on the PC,
   and must never be sent when the companion channel is not connected.
3. **The wrong media session.** The session token changes when the user switches
   apps; a command must go to the session it was issued for, never to whichever
   session happens to be playing now.
4. **File browsing must be read-only.** Browsing and sending must never move,
   rename or delete anything on the phone.
5. **A revoked projection.** The user can revoke screen sharing from the status bar
   at any moment; the capture must die then, not keep the screen.

---

# Part 0 — Protocol scaffolding

### Task 1: Frame kinds and protocol version 2

**Files:**
- Modify: `companion-android/app/src/main/kotlin/com/aplusconnect/companion/protocol/FrameCodec.kt`
- Modify: `companion-android/app/src/main/kotlin/com/aplusconnect/companion/protocol/ProtocolMessage.kt`
- Modify: `services/companion-protocol.js`
- Modify: `scripts/protocol-check.js`
- Test: `companion-android/app/src/test/kotlin/com/aplusconnect/companion/protocol/FrameCodecTest.kt`
- Test: `companion-android/app/src/test/resources/fixtures/frames.json` (the shared fixture file both sides read)

**Interfaces:**
- Consumes: nothing (first task).
- Produces: `FrameCodec.encodeBinary(bytes: ByteArray): ByteArray` and
  `FrameCodec.decodeBinary(frame: ByteArray): ByteArray?` on Android;
  `protocol.encodeBinary(bytes)` / `protocol.decodeBinary(frame)` in Node;
  `protocol.PROTOCOL_VERSION === 2`; `protocol.TYPES` unchanged in content but the
  version constant moves to 2.

- [ ] **Step 1: Write the failing test** — in `FrameCodecTest.kt` add
  `binary frames round trip` : encode `byteArrayOf(0, 1, 2, 250)` with
  `encodeBinary`, decode it with `decodeBinary`, assert the bytes are equal; and
  `a JSON frame is not a binary frame`: `decodeBinary(encode(jsonMessage))` returns
  null.
- [ ] **Step 2: Run the test to verify it fails** —
  `./gradlew :app:testDebugUnitTest --tests '*FrameCodecTest*'` → fails with
  "unresolved reference: encodeBinary".
- [ ] **Step 3: Implement** `encodeBinary`/`decodeBinary` in `FrameCodec.kt`: the
  length prefix counts the kind byte plus the payload; kind `1` is binary, kind `0`
  is JSON. Mirror it in `services/companion-protocol.js` as `encodeBinary` /
  `decodeBinary`, and bump `PROTOCOL_VERSION` to `2` in **both** files.
- [ ] **Step 4: Add the shared fixture** — write one binary frame (the bytes above)
  into `fixtures/frames.json` as `{"kind":"binary","hex":"000102fa"}`, and make
  `scripts/protocol-check.js` assert that both implementations produce that exact
  byte sequence for it.
- [ ] **Step 5: Run the checks** — `./gradlew :app:testDebugUnitTest` (all green) and
  `node scripts/protocol-check.js` (prints `protocol-check: frames matched`).
- [ ] **Step 6: Commit** — `git add -A && git commit -m "feat: add binary frames and protocol version 2"`.

### Task 2: Message types for the four features

**Files:**
- Create: `companion-android/app/src/main/kotlin/com/aplusconnect/companion/protocol/FeaturePayloads.kt`
- Modify: `services/companion-protocol.js`
- Test: `companion-android/app/src/test/kotlin/com/aplusconnect/companion/protocol/FeaturePayloadsTest.kt`
- Test: `scripts/protocol-check.js` (extended)

**Interfaces:**
- Consumes: `FrameCodec` from Task 1.
- Produces: `FeaturePayloads.notification(id, pkg, app, title, text, ts, ongoing, actions)`,
  `FeaturePayloads.mediaState(app, title, artist, album, playing, positionMs, durationMs)`,
  `FeaturePayloads.fileList(path, entries)`, and the parsers
  `FeaturePayloads.parseNotification(json)`, `FeaturePayloads.parseMediaState(json)`,
  `FeaturePayloads.parseFileList(json)`; plus the new type strings in
  `ProtocolMessage.TYPES`: `notification`, `notification-removed`, `notification-action`,
  `media-state`, `media-command`, `file-list-request`, `file-list`, `mirror-start`,
  `mirror-stop`.

- [ ] **Step 1: Write the failing test** — `FeaturePayloadsTest.kt`: build a
  notification payload, parse it back, assert `id`, `title`, `text` and that
  `actions` keeps its order; build a media-state payload and assert `playing` and
  `positionMs` survive; assert `parseNotification` on a JSON object without `id`
  returns null.
- [ ] **Step 2: Run it to verify it fails** — `./gradlew :app:testDebugUnitTest --tests '*FeaturePayloadsTest*'` → unresolved reference.
- [ ] **Step 3: Implement** `FeaturePayloads` with the exact JSON keys:
  notification → `{type,id,pkg,app,title,text,ts,ongoing,actions:[{id,title}]}`;
  media-state → `{type,app,title,artist,album,playing,positionMs,durationMs}`;
  file-list → `{type,path,entries:[{name,size,isDir,mime}]}`.
  Mirror the same builders in `services/companion-protocol.js` as
  `buildNotification`, `buildMediaState`, `buildFileList` and their parsers, and add
  the nine new strings to `TYPES` in both files.
- [ ] **Step 4: Extend `scripts/protocol-check.js`** so it builds one payload of each
  kind with the Node builders, decodes it with the Android-shaped fixture rules, and
  asserts the JSON matches the Kotlin builder's JSON byte for byte (the fixture file
  holds the expected JSON strings).
- [ ] **Step 5: Run the checks** — `./gradlew :app:testDebugUnitTest` and
  `node scripts/protocol-check.js` → both green.
- [ ] **Step 6: Commit** — `git commit -m "feat: add the message types for the four features"`.

---

# Part 1 — Notifications

### Task 3: The phone listens to notifications

**Files:**
- Create: `companion-android/app/src/main/kotlin/com/aplusconnect/companion/service/PhoneNotificationListener.kt`
- Modify: `companion-android/app/src/main/AndroidManifest.xml`
- Modify: `companion-android/app/src/main/kotlin/com/aplusconnect/companion/service/CompanionService.kt`
- Test: `companion-android/app/src/test/kotlin/com/aplusconnect/companion/protocol/FeaturePayloadsTest.kt` (extend)

**Interfaces:**
- Consumes: `FeaturePayloads.notification` (Task 2), `CompanionService.send(text)`
  is **not** reused — notifications use their own queue, see below.
- Produces: `class PhoneNotificationListener : NotificationListenerService` with
  `override fun onNotificationPosted(sbn: StatusBarNotification)` and
  `override fun onNotificationRemoved(sbn: StatusBarNotification)`; both forward a
  `notification` / `notification-removed` message to `CompanionService`, which queues
  them when the channel is not connected and flushes them in order when it is.
  `CompanionService.Status.lastNotification` holds the newest one for the app UI.

- [ ] **Step 1: Write the failing test** — extend `FeaturePayloadsTest.kt` with
  `a notification id is stable for the same notification`: the id built from
  `pkg=com.example`, `id=7`, `tag=null` equals the id built from the same three
  values, and differs when the tag is `x`.
- [ ] **Step 2: Run it to verify it fails** — unresolved reference to the id helper.
- [ ] **Step 3: Implement** the listener: `onNotificationPosted` extracts the
  package, `sbn.id`, `sbn.tag`, the title and text from `sbn.notification.extras`
  (`EXTRA_TITLE`, `EXTRA_TEXT`, `EXTRA_BIG_TEXT`), the `actions` from `sbn.actions`,
  and builds the payload with `FeaturePayloads.notification`. Register it in the
  manifest with `android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"`.
- [ ] **Step 4: Queue it** — `CompanionService` gets `sendNotification(payload)` and
  `sendNotificationRemoved(id)`, which append to a `ArrayDeque` when the client is
  not ready and flush in order on connect; the same id is never sent twice.
- [ ] **Step 5: Run the checks** — `./gradlew :app:testDebugUnitTest` green, and
  `adb shell cmd notification` is **not** used anywhere in the implementation.
- [ ] **Step 6: Commit** — `git commit -m "feat: forward the phone's notifications over the companion channel"`.

### Task 4: The desktop shows notifications from the companion

**Files:**
- Modify: `services/companion.js`
- Modify: `services/notifications.js`
- Modify: `main.js`, `preload.js`
- Modify: `src/renderer.js`, `src/index.html`
- Test: `scripts/check-companion-notifications.js` (new)

**Interfaces:**
- Consumes: the `notification` and `notification-removed` messages (Task 2).
- Produces: `services/companion.js` emits `'notification-received'` with the same
  shape `services/notifications.js` already emits (`{id, app, title, text, time}`),
  and `'notification-dismissed'` with `{id}`.

- [ ] **Step 1: Write the failing check** — `scripts/check-companion-notifications.js`
  builds a `notification` message with `protocol.buildNotification(...)`, feeds it to
  the handler exported from `services/companion.js`, and asserts the emitted object
  has `id`, `app`, `title`, `text`; then feeds a `notification-removed` message and
  asserts a `notification-dismissed` event with the same id. Run
  `node scripts/check-companion-notifications.js` → fails, "no notification event".
- [ ] **Step 2: Implement the routing** — in `services/companion.js`, handle
  `message.type === protocol.TYPES.NOTIFICATION` and `...NOTIFICATION_REMOVED` next
  to the existing `CLIPBOARD_SET` case, and emit the two events.
- [ ] **Step 3: Merge into the history** — `services/notifications.js` gains
  `addFromCompanion(notif)` and `removeById(id)`; `main.js` forwards the companion
  events to the same `event:notification-received` channel the ADB path uses, so the
  renderer needs no change. The ADB poller must **not** run while the companion is
  connected (one transport per event).
- [ ] **Step 4: Run the checks** — `node scripts/check-companion-notifications.js`
  prints `companion notifications: ok`; the desktop app still starts
  (`npm start`) and the Notifications panel renders the history.
- [ ] **Step 5: Commit** — `git commit -m "feat: show the phone's notifications on the desktop"`.

### Task 5: Dismissing a notification from the desktop

**Files:**
- Modify: `companion-android/app/src/main/kotlin/com/aplusconnect/companion/service/PhoneNotificationListener.kt`
- Modify: `companion-android/app/src/main/kotlin/com/aplusconnect/companion/service/CompanionService.kt`
- Modify: `services/companion.js`, `services/notifications.js`

**Interfaces:**
- Consumes: the `notification-action` message with `{id, action}` where `action` is
  `dismiss` or the id of one of the notification's own action buttons.
- Produces: `PhoneNotificationListener.dismiss(id)` — finds the live
  `StatusBarNotification` whose id matches and calls `cancelNotification(key)`;
  the listener's own `onNotificationRemoved` then sends the `notification-removed`
  message, so the desktop's list updates through the same path as a swipe on the
  phone.

- [ ] **Step 1: Write the failing check** — in
  `scripts/check-companion-notifications.js`, send a `notification-action` message
  with `action: "dismiss"` and assert the desktop emits `notification-dismissed`
  with that id.
- [ ] **Step 2: Run it to verify it fails** — no event emitted.
- [ ] **Step 3: Implement the phone side** — `CompanionService` routes
  `notification-action` to `PhoneNotificationListener`, which keeps a map of
  live notifications by id (filled in `onNotificationPosted`, removed in
  `onNotificationRemoved`) and calls `cancelNotification(sbn.key)` for a dismiss.
  An unknown id is ignored, not an error.
- [ ] **Step 4: Implement the desktop side** — `services/companion.js` sends
  `notification-action` when the renderer asks to dismiss a companion notification.
- [ ] **Step 5: Run the checks** — `node scripts/check-companion-notifications.js`
  green.
- [ ] **Step 6: Commit** — `git commit -m "feat: dismiss a phone notification from the desktop"`.

---

# Part 2 — Playback control

### Task 6: The phone reports and controls what is playing

**Files:**
- Create: `companion-android/app/src/main/kotlin/com/aplusconnect/companion/service/MediaSessionBridge.kt`
- Modify: `companion-android/app/src/main/kotlin/com/aplusconnect/companion/service/CompanionService.kt`

**Interfaces:**
- Consumes: `FeaturePayloads.mediaState` (Task 2).
- Produces: `class MediaSessionBridge(context: Context, listener: PhoneNotificationListener)`
  with `fun currentState(): MediaState?`, `fun command(command: String, positionMs: Long?)`,
  where `MediaState` is a data class
  `(app, title, artist, album, playing, positionMs, durationMs)`.
  The commands are exactly `play`, `pause`, `toggle`, `next`, `previous`, `stop`,
  `seek`.

- [ ] **Step 1: Write the failing test** — `MediaSessionBridgeTest.kt` (a pure-JVM
  test of the *mapping*, not the platform call): given a fake session description
  (`title="Song"`, `artist="Band"`, `album="Album"`, `state=PLAYING`,
  `position=1_000`, `duration=180_000`), the mapping produces a `MediaState` with
  those values; a session with no metadata produces `null` rather than an empty
  state.
- [ ] **Step 2: Run it to verify it fails** — unresolved reference.
- [ ] **Step 3: Implement the mapping** — put the pure mapping in
  `MediaSessionBridge.Companion.toMediaState(title, artist, album, state, position,
  duration): MediaState?` so it can be unit-tested without a device.
- [ ] **Step 4: Implement the platform part** — the bridge gets active sessions with
  `MediaSessionManager.getActiveSessions(ComponentName(context, PhoneNotificationListener::class.java))`
  (this is why it needs notification access), picks the one that is playing or
  paused, and sends `media-state` whenever the state changes and every second
  while playing (for the position).
- [ ] **Step 5: Implement the commands** — `command()` resolves the session *token*
  captured when the state was produced and calls `MediaController.transportControls`
  (`play`, `pause`, `stop`, `skipToNext`, `skipToPrevious`, `seekTo`). If the token
  is gone, the command is dropped, not sent to another session.
- [ ] **Step 6: Run the checks** — `./gradlew :app:testDebugUnitTest` green.
- [ ] **Step 7: Commit** — `git commit -m "feat: report and control what the phone is playing"`.

### Task 7: The desktop shows a Now Playing card

**Files:**
- Modify: `services/companion.js`, `main.js`, `preload.js`
- Modify: `src/renderer.js`, `src/index.html`
- Test: `scripts/check-companion-media.js` (new)

**Interfaces:**
- Consumes: the `media-state` message (Task 2) and the `media-command` message.
- Produces: `services/companion.js` emits `'media-state'` with
  `{app,title,artist,album,playing,positionMs,durationMs}`; `main.js` exposes
  `ipcMain.handle('media:command', ...)` and `preload.js` exposes
  `window.api.sendMediaCommand(command)` and `window.api.onMediaState(cb)`.

- [ ] **Step 1: Write the failing check** — `scripts/check-companion-media.js` feeds a
  `media-state` message and asserts the emitted object has `title` and `playing`;
  then asserts that calling the command handler with `next` puts a `media-command`
  message with `command: "next"` on the wire.
- [ ] **Step 2: Run it to verify it fails** — no event.
- [ ] **Step 3: Implement** the routing in `services/companion.js` and the IPC in
  `main.js` / `preload.js`.
- [ ] **Step 4: Implement the card** — in `src/renderer.js` a "Now playing" card
  under the device card: title, artist, a play/pause button, previous/next, and a
  progress bar. The buttons are disabled when no session is playing. **No emoji
  icons**: use the same inline SVG set the rest of the app uses.
- [ ] **Step 5: Run the checks** — `node scripts/check-companion-media.js` prints
  `companion media: ok`; the desktop app starts and the card appears when the phone
  plays something.
- [ ] **Step 6: Commit** — `git commit -m "feat: add the now playing card to the desktop"`.

---

# Part 3 — File transfer

### Task 8: The phone lists its files, read-only

**Files:**
- Create: `companion-android/app/src/main/kotlin/com/aplusconnect/companion/file/PhoneFileBrowser.kt`
- Modify: `companion-android/app/src/main/kotlin/com/aplusconnect/companion/service/CompanionService.kt`
- Modify: `companion-android/app/src/main/AndroidManifest.xml` (no storage permission — the picker needs none)

**Interfaces:**
- Consumes: `FeaturePayloads.fileList` (Task 2).
- Produces: `class PhoneFileBrowser(context: Context)` with
  `fun setRoot(treeUri: Uri)`, `fun list(path: String): List<FileEntry>?` where
  `FileEntry` is `(name, size, isDir, mime)`, and
  `fun open(path: String): InputStream?`. The root comes from
  `ACTION_OPEN_DOCUMENT_TREE`; the tree URI is persisted so the choice survives a
  restart.

- [ ] **Step 1: Write the failing test** — `PhoneFileBrowserTest.kt` tests the pure
  part: `entryFor(displayName="photo.jpg", size=1024, mime="image/jpeg")` produces a
  `FileEntry` with `isDir=false`, `size=1024`, `mime="image/jpeg"`; a directory
  entry produces `isDir=true` and `size=0`.
- [ ] **Step 2: Run it to verify it fails** — unresolved reference.
- [ ] **Step 3: Implement** the listing with `DocumentsContract.buildChildDocumentsUriUsingTree`
  over the persisted tree URI, one level deep per request (`path` is the document id
  of the folder being listed, empty string = the root). Entries are sorted: folders
  first, then files by name.
- [ ] **Step 4: Implement the send** — `CompanionService` handles `file-list-request`
  by calling `list(path)` and answering with `file-list`; it handles a new
  `file-send-request` by streaming the file through the **existing** `file-start` /
  `file-chunk` / `file-done` messages, so the PC side needs no new file code.
- [ ] **Step 5: Keep it read-only** — no code path in `PhoneFileBrowser` may call
  `DocumentsContract.deleteDocument`, `renameDocument` or `createDocument`. The
  build guard test asserts those symbols do not appear in the file.
- [ ] **Step 6: Run the checks** — `./gradlew :app:testDebugUnitTest` green.
- [ ] **Step 7: Commit** — `git commit -m "feat: browse the phone's files over the companion channel"`.

### Task 9: The desktop gets a phone file browser

**Files:**
- Modify: `src/renderer.js`, `src/index.html`, `src/style.css`
- Modify: `services/companion.js`, `main.js`, `preload.js`

**Interfaces:**
- Consumes: `file-list` (Task 2) and the existing `file-start`/`file-chunk`/`file-done`
  messages that already deliver a file to the PC.
- Produces: `window.api.listPhoneFiles(path)` and `window.api.downloadPhoneFile(path)`;
  the renderer shows a folder list under "Send File" with a "phone" column.

- [ ] **Step 1: Write the failing check** — extend `scripts/protocol-check.js` with a
  round trip: build a `file-list` payload with the Node builder, decode it, assert
  `entries[0].name` and `isDir`.
- [ ] **Step 2: Run it to verify it fails** — the builder does not exist yet (it was
  added in Task 2, so this step instead asserts the *ordering* rule: a folder must
  sort before a file).
- [ ] **Step 3: Implement the IPC and the UI** — the phone's list is shown in the
  existing "Send File" tab, as a second column next to the PC's files; clicking a
  file asks the phone to send it and the desktop saves it with
  `DownloadDestination`, exactly like a file that arrives over ADB today.
- [ ] **Step 4: Run the checks** — `node scripts/protocol-check.js` green; the
  desktop app opens the tab without errors.
- [ ] **Step 5: Commit** — `git commit -m "feat: browse the phone's files from the desktop"`.

---

# Part 4 — Screen mirroring

### Task 10: The phone captures and encodes the screen

**Files:**
- Create: `companion-android/app/src/main/kotlin/com/aplusconnect/companion/mirror/ScreenCaptureService.kt`
- Create: `companion-android/app/src/main/kotlin/com/aplusconnect/companion/mirror/ScreenEncoder.kt`
- Modify: `companion-android/app/src/main/kotlin/com/aplusconnect/companion/service/CompanionService.kt`
- Modify: `companion-android/app/src/main/AndroidManifest.xml`
  (`android:foregroundServiceType="mediaProjection"`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`)

**Interfaces:**
- Consumes: `mirror-start` / `mirror-stop` messages (Task 2) and
  `CompanionClient.sendMirrorFrame(bytes)`.
- Produces: `class ScreenCaptureService : Service` with a `MediaProjection.Callback`
  that stops the capture when the user revokes it; `class ScreenEncoder` that turns
  the projection's surface into Annex-B H.264 NAL units, one per keyframe interval.

- [ ] **Step 1: Write the failing test** — `ScreenEncoderTest.kt` tests the pure
  part: `ScreenEncoder.toAnnexB(byteArrayOf(0,0,0,1, 65, 66))` returns the same
  bytes with a 4-byte start code, and `isKeyframe(frameIndex, keyframeInterval)`
  is true for index 0 and every `keyframeInterval`-th frame after it.
- [ ] **Step 2: Run it to verify it fails** — unresolved reference.
- [ ] **Step 3: Implement the encoder** — `MediaCodec` configured for
  `video/avc` in the `KEY_COLOR_FORMAT_SURFACE` mode, 1080p (or the display's own
  size, capped at 1080p), 30 fps, a keyframe every 2 seconds, and the bitrate
  scaled from the resolution (8 Mbps at 1080p). Each output buffer is prefixed
  with a 4-byte start code when the codec does not already emit one.
- [ ] **Step 4: Implement the service** — the service is started by the app's
  Settings tab after the user accepts the system's projection dialog; it creates a
  `VirtualDisplay` on the encoder's input surface, runs the codec on its own
  thread, and pushes each frame with `CompanionClient.sendMirrorFrame`.
- [ ] **Step 5: Honour the revocation** — `MediaProjection.Callback.onStop()` stops
  the encoder, the service, and sends `mirror-stop` to the PC.
- [ ] **Step 6: Run the checks** — `./gradlew :app:testDebugUnitTest` green; the
  service is registered with `mediaProjection` foreground type and the app asks for
  the projection *before* starting the service, so Android 14+'s one-shot consent
  rule is respected.
- [ ] **Step 7: Commit** — `git commit -m "feat: capture the phone screen over the companion channel"`.

### Task 11: The desktop decodes and shows it

**Files:**
- Create: `src/mirror.js` (loaded by `src/index.html` before `src/renderer.js`)
- Modify: `src/renderer.js`, `src/index.html`, `src/style.css`
- Modify: `services/companion.js`, `main.js`, `preload.js`

**Interfaces:**
- Consumes: binary frames of kind `1` (Task 1) carrying Annex-B H.264.
- Produces: `window.mirror.start()`, `window.mirror.stop()`,
  `window.mirror.onFrame(callback)` in `src/mirror.js`, built on `VideoDecoder`
  (WebCodecs) with `avc1.42E01E` and the Annex-B parser; the renderer shows the
  frames in a canvas inside a "Screen" tab.

- [ ] **Step 1: Write the failing check** — `scripts/check-mirror-frames.js` asserts
  that `src/mirror.js` parses an Annex-B stream: given the bytes
  `00 00 00 01 65 88 84 00 00 00 01 41 9a` it finds two NAL units with types `5`
  (IDR) and `1` (non-IDR).
- [ ] **Step 2: Run it to verify it fails** — the module does not exist.
- [ ] **Step 3: Implement the decoder** — `src/mirror.js` keeps the previous
  keyframe's bytes so a frame that arrives without a keyframe can still be decoded,
  and drops frames that the decoder cannot accept instead of queueing them.
- [ ] **Step 4: Wire the transport** — `services/companion.js` reads binary frames
  (kind `1`) and emits them as `'mirror-frame'`; `main.js` forwards them with
  `sendToRenderer('event:mirror-frame', bytes)`; the renderer passes them to
  `window.mirror`.
- [ ] **Step 5: Implement the UI** — a "Screen" tab in the app, a canvas that keeps
  the aspect ratio of the phone, and a stop button that sends `mirror-stop`.
- [ ] **Step 6: Run the checks** — `node scripts/check-mirror-frames.js` prints
  `mirror frames: ok`; the desktop app starts and the tab is empty until a
  projection starts.
- [ ] **Step 7: Commit** — `git commit -m "feat: show the phone's screen on the desktop"`.

---

# Part 5 — End to end, without ADB

### Task 12: Prove it works with debugging off

**Files:**
- Create: `scripts/verify-without-adb.md` (the checklist, with the exact commands)
- Modify: `README.md`, `AGENTS.md`

**Interfaces:**
- Consumes: everything above.
- Produces: a written procedure the user can follow on their own phone: turn
  Developer options **off**, unplug USB, and check that the clipboard, the
  notifications, the playback controls, the file browser and the screen mirror all
  still work.

- [ ] **Step 1: Write the checklist** — one line per feature, each with what
  "working" looks like, and the single command that proves the phone is no longer
  reachable over ADB (`adb devices` shows nothing, which the user can check by
  unplugging).
- [ ] **Step 2: Update the docs** — `README.md` gains a "Without ADB" section; the
  `AGENTS.md` gotcha list loses "notifications are ADB-only" and gains "the
  notification listener and the projection are optional permissions the user
  grants".
- [ ] **Step 3: Verify** — with the phone in that state, walk the checklist and
  record the result of each line in the commit message body of the PR, not in the
  code.
- [ ] **Step 4: Commit** — `git commit -m "docs: the checklist for running without adb"`.

---

## Self-review

- **Spec coverage:** every part of the request (notifications, playback control,
  file transfer, screen mirroring, no ADB, no developer options) maps to a task
  above; the clipboard, which this plan assumes works, is covered by
  `docs/companion/keyboard-fork.md`.
- **Step scan:** every step names a file, a signature, a command and its expected
  output. The two places that leave a choice (the exact frame bytes, the exact
  payload keys) are pinned in Task 1 and Task 2.
- **Type consistency:** `FeaturePayloads`, `MediaState`, `FileEntry`, the nine
  message types and the command names are defined once, in Task 2 / Task 6 /
  Task 8, and used by name everywhere else.
- **Review Focus:** each of the five failure modes has an owning task: (1) Task 3
  step 4, (2) Task 4 step 3, (3) Task 6 step 5, (4) Task 8 step 5, (5) Task 10
  step 5.
- **Proportion:** the plan is longer than a spec because there is no spec for
  these features — it is the spec. Once Task 0 lands, the payload builders fix the
  shape and later tasks shrink.
