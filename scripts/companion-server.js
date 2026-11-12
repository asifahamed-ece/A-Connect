#!/usr/bin/env node
/**
 * A stand-in for the desktop app's companion server, used to prove the phone
 * end to end without the whole Electron app in the loop.
 *
 * It does the real thing: a TLS server with the PC's certificate, the pairing
 * handshake, and the framed protocol - and it prints what the phone sends.
 *
 * Usage: node scripts/companion-server.js [port]
 */
'use strict';

const fs = require('fs');
const os = require('os');
const tls = require('tls');
const { spawn } = require('child_process');

const protocol = require('../services/companion-protocol');
const { ensureCertificate } = require('../services/companion-cert');

const PORT = Number(process.argv[2] || 24443);
const CODE = process.env.PAIRING_CODE || process.argv[3] ||
  String(Math.floor(100000 + Math.random() * 900000));
const PC_ID = 'pc-test';

const { certPath, keyPath, fingerprint } = ensureCertificate();

function writeDesktopClipboard(text) {
  if (typeof text !== 'string' || !text) return;

  const env = { ...process.env };
  if (!env.WAYLAND_DISPLAY) {
    try {
      const uid = process.getuid();
      if (fs.existsSync(`/run/user/${uid}/wayland-1`)) {
        env.WAYLAND_DISPLAY = 'wayland-1';
      } else if (fs.existsSync(`/run/user/${uid}/wayland-0`)) {
        env.WAYLAND_DISPLAY = 'wayland-0';
      }
    } catch {}
  }

  const isWayland = Boolean(env.WAYLAND_DISPLAY);
  const binary = isWayland ? 'wl-copy' : 'xclip';
  const args = isWayland ? [] : ['-i', '-selection', 'clipboard'];

  try {
    const child = spawn(binary, args, { env, detached: true, stdio: ['pipe', 'ignore', 'ignore'] });
    child.on('error', (err) => console.log(`[clipboard error] ${err.message}`));
    child.stdin.on('error', () => {});
    child.on('spawn', () => {
      child.stdin.end(text);
      child.unref();
      console.log(`[server] copied to PC clipboard (${text.length} chars)`);
    });
  } catch (err) {
    console.log(`[clipboard error] ${err.message}`);
  }

  try {
    const preview = text.length > 80 ? text.slice(0, 80) + '…' : text;
    spawn('notify-send', ['-a', 'A+ Connect', '-i', 'edit-copy', 'Clipboard Synced', preview], {
      env,
      detached: true,
      stdio: 'ignore',
    }).unref();
  } catch {}
}

function localAddress() {
  const nets = os.networkInterfaces();
  for (const name of Object.keys(nets)) {
    for (const net of nets[name] || []) {
      if (net.family === 'IPv4' && !net.internal) return net.address;
    }
  }
  return '127.0.0.1';
}

const server = tls.createServer(
  { cert: fs.readFileSync(certPath), key: fs.readFileSync(keyPath) },
  (socket) => {
    console.log(`\n[server] the phone connected from ${socket.remoteAddress}`);
    let buffer = Buffer.alloc(0);
    let handshakeDone = false;

    socket.on('data', (chunk) => {
      buffer = Buffer.concat([buffer, chunk]);

      for (;;) {
        if (buffer.length < 4) return;
        const length = buffer.readInt32BE(0);
        if (buffer.length < 4 + length) return;
        const frame = buffer.subarray(0, 4 + length);
        buffer = buffer.subarray(4 + length);

        let message;
        try {
          message = protocol.decode(frame);
        } catch (err) {
          console.log(`[server] bad frame: ${err.message}`);
          socket.destroy();
          return;
        }

        if (message.type === protocol.TYPES.HELLO) {
          if (message.payload.code !== CODE) {
            console.log(`[server] refusing: wrong pairing code`);
            socket.write(protocol.encode({
              version: 1,
              type: protocol.TYPES.HELLO_ERROR,
              id: 'err',
              ts: Date.now(),
              payload: { reason: protocol.REASONS.BAD_CODE },
            }));
            socket.end();
            return;
          }
          handshakeDone = true;
          console.log(`[server] paired with ${message.payload.deviceName} (${message.payload.deviceId})`);
          socket.write(protocol.encode({
            version: 1,
            type: protocol.TYPES.HELLO_ACK,
            id: 'ack',
            ts: Date.now(),
            payload: { sessionId: 's-1', pcId: PC_ID, fingerprint, nonce: message.payload.nonce },
          }));
          continue;
        }

        if (message.type === protocol.TYPES.CLIPBOARD_SET) {
          const text = message.payload.text;
          console.log(`[server] clipboard: ${JSON.stringify(text)}`);
          writeDesktopClipboard(text);
          continue;
        }

        console.log(`[server] ${message.type}`);
      }
    });

    socket.on('error', (err) => console.log(`[server] socket error: ${err.message}`));
    socket.on('close', () => {
      console.log(`[server] disconnected (paired: ${handshakeDone})`);
    });
  }
);

server.listen(PORT, '0.0.0.0', () => {
  const address = localAddress();
  console.log('==============================================');
  console.log(`  A+ Connect companion server listening on ${address}:${PORT}`);
  console.log(`  pairing code : ${CODE}`);
  console.log(`  fingerprint  : ${fingerprint}`);
  console.log('==============================================');
});
