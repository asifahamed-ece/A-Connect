# Screen mirroring over the companion channel — design

Date: 2026-11-14
Status: proposed, awaiting review
Scope: the basic slice only. Audio, phone control from the PC, a quality UI,
fullscreen and rotation handling are explicitly out of scope and listed at the
end.

## What this is

The phone's screen, visible on the desktop, over the encrypted companion
channel, with **no USB cable, no wireless debugging and no developer options**.

Today the app's "Multi-Screen Connect" card spawns `scrcpy`, which needs ADB.
This adds the same visible outcome for a phone that is reachable only over the
companion channel.

## Intent

- **See** the phone's screen on the desktop. Not control it.
- The phone stays **fully usable** while mirrored: its own display is untouched.
- The picture is a duplicate, so the phone's screen must stay awake. This is a
  platform rule, not a shortcut — see "Constraints the platform imposes".
- Useful quality on a normal LAN, with a fixed, sensible configuration. A
  settings UI comes later.
- **No new trust path.** The video socket proves it is the trusted phone with the
  same handshake the clipboard already uses.

## Verified facts this design rests on

Measured or read, not assumed:

| Fact | How it was established |
| --- | --- |
| WebCodecs `VideoDecoder` exists in the desktop build | Probed the running app over CDP |
| H.264 decodes (`avc1.42E01E`, `avc1.64001F` both supported) | Same probe |
| **HEVC does not decode** (`hvc1.1.6.L93.B0` unsupported) | Same probe — this is why the codec is H.264 and not the HEVC encoder `scrcpy.js` uses |
| Binary frame kind 1 is implemented on both sides, tested by the shared fixture, and unused | `services/companion-protocol.js`, `FrameCodec.kt`, `scripts/protocol-check.js` |
| `mirror-start` / `mirror-stop` are declared but handled nowhere | `ProtocolMessage.kt`, `services/companion-protocol.js` |
| Projection needs `FOREGROUND_SERVICE_MEDIA_PROJECTION` + `foregroundServiceType="mediaProjection"` | Android docs, media projection guide |
| Consent is per session; the token is single-use; a second `createVirtualDisplay()` throws `SecurityException` | Same |
| `MediaProjection.Callback` must be registered **before** `createVirtualDisplay()` or it throws `IllegalStateException` | Same |
| Projection **auto-stops when the display locks/sleeps**; Android 15 QPR1+ adds a status-bar chip the user can tap | Same |
| `KEY_PREPEND_HEADER_TO_SYNC_FRAMES`, `KEY_LATENCY`, `KEY_PRIORITY`, `KEY_BITRATE_MODE`, `KEY_I_FRAME_INTERVAL` all exist | Android `MediaFormat` reference |

## The shape

Two sockets, one trust implementation.

- **Control channel** (existing, port 24443): mirror start/stop requests and
  outcomes. Unchanged.
- **Video channel** (new, port = control + 1, 24444 by default): video frames
  only, opened when mirroring starts and **closed when it stops**. The desktop
  listens; the phone dials out, so the phone still never listens on a port.

### Components

| Piece | Responsibility |
| --- | --- |
| `companion-android/.../service/ScreenMirror.kt` (new) | MediaProjection, virtual display, H.264 encoder, the drain loop, the drop policy |
| `companion-android/.../net/VideoClient.kt` (new) | The second TLS client: dial, handshake, one writer thread |
| `companion-android/.../mirror/VideoFrameHeader.kt` (new) | The 10-byte header, encode and parse. Pure. |
| `companion-android/.../mirror/MirrorConfig.kt` (new) | Size, fps, bitrate, keyframe interval; fitting the capture to the screen, rounded to even dimensions. Pure. |
| `companion-android/.../protocol/H264.kt` (new) | SPS → WebCodecs codec string. Pure. |
| `services/mirror.js` (new) | Desktop video listener, handshake, frame parsing, fan-out to the renderer |
| `src/renderer.js` + canvas | WebCodecs `VideoDecoder`, canvas painting, the card's state |

### Sequence

1. The user clicks **Start mirroring**. The desktop opens the video listener on
   the video port, then sends `mirror-start` on the control channel with the
   video port and the quality settings.
2. The phone asks for consent for **this** session. In the foreground the system
   dialog opens at once. In the background Android forbids a service from
   starting it, so the phone posts a notification — *"The computer wants to
   mirror this screen — tap to allow"* — which opens the app and the dialog.
3. On consent the phone dials the video port and performs the **same handshake**
   (same pinned certificate, same HMAC proof, same `Handshake.kt` /
   `companion-protocol.js`). Only a device already in the paired-devices store
   with a valid proof is accepted.

   Two details that must not be left to the implementer:
   - the video listener serves the **same desktop certificate** the phone has
     already pinned, so there is no second fingerprint to trust;
   - **pairing is refused on the video socket.** An unknown device is turned
     away; there is no code exchange there. Pairing happens on the control
     channel, once, and the video socket only ever inherits the result.
4. The phone starts the virtual display and the encoder, and reports
   `mirror-started` on the **control** channel with the real width/height and the
   chosen encoder name. Failures are reported the same way, so the user sees
   *why* on the connection that is already trusted rather than an empty canvas.
5. Frames flow on the video socket. Nothing else uses it.
6. Stop — from either side, or by the system — tears everything down through one
   path (below).

Two more behaviours worth fixing now rather than discovering later:

- **Starting twice is refused.** Start while already streaming answers with a
  plain "already mirroring" rather than opening a second listener or a second
  MediaProjection (which would invalidate the first — the token is single-use).
- **A lost video socket does not silently restart.** It stops mirroring with
  `link-lost` and waits for the user to press Start again, because restarting
  needs a fresh consent dialog and popping that up unprompted would be worse than
  saying what happened.

## The video frame

Inside the existing binary frame envelope (kind 1), so the envelope itself is
unchanged and the shared fixture keeps passing:

```
byte  0      stream id    1 = video, 2 reserved for audio later
byte  1      flags        bit 0 = keyframe
bytes 2..9   pts          microseconds, unsigned big-endian
bytes 10..   payload      H.264, Annex-B
```

- The **stream id** is what leaves the door open for audio, or for a second
  display, without changing the format.
- The **pts** becomes WebCodecs' chunk timestamp, and lets the desktop notice a
  stalled stream instead of drawing a frozen frame for ever.
- **Annex-B** because it is what `MediaCodec` already produces, and because it
  keeps every keyframe self-contained (below), so no extradata handshake is
  needed.

### Self-contained keyframes

The encoder is configured with `KEY_PREPEND_HEADER_TO_SYNC_FRAMES`, so every
keyframe carries its own SPS/PPS. Consequences:

- The desktop needs no `csd-0` / `avcC` description.
- A decoder that starts mid-stream waits at most one keyframe interval (1 s).
- A dropped delta frame cannot desynchronise the decoder for long.

### Codec string

WebCodecs wants `avc1.PPCCLL`. The desktop parses `profile_idc`, the constraint
flags and `level_idc` out of the first SPS in the first keyframe, and falls back
to `avc1.64001F` if the parse fails. Hardcoding one profile would break when the
phone picks another.

## Quality (fixed for v1, carried in `mirror-start`)

| Setting | Value | Why |
| --- | --- | --- |
| Capture size | long side capped at **1280** | A 1256×2760 phone becomes 582×1280, aspect preserved. Native resolution costs the encoder far more than the picture gains on a desktop |
| Frame rate | **30** | Enough for scrolling to look smooth |
| Bitrate | **4 Mbps** | Comfortable on LAN, small enough not to starve anything |
| Keyframe interval | **1 s** | The ceiling on how long a late joiner waits, and on how long a lost frame hurts |
| Bitrate mode | VBR if the encoder supports it, else CBR | |
| Encoder | First hardware encoder for `video/avc`, software fallback | The phone's HEVC encoder is useless to us — the desktop cannot decode HEVC |
| Latency | `KEY_LATENCY=0` (API 30+), `KEY_PRIORITY=0` | Ask for realtime |

All of it travels in the `mirror-start` payload, so a settings UI later is a UI
change, not a protocol change.

## Backpressure: drop, don't queue

One frame in flight. When the encoder produces a frame:

- **keyframe** → always sent; the decoder cannot recover without it;
- **delta frame and a write already outstanding** → **dropped**, counted, and
  the count logged once a second.

TCP delivers reliably, which is the wrong promise for live video: without this
rule a slow link would lengthen the delay instead of losing frames. Dropping
keeps the picture live and the latency flat.

## Teardown: one path

`stopMirror(reason)` releases the codec, the virtual display and the projection,
closes the video socket, and tells the other side. Every trigger routes through
it:

| Trigger | Detected on | Reason shown |
| --- | --- | --- |
| Desktop **Stop** button | desktop | `requested` |
| Desktop **Disconnect** (the existing companion one) | desktop | `disconnected` |
| Phone notification **Stop sharing** action | phone | `requested` |
| System status-bar chip | phone (`onStop`) | `projection-stopped` |
| Screen lock or display sleep | phone (`onStop`) | `screen-slept` |
| Video socket closing | both | `link-lost` |
| Either app dying | both | `link-lost` |

The phone's foreground notification — which Android already requires for the
projection service — carries the **Stop sharing** action while mirroring.

## Failure surfaces

| Failure | Reported as |
| --- | --- |
| Consent refused | `mirror-failed: consent-refused` |
| No usable encoder | `mirror-failed: no-encoder` |
| Video socket unreachable | `mirror-failed: video-connect-failed` |
| Handshake rejected | `mirror-failed: not-trusted` (video socket closed) |
| Control channel down when Start is pressed | refused in the UI: "the phone is not connected" |
| Start pressed while already mirroring | refused: "already mirroring" |
| Video socket lost mid-stream | `mirror-stopped: link-lost`, and Stop until the user starts again |

## Testing

**Phone (pure, unit-tested, no Android runtime):**
- the 10-byte header: encode/parse round-trip, a truncated buffer, an unknown
  stream id;
- `MirrorConfig`: long-side cap, aspect preservation, portrait and landscape,
  a screen smaller than the cap;
- SPS → codec string, against a real SPS byte sequence taken from the encoder;
- the drop policy: keyframe always sent, delta dropped while a write is
  outstanding, counters correct;
- the mirror state machine: idle → awaiting consent → streaming → stopped, and
  that every trigger reaches the same stop.

**Desktop:**
- `scripts/check-mirror.js`: header parse (valid, truncated, unknown stream),
  a frame reaching the renderer event, a malformed frame dropped without taking
  the socket down, stop clearing the state, and video frames never touching the
  control channel;
- `scripts/protocol-check.js` extended with the new payloads, cross-checked
  against the Kotlin builders through the shared fixture.

**Live, on the phone:**
- start from the desktop → the canvas shows the phone within a second or two;
- the phone stays usable while mirrored (open an app, type);
- Stop from the notification, from the desktop, and from the status-bar chip,
  each clearing the canvas with the right reason;
- lock the phone → mirroring stops and says why;
- phone app killed → the desktop's canvas clears;
- Disconnect on the companion row → mirroring stops too;
- cable out, developer options off → the same start/stop flow.

## Out of scope for this slice

Listed so nobody mistakes them for oversights:

- **Audio.** The stream id leaves room for it.
- **Controlling the phone from the PC.** Needs input injection, which for an
  ordinary app means an accessibility service, which breaks contactless
  payments (measured). This is the parked decision.
- **Phone display dark while mirrored.** scrcpy does it with ADB; an app cannot
  blank its own panel and keep capturing.
- **A quality/settings UI**, fullscreen, and a pop-out window.
- **Rotation.** The virtual display keeps its size and the system letterboxes
  (Android 12L+ scales and centres). A `onCapturedContentResize` handler is the
  later fix.

## Open questions

None blocking. The kill switch (dormant/paused state on both devices) is a
separate subsystem with its own spec; this design only guarantees that mirroring
stops through a single call it can reuse.
