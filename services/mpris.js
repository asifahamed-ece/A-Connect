'use strict';

/**
 * The phone as an MPRIS player.
 *
 * Desktop shells - Caelestia/Quickshell, Waybar, GNOME, KDE - and `playerctl`
 * all speak MPRIS. Publishing the phone's media session as one is what makes
 * the track appear in the shell's media controls and makes those controls work,
 * which is exactly how KDE Connect does it. The app deliberately has no
 * "now playing" panel of its own.
 *
 * Commands are forwarded to the phone as `media-command` messages, and the
 * phone applies them to the session it had when it produced the state.
 */

const { EventEmitter } = require('events');
const dbus = require('dbus-next');

const { Interface, ACCESS_READ } = dbus.interface;
const { Variant } = dbus;

const BUS_NAME = 'org.mpris.MediaPlayer2.aplusconnect';
const OBJECT_PATH = '/org/mpris/MediaPlayer2';
const ROOT_IFACE = 'org.mpris.MediaPlayer2';
const PLAYER_IFACE = 'org.mpris.MediaPlayer2.Player';
const TRACK_PATH = '/com/aplusconnect/track/1';

/**
 * The MPRIS metadata dictionary for a phone state.
 *
 * Pure on purpose: the check script proves the mapping without a session bus.
 * A missing state means "nothing playing", so only the track id is present.
 */
function toMetadata(state, trackPath = TRACK_PATH) {
  const metadata = { 'mpris:trackid': new Variant('o', trackPath) };
  if (!state) return metadata;
  if (state.title) metadata['xesam:title'] = new Variant('s', state.title);
  if (state.artist) metadata['xesam:artist'] = new Variant('as', [state.artist]);
  if (state.album) metadata['xesam:album'] = new Variant('s', state.album);
  if (state.durationMs > 0) {
    // MPRIS lengths and positions are microseconds, as signed 64 bit integers.
    metadata['mpris:length'] = new Variant('x', BigInt(Math.round(state.durationMs * 1000)));
  }
  return metadata;
}

/** The MPRIS PlaybackStatus string for a phone state. */
function playbackStatus(state) {
  if (!state) return 'Stopped';
  return state.playing ? 'Playing' : 'Paused';
}

class RootInterface extends Interface {
  constructor(service) {
    super(ROOT_IFACE);
    this.service = service;
  }

  get Identity() {
    return 'A+ Connect';
  }

  get DesktopEntry() {
    return 'aplusconnect';
  }

  get CanQuit() {
    return false;
  }

  get CanRaise() {
    return false;
  }

  get CanSetFullscreen() {
    return false;
  }

  get Fullscreen() {
    return false;
  }

  set Fullscreen(_value) {}

  get HasTrackList() {
    return false;
  }

  get SupportedUriSchemes() {
    return [];
  }

  get SupportedMimeTypes() {
    return [];
  }

  Raise() {}

  Quit() {}
}

RootInterface.configureMembers({
  properties: {
    Identity: { signature: 's', access: ACCESS_READ },
    DesktopEntry: { signature: 's', access: ACCESS_READ },
    CanQuit: { signature: 'b', access: ACCESS_READ },
    CanRaise: { signature: 'b', access: ACCESS_READ },
    CanSetFullscreen: { signature: 'b', access: ACCESS_READ },
    Fullscreen: { signature: 'b', access: ACCESS_READ },
    HasTrackList: { signature: 'b', access: ACCESS_READ },
    SupportedUriSchemes: { signature: 'as', access: ACCESS_READ },
    SupportedMimeTypes: { signature: 'as', access: ACCESS_READ },
  },
  methods: {
    Raise: { inSignature: '', outSignature: '' },
    Quit: { inSignature: '', outSignature: '' },
  },
});

class PlayerInterface extends Interface {
  constructor(service) {
    super(PLAYER_IFACE);
    this.service = service;
  }

  get PlaybackStatus() {
    return playbackStatus(this.service.state);
  }

  get Metadata() {
    return toMetadata(this.service.state);
  }

  get Position() {
    const positionMs = this.service.state ? this.service.state.positionMs : 0;
    return BigInt(Math.round(positionMs * 1000));
  }

  get Rate() {
    return 1.0;
  }

  set Rate(_value) {}

  get MinimumRate() {
    return 1.0;
  }

  get MaximumRate() {
    return 1.0;
  }

  get Volume() {
    return 1.0;
  }

  set Volume(_value) {}

  // A phone session is always controllable while it exists, and never while it
  // does not: a shell greys its buttons out on these.
  get CanControl() {
    return true;
  }

  get CanPlay() {
    return Boolean(this.service.state);
  }

  get CanPause() {
    return Boolean(this.service.state);
  }

  get CanGoNext() {
    return Boolean(this.service.state);
  }

  get CanGoPrevious() {
    return Boolean(this.service.state);
  }

  get CanSeek() {
    return Boolean(this.service.state);
  }

  Play() {
    this.service.command('play');
  }

  Pause() {
    this.service.command('pause');
  }

  PlayPause() {
    this.service.command('toggle');
  }

  Stop() {
    this.service.command('stop');
  }

  Next() {
    this.service.command('next');
  }

  Previous() {
    this.service.command('previous');
  }

  /** MPRIS gives a relative offset in microseconds. */
  Seek(offset) {
    this.service.seekBy(Number(offset));
  }

  /** MPRIS gives an absolute position in microseconds. */
  SetPosition(_trackId, position) {
    this.service.seekTo(Number(position));
  }

  /** The signal the spec requires after a seek. */
  Seeked(position) {
    return [BigInt(position)];
  }
}

PlayerInterface.configureMembers({
  properties: {
    PlaybackStatus: { signature: 's', access: ACCESS_READ },
    Metadata: { signature: 'a{sv}', access: ACCESS_READ },
    Position: { signature: 'x', access: ACCESS_READ },
    Rate: { signature: 'd', access: ACCESS_READ },
    MinimumRate: { signature: 'd', access: ACCESS_READ },
    MaximumRate: { signature: 'd', access: ACCESS_READ },
    Volume: { signature: 'd', access: ACCESS_READ },
    CanControl: { signature: 'b', access: ACCESS_READ },
    CanPlay: { signature: 'b', access: ACCESS_READ },
    CanPause: { signature: 'b', access: ACCESS_READ },
    CanGoNext: { signature: 'b', access: ACCESS_READ },
    CanGoPrevious: { signature: 'b', access: ACCESS_READ },
    CanSeek: { signature: 'b', access: ACCESS_READ },
  },
  methods: {
    Play: { inSignature: '', outSignature: '' },
    Pause: { inSignature: '', outSignature: '' },
    PlayPause: { inSignature: '', outSignature: '' },
    Stop: { inSignature: '', outSignature: '' },
    Next: { inSignature: '', outSignature: '' },
    Previous: { inSignature: '', outSignature: '' },
    Seek: { inSignature: 'x', outSignature: '' },
    SetPosition: { inSignature: 'ox', outSignature: '' },
  },
  signals: {
    Seeked: { signature: 'x' },
  },
});

const CHANGED_PROPERTIES = [
  'PlaybackStatus',
  'Metadata',
  'Position',
  'CanPlay',
  'CanPause',
  'CanGoNext',
  'CanGoPrevious',
  'CanSeek',
];

class MprisService extends EventEmitter {
  constructor() {
    super();
    this.bus = null;
    this.player = null;
    this.state = null;
    this.started = false;
  }

  async start() {
    if (this.started) return;
    try {
      this.bus = dbus.sessionBus();
      this.bus.on('error', (err) => console.error('[mpris] bus error:', err.message));

      const root = new RootInterface(this);
      this.player = new PlayerInterface(this);
      this.bus.export(OBJECT_PATH, root);
      this.bus.export(OBJECT_PATH, this.player);

      const reply = await this.bus.requestName(BUS_NAME, 0);
      // 1 = primary owner, 4 = already owner.
      if (reply !== 1 && reply !== 4) {
        console.log(`[mpris] could not own ${BUS_NAME} (request returned ${reply})`);
        return;
      }
      this.started = true;
      console.log(`[mpris] publishing the phone as ${BUS_NAME}`);
    } catch (e) {
      // A desktop with no session bus simply does not get the player.
      console.error('[mpris] could not publish the player:', e.message);
    }
  }

  /** Takes a state from the phone and tells every listener about it. */
  update(state) {
    this.state = state;
    this.publishChanged();
  }

  /** The phone went away: stop claiming something is playing. */
  clear() {
    this.state = null;
    this.publishChanged();
  }

  stop() {
    try {
      if (this.started && this.bus) this.bus.unexport(OBJECT_PATH);
    } catch (e) {
      console.error('[mpris] could not withdraw the player:', e.message);
    }
    this.started = false;
    this.player = null;
  }

  /** Sends a command to the phone, through the companion channel. */
  command(name) {
    this.emit('command', name, undefined);
  }

  seekTo(positionMicroseconds) {
    if (!this.state) return;
    const positionMs = Math.max(0, Math.round(positionMicroseconds / 1000));
    this.emit('command', 'seek', positionMs);
  }

  seekBy(offsetMicroseconds) {
    if (!this.state) return;
    const target = this.state.positionMs * 1000 + offsetMicroseconds;
    this.seekTo(target);
  }

  publishChanged() {
    if (!this.started || !this.player) return;
    const changed = {};
    for (const name of CHANGED_PROPERTIES) changed[name] = this.player[name];
    try {
      Interface.emitPropertiesChanged(this.player, changed);
    } catch (e) {
      console.error('[mpris] could not announce the change:', e.message);
    }
  }
}

module.exports = {
  MprisService,
  toMetadata,
  playbackStatus,
  BUS_NAME,
  OBJECT_PATH,
};