# Credits & Open Source Use

## Project

**A+ Connect** — a Linux desktop companion for OPPO / ColorOS phones, a
reimplementation of OPPO's O+ Connect for the Linux desktop (KDE / Wayland &
X11).

**Author:** Asif Ahamed  
**Repository:** https://github.com/asifahamed-ece/Aplus_Connect  
**License:** MIT (see `LICENSE`)

---

## Time Investment Credit

This project is the product of sustained, hands-on development effort,
including:

- Reverse-engineering OPPO's O+ Connect protocol and UI behavior
- Wiring the Electron desktop app (main process, preload, renderer) to the
  `@yume-chan/*` scrcpy/adb libraries over ADB and to a custom TLS companion
  protocol on the phone
- Building the Android companion app (Kotlin / Jetpack Compose) that dials out
  to the desktop over encrypted TLS 1.3
- Implementing bidirectional clipboard sync without requiring accessibility
  services or contactless-payment workarounds (patched HeliBoard IME fork)
- Fixing companion connection stability (no reconnect loop), first-install
  file transfers (`mkdirs()` before writing temp files), and cancellation-safe
  transfers on both PC→phone and phone→PC directions

If you use this project in an open-source context, please keep the author
credit in place and link back to the repository.

---

## Third-Party & Open Source Components

| Component | Purpose | License |
|-----------|---------|---------|
| Electron | Desktop app framework | MIT |
| `@yume-chan/adb` family | ADB / scrcpy protocol over Node | MIT |
| `dbus-next` | D-Bus notification integration | MIT |
| `qrcode` | Pairing QR code rendering | MIT |
| Scrcpy | Screen mirroring engine | Apache-2.0 (Genymobile) |
| HeliBoard (forked as Keyboard) | Background clipboard IME | GPL-3.0 |
| AndroidX / Jetpack Compose | Companion app UI | Apache-2.0 |
| `notify-send` / `libnotify` | Desktop notification fallback | Various (system) |

**Not covered by this project's license:**
- `assets/` — resources extracted from OPPO's official O+ Connect client (UI
  design inspiration only)
- `keyboard/` — the HeliBoard fork is GPL-3.0 and pulled in separately
- `dist/` AppImage and APK builds are compiled binaries, not source

---

## Using This in an Open-Source Project

You are free to use, modify, and distribute A+ Connect under the MIT License.
The only requirements are:

1. Include the `LICENSE` text and copyright notice in copies or substantial
   portions of the software.
2. Do not represent the OPPO-extracted `assets/` as original project work —
   they remain OPPO's property and are used here only as design reference.
3. If you redistribute the Keyboard fork, comply with GPL-3.0 (provide
   corresponding source).

Attribution example:

> A+ Connect by Asif Ahamed — https://github.com/asifahamed-ece/Aplus_Connect  
> Licensed under the MIT License.
