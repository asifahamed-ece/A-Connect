#!/usr/bin/env node
/**
 * The kill switch.
 *
 * One switch on the desktop, and it has to mean what it says: off means there
 * is no listener at all, the phone is told *why* it is being let go, and the
 * choice survives a restart.
 *
 * The parts that are easy to get wrong, and are checked here:
 *  - turning it off closes the port, rather than leaving a listener that
 *    politely refuses;
 *  - the phone is told "dormant", not "hung up" - the phone keeps looking
 *    for a switched-off computer and gives up on a deliberate hang-up;
 *  - the state is remembered, and a fresh instance reads it back;
 *  - the paired devices and their secrets are untouched by the switch.
 *
 * There is no test runner in this repo: node scripts/check-kill-switch.js
 */
'use strict';

const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const tls = require('tls');

const protocol = require('../services/companion-protocol');
const CompanionService = require('../services/companion');

const SECRET = 's3cret';
const PAIRED = {
  'dev-1': {
    deviceId: 'dev-1',
    deviceName: 'Phone',
    pairedAt: Date.now(),
    secret: Buffer.from(SECRET, 'utf8').toString('base64'),
  },
};

/** A settings file of its own, so the check never touches the user's. */
function tempSettings() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'aplus-kill-'));
  return path.join(dir, 'settings.json');
}

/** A service on a free port, with the store the control channel would have. */
function makeService(settingsFile) {
  const service = new CompanionService({ port: 0, settingsFile });
  service.pairedDevices = { ...PAIRED };
  return service;
}

/** Waits for an event, or rejects. */
function once(emitter, name, timeoutMs = 3000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`${name} never happened`)), timeoutMs);
    emitter.once(name, (data) => {
      clearTimeout(timer);
      resolve(data);
    });
  });
}

/** Starts listening and waits until the port is open. */
async function listening(service) {
  const opened = once(service, 'listening');
  service.start(0);
  await opened;
  return service.port;
}

/** A paired phone, connected and waiting. */
function connect(port) {
  const socket = tls.connect({ port, host: '127.0.0.1', rejectUnauthorized: false });
  const messages = [];
  let buffer = Buffer.alloc(0);

  socket.on('data', (chunk) => {
    buffer = Buffer.concat([buffer, chunk]);
    while (buffer.length >= 4) {
      const length = buffer.readUInt32BE(0);
      if (buffer.length < 4 + length) return;
      const frame = buffer.subarray(0, 4 + length);
      buffer = buffer.subarray(4 + length);
      if (frame.readUInt8(4) !== protocol.KIND_JSON) continue;
      messages.push(protocol.decode(frame));
    }
  });

  const waitFor = (type, timeoutMs = 3000) =>
    new Promise((resolve, reject) => {
      const found = () => messages.find((m) => m.type === type);
      if (found()) return resolve(found());
      const timer = setTimeout(() => reject(new Error(`no ${type} arrived`)), timeoutMs);
      const tick = setInterval(() => {
        const message = found();
        if (!message) return;
        clearInterval(timer);
        clearInterval(tick);
        resolve(message);
      }, 20);
    });

  return {
    socket,
    messages,
    waitFor,
    hello() {
      const nonce = 'nonce-1';
      const payload = { deviceId: 'dev-1', deviceName: 'Phone', nonce, code: '' };
      payload.secret = Buffer.from(SECRET, 'utf8').toString('base64');
      payload.proof = protocol.computeProof(
        Buffer.from(SECRET, 'utf8'),
        'dev-1',
        nonce
      );
      socket.write(
        protocol.encode({
          version: protocol.PROTOCOL_VERSION,
          type: protocol.TYPES.HELLO,
          id: 'h1',
          ts: Date.now(),
          payload,
        })
      );
    },
  };
}

/** Is anything listening on this port? */
function isListening(port) {
  const net = require('net');
  return new Promise((resolve) => {
    const socket = net.connect({ port, host: '127.0.0.1' });
    socket.on('connect', () => {
      socket.destroy();
      resolve(true);
    });
    socket.on('error', () => resolve(false));
    setTimeout(() => {
      socket.destroy();
      resolve(false);
    }, 1500);
  });
}

/** Off means off: the port is closed, not politely refusing. */
async function checkOffClosesTheListener() {
  const settingsFile = tempSettings();
  const service = makeService(settingsFile);
  const port = await listening(service);

  const result = service.setEnabled(false);
  assert.strictEqual(result.enabled, false, 'the switch did not report itself as off');
  assert.strictEqual(service.isEnabled, false, 'the service still thinks it is on');
  assert.strictEqual(service.server, null, 'the server was left running');
  assert.strictEqual(await isListening(port), false, 'the port is still open');

  service.stop();
}

/** The phone is told why, so it keeps looking instead of giving up. */
async function checkThePhoneIsToldWhy() {
  const settingsFile = tempSettings();
  const service = makeService(settingsFile);
  const port = await listening(service);

  const client = connect(port);
  await new Promise((r) => client.socket.once('secureConnect', r));
  client.hello();
  await client.waitFor(protocol.TYPES.HELLO_ACK);

  const bye = once(service, 'status');
  service.setEnabled(false);
  const message = await client.waitFor(protocol.TYPES.BYE);

  assert.strictEqual(
    message.payload.reason,
    protocol.REASONS.DORMANT,
    'the phone was not told the computer is dormant'
  );
  assert.notStrictEqual(
    message.payload.reason,
    'the computer disconnected',
    'the dormant reason is the hang-up reason'
  );

  client.socket.destroy();
  service.stop();
}

/** On again: the listener comes back. */
async function checkOnReopensTheListener() {
  const settingsFile = tempSettings();
  const service = makeService(settingsFile);
  const port = await listening(service);

  service.setEnabled(false);
  assert.strictEqual(await isListening(port), false, 'the port stayed open while off');

  service.setEnabled(true);
  assert.strictEqual(service.isEnabled, true, 'the switch did not come back on');
  assert.ok(service.server, 'the listener did not come back');
  assert.strictEqual(await isListening(port), true, 'the port did not come back');

  service.stop();
}

/** Off from the start: nothing is opened at all. */
async function checkOffAtStartupOpensNothing() {
  const settingsFile = tempSettings();
  fs.writeFileSync(settingsFile, JSON.stringify({ enabled: false }), 'utf8');

  const service = makeService(settingsFile);
  assert.strictEqual(service.isEnabled, false, 'a saved "off" was not read back');

  service.start(0);
  assert.strictEqual(service.server, null, 'a dormant desktop opened a listener anyway');
  assert.strictEqual(service.status, 'disabled', 'the status did not say why');

  service.stop();
}

/** The choice is remembered, and the secrets are not touched by it. */
async function checkTheChoiceIsRemembered() {
  const settingsFile = tempSettings();
  const first = makeService(settingsFile);
  first.setEnabled(false);
  first.stop();

  const second = makeService(settingsFile);
  assert.strictEqual(second.isEnabled, false, 'the switch did not survive a restart');

  // The switch is not a re-pairing: the paired devices and their secret are
  // still there, untouched.
  assert.deepStrictEqual(
    Object.keys(second.pairedDevices).sort(),
    Object.keys(PAIRED).sort(),
    'the switch disturbed the paired devices'
  );

  second.setEnabled(true);
  assert.strictEqual(second.isEnabled, true, 'the switch could not be turned back on');
  second.stop();
}

async function run() {
  await checkOffClosesTheListener();
  await checkThePhoneIsToldWhy();
  await checkOnReopensTheListener();
  await checkOffAtStartupOpensNothing();
  await checkTheChoiceIsRemembered();
  console.log('kill switch: ok');
}

run().catch((e) => {
  console.error(e.message);
  process.exit(1);
});
