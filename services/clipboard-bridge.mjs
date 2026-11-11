/**
 * A+ Connect - Clipboard Bridge (transport layer)
 *
 * Runs as a forked child process. Speaks the scrcpy control protocol to the
 * phone over ADB so the device clipboard can be read and written without
 * installing anything on the phone.
 *
 * Why scrcpy: the scrcpy server runs as the `shell` user, and Android's
 * ClipboardService explicitly exempts `shell` from the Android 10 background
 * clipboard-read restriction. Normal apps cannot do this, which is why KDE
 * Connect on the same phone still needs a manual "send clipboard" tap.
 *
 * Protocol (parent <-> child, via child_process IPC):
 *   parent -> child: { cmd: 'connect', serial }
 *                     { cmd: 'disconnect' }
 *                     { cmd: 'set', text }
 *                     { cmd: 'quit' }
 *   child  -> parent: { evt: 'ready', status }
 *                     { evt: 'clipboard', text }
 *                     { evt: 'error', message }
 *                     { evt: 'closed', reason }
 */

import { createReadStream, existsSync } from "node:fs";
import { Readable } from "node:stream";
import { AdbServerNodeJsClient } from "@yume-chan/adb-server-node-tcp";
import { AdbScrcpyClient, AdbScrcpyOptions5_0_1 } from "@yume-chan/adb-scrcpy";

// Where distributions install the scrcpy server binary.
const SERVER_CANDIDATES = [
  "/usr/share/scrcpy/scrcpy-server",
  "/usr/local/share/scrcpy/scrcpy-server",
  "/usr/lib/scrcpy/scrcpy-server",
  "/opt/scrcpy/scrcpy-server",
];

// Where we stage it on the device.
const REMOTE_SERVER_PATH = "/data/local/tmp/scrcpy-server.jar";

let server = null; // AdbServerNodeJsClient
let scrcpy = null; // AdbScrcpyClient
let currentSerial = null;
let connecting = false;

function send(message) {
  if (process.send) {
    try {
      process.send(message);
    } catch {
      // Parent went away; the exit handler takes care of cleanup.
    }
  }
}

function fail(message) {
  send({ evt: "error", message: String(message?.message ?? message) });
}

function resolveServerPath() {
  const found = SERVER_CANDIDATES.find((p) => existsSync(p));
  if (!found) {
    throw new Error(
      `scrcpy server binary not found. Install scrcpy (Arch: 'pacman -S scrcpy'). Looked in: ${SERVER_CANDIDATES.join(", ")}`,
    );
  }
  return found;
}

async function disconnect() {
  const client = scrcpy;
  scrcpy = null;
  currentSerial = null;

  if (client) {
    try {
      await client.exited.catch(() => {});
    } catch {
      // Server already gone.
    }
  }
}

async function connect(serial) {
  if (connecting) return;
  connecting = true;

  try {
    await disconnect();

    server = new AdbServerNodeJsClient();
    const devices = await server.getDevices();
    const device = devices.find((d) => d.serial === serial);

    if (!device) throw new Error(`device ${serial} is not connected to the ADB server`);
    if (device.state !== "device") throw new Error(`device ${serial} is ${device.state}`);

    const adb = await server.createAdb(device);

    const localServer = resolveServerPath();
    await AdbScrcpyClient.pushServer(
      adb,
      Readable.toWeb(createReadStream(localServer)),
      REMOTE_SERVER_PATH,
    );

    const options = new AdbScrcpyOptions5_0_1({
      video: false,
      audio: false,
      control: true,
      clipboardAutosync: true,
      tunnelForward: true,
      // With video disabled the scrcpy 5.x server puts its device-meta payload
      // on the control socket, which this client cannot parse ("Unknown device
      // message id 67"). Turning it off keeps the control socket clean.
      sendDeviceMeta: false,
    });

    scrcpy = await AdbScrcpyClient.start(adb, REMOTE_SERVER_PATH, options);
    currentSerial = serial;

    const stream = scrcpy.clipboard;
    if (!stream) throw new Error("clipboard stream unavailable");

    (async () => {
      const reader = stream.getReader();
      try {
        for (;;) {
          const { value, done } = await reader.read();
          if (done) break;
          if (typeof value === "string" && value.length > 0) {
            send({ evt: "clipboard", text: value });
          }
        }
      } catch (e) {
        if (currentSerial === serial) fail(e);
      }
    })();

    scrcpy.exited
      .then(() => {
        if (currentSerial === serial) {
          currentSerial = null;
          send({ evt: "closed", reason: "scrcpy server exited" });
        }
      })
      .catch((e) => {
        if (currentSerial === serial) fail(e);
      });

    send({ evt: "ready", status: "syncing", serial });
  } catch (e) {
    currentSerial = null;
    scrcpy = null;
    fail(e);
    send({ evt: "closed", reason: String(e?.message ?? e) });
  } finally {
    connecting = false;
  }
}

process.on("message", async (message) => {
  if (!message || typeof message !== "object") return;

  switch (message.cmd) {
    case "connect":
      await connect(message.serial);
      break;

    case "disconnect":
      await disconnect();
      break;

    case "set": {
      const text = message.text;
      if (typeof text !== "string" || !text) break;
      if (!scrcpy) {
        fail("not connected");
        break;
      }
      try {
        // sequence 0 => do not wait for an ack, just set the device clipboard.
        // paste: false => set the clipboard without injecting a paste gesture.
        await scrcpy.controller.setClipboard({
          sequence: 0n,
          paste: false,
          content: text,
        });
      } catch (e) {
        fail(e);
      }
      break;
    }

    case "quit":
      await disconnect();
      process.exit(0);
      break;
  }
});

process.on("uncaughtException", (e) => fail(e));
process.on("unhandledRejection", (e) => fail(e));

send({ evt: "ready", status: "idle" });
