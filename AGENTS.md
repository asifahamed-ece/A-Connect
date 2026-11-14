# AGENTS.md

A+ Connect: an Electron desktop app (Linux/KDE) that mirrors and controls an
Android phone (OPPO Reno 14 5G / ColorOS) by shelling out to `adb` and `scrcpy`.
It is a Linux reimplementation of OPPO's O+ Connect.

There is a second, cable-free path: a companion app on the phone dials out to
this desktop over TLS, and the desktop speaks a JSON protocol to it. The two
paths are independent and can both be live at once.

## No build step; a few pinned npm dependencies

- There is **no bundler and no build step**, but `package.json` declares six
  runtime `dependencies`: `@yume-chan/adb`, `@yume-chan/adb-scrcpy`,
  `@yume-chan/adb-server-node-tcp`, and `@yume-chan/scrcpy` (all at
  `3.0.0-beta.5`), plus `dbus-next` and `qrcode`. The `@yume-chan/*` packages
  speak the scrcpy protocol over ADB from Node, which is how the ADB path reads
  the phone clipboard; `dbus-next` talks to the notification daemon; `qrcode`
  renders pairing codes. `npm install` is therefore required; `npm start` runs
  `scripts/ensure-deps.js` first and fails with a clear message if modules are
  missing. That check covers five modules and **does not check `qrcode`** — if
  QR pairing throws `MODULE_NOT_FOUND`, run `npm install`.
- Everything else still drives **system binaries** through `child_process`.
  Required on `PATH`: `electron`, `adb` (android-tools), `scrcpy` (5.x, its
  `scrcpy-server` is pushed to the phone), `notify-send` (libnotify), `wl-paste`
  + `wl-copy` (Wayland clipboard) or `xclip` (X11).
- `services/clipboard-bridge.mjs` is **ESM** (the beta libraries are ESM-only)
  and is run as a forked child process; `services/clipboard.js` is CommonJS and
  talks to it over Node IPC (`{ cmd }` down, `{ evt }` up). Do not `require()`
  the `.mjs` from `main.js`.

## Commands

- Run the app: `npm start` (identical to `npm run dev`; both are
  `electron . --disable-vulkan`). Requires an X11/Wayland session — it opens a
  GUI window, so it will not run headless in CI.
- There is **no test runner, linter, typechecker, formatter, or CI workflow** in
  this repo. Do not claim a verification command passes; the only available
  check is launching the app.
- `setup-usb.sh` — one-time, interactive; enables wireless ADB over USB.
- `test-fixes.sh` / `verify-fixes.sh` — interactive, manual, device-specific
  smoke scripts (they hardcode one serial, see below). They are not a test suite.

## Architecture / where to change what

- `main.js` — Electron main process. Owns services, the system tray, all IPC
  handlers, and the `adb.startPolling(2500)` device-discovery loop.
- `preload.js` — `contextBridge` exposes the whole API as `window.api`. The
  renderer's only channel to the backend.
- `services/*.js` — backend logic, each an `EventEmitter`. ADB path: `adb`,
  `scrcpy`, `file-transfer`. Companion path: `companion`, `companion-protocol`,
  `companion-cert`, `companion-handshake`, `mirror`, `mpris`,
  `preview-cache`, `h264`. Shared: `clipboard` (+ `clipboard-bridge.mjs`),
  `notifications`, `desktop-notify`.
- `src/index.html`, `src/renderer.js`, `src/style.css` — renderer UI. Plain
  script (no bundler/framework); handlers are global functions called from
  inline `onclick=` attributes.
- `assets/` — raw resources extracted from the official O+ Connect client;
  `assets/icon.png` is used for both the window and the tray.

To add a feature, wire all four layers: a `services/*.js` method → an
`ipcMain.handle` in `main.js` → a `window.api` method in `preload.js` → the
renderer. IPC channel naming is `namespace:action` (`adb:pairWifi`,
`scrcpy:start`); main→renderer pushes use `event:*` (`event:telemetry-updated`).
Keep this convention — the renderer only ever talks through `window.api`
(`contextIsolation: true`, `nodeIntegration: false`).

- **Two transports, one renderer.** `state.target` in `src/renderer.js` picks
  which connection the Files view follows; `updateFilesTransport()` is the
  switch. Keep per-transport state keyed by target — `selectedFiles.adb` vs
  `selectedFiles.companion` — so batches never cross transports. Downloads use
  `pullFile` (ADB) or `downloadPhoneFile` (companion).

## Gotchas

- **Closing the window does not quit.** `main.js` hides to the tray unless
  `isQuitting` is set; quit via the tray menu or `app.quit()`. A "stuck" running
  app is normal.
- `--disable-vulkan` in the start scripts is deliberate (compositor/GPU issue);
  keep it.
- `services/scrcpy.js` hardcodes the MediaTek encoder
  `--video-encoder=c2.mtk.hevc.encoder` and `--video-codec=h265`. This only
  works on the Dimensity-based target device; other phones need a different
  encoder.
- `test-fixes.sh` and `verify-fixes.sh` hardcode one device serial
  (`adb-XS5TE6ZXLRYTBUM7-...`). Treat them as author one-offs, not reusable.
- On the **ADB path**, notification sync is `adb logcat` filtering, not a real
  `NotificationListenerService`: **text is a placeholder** ("New notification"),
  only the app name is real. The companion path carries real text.
- **Desktop bubbles go through `dbus-next`, not just `notify-send`.**
  `notify-send` cannot replace or retract a bubble (it returns no id), so
  `services/desktop-notify.js` speaks `org.freedesktop.Notifications` directly
  and falls back to `notify-send` when no daemon is on the session bus. Updates
  and closes become no-ops without a daemon; nothing breaks.
- **Dismissing a notification is local.** Clearing history in the app must not
  dismiss it on the phone; `notifications:dismiss` does not forward to the
  companion channel.
- **Bulk downloads run one at a time on purpose.** `file-transfer.js`
  `uniqueLocalTarget()` allocates `name (1).ext` instead of overwriting, and that
  existence check is only race-free because the renderer awaits each pull.
- Clipboard sync is bidirectional and needs **no app on the phone**: the scrcpy
  server runs as `shell`, which Android exempts from the Android 10+
  background-clipboard restriction that forces KDE Connect to ask for a tap.
- **Do not use Electron's `clipboard` module for the desktop side.** On this
  stack it cannot see other clients' Wayland selection (it reads `""`) and its
  writes never reach the compositor. `services/clipboard.js` uses
  `wl-paste`/`wl-copy` (X11: `xclip`) and only falls back to Electron.
- Electron 44's clipboard API is Promise-based (`readText()` returns a Promise,
  `availableFormats()` is gone). If you touch that code path, await it — a
  Promise passed to `webContents.send` serialises to `{}` or throws
  `Failed to serialize arguments`.
- All main→renderer pushes go through `sendToRenderer()` in `main.js`; it
  tolerates a destroyed/reloading window. Sending directly throws
  `Render frame was disposed`.
- The repo is MIT per `package.json`, but there is still **no `LICENSE` file**.
  The README states that plainly now; add one before a release.
- `assets/` is extracted from OPPO's O+ Connect client and is not covered by the
  project license. Don't ship it to third-party services as "our" code.

## Docs that do not match the code

`development-plan.md`, `implementation-roadmap.md`, `project-documentation.md`,
`protocol-specifications.md`, and `WEEKEND-SPRINT-SUMMARY.md` describe an
aspirational design or an early snapshot — an unimplemented Rust transport,
Qt6/Kirigami UI, D-Bus service, and the reverse-engineered "Panta" protocol.
**None of it is in this codebase**, and each file now carries a banner saying so.

For the current truth use `README.md` and
`docs/companion/companion-status.md`. Do not implement against the historical
files.
