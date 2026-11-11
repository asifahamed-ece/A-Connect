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

function main() {
  const write = process.argv.includes('--write');

  // The canonical bytes are regenerated before the check, so a fixture whose
  // json was edited but whose hex was not gets repaired rather than failing.
  if (write) writeCanonicalFixture();

  const frames = readFrames(KOTLIN_FIXTURE);
  assert.ok(frames.length > 0, 'the fixture is empty');

  for (const frame of frames) checkFrame(frame);
  checkClipboardRules();

  if (write) writeNodeFixture();

  console.log(`protocol-check: ${frames.length} frames decoded, round tripped and matched`);
}

main();
