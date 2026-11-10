const { spawn, execFile } = require('child_process');
const EventEmitter = require('events');

class NotificationService extends EventEmitter {
  constructor(adbPath = 'adb') {
    super();
    this.adbPath = adbPath;
    this.logcatProc = null;
    this.activeDeviceId = null;
    this.history = [];
  }

  start(deviceId) {
    this.stop();
    this.activeDeviceId = deviceId;

    const args = [];
    if (deviceId) args.push('-s', deviceId);
    // Stream notification events from Android logcat
    args.push('logcat', '-v', 'brief', '-s', 'NotificationService:I', 'StatusBar:I', 'OplusNotification:I');

    try {
      this.logcatProc = spawn(this.adbPath, args);

      this.logcatProc.stdout.on('data', (data) => {
        const text = data.toString();
        const lines = text.split('\n');
        for (const line of lines) {
          this.parseLogcatLine(line);
        }
      });

      this.logcatProc.on('error', (err) => {
        console.error('Notification logcat error:', err);
      });
    } catch (e) {
      console.error('Failed to spawn notification logcat:', e);
    }
  }

  parseLogcatLine(line) {
    if (!line || !line.trim()) return;

    // Filter for post/enqueue notifications
    if (line.includes('enqueueNotificationInternal') || line.includes('NotificationRecord') || line.includes('postNotification')) {
      const pkgMatch = line.match(/pkg=([a-zA-Z0-9_.]+)/);
      if (pkgMatch) {
        const pkg = pkgMatch[1];
        // Filter out system internal background chatter
        if (pkg === 'android' || pkg === 'com.android.systemui' || pkg.includes('providers')) return;

        const notif = {
          id: `notif_${Date.now()}_${Math.random().toString(36).substring(2, 6)}`,
          pkg,
          title: this.getReadableAppName(pkg),
          message: 'New incoming notification',
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
      'com.oplus.battery': 'ColorOS Battery'
    };
    return known[pkg] || pkg.split('.').pop() || 'Notification';
  }

  addNotification(notif) {
    this.history.unshift(notif);
    if (this.history.length > 50) this.history.pop();

    this.emit('notification-received', notif);

    // Send native desktop notification via notify-send for KDE Plasma
    this.sendDesktopNotification(notif.title, notif.message);
  }

  sendDesktopNotification(title, message) {
    execFile('notify-send', [
      '-a', 'A+ Connect',
      '-i', 'phone',
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
