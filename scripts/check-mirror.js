#!/usr/bin/env node
/**
 * The video socket.
 *
 * Screen mirroring travels on its own TLS connection. This drives a real
 * listener with a real client, because the parts that break are not the parsers
 * - they are the handshake, the frame loop and the decision to keep reading
 * after a bad packet.
 *
 * What it proves:
 *  - a paired phone gets in and its frames arrive with keyframe, timestamp and
 *    payload intact;
 *  - an unpaired phone is refused **even with a live pairing code**, because the
 *    video socket inherits a pairing and never creates one;
 *  - a malformed frame is dropped and the socket keeps going;
 *  - the codec string comes out of the stream's own SPS.
 *
 * There is no test runner in this repo: node scripts/check-mirror.js
 */
'use strict';

const assert = require('assert');
const tls = require('tls');

const protocol = require('../services/companion-protocol');
const MirrorService = require('../services/mirror');
const h264 = require('../services/h264');

const SECRET = 's3cret';
const PAIRED = {
  'dev-1': {
    deviceId: 'dev-1',
    deviceName: 'Phone',
    pairedAt: Date.now(),
    secret: Buffer.from(SECRET, 'utf8').toString('base64'),
  },
};

/** A keyframe: SPS, PPS, then an IDR slice, in Annex-B. */
const KEYFRAME = Buffer.concat([
  Buffer.from([0, 0, 0, 1]), Buffer.from([0x67, 0x64, 0x00, 0x1f, 0xac, 0xd9, 0x40, 0x50]),
  Buffer.from([0, 0, 0, 1]), Buffer.from([0x68, 0xee, 0x3c, 0x80]),
  Buffer.from([0, 0, 0, 1]), Buffer.from([0x65, 0x88, 0x84, 0x00, 0x21]),
]);

/** One service on a free port, with the store the control channel would have. */
async function listening(pairedDevices = PAIRED) {
  const service = new MirrorService({
    pairedDevices,
    isPairingCodeValid: (code) => code === '123456',
    savePairedDevices: () => {},
    pcId: 'pc-test',
    fingerprint: 'AA:BB',
    // The check cannot spend six seconds proving a timeout exists.
    frameStallMs: 400,
  });
  const port = await service.start(0);
  service.framesSeen = [];
  service.on('frame', (frame) => service.framesSeen.push(frame));
  return { service, port };
}

/** A client that can be talked to frame by frame. */
function connect(port) {
  const socket = tls.connect({ port, host: '127.0.0.1', rejectUnauthorized: false });
  let buffer = Buffer.alloc(0);
  const messages = [];
  const waiters = [];

  socket.on('data', (chunk) => {
    buffer = Buffer.concat([buffer, chunk]);
    while (buffer.length >= 4) {
      const length = buffer.readUInt32BE(0);
      if (buffer.length < 4 + length) return;
      const frameBytes = buffer.subarray(0, 4 + length);
      buffer = buffer.subarray(4 + length);
      if (frameBytes.readUInt8(4) !== protocol.KIND_JSON) continue;
      const message = protocol.decode(frameBytes);
      messages.push(message);
      while (waiters.length) waiters.shift()();
    }
  });

  return {
    socket,
    messages,
    /** Waits for a message of this type, or rejects after a moment. */
    waitFor(type, timeoutMs = 3000) {
      const found = () => messages.find((m) => m.type === type);
      if (found()) return Promise.resolve(found());
      return new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error(`no ${type} arrived`)), timeoutMs);
        waiters.push(() => {
          const m = found();
          if (m) {
            clearTimeout(timer);
            resolve(m);
          }
        });
      });
    },
    hello(deviceId = 'dev-1', proofSecret = SECRET, code = '') {
      const nonce = 'nonce-1';
      const payload = { deviceId, deviceName: 'Phone', nonce, code };
      payload.secret = Buffer.from(proofSecret, 'utf8').toString('base64');
      payload.proof = protocol.computeProof(
        Buffer.from(proofSecret, 'utf8'),
        deviceId,
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
    sendFrame({ keyframe = false, ptsMicros = 0, payload = KEYFRAME, raw = null }) {
      const body = raw || protocol.buildVideoFrame({ keyframe, ptsMicros, payload });
      socket.write(protocol.encodeBinary(body));
    },
  };
}

function waitForFrame(service) {
  const deadline = Date.now() + 3000;
  return new Promise((resolve, reject) => {
    const tick = () => {
      if (service.framesSeen.length) return resolve(service.framesSeen[0]);
      if (Date.now() > deadline) return reject(new Error('no frame arrived'));
      setTimeout(tick, 20);
    };
    tick();
  });
}

/** A paired phone: handshake, then a keyframe that arrives intact. */
async function checkPairedPhoneStreams() {
  const { service, port } = await listening();
  const client = connect(port);
  await new Promise((r) => client.socket.once('secureConnect', r));

  client.hello();
  await client.waitFor(protocol.TYPES.HELLO_ACK);

  client.sendFrame({ keyframe: true, ptsMicros: 123_456, payload: KEYFRAME });
  const frame = await waitForFrame(service);

  assert.strictEqual(frame.keyframe, true, 'the keyframe flag was lost');
  assert.strictEqual(frame.ptsMicros, 123_456, 'the timestamp was lost');
  assert.ok(frame.payload.equals(KEYFRAME), 'the payload changed on the way');
  assert.strictEqual(service.frames, 1, 'the frame was not counted');

  // The codec string comes out of the stream's own SPS, not out of a setting.
  assert.strictEqual(h264.codecStringFromAnnexB(frame.payload), 'avc1.64001F',
    'the SPS did not yield the codec string');

  client.socket.destroy();
  service.stop();
}

/**
 * An unpaired phone is refused, even holding the live pairing code.
 *
 * This is the rule that makes the second socket safe: it inherits a pairing
 * from the control channel and cannot create one.
 */
async function checkPairingIsRefused() {
  const { service, port } = await listening({});
  const client = connect(port);
  await new Promise((r) => client.socket.once('secureConnect', r));

  client.hello('stranger', 'any-secret', '123456');
  const refusal = await client.waitFor(protocol.TYPES.HELLO_ERROR);

  assert.strictEqual(refusal.payload.reason, protocol.REASONS.AUTH_FAILED,
    'an unpaired phone was not told why it was refused');

  client.sendFrame({ keyframe: true, payload: KEYFRAME });
  await new Promise((r) => setTimeout(r, 200));
  assert.strictEqual(service.frames, 0, 'a refused socket still delivered video');

  client.socket.destroy();
  service.stop();
}

/** A bad frame is dropped; the next good one still arrives. */
async function checkBadFrameDoesNotKillTheStream() {
  const { service, port } = await listening();
  const client = connect(port);
  await new Promise((r) => client.socket.once('secureConnect', r));
  client.hello();
  await client.waitFor(protocol.TYPES.HELLO_ACK);

  // A frame claiming an unknown stream id, then a real one.
  const wrongStream = Buffer.from(protocol.buildVideoFrame({ keyframe: true, payload: KEYFRAME }));
  wrongStream.writeUInt8(9, 0);
  client.sendFrame({ raw: wrongStream });
  client.sendFrame({ keyframe: false, ptsMicros: 7, payload: Buffer.from([0, 0, 1, 0x41, 0x9a]) });

  const frame = await waitForFrame(service);
  assert.strictEqual(frame.keyframe, false, 'the wrong frame got through');
  assert.strictEqual(frame.ptsMicros, 7, 'the good frame after a bad one did not arrive');
  assert.ok(service.dropped >= 1, 'the bad frame was not counted as dropped');

  client.socket.destroy();
  service.stop();
}

/** A frame that is too short to hold a header is dropped, not fatal. */
/**
 * A socket that goes quiet is ended, not left claiming to stream.
 *
 * This is the frozen-picture case: TCP keeps a dead link open for minutes, so
 * without a watchdog the card says "streaming" over the last frame received.
 */
async function checkSilenceEndsTheSession() {
  const { service, port } = await listening();
  const stalled = new Promise((resolve) => service.on('stalled', resolve));

  const client = connect(port);
  await new Promise((r) => client.socket.once('secureConnect', r));
  client.hello();
  await client.waitFor(protocol.TYPES.HELLO_ACK);

  // One real frame, then silence with the socket still open - exactly what a
  // phone out of Wi-Fi range looks like from here.
  client.sendFrame({ keyframe: true, ptsMicros: 1, payload: KEYFRAME });
  await waitForFrame(service);

  const event = await Promise.race([
    stalled,
    new Promise((_, reject) => setTimeout(() => reject(new Error('silence was never noticed')), 4000)),
  ]);
  assert.strictEqual(event.deviceId, 'dev-1', 'the stalled socket was not identified');

  // The socket is destroyed just after the event, so the close lands a tick
  // later; polling for it is the honest way to check.
  const deadline = Date.now() + 2000;
  while (service.sockets.size > 0 && Date.now() < deadline) {
    await new Promise((r) => setTimeout(r, 25));
  }
  assert.strictEqual(service.sockets.size, 0, 'the stalled socket was left open');

  client.socket.destroy();
  service.stop();
}

function checkShortFrame() {
  assert.strictEqual(protocol.parseVideoFrame(Buffer.alloc(3)), null, 'a 3-byte frame parsed');
  const headerOnly = protocol.buildVideoFrame({ keyframe: true, ptsMicros: 1, payload: Buffer.alloc(0) });
  const parsed = protocol.parseVideoFrame(headerOnly);
  assert.ok(parsed, 'a header-only frame was rejected');
  assert.strictEqual(parsed.payload.length, 0, 'a header-only frame invented a payload');
}

async function run() {
  checkShortFrame();
  await checkPairedPhoneStreams();
  await checkPairingIsRefused();
  await checkBadFrameDoesNotKillTheStream();
  await checkSilenceEndsTheSession();
  console.log('mirror: ok');
}

run().catch((e) => {
  console.error(e.message);
  process.exit(1);
});