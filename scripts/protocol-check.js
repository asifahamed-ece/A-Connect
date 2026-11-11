#!/usr/bin/env node
/**
 * Cross-implementation conformance check for the companion protocol.
 *
 * Decodes every frame in the shared fixture (produced by the Kotlin side),
 * compares the values, round-trips each frame, and checks the clipboard
 * payload rules. With --write it also emits the fixture that the Kotlin side
 * reads back, which is what proves the two implementations agree in both
 * directions.
 *
 * Usage: node scripts/protocol-check.js [--write]
 */
'use strict';

const fs = require('fs');
const path = require('path');
const assert = require('assert');

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
}

function checkClipboardRules() {
  const long = 'x'.repeat(300000);
  const payload = protocol.buildClipboardPayload(long);
  assert.strictEqual(payload.text.length, protocol.MAX_CLIPBOARD_CHARS, 'clipboard was not truncated');
  assert.strictEqual(payload.truncated, true, 'truncation was not flagged');
  assert.strictEqual(
    payload.hash,
    protocol.buildClipboardPayload('x'.repeat(protocol.MAX_CLIPBOARD_CHARS)).hash,
    'hash does not cover the transmitted text'
  );

  const short = protocol.buildClipboardPayload('abc');
  assert.strictEqual(
    short.hash,
    'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad',
    'hash is not the sha256 of the text'
  );

  assert.strictEqual(protocol.shouldSendClipboard(short, null), true, 'first send was suppressed');
  assert.strictEqual(protocol.shouldSendClipboard(short, short.hash), false, 'repeat was not suppressed');
  assert.strictEqual(
    protocol.shouldSendClipboard(protocol.buildClipboardPayload(''), null),
    false,
    'empty clipboard was not suppressed'
  );
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
  const frames = readFrames(KOTLIN_FIXTURE);
  assert.ok(frames.length > 0, 'the fixture is empty');

  for (const frame of frames) checkFrame(frame);
  checkClipboardRules();

  if (process.argv.includes('--write')) writeNodeFixture();

  console.log(`protocol-check: ${frames.length} frames decoded, round tripped and matched`);
}

main();
