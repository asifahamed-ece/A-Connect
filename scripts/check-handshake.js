#!/usr/bin/env node
/**
 * The HELLO handshake, which both sockets now run.
 *
 * The control channel and the video socket share one implementation, and the
 * only difference between them is whether a brand new phone may pair. That
 * difference is the reason this file exists: a video port that accepted a
 * pairing code would be a second, quieter place to attack, and "it was refactored
 * out of companion.js" is not a reason to trust it.
 *
 * There is no test runner in this repo: node scripts/check-handshake.js
 */
'use strict';

const assert = require('assert');

const protocol = require('../services/companion-protocol');
const { handleHello } = require('../services/companion-handshake');

/** A socket that records what was written and whether it was closed. */
function fakeSocket(ip = '127.0.0.1') {
  const written = [];
  return {
    clientIp: ip,
    handshakeDone: false,
    written,
    ended: false,
    write: (buf) => written.push(Buffer.from(buf)),
    end: () => {
      written.ended = true;
    },
    /** The JSON of the last frame written, or null. */
    last() {
      return written.length ? protocol.decode(written[written.length - 1]) : null;
    },
    types() {
      return written.map((buf) => protocol.decode(buf).type);
    },
  };
}

/** A service that behaves like the real one, without a socket or a file. */
function fakeHost(pairedDevices = {}) {
  const paired = [];
  return {
    pairedDevices,
    pcId: 'pc-test',
    fingerprint: 'AA:BB',
    pairingCode: '123456',
    codeValid: true,
    saves: 0,
    isPairingCodeValid(code) {
      return this.codeValid && code === this.pairingCode;
    },
    savePairedDevices() {
      this.saves += 1;
    },
    logLine() {},
    onPaired(device) {
      paired.push(device);
    },
    pairedEvents: paired,
  };
}

/** A HELLO from a device holding `secret`, with `code` offered for pairing. */
function hello({ deviceId = 'dev-1', deviceName = 'Phone', secret = 's3cret', code = '' } = {}) {
  const nonce = 'nonce-1';
  const payload = { deviceId, deviceName, code, nonce };
  if (secret) {
    payload.secret = Buffer.from(secret, 'utf8').toString('base64');
    payload.proof = protocol.computeProof(Buffer.from(secret, 'utf8'), deviceId, nonce);
  }
  return { type: protocol.TYPES.HELLO, id: 'm1', ts: Date.now(), payload };
}

/** A device already in the store, as the desktop would have written it. */
function stored(deviceId, secret) {
  return {
    [deviceId]: {
      deviceId,
      deviceName: 'Phone',
      pairedAt: Date.now(),
      secret: Buffer.from(secret, 'utf8').toString('base64'),
    },
  };
}

function checkReturningDevice() {
  const socket = fakeSocket();
  const host = fakeHost(stored('dev-1', 's3cret'));

  const trusted = handleHello(host, socket, hello(), { allowPairing: false });

  assert.strictEqual(trusted, true, 'a returning phone with a valid proof was refused');
  assert.strictEqual(socket.handshakeDone, true, 'the socket was not marked trusted');
  assert.strictEqual(socket.deviceId, 'dev-1', 'the device id was not kept');
  assert.deepStrictEqual(socket.types(), [protocol.TYPES.HELLO_ACK], 'no ack was sent');
  assert.strictEqual(socket.last().payload.pcId, 'pc-test', 'the ack lost the pc id');
  assert.strictEqual(host.pairedEvents.length, 1, 'the pairing was not announced');
  assert.strictEqual(host.pairedEvents[0].isNew, false, 'a returning phone was announced as new');
}

function checkBadProof() {
  const socket = fakeSocket();
  // The right device id, the wrong secret: an id leaks, a secret does not.
  const host = fakeHost(stored('dev-1', 'the-real-secret'));

  const trusted = handleHello(host, socket, hello({ secret: 'guessed' }), { allowPairing: true });

  assert.strictEqual(trusted, false, 'a wrong proof was accepted');
  assert.strictEqual(socket.handshakeDone, false, 'a refused socket was marked trusted');
  const refusal = socket.last();
  assert.strictEqual(refusal.type, protocol.TYPES.HELLO_ERROR, 'the refusal was not explained');
  assert.strictEqual(refusal.payload.reason, protocol.REASONS.AUTH_FAILED, 'the reason was wrong');
  assert.strictEqual(socket.written.ended, true, 'a refused socket was left open');
}

function checkPairingOnTheControlChannel() {
  const socket = fakeSocket();
  const host = fakeHost({});

  const trusted = handleHello(host, socket, hello({ code: '123456' }), { allowPairing: true });

  assert.strictEqual(trusted, true, 'a new phone with the live code was refused');
  assert.strictEqual(host.saves, 1, 'the new pairing was not persisted');
  assert.ok(host.pairedDevices['dev-1'], 'the new device was not stored');
  assert.strictEqual(host.pairedEvents[0].isNew, true, 'a new phone was announced as returning');

  // And the same phone next time is a returning one, with no code at all.
  const again = fakeSocket();
  assert.strictEqual(handleHello(host, again, hello(), { allowPairing: true }), true,
    'the phone could not come back with its own secret');
  assert.strictEqual(host.pairedEvents[1].isNew, false, 'the second visit was announced as new');
}

function checkNoCodeNoPairing() {
  const socket = fakeSocket();
  const host = fakeHost({});

  const trusted = handleHello(host, socket, hello({ code: '000000' }), { allowPairing: true });

  assert.strictEqual(trusted, false, 'a wrong code paired a new phone');
  assert.strictEqual(socket.last().payload.reason, protocol.REASONS.BAD_CODE, 'the reason was wrong');
}

/**
 * The video socket inherits a pairing and can never create one.
 *
 * Even holding a live code, an unknown device is turned away: the code is for
 * the control channel, which is where the user is looking when they scan it.
 */
function checkPairingIsRefusedOnTheVideoSocket() {
  const socket = fakeSocket();
  const host = fakeHost({});

  const trusted = handleHello(host, socket, hello({ code: '123456' }), { allowPairing: false });

  assert.strictEqual(trusted, false, 'the video socket paired a brand new device');
  assert.strictEqual(host.saves, 0, 'the video socket wrote a pairing to the store');
  assert.strictEqual(socket.handshakeDone, false, 'the video socket was marked trusted');
  assert.strictEqual(
    socket.last().payload.reason,
    protocol.REASONS.AUTH_FAILED,
    'a refused video socket did not say why'
  );

  // The phone that is already paired walks straight in, which is the whole
  // point of reusing this handshake instead of inventing a second trust path.
  const known = fakeSocket();
  assert.strictEqual(handleHello(host, known, hello(), { allowPairing: false }), false,
    'an empty store let a phone in');

  const pairedHost = fakeHost(stored('dev-1', 's3cret'));
  const returning = fakeSocket();
  assert.strictEqual(handleHello(pairedHost, returning, hello(), { allowPairing: false }), true,
    'the already-paired phone was refused on the video socket');
}

function checkDeviceWithoutAnId() {
  const socket = fakeSocket();
  const host = fakeHost({});
  const trusted = handleHello(host, socket, { type: protocol.TYPES.HELLO, payload: {} }, {});
  assert.strictEqual(trusted, false, 'a HELLO with no device id was trusted');
  assert.strictEqual(socket.written.ended, true, 'a malformed HELLO left the socket open');
}

checkReturningDevice();
checkBadProof();
checkPairingOnTheControlChannel();
checkNoCodeNoPairing();
checkPairingIsRefusedOnTheVideoSocket();
checkDeviceWithoutAnId();
console.log('handshake: ok');
