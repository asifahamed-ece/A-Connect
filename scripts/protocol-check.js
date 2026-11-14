#!/usr/bin/env node
/**
 * Cross-implementation conformance check for the companion protocol.
 *
 * Decodes every frame in the shared fixture, compares the values, round-trips
 * each frame, and checks the clipboard payload rules - including that the
 * hash in a clipboard frame really is the hash of its text, which is the one
 * thing a fixture can get wrong while still looking plausible.
 *
 * With --write it regenerates both fixture files from this implementation, so
 * the Kotlin side and the Node side can be compared in both directions.
 *
 * Usage: node scripts/protocol-check.js [--write]
 */
'use strict';

const fs = require('fs');
const path = require('path');
const assert = require('assert');
const crypto = require('crypto');

const protocol = require('../services/companion-protocol');

const FIXTURE_DIR = path.join(
  __dirname,
  '..',
  'companion-android',
  'app',
  'src',
  'test',
  'resources',
  'fixtures'
);
const KOTLIN_FIXTURE = path.join(FIXTURE_DIR, 'protocol-frames.json');
const NODE_FIXTURE = path.join(FIXTURE_DIR, 'protocol-frames-node.json');
const BINARY_FIXTURE = path.join(FIXTURE_DIR, 'frames.json');
const FEATURE_FIXTURE = path.join(FIXTURE_DIR, 'feature-payloads.json');

function readFrames(file) {
  return JSON.parse(fs.readFileSync(file, 'utf8')).frames;
}

function checkFrame(frame) {
  const bytes = Buffer.from(frame.hex, 'hex');
  const message = protocol.decode(bytes);
  const expected = frame.json;

  assert.strictEqual(message.version, expected.v, `${frame.name}: version mismatch`);
  assert.strictEqual(message.type, expected.type, `${frame.name}: type mismatch`);
  assert.strictEqual(message.id, expected.id, `${frame.name}: id mismatch`);
  assert.strictEqual(message.ts, expected.ts, `${frame.name}: ts mismatch`);
  assert.deepStrictEqual(message.payload, expected.payload, `${frame.name}: payload mismatch`);

  const roundTripped = protocol.decode(protocol.encode(message));
  assert.deepStrictEqual(roundTripped, message, `${frame.name}: round trip changed the message`);

  if (message.type === protocol.TYPES.CLIPBOARD_SET) {
    const text = message.payload.text;
    const hash = crypto.createHash('sha256').update(text, 'utf8').digest('hex');
    assert.strictEqual(
      message.payload.hash,
      hash,
      `${frame.name}: the hash is not the sha256 of the text it carries`
    );
  }
}

function checkClipboardRules() {
  const long = 'x'.repeat(300000);
  const payload = protocol.buildClipboardPayload(long);
  assert.strictEqual(payload.truncated, true, 'long clipboard was not flagged as truncated');
  assert.ok(
    Buffer.byteLength(payload.text, 'utf8') <= protocol.MAX_CLIPBOARD_BYTES,
    'the clipboard budget is bytes, not characters'
  );

  const short = protocol.buildClipboardPayload('abc');
  assert.strictEqual(
    short.hash,
    'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad',
    'hash is not the sha256 of the text'
  );

  // The same rules the phone applies: an emoji must not be cut in half, and
  // an unpaired surrogate must hash the same on both sides.
  const emoji = protocol.buildClipboardPayload('😀'.repeat(100000));
  assert.strictEqual(emoji.truncated, true, 'emoji payload was not truncated');
  assert.ok(
    !/[\uD800-\uDBFF]$/.test(emoji.text),
    'the truncation split a surrogate pair'
  );

  const lone = protocol.buildClipboardPayload('a\uD800b');
  assert.strictEqual(lone.text, 'a�b', 'the unpaired surrogate was not normalised');

  assert.strictEqual(protocol.shouldSendClipboard(short, null), true, 'first send was suppressed');
  assert.strictEqual(protocol.shouldSendClipboard(short, short.hash), false, 'repeat was not suppressed');
  assert.strictEqual(
    protocol.shouldSendClipboard(protocol.buildClipboardPayload(''), null),
    false,
    'empty clipboard was not suppressed'
  );
}

/**
 * Rewrites the canonical fixture's hex from its own json values, using this
 * implementation's encoder. The canonical bytes come from Node because the
 * JVM's org.json and Node's JSON.stringify order object keys differently, so
 * only one of them can own the canonical file; the Kotlin side proves it can
 * read the result.
 */
function writeCanonicalFixture() {
  const frames = readFrames(KOTLIN_FIXTURE).map((frame) => {
    const json = frame.json;
    const message = {
      version: json.v,
      type: json.type,
      id: json.id,
      ts: json.ts,
      payload: json.payload,
    };
    return { name: frame.name, hex: protocol.encode(message).toString('hex'), json };
  });
  fs.writeFileSync(KOTLIN_FIXTURE, `${JSON.stringify({ frames }, null, 2)}\n`);
  console.log(`wrote ${frames.length} frames to ${KOTLIN_FIXTURE}`);
}

function writeNodeFixture() {
  const frames = readFrames(KOTLIN_FIXTURE).map((frame) => {
    const message = protocol.decode(Buffer.from(frame.hex, 'hex'));
    return {
      name: frame.name,
      hex: protocol.encode(message).toString('hex'),
      json: frame.json,
    };
  });
  fs.writeFileSync(NODE_FIXTURE, `${JSON.stringify({ frames }, null, 2)}\n`);
  console.log(`wrote ${frames.length} frames to ${NODE_FIXTURE}`);
}

/**
 * The shared binary fixture holds one payload in an implementation-neutral
 * file; both sides must frame it to the same bytes. The Kotlin side asserts
 * the same literal frame in FrameCodecTest.
 */
function checkBinaryFrames() {
  const fixture = JSON.parse(fs.readFileSync(BINARY_FIXTURE, 'utf8'));
  assert.strictEqual(fixture.kind, 'binary', 'the binary fixture is not marked binary');
  const payload = Buffer.from(fixture.hex, 'hex');

  const frame = protocol.encodeBinary(payload);
  assert.strictEqual(
    frame.readInt32BE(0),
    1 + payload.length,
    'the frame length must count the kind byte and the payload'
  );
  assert.strictEqual(frame.readUInt8(4), protocol.KIND_BINARY, 'the kind byte must be binary');
  assert.strictEqual(frame.toString('hex'), '0000000501000102fa', 'the binary frame bytes changed');

  const decoded = protocol.decodeBinary(frame);
  assert.ok(decoded && decoded.equals(payload), 'the binary frame did not round trip');

  const jsonFrame = protocol.encode({ v: 2, type: 'ping', id: '1', ts: 1, payload: {} });
  assert.strictEqual(
    protocol.decodeBinary(jsonFrame),
    null,
    'a JSON frame was read as a binary frame'
  );
  assert.strictEqual(protocol.decode(jsonFrame).type, 'ping', 'a JSON frame stopped decoding');
}

/**
 * The four features' payloads, against the one fixture both implementations
 * read. The Kotlin side asserts the same fixture bytes in FeaturePayloadsTest,
 * so a key that is renamed on one side fails there as well as here.
 */
function checkFeaturePayloads() {
  const expected = JSON.parse(fs.readFileSync(FEATURE_FIXTURE, 'utf8'));

  for (const name of [
    'NOTIFICATION',
    'NOTIFICATION_REMOVED',
    'NOTIFICATION_ACTION',
    'MEDIA_STATE',
    'MEDIA_COMMAND',
    'FILE_LIST_REQUEST',
    'FILE_LIST',
    'MIRROR_START',
    'MIRROR_STOP',
  ]) {
    assert.ok(protocol.TYPES[name], `TYPES is missing ${name}`);
  }

  const notification = protocol.buildNotification({
    id: 'com.example:7:null',
    pkg: 'com.example',
    app: 'Example',
    title: 'Ada',
    text: 'hello',
    ts: 1700000000000,
    ongoing: false,
    actions: [
      { id: 'reply', title: 'Reply' },
      { id: 'archive', title: 'Archive' },
    ],
  });
  assert.deepStrictEqual(notification, expected.notification, 'the notification payload changed');
  assert.deepStrictEqual(
    protocol.parseNotification(expected.notification).actions,
    expected.notification.actions,
    'the action order did not survive the parser'
  );
  assert.strictEqual(
    protocol.parseNotification({ pkg: 'com.example' }),
    null,
    'a notification without an id parsed'
  );

  const mediaState = protocol.buildMediaState({
    pkg: 'com.example.music',
    app: 'Music',
    title: 'Song',
    artist: 'Band',
    album: 'Album',
    playing: true,
    positionMs: 1000,
    durationMs: 180000,
  });
  assert.deepStrictEqual(mediaState, expected.mediaState, 'the media state payload changed');
  assert.strictEqual(protocol.parseMediaState(mediaState).playing, true, 'playing did not survive');
  assert.strictEqual(
    protocol.parseMediaState(mediaState).pkg,
    'com.example.music',
    'the player identity did not survive the parser'
  );

  // "There is no cover for this track" is not the same as "this state did not
  // carry one": without the distinction the desktop blanks artwork that is
  // still correct.
  const cleared = protocol.buildMediaState({
    pkg: 'com.example.music',
    app: 'Music',
    title: 'Song',
    playing: true,
    artCleared: true,
  });
  assert.strictEqual(cleared.artCleared, true, 'a track without a cover did not say so');
  assert.strictEqual(
    protocol.parseMediaState(cleared).artCleared,
    true,
    'the "no cover" flag did not survive the parser'
  );
  assert.strictEqual(protocol.buildMediaState({ app: 'x', title: 'y' }).artCleared, undefined,
    'a state with no flag invented one');

  // One player ending is its own message, so it cannot withdraw the others.
  assert.deepStrictEqual(
    protocol.buildMediaOff('com.example.music'),
    { type: protocol.TYPES.MEDIA_OFF, pkg: 'com.example.music', side: 'phone' },
    'the media-off payload changed'
  );
  assert.strictEqual(protocol.parseMediaOff({ pkg: '' }), null, 'an empty package parsed as a player');

  // Screen mirroring: the request, the two outcomes, and the video frame
  // header that both ends read. A drift here shows up as a blank canvas.
  const mirrorStart = protocol.buildMirrorStart({
    videoPort: 24444,
    maxLongSide: 1280,
    fps: 30,
    bitrate: 4000000,
    keyframeSeconds: 1,
  });
  assert.deepStrictEqual(mirrorStart, expected.mirrorStart, 'the mirror request changed');
  assert.strictEqual(
    protocol.parseMirrorStart(mirrorStart).videoPort,
    24444,
    'the video port did not survive the parser'
  );
  assert.strictEqual(
    protocol.parseMirrorStart({ type: 'mirror-start' }),
    null,
    'a mirror request without a port parsed'
  );

  assert.deepStrictEqual(
    protocol.buildMirrorStarted({ width: 582, height: 1280, encoder: 'c2.mtk.avc.encoder' }),
    expected.mirrorStarted,
    'the mirror started payload changed'
  );
  assert.deepStrictEqual(
    protocol.buildMirrorStopped('requested'),
    expected.mirrorStopped,
    'the mirror stopped payload changed'
  );
  assert.deepStrictEqual(
    protocol.buildMirrorFailed('no-encoder'),
    expected.mirrorFailed,
    'the mirror failed payload changed'
  );

  // The 10-byte video header, and the refusal of anything that is not one.
  const videoFrame = protocol.buildVideoFrame({
    keyframe: true,
    ptsMicros: 1234567,
    payload: Buffer.from([0, 0, 0, 1, 0x65]),
  });
  assert.strictEqual(videoFrame.length, protocol.VIDEO_HEADER_BYTES + 5, 'the header size changed');
  const parsedFrame = protocol.parseVideoFrame(videoFrame);
  assert.strictEqual(parsedFrame.keyframe, true, 'the keyframe flag was lost');
  assert.strictEqual(parsedFrame.ptsMicros, 1234567, 'the timestamp was lost');
  assert.strictEqual(protocol.parseVideoFrame(Buffer.alloc(3)), null, 'a short frame parsed');
  const otherStream = Buffer.from(videoFrame);
  otherStream[0] = 2;
  assert.strictEqual(protocol.parseVideoFrame(otherStream), null, 'a non-video stream parsed');

  const fileList = protocol.buildFileList('root', [
    { id: 'doc:pictures', name: 'Pictures', size: 0, isDir: true, mime: '' },
    { id: 'doc:photo', name: 'photo.jpg', size: 1024, isDir: false, mime: 'image/jpeg' },
  ]);
  assert.deepStrictEqual(fileList, expected.fileList, 'the file list payload changed');
  assert.strictEqual(
    protocol.parseFileList(fileList).entries[1].name,
    'photo.jpg',
    'the file entries did not survive the parser'
  );
  assert.strictEqual(
    protocol.parseFileList(fileList).entries[1].id,
    'doc:photo',
    'the document id did not survive the parser, so nothing can be opened'
  );
}

/**
 * The envelope version must always come from PROTOCOL_VERSION.
 *
 * A literal `version: 1` left behind when the constant moved to 2 makes the
 * phone refuse the computer's hello-ack with "protocol-version", which looks
 * exactly like an unreachable computer. This caught five of them.
 */
function checkNoHardcodedVersion() {
  const offenders = [];
  for (const dir of ['services', 'scripts']) {
    const full = path.join(__dirname, '..', dir);
    for (const name of fs.readdirSync(full)) {
      if (!name.endsWith('.js')) continue;
      // This file necessarily contains the pattern it looks for.
      if (name === 'protocol-check.js') continue;
      const text = fs.readFileSync(path.join(full, name), 'utf8');
      text.split('\n').forEach((line, index) => {
        if (/version:\s*\d/.test(line)) offenders.push(`${dir}/${name}:${index + 1}`);
      });
    }
  }
  assert.deepStrictEqual(
    offenders,
    [],
    `the envelope version must come from PROTOCOL_VERSION, not a literal: ${offenders.join(', ')}`
  );
}

function checkProofRules() {
  // The same vector the Kotlin side asserts, so a divergence between the two
  // implementations is a test failure rather than a pairing that mysteriously
  // fails on one phone.
  const secret = Buffer.from('s3cr3t-secret', 'utf8');
  const proof = protocol.computeProof(secret, 'phone-1', 'nonce-1');

  assert.strictEqual(
    proof,
    '67f2e8753992a2b530ecd2f2359f2ead1d231e93859227ceaadf086d4f91050d',
    'the proof is not the expected hmac'
  );
  assert.strictEqual(protocol.verifyProof(secret, 'phone-1', 'nonce-1', proof), true, 'a valid proof was refused');
  assert.strictEqual(
    protocol.verifyProof(Buffer.from('other', 'utf8'), 'phone-1', 'nonce-1', proof),
    false,
    'a proof verified against the wrong secret'
  );
  assert.strictEqual(
    protocol.verifyProof(secret, 'phone-1', 'nonce-2', proof),
    false,
    'a proof verified against the wrong nonce'
  );
  assert.strictEqual(
    protocol.verifyProof(secret, 'phone-2', 'nonce-1', proof),
    false,
    'a proof verified against the wrong device id'
  );
}

function checkPairingPayload() {
  const info = {
    pcName: 'Studio PC',
    host: '192.168.1.39',
    port: 24443,
    code: '123456',
    fingerprint: 'AA:BB:CC',
    pcId: 'pc-1',
  };

  const text = JSON.stringify(protocol.buildPairingPayload(info));
  const parsed = protocol.parsePairingPayload(text);

  assert.deepStrictEqual(parsed, info, 'the pairing payload did not round trip');
  assert.strictEqual(protocol.parsePairingPayload('hello'), null, 'a foreign payload was accepted');
  assert.strictEqual(
    protocol.parsePairingPayload(JSON.stringify({ v: 1, type: 'aplus-pairing', host: '1.2.3.4', port: 24443 })),
    null,
    'a payload without a code was accepted'
  );
}

/**
 * The phone's file browser, from the desktop's side.
 *
 * The listing must survive a real frame - not just the builder - because the
 * frame is what crosses the wire; and a request must carry the id in the same
 * `path` key the answer echoes, or the desktop sends back a value the phone
 * does not understand and silently gets the root folder.
 *
 * Ids are paths relative to the phone's storage root, because that is what the
 * browser opens: `Download/Pictures`, not an opaque document id.
 */
function checkFileBrowser() {
  const list = protocol.buildFileList('Download', [
    { id: 'Download/Pictures', name: 'Pictures', size: 0, isDir: true, mime: '' },
    { id: 'Download/photo.jpg', name: 'photo.jpg', size: 1024, isDir: false, mime: 'image/jpeg' },
  ]);

  const frame = protocol.encode({
    version: protocol.PROTOCOL_VERSION,
    type: protocol.TYPES.FILE_LIST,
    id: 'frame-1',
    ts: 1,
    payload: list,
  });
  const parsed = protocol.parseFileList(protocol.decode(frame).payload);
  assert.ok(parsed, 'the file list did not survive the frame');
  assert.strictEqual(parsed.path, 'Download', 'the folder path did not survive the frame');
  assert.strictEqual(parsed.entries[0].name, 'Pictures', 'the first entry changed');
  assert.strictEqual(parsed.entries[0].isDir, true, 'a folder was read as a file');
  assert.strictEqual(parsed.entries[1].isDir, false, 'a file was read as a folder');
  assert.strictEqual(parsed.entries[0].id, 'Download/Pictures', 'a folder lost its path');
  assert.strictEqual(parsed.entries[1].id, 'Download/photo.jpg', 'a file lost its path');

  // The same ordering rule the phone applies, so a list that arrives out of
  // order is still drawn with the folders on top.
  const sorted = protocol.sortFileEntries([
    { name: 'zeta.txt', size: 10, isDir: false, mime: 'text/plain' },
    { name: 'Music', size: 0, isDir: true, mime: '' },
    { name: 'alpha.txt', size: 20, isDir: false, mime: 'text/plain' },
    { name: 'Download', size: 0, isDir: true, mime: '' },
  ]);
  assert.deepStrictEqual(
    sorted.map((e) => e.name),
    ['Download', 'Music', 'alpha.txt', 'zeta.txt'],
    'folders must sort before files, each by name'
  );

  // A payload without entries is not a listing, and must not be drawn as an
  // empty folder: that would hide a protocol mistake.
  assert.strictEqual(protocol.parseFileList({ path: 'x' }), null, 'a list without entries parsed');

  const listRequest = protocol.buildFileRequest(protocol.TYPES.FILE_LIST_REQUEST, 'Download');
  assert.strictEqual(listRequest.path, 'Download', 'the list request lost the folder path');
  assert.strictEqual(
    listRequest.type,
    protocol.TYPES.FILE_LIST_REQUEST,
    'the list request lost its type'
  );

  const sendRequest = protocol.buildFileRequest(protocol.TYPES.FILE_SEND_REQUEST, 'Download/a.pdf');
  assert.strictEqual(sendRequest.path, 'Download/a.pdf', 'the send request lost the file path');
  assert.strictEqual(
    sendRequest.type,
    protocol.TYPES.FILE_SEND_REQUEST,
    'the send request lost its type'
  );

  // The root is the empty string, and must be sent as one rather than dropped.
  assert.strictEqual(protocol.buildFileRequest(protocol.TYPES.FILE_LIST_REQUEST, '').path, '', 'the root request lost its path');

  // A search is its own request, and its answer is an ordinary file list keyed
  // by the query. Both sides must spell that key the same way, or a search
  // answer resolves nothing and the desktop spins for ten seconds.
  const searchRequest = protocol.buildFileSearchRequest('dune');
  assert.strictEqual(
    searchRequest.type,
    protocol.TYPES.FILE_SEARCH_REQUEST,
    'the search request lost its type'
  );
  assert.strictEqual(searchRequest.query, 'dune', 'the search request lost the query');
  // Refresh asks for a walk; typing must not. The phone reads this flag, so its
  // absence would silently mean "never re-walk".
  assert.strictEqual(searchRequest.fresh, false, 'a typed search asked for a storage walk');
  assert.strictEqual(
    protocol.buildFileSearchRequest('dune', true).fresh,
    true,
    'a refreshed search did not ask for a storage walk'
  );
  assert.strictEqual(protocol.SEARCH_KEY_PREFIX, 'search:', 'the search key prefix changed');

  const searchAnswer = protocol.buildFileList(protocol.searchKey('dune'), [
    { id: 'Download/books/Dune.epub', name: 'Dune.epub', size: 42, isDir: false, mime: 'application/epub+zip' },
  ]);
  const parsedSearch = protocol.parseFileList(
    protocol.decode(
      protocol.encode({
        version: protocol.PROTOCOL_VERSION,
        type: protocol.TYPES.FILE_LIST,
        id: 'frame-2',
        ts: 1,
        payload: searchAnswer,
      })
    ).payload
  );
  assert.strictEqual(parsedSearch.path, 'search:dune', 'a search answer is not keyed by its query');
  assert.strictEqual(
    parsedSearch.entries[0].id,
    'Download/books/Dune.epub',
    'a search result lost the path that opens it'
  );
}

function main() {
  const write = process.argv.includes('--write');

  // The canonical bytes are regenerated before the check, so a fixture whose
  // json was edited but whose hex was not gets repaired rather than failing.
  if (write) writeCanonicalFixture();

  const frames = readFrames(KOTLIN_FIXTURE);
  assert.ok(frames.length > 0, 'the fixture is empty');

  for (const frame of frames) checkFrame(frame);
  checkClipboardRules();
  checkBinaryFrames();
  checkFeaturePayloads();
  checkFileBrowser();
  checkNoHardcodedVersion();
  checkProofRules();
  checkPairingPayload();

  if (write) writeNodeFixture();

  console.log(`protocol-check: ${frames.length} frames decoded, round tripped and matched`);
  console.log('protocol-check: frames matched');
}

main();
