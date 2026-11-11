# AGENTS.md

A+ Connect: an Electron desktop app (Linux/KDE) that mirrors and controls an
Android phone (OPPO Reno 14 5G / ColorOS) by shelling out to `adb` and `scrcpy`.
It is a Linux reimplementation of OPPO's O+ Connect.

## No build step; a few pinned npm dependencies

- There is **no bundler and no build step**, but `package.json` now declares four
  runtime `dependencies` (all `@yume-chan/*` at `3.0.0-beta.5`): `adb`,
  `adb-scrcpy`, `adb-server-node-tcp`, `scrcpy`. They speak the scrcpy protocol
  over ADB from Node, which is how clipboard sync reads the phone clipboard.
  `npm install` is therefore required; `npm start` runs
  `scripts/ensure-deps.js` first and fails with a clear message if they are
  missing.
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
- `services/*.js` — backend logic (`adb`, `scrcpy`, `file-transfer`,
  `clipboard`, `notifications`), each an `EventEmitter`.
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
- Notification sync is `adb logcat` filtering, not a real
  `NotificationListenerService`: notification **text is a placeholder**
  ("New notification"), only the app name is real.
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
- The repo is MIT per `package.json`, but there is no `LICENSE` file despite the
  README linking one.

## Docs that do not match the code

`development-plan.md`, `implementation-roadmap.md`, `project-documentation.md`,
and `protocol-specifications.md` describe an aspirational design — an
unimplemented Rust transport, Qt6/Kirigami UI, D-Bus service, and the reverse-
engineered "Panta" protocol. **None of it is in this codebase.** The real app is
Electron (see `README.md` and `WEEKEND-SPRINT-SUMMARY.md`). Do not implement
against those four files.
