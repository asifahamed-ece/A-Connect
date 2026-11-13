'use strict';

/**
 * Node side of the A+ Connect companion wire protocol.
 *
 * Mirrors companion-android/.../protocol/FrameCodec.kt exactly: a 4 byte big
 * endian length, then a 1 byte frame kind, then the payload. Kind 0 is UTF-8
 * JSON, kind 1 is opaque binary. The length counts the kind byte and the
 * payload. Keep the two in step - the shared fixtures in the Android test
 * resources are what prove it.
 */

const crypto = require('crypto');

const PROTOCOL_VERSION = 2;

const KIND_JSON = 0;
const KIND_BINARY = 1;

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

  // The four features that ride the same channel as the clipboard.
  NOTIFICATION: 'notification',
  NOTIFICATION_REMOVED: 'notification-removed',
  NOTIFICATION_ACTION: 'notification-action',
  MEDIA_STATE: 'media-state',
  MEDIA_COMMAND: 'media-command',
  FILE_LIST_REQUEST: 'file-list-request',
  FILE_LIST: 'file-list',
  FILE_SEND_REQUEST: 'file-send-request',

  /**
   * A search across the whole phone, not just the folder being shown.
   *
   * The answer is an ordinary `file-list` whose `path` is `searchKey(query)`,
   * so a search resolves through the same map as a folder listing and needs no
   * separate parser. Both sides must spell the prefix the same way.
   */
  FILE_SEARCH_REQUEST: 'file-search-request',
  MIRROR_START: 'mirror-start',
  MIRROR_STOP: 'mirror-stop',
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

function frame(kind, body) {
  const length = 1 + body.length;
  if (length > MAX_FRAME_BYTES) {
    throw new Error(`frame too large: ${body.length}`);
  }
  const out = Buffer.allocUnsafe(4 + length);
  out.writeInt32BE(length, 0);
  out.writeUInt8(kind, 4);
  body.copy(out, 5);
  return out;
}

function encode(message) {
  return frame(KIND_JSON, Buffer.from(JSON.stringify(toJson(message)), 'utf8'));
}

/** Wraps raw bytes - a mirror frame, for instance - as a binary frame. */
function encodeBinary(bytes) {
  return frame(KIND_BINARY, Buffer.from(bytes));
}

/**
 * Reads one whole frame. Returns the payload slice if the frame's kind matches,
 * or null when it does not; a malformed JSON frame throws, as before.
 */
function payloadOf(frameBytes, kind) {
  if (frameBytes.length < 4) return null;
  const length = frameBytes.readInt32BE(0);
  if (length < 1 || length > MAX_FRAME_BYTES) {
    if (kind === KIND_JSON) throw new Error(`illegal frame length: ${length}`);
    return null;
  }
  if (frameBytes.length < 4 + length) {
    if (kind === KIND_JSON) throw new Error('truncated frame');
    return null;
  }
  if (frameBytes.readUInt8(4) !== kind) return null;
  return frameBytes.subarray(5, 4 + length);
}

function decode(frameBytes) {
  const body = payloadOf(frameBytes, KIND_JSON);
  if (body === null) throw new Error('frame is not JSON');

  let parsed;
  try {
    parsed = JSON.parse(body.toString('utf8'));
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

/** The payload of a binary frame, or null when the frame is anything else. */
function decodeBinary(frameBytes) {
  return payloadOf(frameBytes, KIND_BINARY);
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

/**
 * The feature payloads, identical keys to FeaturePayloads.kt on the phone.
 * The shared fixture in the Android test resources (feature-payloads.json) is
 * what proves the two implementations agree.
 */
function buildNotification(n) {
  return {
    type: TYPES.NOTIFICATION,
    id: n.id,
    pkg: n.pkg,
    app: n.app,
    title: n.title,
    text: n.text,
    ts: n.ts,
    ongoing: Boolean(n.ongoing),
    actions: (n.actions || []).map((a) => ({ id: a.id, title: a.title })),
  };
}

function parseNotification(payload) {
  if (!payload || typeof payload.id !== 'string') return null;
  return {
    id: payload.id,
    pkg: payload.pkg || '',
    app: payload.app || '',
    title: payload.title || '',
    text: payload.text || '',
    ts: payload.ts || 0,
    ongoing: Boolean(payload.ongoing),
    actions: Array.isArray(payload.actions)
      ? payload.actions.map((a) => ({ id: a.id, title: a.title }))
      : [],
  };
}

function buildMediaState(s) {
  const state = {
    type: TYPES.MEDIA_STATE,
    app: s.app,
    title: s.title,
    artist: s.artist,
    album: s.album,
    playing: Boolean(s.playing),
    positionMs: s.positionMs,
    durationMs: s.durationMs,
  };
  if (s.art) state.art = s.art;
  return state;
}

function parseMediaState(payload) {
  if (!payload || typeof payload.title !== 'string') return null;
  const state = {
    app: payload.app || '',
    title: payload.title,
    artist: payload.artist || '',
    album: payload.album || '',
    playing: Boolean(payload.playing),
    positionMs: payload.positionMs || 0,
    durationMs: payload.durationMs || 0,
  };
  // The cover travels only when it changed, so it is optional.
  if (payload.art) state.art = payload.art;
  return state;
}

function buildFileList(path, entries) {
  return {
    type: TYPES.FILE_LIST,
    path,
    entries: (entries || []).map((e) => ({
      // The document id is what the phone is asked for when the user opens a
      // folder or pulls a file, so a listing without it is a list of names
      // nobody can act on.
      id: e.id || '',
      name: e.name,
      size: e.size,
      isDir: Boolean(e.isDir),
      mime: e.mime,
    })),
  };
}

function parseFileList(payload) {
  if (!payload || !Array.isArray(payload.entries)) return null;
  return {
    path: payload.path || '',
    entries: payload.entries.map((e) => ({
      id: e.id || '',
      name: e.name,
      size: e.size || 0,
      isDir: Boolean(e.isDir),
      mime: e.mime || '',
    })),
  };
}

/**
 * The same ordering rule the phone applies: folders first, then files, each by
 * name. The phone already sorts, and this repeats it so a list that arrives in
 * another order - an older phone, a provider that ignores the sort - is still
 * drawn the way the user expects.
 */
function sortFileEntries(entries) {
  return (entries || []).slice().sort((a, b) => {
    if (Boolean(a.isDir) !== Boolean(b.isDir)) return a.isDir ? -1 : 1;
    return String(a.name || '').toLowerCase().localeCompare(String(b.name || '').toLowerCase());
  });
}

/**
 * Asks the phone for a folder listing, or for one file.
 *
 * `path` is the document id the phone handed out (the empty string is the root
 * of the folder the user shared), and it is the same key the phone's answer
 * echoes back, so the desktop never has to invent a second identifier.
 */
function buildFileRequest(type, path) {
  if (type !== TYPES.FILE_LIST_REQUEST && type !== TYPES.FILE_SEND_REQUEST) {
    throw new Error(`not a file request: ${type}`);
  }
  return { type, path: typeof path === 'string' ? path : '' };
}

/**
 * The prefix that marks a `file-list` as the answer to a search.
 *
 * A folder path is relative to the phone's storage root and never contains a
 * colon at the start, so the two kinds of answer cannot be confused.
 */
const SEARCH_KEY_PREFIX = 'search:';

/** The key a search's answer carries in `path`. Mirrored in Kotlin. */
function searchKey(query) {
  return SEARCH_KEY_PREFIX + String(query ?? '');
}

/** A search over the whole phone's storage. */
function buildFileSearchRequest(query) {
  return { type: TYPES.FILE_SEARCH_REQUEST, query: String(query ?? '') };
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
  KIND_JSON,
  KIND_BINARY,
  MAX_FRAME_BYTES,
  MAX_CLIPBOARD_BYTES,
  TYPES,
  REASONS,
  isSupportedVersion,
  encode,
  encodeBinary,
  decode,
  decodeBinary,
  buildClipboardPayload,
  shouldSendClipboard,
  buildNotification,
  parseNotification,
  buildMediaState,
  parseMediaState,
  buildFileList,
  parseFileList,
  sortFileEntries,
  buildFileRequest,
  buildFileSearchRequest,
  searchKey,
  SEARCH_KEY_PREFIX,
  computeProof,
  verifyProof,
  buildPairingPayload,
  parsePairingPayload,
  normalize,
  truncateToBytes,
};
