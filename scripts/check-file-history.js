#!/usr/bin/env node
/**
 * The file history and the QR button, without a window.
 *
 * Rules checked here (step 5):
 *  - a finished send lands in History as "sent", a finished receive as
 *    "received"; running, failed, cancelled and view-only fetches do not;
 *  - the Files page keeps only live transfers (running or failed), so a
 *    finished file is listed once - in History;
 *  - the history keeps at most HISTORY_MAX rows, newest first, and a transfer
 *    reported twice is one row;
 *  - the pairing QR button is one button: "Hide QR" while the code shows,
 *    "Show QR" while it does not.
 *
 * There is no test runner in this repo: node scripts/check-file-history.js
 */
'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const sandbox = {};
for (const file of ['format.js', 'history.js']) {
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '..', 'src', file), 'utf8'), sandbox);
}
const {
  HISTORY_MAX,
  historyEntryFromTransfer,
  addHistoryEntry,
  historyCounts,
  historyFor,
  parseHistory,
  isLiveTransfer,
  qrToggleLabel,
} = sandbox;

const NOW = 1_763_140_000_000;

// Direction.
const sent = historyEntryFromTransfer(
  { id: 'a', status: 'done', type: 'push', filename: 'report.pdf', size: 2048, localFilePath: '/home/me/report.pdf', targetPath: '/sdcard/Download/report.pdf' },
  NOW,
);
assert.strictEqual(sent.direction, 'sent');
assert.strictEqual(sent.localPath, '/home/me/report.pdf');
assert.strictEqual(sent.where, '/sdcard/Download/report.pdf');

const received = historyEntryFromTransfer(
  { id: 'b', status: 'done', type: 'pull', filename: 'IMG_0001.jpg', targetPath: '/home/me/Downloads/A+Connect/IMG_0001.jpg' },
  NOW,
);
assert.strictEqual(received.direction, 'received');
assert.strictEqual(received.localPath, '/home/me/Downloads/A+Connect/IMG_0001.jpg');
assert.strictEqual(received.where, '/home/me/Downloads/A+Connect');

// Not history.
for (const t of [
  { id: 'c', status: 'active', type: 'push', filename: 'x' },
  { id: 'd', status: 'failed', type: 'push', filename: 'x' },
  { id: 'e', status: 'cancelled', type: 'pull', filename: 'x' },
  { id: 'f', status: 'done', type: 'view', filename: 'peek.png' },
  { id: 'g', status: 'done', type: 'push', filename: '   ' },
]) {
  assert.strictEqual(historyEntryFromTransfer(t, NOW), null, `${t.id} must not be history`);
}

// Files page shows live transfers only.
assert.strictEqual(isLiveTransfer({ status: 'active' }), true);
assert.strictEqual(isLiveTransfer({ status: 'failed' }), true);
assert.strictEqual(isLiveTransfer({ status: 'done' }), false);
assert.strictEqual(isLiveTransfer({ status: 'cancelled' }), false);

// Newest first, one row per transfer, capped.
let list = [];
list = addHistoryEntry(list, sent);
list = addHistoryEntry(list, received);
list = addHistoryEntry(list, { ...sent, at: NOW + 5 });
assert.strictEqual(list.length, 2);
assert.strictEqual(list[0].id, 'a');
assert.deepStrictEqual({ ...historyCounts(list) }, { sent: 1, received: 1 });
assert.strictEqual(historyFor(list, 'received')[0].name, 'IMG_0001.jpg');

let big = [];
for (let i = 0; i < HISTORY_MAX + 25; i++) {
  big = addHistoryEntry(big, { id: `r${i}`, name: `f${i}`, direction: 'received', at: NOW + i });
}
assert.strictEqual(HISTORY_MAX, 200);
assert.strictEqual(big.length, 200);
assert.strictEqual(big[0].id, `r${HISTORY_MAX + 24}`);

// Stored data that is not a list does not break the page.
assert.strictEqual(parseHistory('not json').length, 0);
assert.strictEqual(parseHistory('{"a":1}').length, 0);
assert.strictEqual(parseHistory(JSON.stringify(list)).length, 2);

// The QR button names what it will do.
assert.strictEqual(qrToggleLabel(true), 'Hide QR');
assert.strictEqual(qrToggleLabel(false), 'Show QR');

console.log('file history and QR toggle: all checks passed');
