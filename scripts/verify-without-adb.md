# Verifying A+ Connect without ADB

This is the walkthrough that proves the whole design: **the phone is reachable
over plain Wi-Fi, with no USB cable, no wireless debugging and no developer
options.** Everything else in this project was built while a cable was plugged
in, where a clipboard copy could still have quietly taken the ADB path.

Run `node scripts/check-without-adb.js` first. It checks the four
preconditions that can be checked from outside the app, so the walkthrough
starts from a known state rather than a guess.

```
node scripts/check-without-adb.js
```

| Precondition | Why it matters |
| --- | --- |
| No phone attached over ADB | If a phone is on the cable, a "clipboard works" result might have come from ADB. This is the assertion the whole walkthrough rests on. |
| The companion port serves the pinned certificate | The phone only talks to this exact certificate. If the port serves something else, nothing can pair. |
| The companion switch is on | Off means dormant: the desktop is not listening and nothing flows. |
| The phone is still paired | Otherwise the run needs a pairing code, and the run is testing the link, not the pairing. |

---

## 0. Setting the phone up (once, and after any reinstall)

These are platform requirements, not A+ Connect features. Each one is a
permission the user has to grant by hand, because that is the point.

| Step | Where | Why it is needed |
| --- | --- | --- |
| Set **A+ Connect Keyboard** as the default keyboard | Phone: Settings → System → Languages & input → Default keyboard | Android only lets the *default keyboard* read the clipboard in the background. Without it, the clipboard does not sync at all — and no amount of app work will change that. |
| Grant **notification access** | Phone: Settings → Apps → Special app access → Notification access → A+ Connect Companion | Without it the PC shows no phone notifications. |
| Grant **All files access** | Phone: Settings → Apps → Special app access → All files access → A+ Connect Companion | The file browser and the global search read the whole storage. |
| Turn **Developer options off** | Phone: Settings → About phone → tap Build number 7 times, then Developer options off | Not a feature. It is what makes the test worth running. |
| Leave **wireless debugging off** | Phone: Developer options → Wireless debugging | The companion channel must not depend on it. |
| Both devices on the **same Wi-Fi** | — | The phone dials the computer over LAN. |

The desktop needs `npm install` once, and a **self-signed certificate** it
generates on first run. The phone pins that certificate the first time it
pairs; deleting the certificate means pairing again.

---

## 1. Pairing, by QR

1. On the desktop: **Add a device** (the button in the device list).
   The modal opens on the **Pair by QR** tab, which is the point: the QR code
   carries the address, the port, the one-time code and the certificate
   fingerprint, so nothing is typed and nothing is trusted by hand.
2. The panel shows a QR code, the six-digit code, and the computer's name and
   address.
3. On the phone: **Scan pairing QR** (the first button in the connect dialog),
   and point the camera at the screen.
4. The phone fills the address, port and code, and connects.

**You should see:** the desktop's device list gains the phone, with
`Companion · TLS 1.3` on it, and the tray stops saying "No Device Connected".

**If it does not work:** read the log, which says why.

| What the log says | What it means |
| --- | --- |
| `not paired, and pairing is not open (no valid code)` | The phone was not paired and the six-digit code was missing or expired. The codes last five minutes; open **Add a device** again and scan the new QR. |
| `no valid proof of possession` | The phone is known but no longer holds the secret from the pairing. Scan the QR again while the code is live. |
| The phone's own log: `the computer's certificate changed: expected ..., got ...` | The desktop's certificate was regenerated, so the phone rightly refuses it. Delete `cert.pem` and `key.pem` in `~/.config/aplus-connect/companion/` on the desktop, restart it, and pair again. |

---

## 2. The clipboard, both ways

This is the feature that decides whether the whole thing is worth using.

1. **Phone to PC.** Copy something on the phone (long-press, Copy).
   The desktop's clipboard should hold it within a second.
2. **PC to phone.** Copy something on the desktop.
   The phone's clipboard should hold it — paste it into a message and check.
3. **Paste into a password field on the phone.** If that works, the clipboard
   really is flowing in the background, which is the hard part.

**You should see:** the desktop's clipboard card shows the sync, and the phone
needs no tap for it.

**Why the keyboard matters:** Android gives clipboard access to the default
keyboard only. If the phone is on Gboard, step 1 fails and no error is shown
on the phone — the desktop just never receives anything. The desktop says so in
the companion card ("the phone is not set to the A+ Connect keyboard").

**Do not try to fix this with an accessibility service.** Google Wallet
refuses NFC payments when any accessibility service is enabled, including one
that does nothing. That was measured on this phone, and it is why the keyboard
fork exists.

---

## 3. Notifications

1. Post a notification on the phone — a message, a download, anything.
2. The desktop shows it with the app name, the text, and when it arrived
   ("2 min ago", or a clock time).
3. Dismiss it on the desktop: the notification goes away on the phone too.
4. Post one on the phone while the desktop is off (see step 8), then bring the
   desktop back: the queued notifications arrive.

**You should see:** the newest notification first, with its arrival time.

---

## 4. Playback control from the desktop shell

1. Play something on the phone — YouTube, a music app, a podcast.
2. In the desktop shell:

```
playerctl -l                     # should list aplusconnect
playerctl -p aplusconnect metadata
playerctl -p aplusconnect position 30+    # seek forward
```

**You should see:** the phone's track, and the desktop can pause and seek it.

The app publishes the phone's playback as an MPRIS player, so the shell's media
widget picks it up. If the player is missing, the phone has no playback
permission or nothing is playing.

---

## 5. The phone's files

1. On the desktop: **Send File** → the phone's browser.
2. Browse into a folder, including one the system picker would refuse (the
   storage root, `Download`).
3. Search for something by name: the answer comes back across the whole
   storage.
4. Open a file from the result: it downloads to the desktop.

**You should see:** the phone's real folders, a working search, and a
read-only browser — there is no way to delete anything on the phone.

The first search after connecting may take a few seconds while the index is
built; after that, searches are instant.

---

## 6. Screen mirroring

1. On the desktop: select the **Companion** device, then **Start casting**.
2. The phone asks: *Share your screen with A+ Connect Companion?* — **Share
   screen**.
3. The phone's screen appears on the desktop, at the phone's shape.

**You should see:** a live picture, and the phone's own screen still usable —
the mirror is a duplicate, not a takeover.

**Then check the ways it can end, because all of them must work:**

| Do this | What should happen |
| --- | --- |
| Press **Stop** on the desktop | The picture stops, the phone stops capturing. |
| Pull the notification shade on the phone, press **Stop sharing** | Same, and the desktop says *stopped*, not *connection lost*. |
| Lock the phone | The session ends; the desktop says the screen went to sleep. |
| Tap the system's screen-sharing chip | The session ends. |
| Turn the desktop's companion switch off | The picture stops within about six seconds. |

**You should see:** the desktop never sits on a frozen frame claiming to
stream. A dead link is noticed in about six seconds on both ends.

---

## 7. The kill switch

1. Turn the **companion switch** off on the desktop. The card says *Off*, and
   the tray says *Companion off*.
2. On the phone, the state becomes *The computer is off · waiting*.
3. Turn the desktop's switch back on. **Touch nothing on the phone.**

**You should see:** the phone reconnects by itself, within about half a
minute, and needs no pairing code again.

The same switch on the phone stops the phone talking to the desktop at all —
nothing is copied, notified, transferred or mirrored, and the phone stays that
way after a restart.

---

## 8. The desktop going away, and coming back

1. With the phone connected, quit the desktop app.
2. The phone says *Disconnected · retrying*. It retries quickly at first, and
   after a few failures in a row it assumes the computer is not coming back
   and slows to one attempt every thirty seconds.
3. Start the desktop again.

**You should see:** the phone reconnects on its own within a few seconds (or
within thirty, if it had already slowed down), with no re-pairing.

The one case that does *not* reconnect by itself is the desktop's **Disconnect**
button: that is a deliberate hang-up, and the phone waits for you to press
Reconnect. That difference is deliberate — a hang-up that undid itself in five
seconds would look like the button did nothing.

---

## 9. What this does not cover

- **The dark-screen mode** scrcpy offers. It needs ADB, so it is not part of
  this path.
- **Controlling the phone from the desktop.** That needs an accessibility
  service, which breaks contactless payments.
- **Audio** with the mirror.
- **Rotation and fullscreen** while mirroring.

---

## Resetting between runs

To get back to a clean slate without reinstalling anything:

| To reset | Do this |
| --- | --- |
| Forget the pairing | Desktop: remove the phone from the device list. Phone: clear the app's data (this also clears the pinned certificate). |
| Forget the network | Phone: turn Wi-Fi off and on again; the desktop's listener is still up. |
| Reset the desktop's identity | Delete `~/.config/aplus-connect/companion/cert.pem` and `key.pem`, then restart. The phone must pair again. |
| Start completely fresh | Uninstall both the companion app and the keyboard fork, then reinstall the companion app **first** — the signature permission is granted at install time. |

---

## The honest limits

- The clipboard needs the keyboard fork and the default-keyboard role. There is
  no way around it that does not break payments.
- Notifications need notification access; files need all-files access. Both
  are granted by hand.
- The phone's screen must stay awake while mirroring. That is a platform rule
  about screen capture, not a shortcut in this app.
- The first connection after pairing builds a search index of the phone's
  storage, which takes a few seconds on a phone with a lot of files.
