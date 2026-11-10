const { execFile } = require('child_process');
const { clipboard } = require('electron');
const EventEmitter = require('events');

class ClipboardSyncService extends EventEmitter {
  constructor(adbPath = 'adb') {
    super();
    this.adbPath = adbPath;
    this.lastDesktopText = '';
    this.lastPhoneText = '';
    this.pollTimer = null;
    this.isEnabled = true;
    this.activeDeviceId = null;
  }

  start(deviceId, intervalMs = 2000) {
    this.activeDeviceId = deviceId;
    if (this.pollTimer) clearInterval(this.pollTimer);

    this.lastDesktopText = clipboard.readText();

    this.pollTimer = setInterval(async () => {
      if (!this.isEnabled || !this.activeDeviceId) return;

      try {
        const currentDesktop = clipboard.readText();
        if (currentDesktop && currentDesktop !== this.lastDesktopText && currentDesktop !== this.lastPhoneText) {
          this.lastDesktopText = currentDesktop;
          await this.sendToPhone(this.activeDeviceId, currentDesktop);
        }
      } catch (err) {
        // Silent catch for background polling
      }
    }, intervalMs);
  }

  stop() {
    if (this.pollTimer) {
      clearInterval(this.pollTimer);
      this.pollTimer = null;
    }
    this.activeDeviceId = null;
  }

  async sendToPhone(deviceId, text) {
    if (!text || text.length > 50000) return; // Ignore empty or excessively large payloads

    // Escape text for Android shell input
    const base64Text = Buffer.from(text).toString('base64');
    const script = `echo '${base64Text}' | base64 -d | am broadcast -a clipper.set -e text "$(cat)" 2>/dev/null || true`;

    const args = [];
    if (deviceId) args.push('-s', deviceId);
    args.push('shell', script);

    return new Promise((resolve) => {
      execFile(this.adbPath, args, { timeout: 3000 }, (err) => {
        if (!err) {
          this.emit('synced', { direction: 'desktop-to-phone', length: text.length });
        }
        resolve(!err);
      });
    });
  }

  setEnabled(val) {
    this.isEnabled = Boolean(val);
  }
}

module.exports = ClipboardSyncService;
