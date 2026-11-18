# Security Policy

## Supported versions

| Version | Supported |
|---|---|
| 1.1.x | Yes |
| < 1.1 | No |

Fixes land on `main` and in the latest GitHub release. If you run an older
AppImage or APK, update before reporting anything that may already be fixed.

## Reporting a vulnerability

**Do not open a public issue for a security problem.**

Report privately through GitHub's
[private vulnerability reporting](https://github.com/asifahamed-ece/Aplus_Connect/security/advisories/new),
or email **asifahamed670@gmail.com** if you cannot use that form.

Please include:

- the affected component (desktop app, companion APK, keyboard fork, or the
  pairing protocol),
- the version you tested (`package.json`, the release tag, or the APK's
  `versionName`),
- what an attacker gains, and the steps to reproduce it,
- any logs, captures, or proof-of-concept code you are comfortable sharing.

You can expect an acknowledgement within a few days. Please give the maintainer
a reasonable window to ship a fix before publishing details.

## Scope

In scope:

- The TLS companion channel (`services/companion.js`,
  `services/companion-handshake.js`, `services/companion-protocol.js`) — pairing,
  proof verification, certificate handling, framing, and message validation.
- The IPC boundary between the Electron main process and the renderer
  (`main.js`, `preload.js`) — anything that escapes `contextIsolation`.
- The Android companion app and the patched keyboard's clipboard broadcast.
- File handling: paths and filenames arriving over the wire, archive or path
  tricks, and writes outside the user's download directory.

Out of scope:

- Attacks that require the phone or desktop to already be fully compromised.
- Malicious apps on the phone that already hold signature-level privileges.
- Vulnerabilities in `adb`, `scrcpy`, Electron, or Android itself — report those
  upstream, though a heads-up is welcome.
- The GPL-3.0 keyboard fork's upstream code.

## What the app does with your data

A+ Connect has no telemetry, no analytics, and no cloud service. Pairing secrets
and settings stay on the two machines:

- desktop: `~/.config/aplus-connect/companion/paired-devices.json` and
  `settings.json`
- phone: the companion app's private storage

The companion channel is TLS with certificate pinning after pairing: the desktop
does not trust arbitrary certificates, and a new phone must pair with the
one-time code shown in the app. The ADB path talks only to the `adb` server on
`localhost`.
