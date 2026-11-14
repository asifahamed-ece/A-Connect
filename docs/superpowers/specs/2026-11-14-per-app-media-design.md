# Per-app media, and media both ways — design

Date: 2026-11-14
Status: proposed, contract frozen below
Scope: the media channel only. Nothing about pairing, the clipboard or files
changes.

## What this is

Today the phone picks **one** media session and the desktop publishes **one**
MPRIS player. Two things follow from that, and both are wrong:

- The desktop's shell shows a single player called "A+ Connect", so it cannot
  say *which app* is playing, and two apps playing at once collapse into one.
- The phone cannot control the desktop's players at all.

KDE Connect does neither of these. This design copies its two decisions that
are worth copying, and leaves the rest.

## What KDE Connect actually does (verified in its source)

| Thing | KDE Connect | Source |
| --- | --- | --- |
| Phone players on the desktop | **one fake MPRIS player per phone player**, bus name `org.mpris.MediaPlayer2.kdeconnect.mpris_<uuid>`, `Identity() = "<player> - <device>"` | `plugins/mprisremote/mprisremoteplayer.cpp` |
| Which phone player is "current" | the phone keeps the last shown one if it still plays, else any playing one — but the desktop receives **all** of them | `kdeconnect-android/.../mpris/MprisMediaSession.kt` |
| Metadata cadence | pushed **only on `MediaController.Callback` events**; no timer. The desktop **interpolates the position locally from wall-clock** | `MprisReceiverPlugin.kt`, `mprisremoteplayer.cpp` |
| Album art | a JPEG payload; the URL carries a hash so a changed bitmap with an unchanged URL is noticed; a 5 MB LRU on disk | `albumart_cache.cpp` |
| Media / ongoing notifications | **deliberately not mirrored** (`FLAG_FOREGROUND_SERVICE`, `FLAG_ONGOING_EVENT`, `CATEGORY_TRANSPORT`) — "media playback controls are handled by the MPRIS plugin" | `NotificationsPlugin.kt` |
| Desktop→phone control | a **separate plugin** (`mpriscontrol`), which identifies players by MPRIS *Identity* and ignores `playerctld` | `plugins/mpriscontrol/` |
| Browsing a file | `sftp` + a KIO worker reads it **through the mount**, so it opens without downloading | `plugins/sftp/`, `kio/kiokdeconnect.cpp` |

What we already have that matches: local position interpolation
(`services/mpris.js positionMs()`), and a content-addressed art cache
(`saveArt`).

What we deliberately do **not** copy: KDE Connect drops persistent
notifications entirely. Our user wants them, shown apart — which is what the
notification list now does (an "Ongoing" section, and media ones filtered at
the phone).

## The contract

One media message per **player**, keyed by the app's package. That is the
whole change: the payload grows a `pkg`, and a session that stops is announced
as gone rather than as "idle" for everything.

```
media-state   { type, pkg, app, title, artist, album, playing,
                positionMs, durationMs, art? }        # one per player
media-off     { type, pkg }                           # that player ended
media-command { type, pkg, command, positionMs? }      # addressed to one player
```

- `pkg` is the phone app's package name, and it is the player's identity.
  It is stable across a track change and across a reconnect, which is what
  makes it usable as a bus name suffix.
- The phone sends one `media-state` per **worth-showing** session — a session
  with playback state and text. The existing `isWorthShowing` rule is reused
  per session instead of applied to pick one.
- `media-off` replaces the old single `idle` flag: one player going away must
  not withdraw the others.
- A command carries the `pkg` it is for, so two players can be paused
  independently.

## Desktop

`services/mpris.js` becomes a registry of players:

- `publish(state)` — create the player for `state.pkg` on first sight,
  otherwise update it.
- `withdraw(pkg)` — remove that player (its bus name is released).
- `clear()` — remove all of them (the phone disconnected).
- Bus name: `org.mpris.MediaPlayer2.aplusconnect.<slug(pkg)>`, where `slug` is
  the package with dots and dashes turned into dashes and anything else
  dropped. `Identity` is `"<app name> — <phone name>"`, so the shell's media
  widget says "YouTube — Reno 14" and not "A+ Connect".
- `DesktopEntry`-style properties stay the same; only the identity changes.

## Phone

`service/MediaSessionBridge.kt` stops picking a winner for the wire. It keeps
its "which one is current" logic **only** for the phone's own notification
(where a single answer is genuinely wanted), and publishes every worth-showing
session:

- one state per session per tick, each carrying its `pkg`;
- art cached per `(pkg, track)`, so two players cannot fight over one cover
  file;
- `media-off` for a session that stopped or went away;
- commands routed by `pkg` to that session's controller.

## Bidirectional: phone controls the desktop

The desktop already reads its own MPRIS players for the shell. Add:

- the desktop enumerates its own players (every `org.mpris.MediaPlayer2.*`
  that is not yours, ignoring `playerctld`) and publishes them to the phone
  over the same messages, with a `side` field (`phone` | `pc`);
- the phone shows them and sends `media-command` back with the matching
  `side`, `pkg` and player identity;
- the phone's UI gets a second group in the player sheet: "This PC".

This is the `mpriscontrol` half of KDE Connect, and it is the part that needs
phone UI, so it is a second slice.

## Order of work

1. The protocol: `pkg`, `media-off`, the command's `pkg`, both sides, shared
   fixture.
2. The desktop registry, with a check that drives two packages and asserts two
   bus names, an update in place, and a withdrawal.
3. The phone: publish every session.
4. Live: two apps playing at once on the phone, two players on the desktop,
   each controllable.
5. The reverse direction (slice 2).

## How it is proved

- `scripts/check-mpris.js` grows: two packages publish two players with
  distinct bus names and identities; updating one leaves the other untouched;
  withdrawing one leaves the other playing.
- The phone: unit tests for the per-session mapping (`toMediaState` per
  session) and for the command routing by package.
- Live: YouTube and a music app on the phone at the same time.
