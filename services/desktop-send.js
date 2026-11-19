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
const { promisify } = require('util');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { EventEmitter } = require('events');

const execAsync = promisify(exec);

const dbus = require('dbus-next');
const { Interface } = dbus.interface;
const { DesktopNotify } = require('./desktop-notify');

const BUS_NAME = 'com.aplusconnect.APlusConnect';
const OBJECT_PATH = '/com/aplusconnect/APlusConnect';
const INTERFACE = 'com.aplusconnect.APlusConnect';

const DESKTOP_NAME = 'aplus-connect-send.desktop';

/*
 * Dolphin 25+ (KDE Frameworks 6) dropped KonqPopupMenu service menus: the
 * context menu is built only from compiled KFileItemAction plugins, and a
 * servicemenus .desktop file is never indexed. An application entry with every
 * file type is still picked up, though, and Dolphin lists it under "Open With"
 * - which is how a file gets to the phone on those builds without shipping a
 * C++ plugin. NoDisplay keeps it out of the application launcher.
 *
 * Both entries run the same helper, and the helper starts the app when it is
 * not running, so the option keeps working with A+ Connect closed.
 */
const KDE_DIRS = [
  path.join(os.homedir(), '.local/share/kio/servicemenus'),
  path.join(os.homedir(), '.local/share/kservices5/ServiceMenus'),
];

const APPLICATIONS_DIR = path.join(os.homedir(), '.local/share/applications');
const HELPER_DIR = path.join(os.homedir(), '.local/share/aplus-connect');
const HELPER_NAME = 'send-to-phone';
const HELPER_PATH = path.join(HELPER_DIR, HELPER_NAME);

const DESKTOP_CONTENT = `[Desktop Entry]
Type=Service
ServiceTypes=KonqPopupMenu/Plugin
MimeType=all/allfiles;
Actions=sendToPhone;
X-KDE-Priority=TopLevel

[Desktop Action sendToPhone]
Name=Send to phone via A+ Connect
Icon=phone
Exec="${HELPER_PATH}" %F
`;

const APP_CONTENT = `[Desktop Entry]
Type=Application
Name=Send to phone (A+ Connect)
GenericName=Send files to the paired phone
Comment=Send the selected files to your phone with A+ Connect
Icon=phone
Terminal=false
NoDisplay=true
MimeType=all/allfiles;
InitialPreference=100
Exec="${HELPER_PATH}" %F
`;

/**
 * A shell helper both entries run. It sends through the running app, and if
 * the app is not there it starts it first - the menu is installed for good, so
 * "Send to phone" has to work when A+ Connect is closed.
 */
function helperScript(launchCommand) {
  return `#!/bin/sh
# Written by A+ Connect on every launch; safe to delete, it comes back.
# Sends the given files to the paired phone, starting A+ Connect if needed.
BUS=com.aplusconnect.APlusConnect
OBJ=/com/aplusconnect/APlusConnect
IFACE=com.aplusconnect.APlusConnect

up() {
  dbus-send --session --print-reply --dest=org.freedesktop.DBus \\
    /org/freedesktop/DBus org.freedesktop.DBus.NameHasOwner \\
    string:"$BUS" 2>/dev/null | grep -q "boolean true"
}

# The app owns its bus name a moment before the phone has finished pairing.
connected() {
  dbus-send --session --print-reply --dest="$BUS" "$OBJ" \\
    org.freedesktop.DBus.Properties.Get \\
    string:"$IFACE" string:Connected 2>/dev/null | grep -q "boolean true"
}

started=0
if ! up; then
  started=1
  ${launchCommand ? `( ${launchCommand} >/dev/null 2>&1 & )` : ':'}
  i=0
  while [ "$i" -lt 80 ] && ! up; do
    sleep 0.25
    i=$((i + 1))
  done
fi

if ! up; then
  notify-send -a "A+ Connect" "Could not reach A+ Connect" \\
    "Start it once, then try again." 2>/dev/null
  exit 1
fi

# A just-started app still has to pair with the phone; sending before that
# only produces "no phone connected".
if [ "$started" = 1 ]; then
  i=0
  while [ "$i" -lt 60 ] && ! connected; do
    sleep 0.25
    i=$((i + 1))
  done
fi

for f in "$@"; do
  # --print-reply is not optional: without it the message is sent with
  # NO_REPLY_EXPECTED and never reaches the exported object.
  dbus-send --session --print-reply --dest="$BUS" "$OBJ" "$IFACE.SendFile" string:"$f" >/dev/null
done
`;
}

class SendInterface extends Interface {
  constructor(service) {
    super(INTERFACE);
    this.service = service;
  }

  /** Whether a phone is paired and authenticated right now. */
  get Connected() {
    return this.service.companion.isConnected;
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
  properties: {
    Connected: { signature: 'b', access: 'read' },
  },
});

class DesktopSendService extends EventEmitter {
  constructor(companion, { launchCommand = '' } = {}) {
    super();
    this.companion = companion;
    this.launchCommand = launchCommand;
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

      await this.installDesktopMenu();
      console.log(`[desktop-send] publishing ${BUS_NAME}`);
    } catch (e) {
      // A desktop with no session bus simply does not get the send menu.
      console.error('[desktop-send] could not publish D-Bus service:', e.message);
    }
  }

  /** Installs the Dolphin service menu into both KDE menu locations. */
  async installDesktopMenu() {
    // The helper first: both entries point at it, and it is what starts the app
    // when the menu is used with A+ Connect closed.
    try {
      fs.mkdirSync(HELPER_DIR, { recursive: true });
      fs.writeFileSync(HELPER_PATH, helperScript(this.launchCommand), { mode: 0o755 });
      fs.chmodSync(HELPER_PATH, 0o755);
    } catch (e) {
      console.error('[desktop-send] could not install the send helper:', e.message);
    }

    for (const dir of KDE_DIRS) {
      try {
        fs.mkdirSync(dir, { recursive: true });
        fs.writeFileSync(path.join(dir, DESKTOP_NAME), DESKTOP_CONTENT);
      } catch (e) {
        console.error(`[desktop-send] could not install ${dir}:`, e.message);
      }
    }

    // KDE Frameworks 6 ignores the service menu above, so also register the
    // same command as an application that claims every file type. Dolphin
    // then offers it as "Open With".
    try {
      fs.mkdirSync(APPLICATIONS_DIR, { recursive: true });
      fs.writeFileSync(path.join(APPLICATIONS_DIR, DESKTOP_NAME), APP_CONTENT);
    } catch (e) {
      console.error(`[desktop-send] could not install the Open With entry:`, e.message);
    }

    // Rebuild the KService cache so Dolphin discovers the new .desktop entry.
    // kbuildsycoca6 (Plasma 6) first, kbuildsycoca5 (Plasma 5) fallback.
    // Don't swallow the exit code/stderr: if sycoca fails the operator needs to know.
    let lastErr = null;
    for (const cmd of ['kbuildsycoca6', 'kbuildsycoca5']) {
      try {
        // promisified exec resolves only on a zero exit, and its result has no
        // `code` field; a failure arrives as a rejection.
        await execAsync(`${cmd} 2>&1`);
        console.log(`[desktop-send] sycoca rebuilt via ${cmd}`);
        return;
      } catch (e) {
        lastErr = e;
      }
    }
    console.error(`[desktop-send] could not rebuild sycoca cache: ${lastErr && lastErr.message}`);
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
