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
  MEDIA_OFF: 'media-off',
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
  MIRROR_STARTED: 'mirror-started',
  MIRROR_STOPPED: 'mirror-stopped',
  MIRROR_FAILED: 'mirror-failed',

  // The phone's own vitals: battery, Android version, storage, etc.
  // Sent right after the handshake and then every ~60 s.
  DEVICE_INFO: 'device-info',

  // Preview cache: the desktop asks for a file's hash before downloading it,
  // so a second look at the same file opens the cached copy instantly.
  FILE_HASH_REQUEST: 'file-hash-request',
  FILE_HASH: 'file-hash',
};

/**
 * The first byte of a binary frame says which stream it belongs to.
 *
 * One here, two reserved for audio: the day a second kind of stream exists, the
 * frame format does not have to change to tell them apart.
 */
const STREAM_VIDEO = 1;

/** stream id, flags, then a 64-bit timestamp. */
const VIDEO_HEADER_BYTES = 10;

/** Why a mirroring session ended, in the words the user sees. */
const MIRROR_REASONS = {
  REQUESTED: 'requested',
  DISCONNECTED: 'disconnected',
  PROJECTION_STOPPED: 'projection-stopped',
  SCREEN_SLEPT: 'screen-slept',
  LINK_LOST: 'link-lost',
  CONSENT_REFUSED: 'consent-refused',
  NO_ENCODER: 'no-encoder',
  VIDEO_CONNECT_FAILED: 'video-connect-failed',
  NOT_TRUSTED: 'not-trusted',
  ALREADY_MIRRORING: 'already-mirroring',
  NO_CONTROL_LINK: 'no-control-link',
};

const REASONS = {
  BAD_CODE: 'bad-code',
  BAD_FINGERPRINT: 'bad-fingerprint',
  FINGERPRINT_MISMATCH: 'fingerprint-mismatch',
  ALREADY_PAIRED: 'already-paired',
  NONCE_MISMATCH: 'nonce-mismatch',
  PROTOCOL_VERSION: 'protocol-version',
  AUTH_FAILED: 'auth-failed',
  /**
   * Why the desktop closed a session when it is going dormant.
   *
   * A `bye` carrying this tells the phone the computer is switched off, not
   * that the user hung up: the phone should keep looking, slowly, rather than
   * give up until someone taps Reconnect.
   */
  DORMANT: 'dormant',
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
    // The package is the player's identity: it survives a track change and a
    // reconnect, which is what makes it usable as a bus name.
    pkg: s.pkg,
    side: s.side || 'phone',
    app: s.app,
    title: s.title,
    artist: s.artist,
    album: s.album,
    playing: Boolean(s.playing),
    positionMs: s.positionMs,
    durationMs: s.durationMs,
  };
  if (s.art) state.art = s.art;
  if (s.artCleared) state.artCleared = true;
  if (s.idle) state.idle = true;
  return state;
}

function parseMediaState(payload) {
  if (!payload || typeof payload.title !== 'string') return null;
  // Older builds send no package. Dropping their media entirely would be a
  // worse failure than losing the per-app identity, so they get a stand-in.
  const pkg = typeof payload.pkg === 'string' && payload.pkg ? payload.pkg : 'aplusconnect.phone';
  const state = {
    pkg,
    side: payload.side === 'pc' ? 'pc' : 'phone',
    app: payload.app || '',
    title: payload.title,
    artist: payload.artist || '',
    album: payload.album || '',
    playing: Boolean(payload.playing),
    positionMs: payload.positionMs || 0,
    durationMs: payload.durationMs || 0,
  };
  // The cover travels only when it changed, so it is optional. Its absence
  // means "unchanged", NOT "none" - which is why a track with no art says so
  // explicitly.
  if (payload.art) state.art = payload.art;
  if (payload.artCleared === true) state.artCleared = true;
  // "Nothing is playing any more", which is not the same as a paused track:
  // the desktop clears the player rather than leaving the last one on screen.
  if (payload.idle === true) state.idle = true;
  return state;
}

/**
 * Asks the phone to start mirroring, and everything it needs to do it.
 *
 * The video port travels with the request rather than being configured on the
 * phone: the phone dials out, so the desktop is the one that decides where the
 * listener is, and a port number that changes in one place cannot drift out of
 * step in the other.
 */
function buildMirrorStart(options = {}) {
  return {
    type: TYPES.MIRROR_START,
    videoPort: options.videoPort,
    maxLongSide: options.maxLongSide,
    fps: options.fps,
    bitrate: options.bitrate,
    keyframeSeconds: options.keyframeSeconds,
  };
}

/** The quality settings a phone was asked for, or null if the request is junk. */
function parseMirrorStart(payload) {
  if (!payload || typeof payload.videoPort !== 'number') return null;
  return {
    videoPort: payload.videoPort,
    maxLongSide: payload.maxLongSide || 1280,
    fps: payload.fps || 30,
    bitrate: payload.bitrate || 4_000_000,
    keyframeSeconds: payload.keyframeSeconds || 1,
  };
}

/** The phone is capturing: the real size, and which encoder it picked. */
function buildMirrorStarted(payload = {}) {
  return {
    type: TYPES.MIRROR_STARTED,
    width: payload.width,
    height: payload.height,
    encoder: payload.encoder || '',
  };
}

/** Mirroring ended, and why. */
function buildMirrorStopped(reason) {
  return { type: TYPES.MIRROR_STOPPED, reason: reason || MIRROR_REASONS.REQUESTED };
}

/** Mirroring never started, and why. */
function buildMirrorFailed(reason) {
  return { type: TYPES.MIRROR_FAILED, reason: reason || MIRROR_REASONS.NO_ENCODER };
}

/** Either outcome, as the desktop reads it. */
function parseMirrorOutcome(payload) {
  if (!payload || typeof payload.reason !== 'string') return null;
  if (payload.type === TYPES.MIRROR_STARTED) {
    return {
      type: TYPES.MIRROR_STARTED,
      width: payload.width || 0,
      height: payload.height || 0,
      encoder: payload.encoder || '',
    };
  }
  return { type: payload.type, reason: payload.reason };
}

/**
 * One video frame, ready for the binary envelope.
 *
 * stream id, a flags byte, and a 64-bit microsecond presentation time. The
 * timestamp is what WebCodecs shows on its clock and what lets the desktop
 * notice a stalled stream instead of painting a frozen frame for ever.
 */
function buildVideoFrame({ keyframe = false, ptsMicros = 0, payload }) {
  const body = payload || Buffer.alloc(0);
  const out = Buffer.alloc(VIDEO_HEADER_BYTES + body.length);
  out.writeUInt8(STREAM_VIDEO, 0);
  out.writeUInt8(keyframe ? 1 : 0, 1);
  out.writeBigUInt64BE(BigInt(Math.max(0, Math.floor(ptsMicros))), 2);
  body.copy(out, VIDEO_HEADER_BYTES);
  return out;
}

/**
 * Reads one video frame. Returns null for anything that is not one: a short
 * buffer, or a stream id this version does not know. A reader must be able to
 * drop a frame it does not understand and keep reading, because the alternative
 * is a socket that dies on one bad packet.
 */
function parseVideoFrame(buffer) {
  if (!buffer || buffer.length < VIDEO_HEADER_BYTES) return null;
  const streamId = buffer.readUInt8(0);
  if (streamId !== STREAM_VIDEO) return null;
  const flags = buffer.readUInt8(1);
  const ptsMicros = Number(buffer.readBigUInt64BE(2));
  return {
    streamId,
    keyframe: (flags & 1) !== 0,
    ptsMicros,
    payload: buffer.subarray(VIDEO_HEADER_BYTES),
  };
}

/** One player ended. The others keep playing. */
function buildMediaOff(pkg, side = 'phone') {
  return { type: TYPES.MEDIA_OFF, pkg, side };
}

function parseMediaOff(payload) {
  if (!payload || typeof payload.pkg !== 'string' || !payload.pkg) return null;
  return { pkg: payload.pkg, side: payload.side === 'pc' ? 'pc' : 'phone' };
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
function buildFileRequest(type, path, extra = {}) {
  if (type !== TYPES.FILE_LIST_REQUEST && type !== TYPES.FILE_SEND_REQUEST) {
    throw new Error(`not a file request: ${type}`);
  }
  const payload = { type, path: typeof path === 'string' ? path : '' };
  // A send request may carry two things a listing request never does:
  //  - `id`, so the transfer that comes back can be tied to this request
  //    instead of guessed at from arrival order;
  //  - `open`, meaning "this is for viewing, not for keeping".
  if (extra.id) payload.id = String(extra.id);
  if (extra.open) payload.open = true;
  return payload;
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
function buildFileSearchRequest(query, fresh = false) {
  return {
    type: TYPES.FILE_SEARCH_REQUEST,
    query: String(query ?? ''),
    // A keystroke must never force a walk of the phone's storage; the Refresh
    // button is the one caller that asks for one.
    fresh: fresh === true,
  };
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

/**
 * The phone's vitals: battery, OS version, storage, model.
 *
 * Sent right after pairing and then every ~60 s, so the desktop's device
 * cards stay current.
 */
function buildDeviceInfo(info) {
  const payload = {
    type: TYPES.DEVICE_INFO,
    batteryLevel: typeof info.batteryLevel === 'number' ? info.batteryLevel : 0,
    isCharging: Boolean(info.isCharging),
    androidVersion: info.androidVersion || '',
    colorOsVersion: info.colorOsVersion || '',
    storageFree: info.storageFree || '',
    storageTotal: info.storageTotal || '',
    model: info.model || '',
  };
  return payload;
}

function parseDeviceInfo(payload) {
  if (!payload || payload.type !== TYPES.DEVICE_INFO) return null;
  return {
    batteryLevel: typeof payload.batteryLevel === 'number' ? payload.batteryLevel : 0,
    isCharging: Boolean(payload.isCharging),
    androidVersion: payload.androidVersion || '',
    colorOsVersion: payload.colorOsVersion || '',
    storageFree: payload.storageFree || '',
    storageTotal: payload.storageTotal || '',
    model: payload.model || '',
  };
}

/**
 * Asks the phone for a file's SHA256, so the desktop can check its cache
 * before downloading.
 */
function buildFileHashRequest(path) {
  return { type: TYPES.FILE_HASH_REQUEST, path };
}

function parseFileHashRequest(payload) {
  if (!payload || payload.type !== TYPES.FILE_HASH_REQUEST) return null;
  return { path: payload.path || '' };
}

/** The phone's answer: the hash, or an error. */
function buildFileHash(path, sha256, size, error) {
  const payload = { type: TYPES.FILE_HASH, path };
  if (error) payload.error = error;
  else {
    payload.sha256 = sha256;
    payload.size = size;
  }
  return payload;
}

function parseFileHash(payload) {
  if (!payload || payload.type !== TYPES.FILE_HASH) return null;
  const result = { path: payload.path || '' };
  if (payload.error) result.error = payload.error;
  else {
    result.sha256 = payload.sha256 || '';
    result.size = typeof payload.size === 'number' ? payload.size : 0;
  }
  return result;
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
  buildMirrorStart,
  parseMirrorStart,
  buildMirrorStarted,
  buildMirrorStopped,
  buildMirrorFailed,
  parseMirrorOutcome,
  buildVideoFrame,
  parseVideoFrame,
  STREAM_VIDEO,
  VIDEO_HEADER_BYTES,
  MIRROR_REASONS,
  buildMediaOff,
  parseMediaOff,
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
  buildDeviceInfo,
  parseDeviceInfo,
  buildFileHashRequest,
  parseFileHashRequest,
  buildFileHash,
  parseFileHash,
};
