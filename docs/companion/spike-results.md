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

## What the literature says (searched after the measurement)

The measurement was checked against the platform documentation and against what real
apps do, because it contradicts what the design assumed.

**There is no clipboard role.** Google was asked for one — AOSP issue 135471818, "offer
'clipboard manager' role", which would let clipboard managers read in the background. It
was closed as *Won't Fix (Obsolete)*. `android.permission.READ_CLIPBOARD_IN_BACKGROUND`
exists, but its protection level is `signature|role`: on this device it is granted only
to apps signed with the platform key (one holder was found,
`com.microsoftsdk.crossdeviceservicebroker`), and no role can be assigned to a normal
app. `cmd role add-role-holder android.app.role.CLIPBOARD` fails with
`Unknown role: android.app.role.CLIPBOARD` — the role does not exist.

**The rule is documented and matches the measurement exactly.** From the platform
behaviour: *"Unless your app is the default input method editor (IME) or is the app that
currently has focus, your app cannot access clipboard data on Android 10 or higher."*
The accessibility service is **not** one of the two doors. That matches what this device
did with the service enabled, bound, with and without window-content access.

**What real apps do instead** — KDE Connect, the reference implementation, uses a
three-part trick for the phone → PC direction:

1. It watches for the *event* of a copy rather than polling the clipboard. Historically
   that meant running `logcat` and looking for the system's clipboard access messages,
   which needs the privileged `READ_LOGS` permission granted through adb. Newer versions
   can use Shizuku for the same job instead of adb.
2. When a copy is detected it launches `ClipboardFloatingActivity` — a transparent,
   non-touchable activity with `FLAG_NOT_TOUCH_MODAL` that raises over other apps and
   takes focus.
3. Because it now has focus, the clipboard read succeeds. It forwards the content and
   finishes immediately.

KDE Connect also puts a **"Send clipboard" action on its persistent notification** as the
manual fallback, which is what most users end up using.

**The PC → phone direction needs no privilege at all**, because the OS allows background
clipboard *writes*. This matches the measurement exactly, and matches the report that
KDE Connect's clipboard flow works "out of the box" in that direction.

### Consequences for the companion app

| Direction | Mechanism | Privilege needed |
|---|---|---|
| PC → phone | Background clipboard write | **None** — works today, measured |
| Phone → PC | Detect the copy event, then raise a transparent activity that takes focus, read, forward, finish | Accessibility service for the event, focus for the read |
| Phone → PC (alternative) | Be the default keyboard | Default-IME role — measured working, but the user must type with our keyboard |
| Phone → PC (manual) | A "Copy to PC" action on the notification | None — the tap gives the app focus |

The accessibility service is therefore still worth having, but for a different reason
than the one the plan assumed: not as a privilege, but as a **copy-event detector**.

### Honest comparison with the ADB path we have today

The scrcpy server runs as the `shell` UID, which Android exempts from the clipboard
restriction outright. That gives today's app a read with no focus stealing, no
accessibility service, no notification and no keyboard change. The companion app trades
that reliability for the thing it was built for: **the phone exposes no listening port on
the network**, so a campus Wi-Fi has nothing to attack.

That is the whole trade: the ADB path is smoother; the companion path is safer.


## Decision

- Detection mechanism: the foreground service polls the clipboard every 2 seconds and
  compares the SHA-256 with the last value it sent.
- Privilege mechanism: **not** the accessibility service, which grants nothing here.
  The phone → PC read happens in a transparent activity that takes focus, triggered by a
  copy event; a "Copy to PC" notification action is the manual fallback; the default-IME
  role is an alternative for a user who prefers it.
- Poll interval chosen: 2 seconds — a read costs a few milliseconds, and 2 s is below the
  threshold where a copied text arriving late is noticeable.
- Foreground service type: `connectedDevice` — accepted, evidence above.
- The shipping app must request `POST_NOTIFICATIONS` at runtime, because without a visible
  notification the foreground service is not "visible" to the system and the notification
  is not shown at all.
