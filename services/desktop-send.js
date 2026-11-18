'use strict';

/**
 * Desktop send entry point.
 *
 * Exposes `com.aplusconnect.APlusConnect.SendFile(string path)` on the session
 * bus, so a KDE Dolphin service menu (or any caller running `dbus-send`) can
 * hand a file path to the running A+ Connect instance, which streams it to the
 * connected phone over the companion channel - the same path the app's own
 * "Send files…" uses.
 *
 * The service menu `.desktop` file is self-installed on first launch into both
 * KDE Plasma 6 (`~/.local/share/kio/servicemenus`) and Plasma 5
 * (`~/.local/share/kservices5/ServiceMenus`), then the icon cache is rebuilt.
 * Nothing the user installs by hand: the AppImage is plug-and-play.
 */

const { exec } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { EventEmitter } = require('events');

const dbus = require('dbus-next');
const { Interface } = dbus.interface;
const { DesktopNotify } = require('./desktop-notify');

const BUS_NAME = 'com.aplusconnect.APlusConnect';
const OBJECT_PATH = '/com/aplusconnect/APlusConnect';
const INTERFACE = 'com.aplusconnect.APlusConnect';

const DESKTOP_NAME = 'aplus-connect-send.desktop';

const DESKTOP_CONTENT = `[Desktop Entry]
Type=Service
ServiceTypes=KonqPopupMenu/Plugin
MimeType=all/allfiles;
Actions=sendToPhone;
X-KDE-Priority=TopLevel

[Desktop Action sendToPhone]
Name=Send to phone via A+ Connect
Icon=phone
Exec=sh -c 'for f in "$@"; do dbus-send --session --dest=com.aplusconnect.APlusConnect /com/aplusconnect/APlusConnect com.aplusconnect.APlusConnect.SendFile string:"$f"; done' _ %F
`;

const KDE_DIRS = [
  path.join(os.homedir(), '.local/share/kio/servicemenus'),
  path.join(os.homedir(), '.local/share/kservices5/ServiceMenus'),
];

class SendInterface extends Interface {
  constructor(service) {
    super(INTERFACE);
    this.service = service;
  }

  async SendFile(filePath) {
    if (typeof filePath !== 'string' || !filePath) return;
    try {
      const id = this.service.companion.sendFile(filePath);
      if (!id) {
        // No authenticated phone is connected: tell the user instead of failing
        // silently when they right-clicked a file in Dolphin.
        await this.service.notifyNoPhone();
      }
    } catch (e) {
      console.error(`[desktop-send] could not send ${filePath}:`, e.message);
    }
  }
}

SendInterface.configureMembers({
  methods: {
    SendFile: { inSignature: 's', outSignature: '' },
  },
});

class DesktopSendService extends EventEmitter {
  constructor(companion) {
    super();
    this.companion = companion;
    this.bus = null;
    this.iface = null;
    this.notifier = null;
    this.started = false;
  }

  async start() {
    if (this.started) return;
    this.started = true;

    try {
      this.bus = dbus.sessionBus();
      this.bus.on('error', (err) => console.error('[desktop-send] bus error:', err.message));

      this.iface = new SendInterface(this);
      this.bus.export(OBJECT_PATH, this.iface);

      const reply = await this.bus.requestName(BUS_NAME, 0);
      // 1 = primary owner, 4 = already owner.
      if (reply !== 1 && reply !== 4) {
        console.log(`[desktop-send] could not own ${BUS_NAME} (request returned ${reply})`);
        return;
      }

      this.installDesktopMenu();
      console.log(`[desktop-send] publishing ${BUS_NAME}`);
    } catch (e) {
      // A desktop with no session bus simply does not get the send menu.
      console.error('[desktop-send] could not publish D-Bus service:', e.message);
    }
  }

  /** Installs the Dolphin service menu into both KDE menu locations. */
  installDesktopMenu() {
    for (const dir of KDE_DIRS) {
      try {
        fs.mkdirSync(dir, { recursive: true });
        fs.writeFileSync(path.join(dir, DESKTOP_NAME), DESKTOP_CONTENT);
      } catch (e) {
        console.error(`[desktop-send] could not install ${dir}:`, e.message);
      }
    }
    exec('kbuildsycoca6 2>/dev/null || kbuildsycoca5 2>/dev/null || true', () => {});
  }

  /** Best-effort desktop notification when no phone is connected to receive. */
  async notifyNoPhone() {
    if (!this.notifier) this.notifier = new DesktopNotify();
    try {
      await this.notifier.notify('desktop-send-no-phone', {
        summary: 'A+ Connect',
        body: 'Cannot send file: No phone connected',
      });
    } catch (e) {
      // Desktop notifications are best-effort.
    }
  }

  stop() {
    try {
      if (this.started && this.bus) this.bus.unexport(OBJECT_PATH);
    } catch (e) {
      console.error('[desktop-send] could not withdraw service:', e.message);
    }
    this.started = false;
  }
}

module.exports = { DesktopSendService, BUS_NAME, OBJECT_PATH, INTERFACE };
