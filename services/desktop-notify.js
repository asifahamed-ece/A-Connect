'use strict';

/**
 * The desktop's own notification drawer, spoken to directly.
 *
 * `notify-send` is enough to pop a bubble and nothing more: it hands back no
 * id, so a notification that updates (a hotspot counter, a download's
 * progress) has no way to say "this is the same one" and the drawer fills with
 * copies. It also offers no way to take a bubble back.
 *
 * The daemon's `org.freedesktop.Notifications` interface has both. Posting with
 * a `replaces_id` updates the bubble that is already there, and
 * `CloseNotification` releases it - which is what lets a notification the
 * phone cleared leave the drawer too.
 *
 * Everything here is best-effort. A desktop with no notification daemon still
 * gets popups through `notify-send`; it just cannot replace or close them.
 */

const { execFile } = require('child_process');
const { EventEmitter } = require('events');

const dbus = require('dbus-next');

const BUS_NAME = 'org.freedesktop.Notifications';
const OBJECT_PATH = '/org/freedesktop/Notifications';
const INTERFACE = 'org.freedesktop.Notifications';

/** The plain popup, for a desktop with no daemon on the session bus. */
function notifySend(args) {
  execFile('notify-send', args, () => {
    // notify-send is optional, and a popup that fails is not worth a log line.
  });
}

class DesktopNotify extends EventEmitter {
  constructor(options = {}) {
    super();
    this.appName = options.appName || 'A+ Connect';
    this.icon = options.icon || 'phone';
    // -1 is the daemon's own default lifetime; the drawer decides.
    this.timeout = options.timeout ?? -1;
    this.daemon = options.daemon || null;
    this.busFactory = options.busFactory || (() => dbus.sessionBus());
    this.notifySend = options.notifySend || notifySend;
    /** Our notification key -> the daemon's id, so a repeat can replace it. */
    this.ids = new Map();
    this.starting = null;
    this.bound = false;
  }

  /**
   * Connects to the daemon, once.
   *
   * Returns whether a daemon is available. It never rejects: a caller posting
   * a notification has already decided the popup is worth attempting.
   */
  async start() {
    if (this.daemon) {
      this.bindClosed();
      return true;
    }
    if (!this.starting) this.starting = this.connect();
    return this.starting;
  }

  async connect() {
    try {
      const bus = this.busFactory();
      bus.on?.('error', (err) => console.error(`[desktop-notify] bus error: ${err.message}`));
      const proxy = await bus.getProxyObject(BUS_NAME, OBJECT_PATH);
      this.daemon = proxy.getInterface(INTERFACE);
      this.bindClosed();
      return true;
    } catch (e) {
      console.error(`[desktop-notify] no notification daemon: ${e.message}`);
      return false;
    }
  }

  /**
   * Follows the daemon's own closings.
   *
   * A bubble the user swiped away is gone, so the key must be forgotten; the
   * next update then starts a fresh bubble instead of replacing a dead id.
   * Nothing is sent back to the phone - this link is read-only.
   */
  bindClosed() {
    if (this.bound || !this.daemon) return;
    this.bound = true;
    try {
      this.daemon.on('NotificationClosed', (id, reason) => {
        for (const [key, value] of this.ids) {
          if (value !== id) continue;
          this.ids.delete(key);
          this.emit('closed', { key, id, reason });
          return;
        }
      });
    } catch (e) {
      console.error(`[desktop-notify] could not watch for closings: ${e.message}`);
    }
  }

  /**
   * Posts or updates one notification, keyed by our own stable key.
   *
   * The key is what makes the second post a replace rather than a copy.
   */
  async notify(key, { summary = '', body = '', icon } = {}) {
    const started = await this.start();
    if (!started || !this.daemon) {
      this.fallbackNotify({ summary, body });
      return null;
    }

    const replacesId = this.ids.get(key) || 0;
    try {
      const id = await this.daemon.Notify(
        this.appName,
        replacesId,
        icon || this.icon,
        summary || '',
        body || '',
        [],
        {},
        this.timeout
      );
      if (typeof id === 'number' && id > 0) this.ids.set(key, id);
      return typeof id === 'number' ? id : null;
    } catch (e) {
      console.error(`[desktop-notify] could not post a notification: ${e.message}`);
      this.fallbackNotify({ summary, body });
      return null;
    }
  }

  /** Releases the bubble for a key, if we posted one. */
  async close(key) {
    const id = this.ids.get(key);
    if (!id) return false;
    this.ids.delete(key);
    if (!this.daemon) return false;
    try {
      await this.daemon.CloseNotification(id);
      return true;
    } catch (e) {
      console.error(`[desktop-notify] could not close a notification: ${e.message}`);
      return false;
    }
  }

  fallbackNotify({ summary, body }) {
    const args = ['-a', this.appName, '-i', this.icon, '-u', 'normal'];
    if (summary) args.push(summary);
    if (body) args.push(body);
    try {
      this.notifySend(args);
    } catch (e) {
      console.error(`[desktop-notify] could not fall back to notify-send: ${e.message}`);
    }
  }
}

module.exports = { DesktopNotify };
