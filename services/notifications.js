const { spawn, execFile } = require('child_process');
const EventEmitter = require('events');

class NotificationService extends EventEmitter {
  constructor(adbPath = 'adb') {
    super();
    this.adbPath = adbPath;
    this.logcatProc = null;
    this.activeDeviceId = null;
    this.history = [];
    this.lastSeenTimestamp = Date.now();
  }

  start(deviceId) {
    this.stop();
    this.activeDeviceId = deviceId;
    this.lastSeenTimestamp = Date.now();

    const args = [];
    if (deviceId) args.push('-s', deviceId);
    // Clear old logs and stream new notifications only
    args.push('logcat', '-c');

    // Wait a moment then start fresh stream
    setTimeout(() => {
      this.startLogcatStream(deviceId);
    }, 500);
  }

  startLogcatStream(deviceId) {
    const args = [];
    if (deviceId) args.push('-s', deviceId);
    // Stream notification events with broader filters
    // ActivityManager shows app launches, NotificationManager shows posted notifications
    args.push('logcat', '-v', 'time', '-s',
      'NotificationService:*',
      'NotificationManager:*',
      'StatusBar:*',
      'NotificationListenerService:*',
      'ActivityManager:I'
    );

    try {
      this.logcatProc = spawn(this.adbPath, args);

      this.logcatProc.stdout.on('data', (data) => {
        const text = data.toString();
        const lines = text.split('\n');
        for (const line of lines) {
          this.parseLogcatLine(line);
        }
      });

      this.logcatProc.stderr.on('data', (data) => {
        console.error('Notification logcat stderr:', data.toString());
      });

      this.logcatProc.on('error', (err) => {
        console.error('Notification logcat error:', err);
      });

      this.logcatProc.on('close', (code) => {
        if (code !== 0 && this.activeDeviceId) {
          console.log('Notification logcat exited, restarting in 5s...');
          setTimeout(() => {
            if (this.activeDeviceId) this.startLogcatStream(this.activeDeviceId);
          }, 5000);
        }
      });
    } catch (e) {
      console.error('Failed to spawn notification logcat:', e);
    }
  }

  parseLogcatLine(line) {
    if (!line || !line.trim()) return;

    // Parse timestamp from logcat -v time format: "11-11 10:29:31.234 I/NotificationService..."
    const timeMatch = line.match(/^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+)/);
    if (!timeMatch) return;

    // Filter for notification post events
    if (line.includes('enqueueNotificationInternal') ||
        line.includes('postNotification') ||
        line.includes('onNotificationPosted') ||
        (line.includes('START u0') && line.includes('act='))) {

      // Extract package name from various log formats
      let pkg = null;

      // Format 1: "pkg=com.example.app"
      const pkgMatch1 = line.match(/pkg=([a-zA-Z0-9_.]+)/);
      if (pkgMatch1) pkg = pkgMatch1[1];

      // Format 2: "cmp=com.example.app/.Activity"
      if (!pkg) {
        const cmpMatch = line.match(/cmp=([a-zA-Z0-9_.]+)\//);
        if (cmpMatch) pkg = cmpMatch[1];
      }

      // Format 3: Activity start "u0 {act=... cmp=...}"
      if (!pkg) {
        const actMatch = line.match(/u0.*cmp=([a-zA-Z0-9_.]+)\//);
        if (actMatch) pkg = actMatch[1];
      }

      if (pkg) {
        // Filter out system internal background chatter
        if (pkg === 'android' ||
            pkg === 'com.android.systemui' ||
            pkg.includes('providers') ||
            pkg.includes('launcher') ||
            pkg === 'com.android.settings') return;

        const notif = {
          id: `notif_${Date.now()}_${Math.random().toString(36).substring(2, 6)}`,
          pkg,
          title: this.getReadableAppName(pkg),
          message: 'New notification',
          time: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
        };

        this.addNotification(notif);
      }
    }
  }

  getReadableAppName(pkg) {
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
      'com.coloros.note': 'OPPO Notes',
      'com.oplus.battery': 'ColorOS Battery',
      'com.facebook.katana': 'Facebook',
      'com.twitter.android': 'Twitter',
      'com.google.android.youtube': 'YouTube',
      'com.snapchat.android': 'Snapchat'
    };
    return known[pkg] || pkg.split('.').pop().charAt(0).toUpperCase() + pkg.split('.').pop().slice(1) || 'Notification';
  }

  addNotification(notif) {
    // Deduplicate: ignore if same app notified within last 3 seconds
    const recent = this.history.find(n =>
      n.pkg === notif.pkg &&
      (Date.now() - this.parseNotifTime(n.time)) < 3000
    );
    if (recent) return;

    this.history.unshift(notif);
    if (this.history.length > 50) this.history.pop();

    this.emit('notification-received', notif);

    // Send native desktop notification via notify-send for KDE Plasma
    this.sendDesktopNotification(notif.title, notif.message);
  }

  parseNotifTime(timeStr) {
    // Parse "HH:MM" format to ms since midnight today
    const [h, m] = timeStr.split(':').map(Number);
    const now = new Date();
    return new Date(now.getFullYear(), now.getMonth(), now.getDate(), h, m).getTime();
  }

  sendDesktopNotification(title, message) {
    execFile('notify-send', [
      '-a', 'A+ Connect',
      '-i', 'phone',
      '-u', 'normal',
      title,
      message
    ], (err) => {
      // notify-send is optional; if missing, silently continue
    });
  }

  stop() {
    if (this.logcatProc) {
      this.logcatProc.kill('SIGTERM');
      this.logcatProc = null;
    }
    this.activeDeviceId = null;
  }

  clearHistory() {
    this.history = [];
    this.emit('history-cleared');
  }
}

module.exports = NotificationService;
