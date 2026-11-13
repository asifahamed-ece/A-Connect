# The kill switch — design

Date: 2026-11-14
Status: proposed, awaiting the go-ahead — no code written yet
Scope: one master switch on each device. Nothing else changes about pairing,
trust or the wire protocol beyond one new `bye` reason.

## What this is

A switch on the desktop and a switch on the phone. When either is **off**, that
device is dormant: the desktop stops listening, the phone stops dialling, and no
clipboard, notification, file or video flows. The choice is remembered, so a
phone left off stays off across a reboot, and a desktop left off starts silent.

The switch is a **local** control on each side, not a remote command: turning the
phone off from the desktop would be a way to blind a phone the user is not
holding. Both devices must be on for anything to happen, which is the whole
point — the phone can always cut itself off, and so can the PC.

## Intent

- **Off means off.** Not "quieter": the desktop's TLS listener is closed, so
  there is no port to find; the phone stops its redial loop.
- **Remembered.** A dormant device stays dormant after a restart.
- **The phone can always be silenced locally**, with no cooperation from the PC.
- **Turning the PC back on is discoverable.** The phone finds it on its own,
  without the user re-pairing or pressing anything.

## The two behaviours that are not obvious

### 1. "The computer is off" is not "the user hung up"

The existing hang-up (`bye`, from the Disconnect button) means *stop retrying
until the user acts*. Dormancy means *keep looking, slowly*. If they shared a
reason, turning the PC off would either look like a hang-up (phone gives up) or
turning the PC off from the Disconnect path would restart the redial storm the
hang-up exists to stop.

So `bye` grows a reason, and the phone keeps the two apart:

| What the phone saw | What it does |
| --- | --- |
| `bye` with reason `dormant` | slow retry every 30 s, "The computer is off" |
| `bye` (any other reason) | stop until the user taps Reconnect (existing) |
| socket dropped, no `bye` | fast retry, 5 s (existing) |

The slow cadence is sticky: once dormant, every failed attempt waits 30 s until
one succeeds, so a PC that is off overnight is not hammered.

### 2. A dormant desktop that never got to say `bye`

The desktop says `bye`/`dormant` before it closes the listener, so a connected
phone learns at once. A phone that was asleep when the PC went dormant only sees
*connection refused*. To keep that from being a 5-second spin, the phone also
enters the slow cadence after a few refusals in a row, and leaves it on the
first successful connection. It cannot tell "PC off" from "PC not there yet",
and it does not need to: both mean "keep looking, slowly".

## Where the state lives

Desktop: `settings.json` next to the paired-devices file, one key `enabled`.
Written whole-file on change, like the paired devices, and read at construction.
`enabled: false` means `start()` returns without opening a listener.

Phone: `CompanionSettings.enabled`, a Boolean in the existing SharedPreferences.
`false` means the service does not dial, and `ClipboardBridgeReceiver` does not
start the service for a copy — a dormant phone must not wake itself up.

## The wire, exactly

The `bye` payload already carries `reason` as free text. Add one machine value:

```
REASONS.DORMANT = 'dormant'
```

```
bye { reason: 'dormant' }     # the desktop is going dormant, keep looking
bye { reason: '<anything>' }  # deliberate hang-up, stop until told otherwise
```

No new message type, no new field, no protocol version change.

## UI

One control per device, both plain and honest about the consequence:

- Desktop: a "Companion off / on" switch in the companion area, and the status
  line reads "Off — the computer is not listening" while dormant.
- Phone: a switch at the top of the Interconnect screen, and the service's
  notification is withdrawn while dormant.

Turning either off does not unpair, forget a secret, or change a fingerprint. It
only stops work.

## Out of scope

- A schedule, a timer, or "off until tomorrow".
- An allow-list of features (clipboard on, files off) — one switch, all of it.
- Turning the phone off from the desktop, for the reason above.

## How it is proved

- Desktop: `scripts/check-kill-switch.js` drives the real service — off closes
  the listener and writes the reason, on reopens it and persists across a
  restart of the object.
- Phone: unit tests for the reason mapping (`dormant` → slow, else → stop) and
  for the settings default and persistence.
- Live: toggle the desktop off while the phone is connected and watch the phone
  say "The computer is off" and settle into the slow loop; toggle it on and
  watch the phone reconnect on its own.
