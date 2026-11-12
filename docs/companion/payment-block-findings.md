# Google Wallet payment blocks — findings

Measured on CPH2737 (OPPO Reno 14 5G), Android 16 / API 36, ColorOS, Google Wallet
`com.google.android.apps.walletnfcrel` (NFC release build).

## The cause

Google Wallet's payment flow calls
`AccessibilityManager.getEnabledAccessibilityServiceList()`. The call is present in the
Wallet APK itself (`classes.dex` / `classes3.dex`), and it is **not** filtered by the
service's capabilities: our watcher declared `canRetrieveWindowContent="false"` and ran
with `capabilities=0`, and it still tripped the guard.

## There are two independent guards

| Guard | Trigger | What Wallet shows |
|-------|---------|-------------------|
| **A** | any enabled accessibility service | "Payments blocked. Some apps have permission to make payments, take action." → tells the user to turn accessibility off for all apps |
| **B** | USB / wireless debugging switched on | "Payment environment not secure" / "USB debugging turned on" / "Fix" |

Guard B was observed with **our accessibility service switched off** — the message named
the debugging connection, not an app. So on this phone, leaving debugging on blocks
payments even with A+ Connect uninstalled.

## What this means for the project

1. **The companion app must never require an accessibility service.** It is not a
   "sensitive but harmless" permission: it takes contactless payments away.
2. **The debugging connection blocks payments too.** So the goal of replacing ADB is not
   only about network exposure — it is what makes the phone usable for payments at all.
   With debugging off, the companion channel is the *only* clipboard path, so it has to be
   seamless.
3. A background app that is neither the default keyboard nor focused cannot even learn
   that the clipboard changed: both `ClipboardManager.addPrimaryClipChangedListener` and
   `getPrimaryClipSource()` are gated by the same `clipboardAccessAllowed()` check in
   `ClipboardService` (verified against the AOSP `android16-release` source). The
   accessibility service was only ever a *trigger*; the privilege never came from it.

## The three ways to get a seamless phone → PC read

| | Mechanism | Privilege | Payments |
|---|---|---|---|
| A | accessibility service (the current trigger) | accessibility | **blocked** |
| B | default keyboard (the app holds the IME role) | IME role | safe |
| C | "Copy to PC" action on the notification | none, but a tap | safe |

C is implemented (`ca7b4c9`). A is what ships today and must go. B is the only route that
is both seamless and payment-safe.

## How the tests were run

```bash
# enable / disable the watcher
adb shell settings put secure enabled_accessibility_services \
  com.aplusconnect.companion/.clip.ClipboardEventWatcher
adb shell settings put secure accessibility_enabled 1

# open Wallet and read what is on screen (no payment is attempted)
adb shell pm clear com.google.android.apps.walletnfcrel
adb shell am start -n com.google.android.apps.walletnfcrel/com.google.commerce.tapandpay.android.wallet.WalletActivity
adb shell uiautomator dump /sdcard/w.xml && adb shell cat /sdcard/w.xml
```

Two traps worth knowing:

- After an app update the system marks the accessibility service as **crashed** and will
  not rebind it; `settings put` alone is then not enough — the service has to be toggled
  off and on again, which is exactly what the user does in Settings.
- Wallet's security screen only appears early in its flow. Once Wallet is past onboarding
  the automated check no longer reaches it, so the final confirmation has to be a real
  payment attempt by the user.
