# Clipboard privilege spike — results

Device: CPH2737 (OPPO Reno 14 5G), Android 16 / API 36, not rooted, ColorOS.
Build: companion-android, minSdk 26 / targetSdk 35, JDK 17, AGP 8.7.3.

## The answer

**On this device, a background app cannot read the clipboard — unless it is the default
keyboard (IME) or it currently has input focus.** The accessibility service does *not*
grant the privilege, on this build.

The measurement that settles it is a self-contained cycle: the app writes a value that is
unique to that cycle, reads the clipboard back 800 ms later, and compares. If the read
returns what it just wrote, access is granted; if it returns nothing, access is refused.
The clipboard being empty and the read being refused look identical from the outside, so
the app supplies its own ground truth instead of guessing.

| Configuration | Background read result |
|---|---|
| Accessibility service **disabled** | `read=NOTHING` |
| Accessibility service **enabled and bound**, `canRetrieveWindowContent=false` | `read=NOTHING` |
| Accessibility service **enabled and bound**, `canRetrieveWindowContent=true` (`capabilities=1`) | `read=NOTHING` |
| Accessibility service enabled + bound + `POST_NOTIFICATIONS` granted + visible foreground-service notification + screen on | `read=NOTHING` |
| **App set as the default IME**, accessibility service off, no foreground window | `read=MATCH` — every cycle |
| App in the foreground (activity visible) | `read=MATCH` |

## What else was measured

| Fact | Measurement |
|---|---|
| Background **write** | Works. The OS shows its clipboard chip, and the value is read back afterwards. |
| Foreground write | Works. |
| Foreground service type `connectedDevice` | Accepted: `isForeground=true`, `types=0x00000010`. Needs `FOREGROUND_SERVICE_CONNECTED_DEVICE` plus `CHANGE_WIFI_STATE`. |
| Accessibility service with `canRetrieveWindowContent=false` | Still bound and running, but it grants no clipboard access. |
| `POST_NOTIFICATIONS` | Required for the foreground-service notification to be shown at all; on this build the shell cannot grant it (`GRANT_RUNTIME_PERMISSIONS` denied), so the app must ask the user. |
| `android.permission.READ_CLIPBOARD_IN_BACKGROUND` | Exists on this device but is `prot=signature\|role` — it is granted through a role, not a normal permission, and no role could be assigned to the app. |
| ColourOS install/update of an app with an **enabled** accessibility service | `INSTALL_FAILED_VERIFICATION_FAILURE` — the accessibility service has to be disabled before the app can be updated. |
| `am force-stop` | Unbinds the accessibility service and leaves the package stopped, so its components do not start. Development-only concern. |

## What this means for the design

The clipboard privilege on Android 16 has exactly two doors an app can walk through:

1. **Be the default keyboard.** Android grants the default IME privileged clipboard
   access, and that privilege belongs to the whole app UID — so the background
   foreground service reads the clipboard normally, with no focus and no visible window.
   This is what the measurement proves.
2. **Have input focus.** The app must be on screen when it reads.

An accessibility service, however carefully configured, is not a third door on this build.

That splits the two directions of the sync:

| Direction | Background feasibility | Why |
|---|---|---|
| **PC → phone** (the PC writes, the phone receives) | **Seamless** | Background clipboard writes work; no privilege needed at all |
| **Phone → PC** (the phone reads, the PC receives) | **Needs the default-IME role** | Background reads are refused otherwise |

### The options for phone → PC

- **Default keyboard** — seamless, measured working. The user selects the companion app as
  their keyboard. It is a real ask, and typing becomes awkward if the app is a poor
  keyboard, so the app would have to be a genuinely usable keyboard or delegate to the
  previous one.
- **Tap to sync** — a notification with a "Copy to PC" action opens the app, the app reads
  the clipboard while it has focus, forwards it, and closes. No privileges, no keyboard
  change, but every copy needs a tap. This is what KDE Connect does on Android 10+.
- **Foreground only** — the phone app stays open; not viable for a background relay.

### The consequence for the project

The A+ Connect companion app is only worth building if the phone → PC direction can be
seamless, because that is the direction that needs the privilege. The default-IME route
makes it seamless, and it does so with **no accessibility service at all** — which also
removes the ColourOS install friction, the permission-screen awkwardness, and the
"enable an accessibility service" warning that users rightly distrust.

The PC → phone direction needs nothing and works in the background today.

## Decision

- Detection mechanism: the foreground service polls the clipboard every 2 seconds and
  compares the SHA-256 with the last value it sent.
- Privilege mechanism: **default IME**, not an accessibility service. The accessibility
  service is dropped from the design entirely.
- Poll interval chosen: 2 seconds — a read costs a few milliseconds, and 2 s is below the
  threshold where a copied text arriving late is noticeable.
- Foreground service type: `connectedDevice` — accepted, evidence above.
- The shipping app must request `POST_NOTIFICATIONS` at runtime, because without a visible
  notification the foreground service is not "visible" to the system and the notification
  is not shown at all.
