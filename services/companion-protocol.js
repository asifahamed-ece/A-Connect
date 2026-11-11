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
const MAX_FRAME_BYTES = 1024 * 1024;
const MAX_CLIPBOARD_CHARS = 256 * 1024;

const TYPES = {
  HELLO: 'hello',
  HELLO_ACK: 'hello-ack',
  HELLO_ERROR: 'hello-error',
  CLIPBOARD_SET: 'clipboard-set',
  PING: 'ping',
  PONG: 'pong',
  BYE: 'bye',
};

const REASONS = {
  BAD_CODE: 'bad-code',
  FINGERPRINT_MISMATCH: 'fingerprint-mismatch',
  ALREADY_PAIRED: 'already-paired',
  NONCE_MISMATCH: 'nonce-mismatch',
  PROTOCOL_VERSION: 'protocol-version',
};

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
  const parsed = JSON.parse(frame.subarray(4, 4 + length).toString('utf8'));
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

/** The clipboard rules, identical to ClipboardPayloads.kt on the phone. */
function buildClipboardPayload(text) {
  const truncated = text.length > MAX_CLIPBOARD_CHARS;
  const body = truncated ? text.substring(0, MAX_CLIPBOARD_CHARS) : text;
  return {
    text: body,
    hash: crypto.createHash('sha256').update(body, 'utf8').digest('hex'),
    truncated,
  };
}

function shouldSendClipboard(payload, lastSentHash) {
  if (!payload.text) return false;
  return payload.hash !== lastSentHash;
}

module.exports = {
  PROTOCOL_VERSION,
  MAX_FRAME_BYTES,
  MAX_CLIPBOARD_CHARS,
  TYPES,
  REASONS,
  encode,
  decode,
  buildClipboardPayload,
  shouldSendClipboard,
};
