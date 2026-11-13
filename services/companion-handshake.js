'use strict';

/**
 * The HELLO handshake, shared by every socket that has to know which phone is
 * on the other end.
 *
 * It lives here rather than inside a service because there are now two of them:
 * the control channel, and the video socket screen mirroring opens. Trust is
 * the part of this app with the most expensive failure mode, so there is one
 * implementation and both callers use it - not two copies that drift.
 *
 * The caller supplies the pieces that differ between them through `host`:
 *
 *   host.pairedDevices        the store, keyed by device id
 *   host.isPairingCodeValid   the live five-minute code
 *   host.savePairedDevices()  persist after a change
 *   host.pcId, host.fingerprint   what goes back in the ack
 *   host.onPaired(device, isNew)  the caller's own bookkeeping
 *   host.logLine(text)        optional, for the caller's own log
 *
 * `allowPairing` is the one behavioural difference: a brand new phone may pair
 * on the control channel, but the video socket only ever inherits a pairing that
 * already exists. Nothing about the video port is worth a code exchange, and
 * turning it on there would double the places a pairing can be abused.
 */

const protocol = require('./companion-protocol');

/** Writes one JSON message to a socket. */
function send(socket, type, payload, id = 'hs') {
  socket.write(
    protocol.encode({
      version: protocol.PROTOCOL_VERSION,
      type,
      id,
      ts: Date.now(),
      payload,
    })
  );
}

/** Refuses the connection: the phone is told why, and the socket is closed. */
function refuse(socket, reason) {
  send(socket, protocol.TYPES.HELLO_ERROR, { reason });
  socket.end();
}

/**
 * Handles a HELLO. Returns true when the socket is now trusted.
 *
 * A phone that is already paired must prove it still holds the secret from the
 * pairing: a device id alone leaks through logs and backups, and a leaked id
 * would otherwise hand over the whole clipboard.
 */
function handleHello(host, socket, message, options = {}) {
  const allowPairing = options.allowPairing !== false;
  const say = (text) => {
    if (typeof host.logLine === 'function') host.logLine(text);
  };

  const deviceId = message.payload.deviceId;
  const deviceName = message.payload.deviceName || 'Phone';
  if (!deviceId) {
    refuse(socket, protocol.REASONS.BAD_FINGERPRINT);
    return false;
  }

  const paired = host.pairedDevices[deviceId];

  if (paired) {
    const secret = paired.secret ? Buffer.from(paired.secret, 'base64') : null;
    const proof = message.payload.proof;
    const nonce = message.payload.nonce;
    const proofIsValid =
      secret && proof && protocol.verifyProof(secret, deviceId, nonce, proof);

    if (proofIsValid) {
      say(`recognized returning paired device: ${deviceName} (${deviceId})`);
      return finish(host, socket, message, deviceId, deviceName, false);
    }

    // A phone that lost its secret - or one paired before the secret existed -
    // can pair again inside the same five-minute window the first pairing uses.
    const canRePair =
      allowPairing && host.isPairingCodeValid(message.payload.code) && message.payload.secret;
    if (!canRePair) {
      console.log(
        `[companion] refusing ${deviceName} (${deviceId}): no valid proof of possession`
      );
      refuse(socket, protocol.REASONS.AUTH_FAILED);
      return false;
    }

    remember(host, deviceId, deviceName, message.payload.secret);
    say(`re-paired ${deviceName} (${deviceId}) with the live code`);
    return finish(host, socket, message, deviceId, deviceName, true);
  }

  // A device nobody has seen before.
  const canPair = allowPairing && host.isPairingCodeValid(message.payload.code) && message.payload.secret;
  if (!canPair) {
    console.log(
      `[companion] refusing connection from ${socket.clientIp}: not paired, and pairing is ` +
        `${allowPairing ? 'not open (no valid code)' : 'not allowed on this socket'}`
    );
    refuse(socket, allowPairing ? protocol.REASONS.BAD_CODE : protocol.REASONS.AUTH_FAILED);
    return false;
  }

  remember(host, deviceId, deviceName, message.payload.secret);
  say(`newly paired with ${deviceName} (${deviceId})`);
  return finish(host, socket, message, deviceId, deviceName, true);
}

/** Stores a new secret for a device, and lets the host persist it. */
function remember(host, deviceId, deviceName, secret) {
  host.pairedDevices[deviceId] = {
    deviceId,
    deviceName,
    pairedAt: Date.now(),
    secret: String(secret),
  };
  if (typeof host.savePairedDevices === 'function') host.savePairedDevices();
}

/** Marks the socket trusted and answers with the ack. */
function finish(host, socket, message, deviceId, deviceName, isNew) {
  socket.handshakeDone = true;
  socket.deviceId = deviceId;
  socket.deviceName = deviceName;

  send(socket, protocol.TYPES.HELLO_ACK, {
    sessionId: 's-' + Date.now(),
    pcId: host.pcId,
    fingerprint: host.fingerprint,
    nonce: message.payload.nonce,
  });

  if (typeof host.onPaired === 'function') {
    host.onPaired({ deviceId, deviceName, ip: socket.clientIp, isNew });
  }
  return true;
}

module.exports = { handleHello };
