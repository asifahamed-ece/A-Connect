const { execFile, spawn } = require('child_process');
const { promisify } = require('util');
const EventEmitter = require('events');

const execFileAsync = promisify(execFile);

// Our own "file received" posts. This is loop prevention, not package
// filtering: every notification the phone shows is mirrored, the system's own
// included, so nothing the user can see on the device goes missing here. Cards
// that would be noisy - a hotspot counter, a charging battery - are kept out of
// the desktop's popups by the ongoing rule instead, and still recorded.
const IGNORED_TAGS = new Set(['aplus_connect']);

/**
 * The key one notification's desktop bubble is tracked under.
 *
 * The ADB path builds a key that carries the notification's timestamp, so the
 * same notification re-posted is a different key there. `baseKey` is the part
 * that stays put, and that is what a bubble has to be keyed by for an update to
 * replace the bubble instead of adding another.
 */
function desktopKey(notif) {
  return String(notif.baseKey || notif.key || notif.id || '');
}

/**
 * A+ Connect - Notification mirroring
 *
 * Android 16 no longer writes notification contents to logcat, so the old
 * `adb logcat` filter matched nothing and the page stayed empty. The Notification
 * Manager can be dumped instead: `dumpsys notification --noredact` exposes the
 * real title/text of every active notification to the shell user.
 *
 * It is a snapshot, not an event stream, so we poll, diff against what we have
 * already seen, and emit only genuinely new notifications.
 */
class NotificationService extends EventEmitter {
  constructor(options = {}) {
    super();
    this.adbPath = options.adbPath ?? 'adb';
    this.pollInterval = options.pollInterval ?? 2000;
    this.history = [];
    this.activeDeviceId = null;
    this.pollTimer = null;
    this.polling = false;
    this.seen = new Map(); // notification key -> when we first saw it
    this.lastEmitted = new Map(); // pkg|id|tag -> last emit time (anti-spam)
    this.baselineDone = false;

    /**
     * The desktop's notification drawer.
     *
     * Created on first use so a check can inject a stand-in, and so the
     * session bus is only touched when there is something to show.
     */
    this.desktopNotify = options.desktopNotify || null;
  }

  /** The drawer client, connected on first use. */
  desktopNotifier() {
    if (!this.desktopNotify) {
      const { DesktopNotify } = require('./desktop-notify');
      this.desktopNotify = new DesktopNotify();
    }
    return this.desktopNotify;
  }

  start(deviceId) {
    this.stop();
    this.activeDeviceId = deviceId;
    this.seen.clear();
    this.baselineDone = false;
    this.history = [];

    // Snapshot immediately so pre-existing notifications are treated as a
    // baseline instead of flooding the page on connect.
    this.poll();
    this.pollTimer = setInterval(() => this.poll(), this.pollInterval);
  }

  stop() {
    if (this.pollTimer) {
      clearInterval(this.pollTimer);
      this.pollTimer = null;
    }
    this.activeDeviceId = null;
  }

  async poll() {
    if (!this.activeDeviceId || this.polling) return;
    this.polling = true;

    try {
      const args = ['-s', this.activeDeviceId, 'shell', 'dumpsys', 'notification', '--noredact'];
      const { stdout } = await execFileAsync(this.adbPath, args, {
        timeout: 8000,
        maxBuffer: 32 * 1024 * 1024,
      });

      const found = this.parse(stdout);

      if (!this.baselineDone) {
        for (const notif of found) this.seen.set(notif.key, Date.now());
        this.baselineDone = true;
        this.pruneSeen();
        return;
      }

      for (const notif of found) {
        if (this.seen.has(notif.key)) continue;
        this.seen.set(notif.key, Date.now());

        // A notification can be reposted under the same tag/id with new content.
        // Treat that as new, but not more than once every 2s per slot.
        const last = this.lastEmitted.get(notif.baseKey) ?? 0;
        if (Date.now() - last < 2000) continue;
        this.lastEmitted.set(notif.baseKey, Date.now());

        this.addNotification(notif);
      }

      this.pruneSeen();
    } catch (e) {
      if (this.activeDeviceId) console.error('Notification poll failed:', e.message);
    } finally {
      this.polling = false;
    }
  }

  // Parse the NotificationRecord blocks out of a dumpsys snapshot.
  parse(dump) {
    const notifications = [];
    const blocks = dump.split(/NotificationRecord\(/).slice(1);

    for (const block of blocks) {
      const header = block.split('\n')[0];

      const pkg = header.match(/\bpkg=([\w.]+)/)?.[1];
      const idMatch = header.match(/\bid=(-?\d+)/);
      const tag = header.match(/\btag=(\S+)/)?.[1];

      if (!pkg || !idMatch) continue;
      if (tag && IGNORED_TAGS.has(tag)) continue;

      // Group summaries duplicate their children; skip them.
      if (/^flags=.*GROUP_SUMMARY/m.test(block)) continue;
      if (/^\s*flags=.*GROUP_SUMMARY/m.test(block)) continue;

      const key = `${pkg}|${idMatch[1]}|${tag ?? 'null'}`;

      const title =
        this.extractString(block, 'android\\.title') ??
        this.extractString(block, 'tickerText') ??
        this.readableAppName(pkg);

      const message =
        this.extractString(block, 'android\\.text') ??
        this.extractString(block, 'android\\.summaryText') ??
        this.firstTextLine(block) ??
        this.extractString(block, 'tickerText') ??
        '';

      const when = Number(block.match(/\bwhen=(\d+)/)?.[1] ?? 0);

      notifications.push({
        key: `${key}|${when}`,
        baseKey: key,
        id: `notif_${pkg}_${idMatch[1]}_${when || Date.now()}`,
        pkg,
        app: this.readableAppName(pkg),
        title: this.clean(title),
        message: this.clean(message),
        time: when ? new Date(when).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : '',
        timestamp: when || Date.now(),
        ongoing: this.isOngoing(block),
      });
    }

    return notifications;
  }

  /**
   * Whether a dumped notification is one the phone keeps up.
   *
   * An ongoing card - a hotspot counter, a running download - is re-posted
   * every time it changes, so it is shown in the app's list and kept out of
   * the desktop's popups. The dump carries the flag either as a name or as the
   * raw bit, so both spellings are accepted; anything unrecognised is treated
   * as an ordinary notification.
   */
  isOngoing(block) {
    if (/\bONGOING_EVENT\b/.test(block)) return true;
    const flags = block.match(/flags=(0x[0-9a-fA-F]+)/)?.[1];
    if (!flags) return false;
    // Notification.FLAG_ONGOING_EVENT
    return (parseInt(flags, 16) & 0x00000002) !== 0;
  }

  extractString(block, fieldPattern) {
    // Extras print as: android.title=String (Some title)
    const re = new RegExp(`${fieldPattern}=(?:String|CharSequence) \\((.*)\\)\\s*$`, 'm');
    const match = block.match(re);
    return match ? match[1] : null;
  }

  firstTextLine(block) {
    // android.textLines=CharSequence[] (n) followed by indented [0] ... lines
    const match = block.match(/android\.textLines=CharSequence\[\] \(\d+\)\n((?:\s*\[\d+\].*\n?)+)/);
    if (!match) return null;
    const first = match[1].match(/\[\d+\]\s(.*)/);
    return first ? first[1].trim() : null;
  }

  clean(text) {
    if (typeof text !== 'string') return '';
    return text.replace(/\\n/g, ' ').trim().slice(0, 400);
  }

  readableAppName(pkg) {
    const known = {
      'com.whatsapp': 'WhatsApp',
      'org.telegram.messenger': 'Telegram',
      'com.google.android.apps.messaging': 'Messages',
      'com.android.mms': 'Messages',
      'com.google.android.gm': 'Gmail',
      'com.instagram.android': 'Instagram',
      'com.discord': 'Discord',
      'com.slack': 'Slack',
      'com.spotify.music': 'Spotify',
      'com.coloros.note': 'Notes',
      'com.facebook.katana': 'Facebook',
      'com.twitter.android': 'X',
      'com.google.android.youtube': 'YouTube',
      'com.snapchat.android': 'Snapchat',
      'com.linkedin.android': 'LinkedIn',
      'com.microsoft.teams': 'Teams',
      'com.google.android.dialer': 'Phone',
    };
    if (known[pkg]) return known[pkg];
    const last = pkg.split('.').pop() ?? pkg;
    return last.charAt(0).toUpperCase() + last.slice(1);
  }

  // Keep the dedupe table bounded; notifications can be dismissed and reposted.
  pruneSeen() {
    const cutoff = Date.now() - 30 * 60 * 1000;
    for (const [key, at] of this.seen) {
      if (at < cutoff) this.seen.delete(key);
    }
    while (this.seen.size > 500) {
      const oldest = this.seen.keys().next().value;
      this.seen.delete(oldest);
    }
    for (const [key, at] of this.lastEmitted) {
      if (at < cutoff) this.lastEmitted.delete(key);
    }
  }

  addNotification(notif) {
    const textOf = (n) => n.text ?? n.message ?? '';
    const index = this.history.findIndex((n) => n.key === notif.key);

    if (index !== -1) {
      const previous = this.history[index];
      // The same notification posted again with the same words is not news:
      // no second row and no second popup. This is what stops a mirrored
      // message from appearing twice when the phone re-sends what it queued.
      if (previous.title === notif.title && textOf(previous) === textOf(notif)) return;
      // New words in the same notification (a chat that keeps its id) replace
      // its row instead of stacking, or the desktop keeps showing the first
      // message of the conversation for ever.
      this.history.splice(index, 1);
    }

    this.history.unshift(notif);
    if (this.history.length > 50) this.history.pop();

    this.emit('notification-received', notif);

    // An update to a notification already on screen replaces its bubble, which
    // is why the popup is no longer throttled: a second guard would only make
    // the drawer show stale words for a moment, and there is nothing left to
    // stack.
    this.sendDesktopNotification(notif);
  }

  /**
   * Shows one notification in the desktop's own drawer.
   *
   * An ongoing card - a hotspot counter, a running download, a media card - is
   * not news on every tick. The phone re-posts it each time something changes,
   * and a popup per tick buries every real message: so it never pops. It is
   * still recorded and still updated in the app's own list, which is where a
   * persistent card belongs.
   */
  sendDesktopNotification(notif) {
    if (notif.ongoing) return;

    const key = desktopKey(notif);
    const notifier = this.desktopNotifier();
    // Fire and forget: a popup that fails must never hold up the history.
    Promise.resolve(
      notifier.notify(key, {
        summary: notif.title || notif.app || 'Phone',
        body: notif.message || notif.text || '',
      })
    ).catch(() => {});
  }

  /** Releases a bubble if one was posted for this entry. */
  releaseBubble(entry) {
    // An ongoing card never popped, so it has no bubble to release.
    if (!entry || entry.ongoing) return;
    const notifier = this.desktopNotify;
    if (!notifier) return;
    try {
      Promise.resolve(notifier.close(desktopKey(entry))).catch(() => {});
    } catch (e) {
      // A close that cannot be attempted is not worth reporting.
    }
  }

  /**
   * Releases the drawer bubble for a notification, keeping its history row.
   *
   * This is what a notification the phone cleared does. The drawer mirrors
   * what is still live on the phone; the app keeps its own record of what
   * arrived, and clearing that is the user's to do.
   */
  closeDesktop(id) {
    const entry = this.history.find((n) => n.id === id);
    if (!entry) return false;
    this.releaseBubble(entry);
    return true;
  }

  clearHistory() {
    this.history = [];
    this.emit('history-cleared');
  }

  /** Remove one notification from the visible history. */
  dismiss(id) {
    const index = this.history.findIndex((n) => n.id === id);
    if (index === -1) return false;
    const [entry] = this.history.splice(index, 1);
    // The user tidied the row here, so its bubble goes with it. This stays
    // local: the phone's own notification is left alone.
    this.releaseBubble(entry);
    this.emit('history-dismissed', { id });
    return true;
  }

  /**
   * Adds a notification that arrived over the companion channel.
   *
   * It is shaped like an ADB one on purpose, so the history, the desktop
   * notification and the renderer need no special case for it.
   */
  addFromCompanion(notif) {
    this.addNotification(notif);
  }
}

module.exports = NotificationService;
