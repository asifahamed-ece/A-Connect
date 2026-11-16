# Changelog

All notable changes to A+ Connect are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.1.2] — 2026-11-16

Desktop-only release. The Android companion app and the keyboard APK are
unchanged from 1.1.1 and are attached for convenience.

### Fixed

- **System tray icon no longer stretched on KDE.** The tray now ships
  purpose-drawn 22 px and 22 px @2x assets instead of downscaling the 1024 px
  application icon, so KDE's status-notifier drawer renders it square.
- **Only one instance runs at a time.** A second launch now focuses the existing
  window instead of opening a rival window, tray icon, `adb` poll loop, and a
  second bind attempt on the companion port.
- **The Android APK reports the real version.** The installer showed "Version
  0.1.0"; the companion app now declares `versionName 1.1.2` (`versionCode
  10102`) and shows the same string in its own settings screen.

## [1.1.1] — 2026-11-15

### Fixed

- Companion connection no longer drops and reconnects in a loop.
- Cancelling an incoming phone→PC transfer no longer crashes the main process
  with `ERR_STREAM_DESTROYED` (and no longer closes screen mirroring).
- Chunks that arrive for an already-cancelled transfer are dropped instead of
  recreating the stream.
- First-install PC→phone transfers no longer fail: temp directories are created
  with `mkdirs()` before the first write.

## [1.1.0] — 2026-11-15

### Added

- Companion path: a phone app that dials out to the desktop over TLS 1.3, with
  QR / code pairing and certificate pinning.
- Companion file browser: browse and search the phone's storage, download single
  or multiple files, and push files to the phone.
- View-only screen mirroring over the companion channel.
- Real notification text on the companion path, with updates replacing existing
  bubbles and dismissal from the desktop.
- MPRIS media control for the phone's current player.
- Bidirectional clipboard sync on the companion path via the patched keyboard.
- Interface showcase in the README and the frosted-glass design pass.

### Changed

- Two transports, one renderer: the Files view follows the selected target, and
  per-transport selection state is kept apart.

## [1.0.0]

Initial Linux desktop release: ADB device discovery, `scrcpy` mirroring, file
transfer both ways, clipboard sync, `adb logcat` notification mirroring, and a
tray-resident window.
