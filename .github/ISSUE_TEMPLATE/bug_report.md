---
name: Bug report
about: Something is broken, crashes, or does not do what the README says
title: ''
labels: bug
assignees: ''
---

## What happened

<!-- One or two sentences. What did you expect, and what happened instead? -->

## Which connection path

- [ ] ADB path (wireless debugging / `adb` + `scrcpy`)
- [ ] Companion path (the phone app over TLS)
- [ ] Both

## Steps to reproduce

1.
2.
3.

## Environment

- A+ Connect version (release tag, or `package.json` version):
- Install method: AppImage / `npm start` from source
- Desktop: distribution, KDE Plasma version, Wayland or X11
- Phone: model, Android version, ColorOS version
- Phone app version, if the companion path is involved (`versionName`):

## Logs

<!--
Launch from a terminal so the main-process lines are visible:

  ./A-Plus-Connect-*.AppImage    (or: npm start)

Paste the relevant lines. On the phone side:

  adb logcat | grep -iE "aplusconnect|companion"
-->

```
```

## Anything else

<!-- Screenshots, a screen recording, or the transfer log entry help. -->
