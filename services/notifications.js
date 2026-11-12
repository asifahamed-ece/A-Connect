const { execFile, spawn } = require('child_process');
const { promisify } = require('util');
const EventEmitter = require('events');

const execFileAsync = promisify(execFile);

// Packages whose notifications are noise in a mirroring UI.
const IGNORED_PACKAGES = new Set([
  'android',
  'com.android.systemui',
  'com.android.settings',
  'com.android.providers.downloads',
]);

// Our own "file received" posts.
const IGNORED_TAGS = new Set(['aplus_connect']);

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
      if (IGNORED_PACKAGES.has(pkg)) continue;
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
      });
    }

    return notifications;
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
    // Newest first; keep the same notification from stacking.
    const duplicate = this.history.find((n) => n.key === notif.key);
    if (duplicate) return;

    this.history.unshift(notif);
    if (this.history.length > 50) this.history.pop();

    this.emit('notification-received', notif);
    this.sendDesktopNotification(notif);
  }

  sendDesktopNotification(notif) {
    const args = ['-a', 'A+ Connect', '-i', 'phone', '-u', 'normal'];
    if (notif.title) args.push(notif.title);
    if (notif.message) args.push(notif.message);

    execFile('notify-send', args, () => {
      // notify-send is optional.
    });
  }

  clearHistory() {
    this.history = [];
    this.emit('history-cleared');
  }

  /** Remove one notification from the visible history. */
  dismiss(id) {
    const before = this.history.length;
    this.history = this.history.filter((n) => n.id !== id);
    if (this.history.length === before) return false;
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

  /** Removes one, after the phone said it was gone. */
  removeById(id) {
    return this.dismiss(id);
  }
}

module.exports = NotificationService;
