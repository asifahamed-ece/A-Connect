# A+ Connect Companion — Design Spec

**Date:** 2026-11-11
**Status:** Draft for review — **partly superseded**; the clipboard trigger described
here was replaced on 2026-11-12. Read `docs/companion/payment-block-findings.md`,
`docs/companion/keyboard-fork.md` and `docs/companion/spike-results.md` for what was
actually measured and built: the accessibility service and the app's own input method
are both gone, and the clipboard now comes from a patched HeliBoard fork that
broadcasts what was copied.
**Author:** brainstorming session (design), pending written implementation plan

---

## 1. Problem

Today, A+ Connect talks to the phone over **ADB**. The phone runs `adbd` and the PC
speaks the adb protocol to it. Over Wi-Fi this means the phone **listens on TCP 5555** on
every network it joins — college Wi-Fi, hotel Wi-Fi, a café. Anyone on that network can
see the port and attempt to talk to it. The user does not want to expose wireless
debugging on untrusted networks, and wireless debugging is the only way the current app
reaches the phone without a USB cable.

Separately, the clipboard sync works today because the scrcpy server runs as the `shell`
UID, which Android exempts from the Android 10+ background-clipboard restriction. That
trick requires ADB, so it cannot survive without ADB.

This spec defines a **companion Android application** that gives the phone a privileged,
background clipboard channel to the PC **without ADB and without any inbound port on the
phone**.

## 2. Goals

| # | Goal | Success measure |
|---|------|-----------------|
| G1 | No inbound network exposure on the phone | The app opens **zero** listening sockets — not even UDP discovery; a network scan of the phone shows nothing |
| G2 | Clipboard sync without ADB | Copy on phone → appears on PC in < 2 s, and vice versa |
| G3 | Works without developer options | APK installed from a file; user never enables USB debugging |
| G4 | Survives hostile networks | Traffic is TLS-encrypted and pinned; a rogue peer on the same Wi-Fi cannot read or inject clipboard content |
| G5 | Works when the Wi-Fi isolates clients | USB-tethering path gives a private link that ignores Wi-Fi entirely |
| G6 | Professional product quality | The phone app reads as a real utility, not a demo |

## 3. Non-goals (v1)

- Screen mirroring over the companion channel (it stays on ADB/scrcpy).
- Notification relay over the companion channel (stays on ADB).
- File transfer over the companion channel (stays on ADB).
- Internet relay / cloud account.
- Multi-PC support.

The wire protocol is versioned and message-based specifically so these can be added later
without breaking existing pairings.

## 4. Architecture

```
┌──────────── PC (Electron, existing app) ────────────┐      ┌──── Phone (new Kotlin app) ────┐
│                                                     │      │                               │
│  services/companion.js                              │      │  MainActivity                 │
│   • TLS server, 0.0.0.0:24443                      │◀────▶│   • pairing, PC address entry  │
│   • self-signed cert, fingerprint pinning           │ TLS  │   • status, settings          │
│   • length-prefixed JSON framing                    │dial  │                               │
│   • connection state, reconnect                     │ out  │  ClipboardAccessibilityService│
│                                                     │      │   • privileged clip read      │
│  services/clipboard.js  (unchanged pipeline)        │      │   • privileged clip write     │
│   • history, echo suppression, write guard          │      │                               │
│                                                     │      │  CompanionService (FGS)       │
│  main.js  • IPC + renderer events                   │      │   • outbound TLS client       │
│  preload.js / src/*  • status row in the UI         │      │   • reconnect w/ backoff      │
│                                                     │      │   • persistent notification   │
└─────────────────────────────────────────────────────┘      └───────────────────────────────┘
```

**Direction of connection.** The **phone dials out** to the PC. The PC never connects to
the phone. This is the single most important security property: on a hostile network the
phone is a client and there is nothing for an attacker to connect to.

### 4.1 Components (one responsibility each)

**Phone — `companion-android/`** (new Gradle project, Kotlin, Jetpack Compose)

| Unit | Responsibility |
|------|----------------|
| `protocol/FrameCodec.kt` | Byte ⇄ JSON message framing. No Android dependencies. |
| `protocol/Protocol.kt` | Message data classes + type constants. No Android dependencies. |
| `protocol/Handshake.kt` | Pairing handshake state machine (code + fingerprint pinning). |
| `net/CompanionClient.kt` | TLS socket lifecycle, reconnect with exponential backoff. |
| `net/CertificatePinner.kt` | Trust-on-first-use fingerprint store. |
| `clip/ClipboardGateway.kt` | Read/write the system clipboard; content hashing; dedupe. |
| `clip/ClipboardAccessibilityService.kt` | Accessibility service; detects copy events. It grants no privilege — see 7.3. |
| `service/CompanionService.kt` | Foreground service; owns the client; posts the status notification. |
| `ui/` | Compose screens: Status, Pair, Settings, plus the design system. |
| `data/` | Room or DataStore for settings + paired PCs. (DataStore + JSON is enough for v1.) |

**PC — existing repo**

| Unit | Responsibility |
|------|----------------|
| `services/companion.js` | TLS server, cert generation, framing, pairing, connection state. |
| `services/companion-cert.js` | Generate/cache the self-signed certificate + its fingerprint. |
| `services/clipboard.js` | **Unchanged** — receives companion clipboard events through the same entry point as ADB events. |
| `main.js` | IPC handlers + renderer events for companion status. |
| `preload.js` | `window.api.companion*` surface. |
| `src/renderer.js`, `src/style.css` | A "Companion" row in the device panel showing connection state. |

### 4.2 Data flow — phone → PC

1. User copies text on the phone.
2. The accessibility service observes the copy event (the toast Android shows when the
   user copies). The app then brings up a transparent activity that takes focus, reads
   the clipboard **once**, and closes. A notification action is the manual fallback.
3. `ClipboardGateway` hashes the text (SHA-256) and compares it with the last value sent.
   Unchanged → nothing is sent.
4. The service sends `clipboard-set` with the text and the hash.
5. The PC's companion service validates the session, then calls the **existing**
   `services/clipboard.js` entry point, which applies the same dedupe/echo-suppression
   as an ADB-sourced clipboard entry.

### 4.3 Data flow — PC → phone

1. The user copies on the PC; the desktop clipboard listener fires.
2. `services/clipboard.js` asks the companion service to deliver it.
3. The phone receives `clipboard-set` and writes it to the system clipboard. Writing from
   the background needs no privilege at all — that direction works today.
4. The phone does **not** echo it back: the hash of what it just wrote is recorded, so the
   change detector ignores its own write.

## 5. Security model

| Threat | Mitigation |
|--------|------------|
| Peer on the same Wi-Fi connects to the phone | The phone listens on nothing. No inbound port exists. |
| Peer on the same Wi-Fi reads the clipboard traffic | TLS 1.3 with a pinned certificate; the phone refuses any certificate that does not match the pinned fingerprint. |
| Rogue peer races the pairing (MITM) | The user compares a **6-digit code** and the **SHA-256 fingerprint** shown by both sides. A rogue peer cannot produce the PC's fingerprint, so it cannot complete the handshake. |
| Peer replays an old session | Each handshake carries a fresh random nonce that the PC echoes in `hello-ack`, binding the two messages to a single attempt. TLS session keys are negotiated per connection. |
| Anyone can pair a rogue PC | Pairing requires the physical 6-digit code; after pairing, the PC is pinned by fingerprint and cannot be substituted. |
| Clipboard content in logs | Clipboard payloads are never written to the app log; only lengths and hashes. |
| College Wi-Fi with client isolation | USB tethering gives a private point-to-point link; the same protocol is used over a different interface. |
| A malware app on the phone reads the clipboard | Out of scope — this is a capability the user grants deliberately to a trusted app. Documented in the app's privacy section. |

**Certificate handling.** The PC generates a self-signed certificate on first run
(`openssl req -x509 -newkey rsa:2048 -nodes`, 10 years) and caches it in
`~/.config/aplus-connect/companion/`. The fingerprint is the SHA-256 of the DER-encoded
certificate, shown as colon-separated hex — the same shape users already recognise from
Bluetooth pairing screens.

**Pairing code.** The PC generates a 6-digit code valid for 5 minutes. The phone sends
`hello` with the code + its own device id. The PC compares the code, replies `hello-ack`
containing the certificate fingerprint. The fingerprint is what the phone pins; the
transport security itself comes from TLS.
The phone stores the fingerprint; every later connection must present a matching
certificate or the connection is refused. A reply without a well-formed fingerprint is
refused outright: pinning an empty string would wedge the phone, refusing every later
connection with no way back.

**Discovery.** **Not in v1.** Automatic discovery needs the phone to bind a UDP socket, which
breaks the "nothing listening on the phone" property that is the point of this project.
Pairing is therefore explicit: the PC's address is displayed on the PC screen and typed
into the phone (or scanned from a QR code the PC renders — also v2, because it would
require the phone to open a camera, not a socket). This keeps the attack surface at
exactly zero inbound ports.

## 6. Wire protocol

**Framing.** 4-byte big-endian unsigned length, then that many bytes of UTF-8 JSON.
Maximum frame **2 MiB**; a longer length header is rejected by the codec, and the
transport closes the connection. The clipboard budget is 256 KiB of UTF-8 bytes, which is
what the 2 MiB frame has to allow for: a control character escapes to six bytes, so the
worst case is 1.5 MiB of JSON plus the envelope.

**Envelope.**

```json
{ "v": 1, "type": "clipboard-set", "id": "uuid", "ts": 1731290000000, "payload": { } }
```

| Type | Direction | Payload |
|------|-----------|---------|
| `hello` | phone → PC | `{ deviceId, deviceName, code, nonce, protocolVersion }` |
| `hello-ack` | PC → phone | `{ sessionId, pcId, fingerprint, nonce }` |
| `hello-error` | PC → phone | `{ reason }` — `bad-code`, `bad-fingerprint`, `fingerprint-mismatch`, `already-paired`, `nonce-mismatch`, `protocol-version` |
| `clipboard-set` | both | `{ text, hash, truncated }` |
| `ping` / `pong` | both | `{ ts }` |
| `bye` | both | `{ reason }` |

`pcId` identifies the computer, `fingerprint` identifies its certificate. The two are
separate on purpose: a different `pcId` means another computer is trying to pair, while
the same `pcId` with a different fingerprint means the paired computer's certificate was
regenerated. Those two failures need different messages, so they must be
distinguishable.

**Versioning.** `v` is checked on `hello`. An unknown major version is refused with
`protocol-version` rather than a dropped connection. The codec keeps the version on
every message so it can be checked at any point.

**Clipboard payloads.** `hash` is the SHA-256 of `text` — the text that is actually
transmitted, after truncation. `truncated` says whether the byte budget cut it. The
budget is in **bytes of UTF-8**, not characters, and the cut never splits a code point,
so the phone and the PC hash exactly the same string. Unpaired surrogates are replaced
with U+FFFD on both sides before hashing, because the JVM and Node encode them
differently.

## 7. Android app design

### 7.1 Permissions and manifest

| Permission | Type | Why |
|------------|------|-----|
| `INTERNET` | normal | Outbound TLS to the PC |
| `ACCESS_NETWORK_STATE` | normal | Detect the active interface, show the tethering path |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CONNECTED_DEVICE` | normal / API 34 | Keeps the clipboard relay alive |
| `CHANGE_WIFI_STATE` | normal | Qualifies the `connectedDevice` foreground-service type |
| `POST_NOTIFICATIONS` | runtime (API 33+) | The persistent status notification |
| `BIND_ACCESSIBILITY_SERVICE` | signature | The privileged clipboard exemption |

`canRetrieveWindowContent` is set to **false** in the accessibility-service declaration.
The service never inspects screen content — it only needs the clipboard exemption, so the
permission screen the user sees stays minimal and truthful. (If the spike shows the
exemption requires window-content access, this flips and the app explains why in plain
language.)

**Foreground service type.** `connectedDevice`, qualified by `CHANGE_WIFI_STATE`.
`specialUse` is the fallback if the store review rejects it.

### 7.2 Background behaviour

- The `CompanionService` is a foreground service with a persistent, tappable notification
  showing connection state ("Connected to Studio PC · 42 ms" / "Waiting for a computer").
  Tapping it opens the app; the notification has a "Disconnect" action.
- Reconnect uses exponential backoff: 1 s, 2 s, 4 s, 8 s, 15 s, 30 s (capped), with
  jitter. The interface is re-checked on every attempt, so plugging in USB tethering is
  picked up without restarting the app.
- The foreground-service notification is required for the service to survive Doze and
  background restrictions. The app explains this in the UI rather than hiding it.

### 7.3 Clipboard mechanics (measured, not assumed)

The spike on the target device (Android 16, ColorOS) settled this, and the answer is not
what the first draft of this document assumed:

- **Background clipboard reads are refused** for an app that is neither the default
  keyboard nor the app with input focus. An enabled accessibility service does **not**
  change that, with or without window-content access, with or without a visible
  foreground-service notification.
- **Background clipboard writes work.** So the PC → phone direction needs no privilege
  at all.
- The **default keyboard** is granted privileged access, and that privilege extends to
  the whole app UID.

The phone → PC direction therefore has three possible mechanisms, and the shipping app
must implement at least one:

1. **Copy-event detection → a transparent activity that takes focus → read → forward.**
   The accessibility service is useful here as an *event detector*, not as a privilege:
   it sees the toast Android shows when the user copies. This is the approach KDE
   Connect uses, and it needs no keyboard change.
2. **A "Copy to PC" action on the persistent notification.** The tap gives the app
   focus, so the read succeeds. No privileges, one tap.
3. **Being the default keyboard.** Seamless, but the user would have to type with the
   app's keyboard, so the app must ship a genuinely usable one.

Which of these ships is decided by the P2 plan, and it is a product decision, not a
technical one.

### 7.4 UI design language

Derived from the ui-ux-pro-max design system, adapted to the existing desktop app's
brand so the two halves read as one product.

**Style:** flat, 2D, typography-led, minimal shadows (elevation only for overlays),
clean lines, icon-heavy, generous whitespace. Light and dark both supported. Transitions
150–200 ms, easing shared via tokens. No gradients.

**Palette** — same tokens as the desktop app:

| Role | Light | Dark |
|------|-------|------|
| Background | `#F7F9FC` | `#0B1220` |
| Surface | `#FFFFFF` | `#131B29` |
| Surface raised | `#FFFFFF` | `#1A2434` |
| Primary | `#2563EB` | `#4C8DFF` |
| On primary | `#FFFFFF` | `#0B1220` |
| Text primary | `#0F172A` | `#E9EFF9` |
| Text secondary | `#475569` | `#A9B7CC` |
| Text tertiary | `#94A3B8` | `#7D8CA3` |
| Border | `#E2E8F0` | `#232F42` |
| Success | `#10B981` | `#34D399` |
| Danger | `#DC2626` | `#F87171` |
| Warning | `#F59E0B` | `#FBBF24` |

Contrast: body text ≥ 4.5:1 in both themes; borders and icon-only controls ≥ 3:1.

**Typography:** Inter (bundled, OFL licence) — display 28/700, title 20/600, body 15/400,
label 13/500, mono 13/400 for fingerprints and addresses. Falls back to the platform sans.

**Spacing:** 4 dp base grid, 8 dp rhythm for screen padding and section gaps. Touch
targets ≥ 48 dp, with ≥ 8 dp between adjacent targets. Safe-area insets respected on
every fixed element.

**Icons:** Material Symbols (Rounded), one family, sizes 20/24/28 dp, never emoji.

### 7.5 Screens

**1. Status (home)** — the screen the user sees 99 % of the time.

- **Connection card** — a large status row: a coloured status dot (connected green /
  connecting amber / offline grey), the PC name, the address, round-trip latency, and the
  last successful sync time. One primary action: **Sync now**.
- **Permission card** — if the accessibility service is off, a prominent card explains
  why the app needs it and has a button that opens the system's accessibility settings.
  If it is on, the card collapses to a single green line.
- **Stats row** — three stat tiles: clipboard events today, the last direction
  (phone → PC / PC → phone), and the connection uptime.
- **Recent activity list** — the last 20 clipboard transfers, newest first, each with
  direction, a one-line preview, a relative timestamp, and a delete action. Text is
  masked by default with a "tap to reveal" affordance, because a shoulder-surfer should
  not read your clipboard.
- The status line is an accessibility live region so changes are announced.

**2. Pair** — manual, explicit, no magic.

- Address field (host + port), labelled and validated with an inline error.
- The 6-digit code and the fingerprint, shown as monospace chips with a copy action.
- A **"Pair this computer"** primary button, disabled until the fields are valid.
- A short note explaining why there is no automatic discovery: *"A+ Connect Companion
  never opens a listening port on your phone, so computers are found by their address
  instead of by a network broadcast."* Automatic discovery and QR pairing are planned
  for a later release.

**3. Settings**

- Clipboard sync: on/off; "Send to computer"; "Receive from computer".
- History: keep last N entries (10 / 25 / 50 / 100), clear history.
- Notifications: show the status notification; show a notification on each received
  clipboard entry.
- Appearance: follow system / light / dark.
- About: version, licence, privacy note, "what this app can read".

**4. Setup sheet** — a bottom sheet shown on first run: three numbered steps (grant
notifications, enable the accessibility service, enter the PC address), each with the
exact system screen it opens. No dark patterns, no permission walls before explaining.

### 7.6 Component inventory

`StatusCard`, `PermissionCard`, `StatTile`, `HistoryRow`, `SettingRow`, `ToggleRow`,
`PrimaryButton`, `SecondaryButton`, `CodeChip`, `TextField` (label + inline error),
`SectionHeader`, `EmptyState`, `BottomSheet`. All are Compose functions over a shared
`MaterialTheme` token set — no hard-coded colours or dimensions outside the theme.

### 7.7 Accessibility

- Every icon-only control has a `contentDescription`; decorative icons are `null`.
- Every input has a visible label **and** an accessibility label.
- Status changes are announced through a live region.
- Dynamic Type is supported up to 200 % without clipping.
- Reduced motion respected.
- Colour is never the only signal — the status dot is paired with a text label.

## 8. PC side design

- `services/companion.js` is an `EventEmitter` like every other service in the repo, and
  emits `connection-changed`, `clipboard-received` and `error`.
- `main.js` forwards `clipboard-received` into the existing clipboard pipeline and pushes
  `event:companion-status` to the renderer.
- The renderer shows a **Companion** row in the device panel: connected PC name, latency,
  and whether the clipboard is currently flowing over the companion or ADB.
- The ADB transport is untouched. If both are available, the ADB path keeps working and
  the UI says which one delivered the last entry.

## 9. Build environment

| Tool | Version | Source |
|------|---------|--------|
| JDK | 17 | `pacman -S jdk17-openjdk` (JDK 27 breaks AGP) |
| Android SDK | platform 36, build-tools 36 | `sdkmanager` from Google's command-line tools |
| Gradle | 8.x (wrapper) | downloaded by the wrapper |
| AGP / Kotlin | 8.x / 2.x | declared in the build files |

The APK is signed with a local keystore generated for this project and is delivered as a
file the user can install from the Files app — **no developer options, no ADB**.

## 10. Testing and verification

There is no test runner in this repository today; the Android project introduces JUnit
for the protocol layer, and the PC side gets a Node harness, matching how the existing
code is verified (launch the app, drive it over CDP).

| Level | What | How |
|-------|-------|-----|
| Unit (JVM) | framing, envelope versioning, clipboard payload rules, handshake and fingerprint validation | `./gradlew testDebugUnitTest` — pure Kotlin, no device needed (30 tests) |
| Conformance | the two implementations agree on the wire format and on the clipboard rules | `node scripts/protocol-check.js` — decodes the shared fixture, round-trips every frame and checks that a clipboard frame's hash really is the hash of its text |
| Spike | **does the a11y exemption work on Android 16; does a read toast** | the `:spike` module, installed under its own application id so it can never reach a phone that has the real app |
| Integration | phone ⇄ PC clipboard over a real network | app on the device + PC app, verified over CDP |
| End-to-end | pairing over hostile Wi-Fi | verify with `tcpdump` that the phone has no listening socket and the traffic is TLS |

**Explicit verification of G1:** `ss -ltnp` on the phone must show **no** listening socket
belonging to the app; `tcpdump` on the PC must show only TLS records.

## 11. Phasing

| Phase | Outcome | Gate |
|-------|---------|------|
| P0 — Spike | A minimal APK proves privileged clipboard read/write on Android 16 and reports toast behaviour | The decision in §7.3 is made from measurement, not belief |
| P1 — Protocol | `FrameCodec`, `Handshake`, unit tests, shared fixtures | Unit tests green on both sides |
| P2 — Phone app | Full app: service, a11y service, screens, design system | Screens match the spec; a11y checklist passes |
| P3 — PC side | `services/companion.js`, cert management, UI row | Pairing works; clipboard flows |
| P4 — Integration | End-to-end clipboard sync over LAN and over USB tethering | G2 measured; G1 verified with `ss`/`tcpdump` |
| P5 — Delivery | Signed APK, install instructions, privacy note | The user installs it without developer options and syncs on a hostile network |

Each phase gets its own implementation plan and its own verification gate; the plan for
this spec is scoped to **P0–P1** first, because P0's measurement decides the mechanism
that P2 is built on.

## 12. Risks

| Risk | Impact | Mitigation |
|------|--------|------------|
| The accessibility service turns out not to grant clipboard access | The whole approach fails | **Measured: it does not.** The app uses the copy-event → focus → read route, with a notification tap as the manual fallback, and the default-IME role as an option. |
| Background reads produce a toast per read | Unusable UX | Event-driven reads, and the spike measured the toast behaviour |
| Background reads produce a toast per read | Unusable UX | Event-driven reads + 2 s fallback poll; the spike measures this |
| Android 16 FGS restrictions block the `connectedDevice` type | The relay dies in the background | `specialUse` fallback; the spike verifies the service survives Doze |
| The user must enable an accessibility service | Perceived as invasive | It is only used to spot copy events, never to read screen content: `canRetrieveWindowContent=false`, a plain-language explanation, and a status card that says exactly what it is for. The alternative — a notification tap — needs no permission at all |
| The PC's TLS server is a new attack surface on the PC | The PC is exposed | It listens on all interfaces but requires the pinned fingerprint; the paired PC's address can be restricted to the tethering interface |

## 13. Future work (explicitly out of v1)

- Notification relay via `NotificationListenerService` (real titles, actions).
- Screen mirroring consent via `MediaProjection` — this would close the gap where the
  phone cannot ask "may I mirror?".
- File transfer over the companion channel.
- An internet relay for networks where neither LAN nor tethering is possible.
- Automatic discovery and QR-code pairing — both would need the phone to listen, so they
  are only worth it if the zero-listening-ports property can be preserved (for example,
  discovery by the PC broadcasting and the phone only ever *sending*).
