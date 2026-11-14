# A+ Connect

A Linux desktop companion for OPPO / ColorOS phones — a Linux reimplementation
of OPPO's O+ Connect. Drive your phone from a KDE desktop: mirror the screen,
move files both ways, share the clipboard, and see notifications without
picking the phone up.

Built for the **OPPO Reno 14 5G (ColorOS, Android 15/16)** and developed on
Linux. It talks to the phone two ways, and you can use either or both.

## The two connection paths

| | **ADB path** | **Companion path** |
|---|---|---|
| Needs a cable | Once, to enable wireless debugging | Never |
| Needs developer options | Yes | No |
| Screen mirroring | Full, with touch control (scrcpy) | View-only |
| File transfer | Two-way, any folder | Browse/search the phone, download, and send files to it |
| Clipboard | Two-way | Two-way, with no tap on the phone |
| Notifications | App name only (text is a placeholder) | Real text, dismissible from the desktop |
| Media control | — | Phone appears as an MPRIS player |

The companion path is a small app on the phone that dials out to this desktop
over TLS. The phone never opens a listening port.

## Features

- **Screen mirroring** — scrcpy 5.x with UHID input injection, which bypasses
  ColorOS's shell touch filters, so taps and drags work.
- **File transfer** — drag files onto the window to send them, or browse the
  phone and pull files back. Multiple files can be selected and downloaded in
  one batch; same-named files no longer overwrite each other.
- **Companion file browser** — browse and search the phone's whole storage
  read-only, download single or multiple files, and push files to it.
- **Clipboard sync** — bidirectional, background, no tap required on the
  companion path.
- **Notification mirroring** — phone notifications appear in a desktop drawer
  and in the app's history. Updates replace the existing bubble instead of
  stacking duplicates.
- **Media control** — the phone's current player is published as an MPRIS
  player, so the shell's media widget can pause, skip, and seek it.
- **Pairing** — by QR code or address + one-time code, with certificate pinning.
- **Kill switch** — turn the companion off on either side; the choice is
  remembered and the other side is told why.
- **Tray app** — closing the window hides to the tray instead of quitting.

## Requirements

**System binaries on `PATH`:**

- `electron` (44+)
- `adb` (android-tools) — for the ADB path
- `scrcpy` 5.x — its server is pushed to the phone for the ADB path
- `wl-paste` + `wl-copy` (Wayland) or `xclip` (X11)
- `notify-send` (libnotify) — fallback when no notification daemon is present

**npm dependencies** (declared in `package.json`, installed with `npm install`):

- `@yume-chan/adb`, `@yume-chan/adb-scrcpy`, `@yume-chan/adb-server-node-tcp`,
  `@yume-chan/scrcpy` — speak the scrcpy protocol over ADB from Node; this is how
  the desktop reads the phone clipboard on the ADB path.
- `dbus-next` — talks to the desktop notification daemon directly.
- `qrcode` — pairing QR codes.

`npm start` runs `scripts/ensure-deps.js` first and exits with a clear message
if the modules are missing.

## Install and run

```bash
git clone https://github.com/asifahamed-ece/A-Connect.git
cd "A+ Connect"
npm install
npm start
```

`npm start` and `npm run dev` are the same command:
`electron . --disable-vulkan`. The window needs a real X11/Wayland session; it
will not run headless.

### ADB path: one-time USB setup

```bash
# 1. Plug the phone in over USB
# 2. Accept "USB debugging" on the phone
./setup-usb.sh
```

The script enables wireless ADB, reads the phone's address, and connects. After
that, `npm start` reconnects over Wi-Fi while both devices are on the same
network.

### Companion path: no cable, no developer options

1. Install the companion app from `companion-android/`.
2. Start the desktop: `npm start`.
3. Pair by scanning the desktop's QR code, or by entering the address and code.

To check the preconditions first:

```bash
node scripts/check-without-adb.js
```

The full walkthrough is in
[`scripts/verify-without-adb.md`](scripts/verify-without-adb.md), and the
per-feature status is in
[`docs/companion/companion-status.md`](docs/companion/companion-status.md).

## Using it

**Files.** Either finder accepts files dragged onto the window. Tick the
checkboxes on individual files to build a batch, then use **Download selected**;
**Clear selection** empties the batch, including files you selected in another
folder. Transfer progress appears in the same list for both connections.

**Notifications.** Phone alerts land in the Notifications view and as desktop
bubbles. Notifications that update replace their existing bubble. Clearing the
app's history is local — it does not clear anything on the phone.

**Clipboard.** Copy on either device and paste on the other. Text only.

**Mirroring.** Use the ADB connection for a mirror you can interact with; the
companion path gives a view-only stream.

## Architecture

```
┌──────────────────────────────────────────────────────────┐
│                   A+ Connect (Electron)                  │
│   main process · tray · IPC · renderer (HTML/CSS/JS)     │
└───────────┬───────────────────────────────┬──────────────┘
            │                               │
   ┌────────▼─────────┐            ┌────────▼──────────┐
   │    ADB path      │            │  Companion path   │
   │ adb · scrcpy 5.x │            │ TLS · protocol.js │
   │ UHID input       │            │ clipboard bridge  │
   │ file push/pull   │            │ files · media     │
   └────────┬─────────┘            └────────┬──────────┘
            │                               │
            └───────────────┬───────────────┘
                            │
                 ┌──────────▼───────────┐
                 │  ColorOS phone       │
                 │  (Reno 14 5G)        │
                 └──────────────────────┘
```

The renderer never touches Node. It talks to the main process only through
`window.api`, exposed by `preload.js` with `contextIsolation` on.

## Project structure

```
A+ Connect/
├── main.js                     Electron main process, tray, IPC handlers
├── preload.js                  contextBridge → window.api
├── package.json
├── services/
│   ├── adb.js                  Device discovery, pairing, shell
│   ├── scrcpy.js               Mirroring over ADB
│   ├── file-transfer.js        Push / pull, collision-safe naming
│   ├── clipboard.js            Desktop clipboard (wl-clipboard / xclip)
│   ├── clipboard-bridge.mjs    ESM bridge to the scrcpy-protocol libraries
│   ├── notifications.js        Notification history and mirroring
│   ├── desktop-notify.js       D-Bus notification drawer (dbus-next)
│   ├── companion.js            TLS companion server
│   ├── companion-protocol.js   Wire framing
│   ├── companion-cert.js       Certificates and pinning
│   ├── companion-handshake.js  Pairing handshake
│   ├── mirror.js               Companion view-only mirror
│   ├── mpris.js                Media player over D-Bus
│   ├── h264.js                 Video decode helpers
│   └── preview-cache.js        Cached previews, keyed by hash
├── src/
│   ├── index.html              UI structure
│   ├── style.css               Design tokens + styling
│   └── renderer.js             UI logic
├── companion-android/          Kotlin / Jetpack Compose phone app
├── scripts/                    Verification and setup scripts
├── docs/
│   ├── companion/              How the companion path works today
│   └── superpowers/            Designs and plans for shipped work
├── assets/                     Resources extracted from the O+ Connect client
└── setup-usb.sh                One-time wireless ADB setup
```

## Troubleshooting

**Closing the window doesn't quit.** That's intentional — it hides to the tray.
Quit from the tray menu.

**Device not found over ADB.** Check that wireless debugging is on, both
devices are on the same network, and the phone's IP hasn't changed. Re-run
`./setup-usb.sh` if needed.

**Mirroring fails to start.** Confirm `scrcpy --version` is 5.x and accept the
screen-capture prompt. The encoder is pinned to the MediaTek HEVC encoder and
H.265; a phone with a different SoC needs a different encoder in
`services/scrcpy.js`.

**Files transfer but the phone shows no alert.** Expected on the ADB path —
file notifications are not implemented there.

**Notification text says "New notification".** Expected on the ADB path, which
reads `adb logcat` and only gets the app name. The companion path carries real
text.

**Clipboard won't sync.** Text only, and on the companion path the A+ Connect
keyboard must be the phone's default keyboard — Android grants background
clipboard access to the default keyboard and nothing else.

**The app won't start and mentions a missing module.** Run `npm install`.

## What this does not do

- The companion file browser is **read-only**; it cannot delete or rename on the
  phone.
- The companion mirror is **view-only**; interaction requires the ADB path.
- ADB-path notifications carry only the app name.
- The MediaTek encoder setting targets the Dimensity-based Reno 14; other phones
  need a code change.

## Documentation map

| Document | What it is |
|---|---|
| [`AGENTS.md`](AGENTS.md) | Conventions and gotchas for agents working in this repo |
| [`docs/companion/companion-status.md`](docs/companion/companion-status.md) | How each companion feature works, and its limits |
| [`docs/companion/`](docs/companion/) | Keyboard fork, clipboard handoff, file access, spike results |
| [`docs/superpowers/specs/`](docs/superpowers/specs/) | Designs for the features that shipped |
| [`docs/superpowers/plans/`](docs/superpowers/plans/) | Implementation plans for the companion work |

> **Historical documents.** `development-plan.md`, `implementation-roadmap.md`,
> `project-documentation.md`, `protocol-specifications.md`, and
> `WEEKEND-SPRINT-SUMMARY.md` describe an early aspirational design — a Rust
> transport, a Qt6/Kirigami UI, a D-Bus service, and a reverse-engineered
> "Panta" protocol. **None of that was built.** The shipped app is Electron. They
> are kept for reference only.

## License

MIT, as declared in `package.json`.

> There is currently no `LICENSE` file in this repository. The MIT declaration in
> `package.json` is the authoritative statement until one is added.

The `assets/` directory contains resources extracted from OPPO's official O+
Connect client and is not covered by this project's license. The keyboard fork
under `keyboard/` is GPL-3.0 and is pulled in separately.

## Acknowledgements

- **Scrcpy** — Genymobile's screen mirroring engine.
- **O+ Connect** — OPPO's official client; UI design inspiration.
- **HeliBoard** — the keyboard the companion clipboard path forks.