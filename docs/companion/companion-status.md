A+ CONNECT — COMPANION STATUS
Reno 14 (CPH2737, ColorOS, Android 16) · Linux desktop
Written 2026-11-12 · 51 commits on feature/companion-services

====================================================================
WHAT THIS IS
====================================================================
A+ Connect can now drive the phone over plain Wi-Fi with NO USB cable,
NO wireless debugging and NO developer options. The phone runs one
small app that dials out to the PC over TLS. The phone never opens a
listening port, so nothing on the network can reach it.

Two separate apps are involved:
  * com.aplusconnect.companion        - the companion app (MIT)
  * com.aplusconnect.keyboard.debug   - the patched keyboard (GPL-3)

They are separate on purpose: the keyboard is a fork of HeliBoard and
is GPL-3, so it cannot be linked into the MIT-licensed app.

====================================================================
THE ONE THING THE PHONE MUST HAVE
====================================================================
Android only lets the DEFAULT KEYBOARD read the clipboard in the
background. There is no other route that does not need an
accessibility service, and an accessibility service makes the phone
refuse contactless payments (Google Wallet checks). Measured, not
assumed - see docs/companion/payment-block-findings.md.

So: the A+ Connect Keyboard must be the default keyboard. Then a copy
on the phone reaches the PC with no tap, in both directions.

The companion app also needs notification access (Settings > Special
app access > Notification access) for the notifications feature. That
is a normal permission, NOT an accessibility service, so payments are
not affected.

====================================================================
DONE - WORKING TODAY
====================================================================
1. SEAMLESS CLIPBOARD, BOTH WAYS
   Copy on the phone, it is on the PC. Copy on the PC, it is on the
   phone. No tap, no toast, no focus steal.
   Phone: keyboard fork -> signature-permission broadcast -> app ->
   TLS. Desktop: services/clipboard.js.

2. PHONE NOTIFICATIONS ON THE DESKTOP
   The phone's notifications reach the desktop history, and the
   desktop can dismiss them (which dismisses them on the phone).
   Queued when the PC is offline, delivered once, in order.
   Each row says when it arrived: "just now" / "2 min ago" for the
   last hour, then a clock ("14:32"), "Yesterday 14:32" or
   "12 Nov 14:32"; newest first, exact moment in the tooltip, and the
   relative text re-renders while the view is open. The app does not
   mirror its own "Connected to ..." notification - it is about this
   very connection and there is nothing to do with it.
   Phone: service/PhoneNotificationListener.kt
   Desktop: services/notifications.js + services/companion.js
            + src/format.js (the wording), scripts/check-notification-time.js

3. THE PHONE AS AN MPRIS PLAYER
   The phone appears in the desktop shell as a real media player, the
   way KDE Connect does it. Caelestia, Waybar and playerctl all see
   it. Cover art, title, artist, album, live position, working
   play/pause/next/previous/seek. Lyrics are fetched by the shell
   itself, once the metadata is real.
   Which session is followed: one that is PLAYING beats one that is
   merely paused, whatever order the system lists them in. A player
   that starts playing after the connection is picked up within about
   two seconds without restarting the app - the system only announces
   *new* sessions, and an in-app YouTube video changes its own state
   without changing the set.
   When playback ends the computer is told, and the shell's panel goes
   away instead of keeping the last track for ever.
   Artwork: bitmap keys, content URIs and MediaDescription icons are
   all tried, because no two players agree; if a session has no
   artwork at all, the playing app's own icon stands in so the panel
   is not an empty box. A player's own placeholder art is a real
   bitmap as far as this is concerned, so it wins - ColourOS's music
   player hands over its own orange note for untagged files.
   Desktop: services/mpris.js  (org.mpris.MediaPlayer2.aplusconnect)
   Phone:   service/MediaSessionBridge.kt

4. PROTOCOL VERSION 2
   4-byte length, 1-byte kind (0 = JSON, 1 = binary), payload. The
   length counts the kind byte. 2 MiB frame limit. Both sides ship
   together - an old phone app and a new desktop cannot talk.
   Phone: protocol/FrameCodec.kt
   Desktop: services/companion-protocol.js
   The two implementations are proven equal by a shared fixture.

5. PAIRING AND TRUST
   6-digit code + SHA-256 certificate fingerprint, shown as a QR code
   the phone scans. The phone pins the certificate; every later
   connection must present it. A returning phone proves it still holds
   the secret with an HMAC, so a leaked device id is not enough.

6. FILE TRANSFER, BOTH WAYS
   PC to phone and phone to PC, over the same channel, into the
   existing file-start/file-chunk/file-done frames.

7. ONE TRANSPORT PER EVENT
   If the companion channel is up, the clipboard comes over it and the
   ADB clipboard bridge stands down, so nothing arrives twice.

8. THE PHONE NEVER LISTENS
   The companion client dials out; there is no inbound socket, not
   even for discovery. Discovery is by address or QR on purpose.

9. DISCONNECT FROM THE PC STICKS (added 2026-11-13)
   The Disconnect button acts on the transport the user has selected,
   so on the "Companion · TLS 1.3" row it hangs up the TLS link rather
   than calling adb at a phone that may not even be on adb. The PC
   writes a `bye` frame before closing, and the phone treats that as
   deliberate: it stops its 5 second redial loop and shows
   "Disconnected by the computer" until the user taps Reconnect.
   A bare socket close would have been undone within five seconds and
   looked like the button did nothing.
   Desktop: services/companion.js disconnectClients(), IPC
            companion:disconnect
   Phone:   CompanionClient.REASON_BYE, CompanionService.onClosed

10. SCREEN MIRRORING, OVER ITS OWN SOCKET (added 2026-11-14)
   View-only mirroring over the companion channel: no USB cable and no
   developer options. The picture travels on a second TLS socket (the
   control port + 1, so 24444) and reuses the control channel's pinned
   certificate and HELLO proof. A pairing code is refused there, so the
   second socket can only inherit trust, never create it.
   The phone captures with MediaProjection and encodes H.264. HEVC was
   ruled out by probing this Electron build: WebCodecs has no `hvc1`.
   Capture is the screen capped at 1280 on the long side (582x1280 on
   this phone), 30 fps asked for, 4 Mbit/s, a keyframe every second.
   Each keyframe carries its own SPS/PPS, and the desktop reads its
   WebCodecs codec string out of the SPS instead of guessing a profile.
   Android requires consent for every session, so the phone raises the
   system dialog itself (or a notification to tap when the app is in the
   background). The foreground notification grows a "Stop sharing"
   action, and the system's own status-bar chip stops it too.
   All eight teardown triggers - the PC's Stop, Disconnect, the phone's
   action, the chip, screen-off, the video socket closing, the app
   dying, and quitting the desktop - go through one serialized
   stopMirror(reason) that releases projection, codec and socket. The
   teardown is serialized because two of those triggers really do fire
   on different threads at once.
   A dead link is noticed in about six seconds on both ends: the desktop
   watches the video socket for silence, the phone watches for a write
   that never completes. Without that pair, taking the phone out of
   Wi-Fi range left the PC saying "streaming" over a frozen frame for
   over a minute.
   Desktop: services/mirror.js, services/h264.js, main.js startMirror /
            stopMirror, IPC mirror:start / mirror:stop / mirror:info
   Phone:   mirror/ScreenMirror.kt, mirror/MirrorConfig.kt,
            mirror/FrameGate.kt, net/VideoClient.kt,
            protocol/VideoFrameHeader.kt

====================================================================
THE COMPONENTS (where the code lives)
====================================================================
PHONE - companion-android/app/src/main/kotlin/com/aplusconnect/companion/
  protocol/FrameCodec.kt        framing: length + kind + payload
  protocol/ProtocolMessage.kt   envelope, type constants, version 2
  protocol/ClipboardPayload.kt  byte budget, safe truncation, hashing
  protocol/FeaturePayloads.kt   notification / media / file payloads
  protocol/Handshake.kt         code, fingerprint pinning, HMAC proof
  protocol/PairingPayload.kt    the QR pairing payload
  protocol/VideoFrameHeader.kt  the 10-byte header in front of a frame
  net/CompanionClient.kt        outbound TLS, keepalive, reconnect
  net/PeerTrust.kt              certificate fingerprint pinning
  net/IncomingFile.kt           chunked file receiver
  net/VideoClient.kt            the video socket, one waiting frame
  mirror/MirrorConfig.kt        capture size arithmetic
  mirror/FrameGate.kt           send / hold / drop, never queue
  mirror/ScreenMirror.kt        MediaProjection + H.264 + teardown
  service/CompanionService.kt   foreground service, queues, routing
  service/PhoneNotificationListener.kt   notifications
  service/MediaSessionBridge.kt what is playing, and the controls
  clip/ClipboardBridgeReceiver.kt        keyboard -> app handover
  clip/ClipboardReadActivity.kt          focus-grab fallback
  data/CompanionSettings.kt     settings, pairing, history
  qr/QrScannerView.kt           the pairing scanner
  MainActivity.kt               Interconnect / Send File / Settings

KEYBOARD FORK - keyboard/patch-heliboard.py
  Patches HeliBoard v4.2: new application id, one hook in
  ClipboardHistoryManager.onPrimaryClipChanged(), and a signature
  permission so only this app can be handed the clipboard.
  Build notes: keyboard/README.md, docs/companion/keyboard-fork.md
  The checkout must live outside this repo (a space in the path
  breaks ndk-build).

DESKTOP - services/ and scripts/
  services/companion.js          TLS server, pairing, message routing
  services/companion-protocol.js the wire protocol, mirrored exactly
  services/companion-handshake.js the HELLO proof, shared by both sockets
  services/companion-cert.js     self-signed certificate + fingerprint
  services/mirror.js             the video listener and its watchdog
  services/h264.js               the codec string, read from the SPS
  services/mpris.js              the MPRIS player
  services/clipboard.js          clipboard pipeline, one transport rule
  services/notifications.js      ADB + companion notifications, one history
  services/adb.js  scrcpy.js  file-transfer.js  clipboard-bridge.mjs
  scripts/protocol-check.js      cross-implementation conformance
  scripts/check-mirror.js        the video socket over real TLS
  scripts/check-handshake.js     the two pairing rules
  scripts/check-mpris.js         the MPRIS mapping
  scripts/check-companion-media.js       media routing and commands
  scripts/check-companion-notifications.js  notification routing
  scripts/ensure-deps.js         fails fast if npm install was skipped

TESTS - 108 Android unit tests, plus eight desktop node checks.
  The guard tests matter as much as the feature tests:
   NoAccessibilityServiceTest  no accessibility service, ever
   NoMainThreadSendTest        no socket write on the main thread
   NoContextInServiceFieldsTest  no Service field reads the context before
                               Android attaches one (that crash stopped the
                               app from opening at all)
   PhoneFileBrowserTest        the browser cannot change anything on the phone
   StorageSearchTest           a search answers without walking, a change this
                               app made is never answered from a stale index

====================================================================
UPCOMING - NOT YET BUILT
====================================================================
Task 12  The checklist for running with Developer options OFF and the
         cable unplugged - the real proof of this whole design.

The kill switch (asked for, not built yet): a master switch on both
devices. Off means dormant - the PC stops listening, the phone stops
dialling, nothing is forwarded, no mirroring - and the choice survives
a restart. Turning the PC back on has to let the phone find it on its
own, so the phone needs a slow retry for "the computer went dormant"
that is distinct from the existing "the user hung up, do not redial".

Also deliberately NOT planned:
  * Remote control through an accessibility service. It would break
    contactless payments, which is not a trade worth making.
  * An internet relay and multi-PC support.
  * Screen mirroring over ADB stays as the fallback.

STILL ON YOU: the final manual test - Developer options off, USB
unplugged, copy something on the phone and start a mirror. Everything
above was verified with the cable in, where a copy could still have
taken the ADB path and adb was used to install, read logs and tap the
consent dialog.

====================================================================
KNOWN GOTCHAS
====================================================================
* Protocol v2 means the phone app and the desktop app must be updated
  together. The desktop refuses an old phone's frames ("bad frame").
* The keyboard fork is a separate APK and must be reinstalled after
  the companion app, or Android refuses the signature permission
  (the grant is fixed at install time).
* An update to the companion app can reset the default keyboard; the
  app's Settings screen says so and can set it again.
* The desktop needs `npm install` - dbus-next was added for MPRIS.
* The companion app's own "Connecting..." notification is relayed to
  the desktop like any other. It is noise, and it is worth filtering.
* Electron on this machine can die with "GPU process isn't usable"
  after a hard kill; clearing ~/.config/a-plus-connect/{Cache,Code
  Cache,DawnGraphiteCache,DawnWebGPUCache} fixes it.
* An untyped startForeground(id, notification) uses EVERY type declared
  in the manifest, not just the ordinary one. Adding mediaProjection to
  CompanionService made it die on launch with a SecurityException,
  because Android will not start a mediaProjection service before the
  user has granted a projection. Startup names the connectedDevice type
  explicitly; the projection type is raised only after consent, and in
  that order: createScreenCaptureIntent(), then startForeground with
  the type, then getMediaProjection().
* MediaProjection stops by itself when the screen locks, and Android 15
  QPR1 and later show a status-bar chip the user can tap to stop it.
  Both arrive through MediaProjection.Callback.onStop, and both end the
  session through the same stopMirror(reason).
* A mirror needs the screen awake. Locking the phone, or the phone
  timing out, ends the session by design; there is no background
  capture without a foreground service the user cannot see.

====================================================================
HOW TO VERIFY IT RIGHT NOW
====================================================================
  npm start                                   # the desktop
  node scripts/protocol-check.js              # protocol conformance
  node scripts/check-mirror.js                # the video socket, real TLS
  node scripts/check-handshake.js             # pairing rules, both sockets
  node scripts/check-mpris.js                 # the MPRIS mapping
  node scripts/check-companion-media.js       # media routing
  node scripts/check-companion-notifications.js
  cd companion-android && ./gradlew :app:testDebugUnitTest
  playerctl -l                                # should list aplusconnect
  playerctl -p aplusconnect metadata          # the phone's track
  playerctl -p aplusconnect position 30+      # seek forward 30 s
