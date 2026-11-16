# Contributing to A+ Connect

Thanks for taking the time to help. This project is a Linux desktop companion
for OPPO / ColorOS phones: an Electron app that drives the phone over `adb` +
`scrcpy`, plus a companion Android app that dials out to the desktop over TLS.

Before you start, read [`AGENTS.md`](AGENTS.md). It documents the conventions,
the four-layer wiring pattern, and the traps that have already cost time. If
your change touches file transfer, clipboard, or the companion channel, also
read [`docs/companion/companion-status.md`](docs/companion/companion-status.md);
the archived documents under `docs/archive/` describe a design that was never
built and must not be used as a reference.

By participating you agree to the [Code of Conduct](CODE_OF_CONDUCT.md).

## Ways to help

- **Bug reports.** Include the desktop version, the phone model and Android /
  ColorOS version, which path you used (ADB or companion), and the exact
  steps. `adb logcat` output or the terminal lines from `npm start` are worth
  more than a description.
- **Device reports.** The MediaTek encoder in `services/scrcpy.js`
  (`c2.mtk.hevc.encoder`) targets the Dimensity-based Reno 14. If you got the
  ADB path working on another phone, say which encoder you used.
- **Documentation.** Setup steps that tripped you up are usually a documentation
  bug too.
- **Code.** Small, focused pull requests are easier to review than large ones.

## Development setup

Requirements:

- Linux with an X11 or Wayland session (the app opens a real window; it will not
  run headless).
- Node.js and npm.
- On `PATH`: `adb` (android-tools), `scrcpy` 5.x, `wl-paste` + `wl-copy`
  (Wayland) or `xclip` (X11), `notify-send` (libnotify), `electron`.
- A phone for anything beyond UI work. Both paths can be live at once, but the
  companion path needs the companion APK installed on the phone.

```bash
git clone https://github.com/asifahamed-ece/Aplus_Connect.git
cd "A+ Connect"
npm install
npm start          # same as npm run dev
```

There is no bundler, no build step, and no transpiler: the renderer is plain
JavaScript loaded straight from `src/`. Edit a file and reload the window.

### Where things live

| Layer | File | Rule |
|---|---|---|
| Backend service | `services/*.js` | One `EventEmitter` per concern |
| IPC handler | `main.js` | `ipcMain.handle('namespace:action', …)` |
| Bridge | `preload.js` | Expose on `window.api` |
| UI | `src/renderer.js`, `src/index.html`, `src/style.css` | Renderer talks only through `window.api` |

A feature is wired in that order: service method → IPC handler → `window.api`
method → renderer. Main-to-renderer pushes use `event:*` channels and must go
through `sendToRenderer()` in `main.js`.

### Android companion app

The companion app lives in `companion-android/` (Kotlin, Jetpack Compose).

```bash
cd companion-android
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Use **JDK 17**. Newer JDKs fail the Kotlin DSL with an
`IllegalArgumentException` about the Java version. The Android SDK path is set
in `companion-android/local.properties`.

Unit tests for the protocol layer are pure Kotlin and need no device:

```bash
./gradlew :app:testDebugUnitTest
```

### Keyboard fork

The optional keyboard is a patched HeliBoard build and is **not** committed. See
[`keyboard/README.md`](keyboard/README.md) for the clone, patch, and build
steps. It is GPL-3.0 and ships as its own APK.

## Checks

Be honest about what you ran. This repository has **no test runner, linter,
typechecker, formatter, or CI workflow** for the desktop app. The available
checks are:

- `node --check <file>` for syntax,
- launching the app with `npm start` and exercising the change by hand,
- `node scripts/check-file-history.js` (`npm run check:history`) for the history
  logic,
- `./gradlew :app:testDebugUnitTest` in `companion-android/`.

If you could not test on a phone, say so in the pull request.

## Style

- Match the file you are editing. The code is CommonJS on the desktop, ESM for
  `services/clipboard-bridge.mjs` only, and Kotlin/Compose on Android.
- Comments explain **why**, not what the next line does. A comment that restates
  the code is noise.
- No new runtime dependency without a reason in the pull request. The pinned
  `@yume-chan/*` versions speak the scrcpy protocol; do not bump them casually.
- Keep `--disable-vulkan` in the start scripts. It is deliberate.
- Do not commit generated output: `dist/`, `node_modules/`, Gradle caches, or
  the HeliBoard checkout.

## Commit messages

One line, imperative, 72 characters or fewer:

```
fix: invert tdoa offset under northeast gravity
docs: restore README title, consolidate image sections
```

A conventional prefix (`fix:`, `feat:`, `docs:`, `refactor:`, `test:`,
`chore:`) helps scanning. No changelog bodies, no file inventories, no
attribution trailers.

## Pull requests

1. Fork the repository and branch from `main`.
2. Keep the change scoped to one problem.
3. Describe the problem and the fix; attach logs or screenshots for UI changes.
4. Say what you verified, and on which device and desktop session.
5. Link the issue it closes, if there is one.

Large changes are easier to accept as an issue first, so the approach can be
agreed on before the code exists.

## License

By contributing you agree that your contribution is licensed under the
project's [MIT License](LICENSE). The `assets/` directory (extracted from OPPO's
O+ Connect client) and the GPL-3.0 keyboard fork are not covered by it; see
[`NOTICE`](NOTICE) and [`CREDITS.md`](CREDITS.md).
