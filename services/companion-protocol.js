'use strict';

/**
 * Node side of the A+ Connect companion wire protocol.
 *
 * Mirrors companion-android/.../protocol/FrameCodec.kt exactly: a 4 byte big
 * endian length prefix followed by UTF-8 JSON. Keep the two in step - the
 * shared fixtures in the Android test resources are what prove it.
 */

const crypto = require('crypto');

const PROTOCOL_VERSION = 1;

/**
 * Two mebibytes, so that the worst case of a clipboard payload fits: the
 * clipboard budget is 256 KiB of UTF-8, and a control character escapes to
 * six bytes, which is 1.5 MiB of JSON plus the envelope.
 */
const MAX_FRAME_BYTES = 2 * 1024 * 1024;

/**
 * The clipboard cap is a byte budget, not a character budget: 256 KiB of
 * UTF-8. A character cap would let a 256 KiB payload of control characters
 * escape to six bytes each and blow past the frame limit.
 */
const MAX_CLIPBOARD_BYTES = 256 * 1024;

const TYPES = {
  HELLO: 'hello',
  HELLO_ACK: 'hello-ack',
  HELLO_ERROR: 'hello-error',
  CLIPBOARD_SET: 'clipboard-set',
  FILE_START: 'file-start',
  FILE_CHUNK: 'file-chunk',
  FILE_DONE: 'file-done',
  PING: 'ping',
  PONG: 'pong',
  BYE: 'bye',
};

const REASONS = {
  BAD_CODE: 'bad-code',
  BAD_FINGERPRINT: 'bad-fingerprint',
  FINGERPRINT_MISMATCH: 'fingerprint-mismatch',
  ALREADY_PAIRED: 'already-paired',
  NONCE_MISMATCH: 'nonce-mismatch',
  PROTOCOL_VERSION: 'protocol-version',
  AUTH_FAILED: 'auth-failed',
};

/** Only the current major version is understood. */
function isSupportedVersion(version) {
  return version === PROTOCOL_VERSION;
}

function encode(message) {
  const body = Buffer.from(JSON.stringify(toJson(message)), 'utf8');
  if (body.length > MAX_FRAME_BYTES) {
    throw new Error(`frame too large: ${body.length}`);
  }
  const frame = Buffer.allocUnsafe(4 + body.length);
  frame.writeInt32BE(body.length, 0);
  body.copy(frame, 4);
  return frame;
}

function decode(frame) {
  if (frame.length < 4) throw new Error('frame too short');
  const length = frame.readInt32BE(0);
  if (length < 0 || length > MAX_FRAME_BYTES) {
    throw new Error(`illegal frame length: ${length}`);
  }
  if (frame.length < 4 + length) throw new Error('truncated frame');

  let parsed;
  try {
    parsed = JSON.parse(frame.subarray(4, 4 + length).toString('utf8'));
  } catch (err) {
    throw new Error('frame is not valid JSON');
  }
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error('frame is not a JSON object');
  }
  if (
    typeof parsed.type !== 'string' ||
    typeof parsed.id !== 'string' ||
    typeof parsed.ts !== 'number'
  ) {
    throw new Error('frame is missing required fields');
  }

  return {
    version: parsed.v,
    type: parsed.type,
    id: parsed.id,
    ts: parsed.ts,
    payload: parsed.payload || {},
  };
}

function toJson(message) {
  return {
    v: message.version !== undefined ? message.version : PROTOCOL_VERSION,
    type: message.type,
    id: message.id,
    ts: message.ts,
    payload: message.payload || {},
  };
}

/**
 * The clipboard rules, identical to ClipboardPayloads.kt on the phone: the
 * same byte budget, the same code point safe truncation, the same
 * normalisation of unpaired surrogates, the same hash.
 */
function buildClipboardPayload(text) {
  const normalized = normalize(text);
  const body = truncateToBytes(normalized, MAX_CLIPBOARD_BYTES);
  return {
    text: body,
    hash: crypto.createHash('sha256').update(body, 'utf8').digest('hex'),
    truncated: body.length !== normalized.length,
  };
}

function shouldSendClipboard(payload, lastSentHash) {
  if (!payload.text) return false;
  return payload.hash !== lastSentHash;
}

/** Replaces unpaired surrogates with U+FFFD, exactly as the JVM does. */
function normalize(text) {
  return Buffer.from(text, 'utf8').toString('utf8');
}

/** Cuts the text to a byte budget without splitting a character. */
function truncateToBytes(text, maxBytes) {
  const buf = Buffer.from(text, 'utf8');
  if (buf.length <= maxBytes) return text;
  let end = maxBytes;
  // A continuation byte means we are inside a character: back up to its start.
  while (end > 0 && (buf[end] & 0xc0) === 0x80) end -= 1;
  return buf.subarray(0, end).toString('utf8');
}

const PAIRING_TYPE = 'aplus-pairing';
const PAIRING_VERSION = 1;

/**
 * What the computer shows as a QR code for the phone to scan: the address,
 * the port, the one time code and the certificate fingerprint, so the user
 * never types an address and the phone can pin the certificate at once.
 */
function buildPairingPayload(info) {
  return {
    v: PAIRING_VERSION,
    type: PAIRING_TYPE,
    pcName: info.pcName || 'Computer',
    host: info.host,
    port: info.port,
    code: info.code,
    fingerprint: info.fingerprint || '',
    pcId: info.pcId || '',
  };
}

/** Parses a scanned payload, or null when it is not one of ours. */
function parsePairingPayload(text) {
  let json;
  try {
    json = JSON.parse(text);
  } catch (err) {
    return null;
  }
  if (!json || json.type !== PAIRING_TYPE || json.v !== PAIRING_VERSION) return null;
  if (!json.host || !json.port || !json.code || String(json.code).length !== 6) return null;

  return {
    pcName: json.pcName || 'Computer',
    host: json.host,
    port: json.port,
    code: String(json.code),
    fingerprint: json.fingerprint || '',
    pcId: json.pcId || '',
  };
}

/**
 * The proof a returning phone presents: an HMAC over its id and the nonce of
 * this connection. The computer stores the secret from the first pairing and
 * can therefore tell the real phone from anyone who merely knows its id.
 *
 * Mirrors Handshake.proofFor in the Android app - the conformance script
 * checks that both sides produce the same bytes.
 */
function computeProof(secret, deviceId, nonce) {
  return crypto
    .createHmac('sha256', secret)
    .update(`${deviceId}:${nonce}`, 'utf8')
    .digest('hex');
}

function verifyProof(secret, deviceId, nonce, proof) {
  if (typeof proof !== 'string' || !proof) return false;
  const expected = computeProof(secret, deviceId, nonce);
  if (expected.length !== proof.length) return false;
  return crypto.timingSafeEqual(Buffer.from(expected, 'utf8'), Buffer.from(proof, 'utf8'));
}

module.exports = {
  PROTOCOL_VERSION,
  MAX_FRAME_BYTES,
  MAX_CLIPBOARD_BYTES,
  TYPES,
  REASONS,
  isSupportedVersion,
  encode,
  decode,
  buildClipboardPayload,
  shouldSendClipboard,
  computeProof,
  verifyProof,
  buildPairingPayload,
  parsePairingPayload,
  normalize,
  truncateToBytes,
};
