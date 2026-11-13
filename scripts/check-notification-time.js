#!/usr/bin/env node
/**
 * How a notification row says when it arrived.
 *
 * The notifications history is only useful if the user can tell what is new.
 * "2 min ago" for the recent ones, a clock for older ones - and a row that
 * arrived out of order (a phone delivers what it queued while the PC was away)
 * must still sort into the right place.
 *
 * There is no test runner in this repo: node scripts/check-notification-time.js
 */
'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const source = fs.readFileSync(path.join(__dirname, '..', 'src', 'format.js'), 'utf8');
const sandbox = {};
vm.runInNewContext(source, sandbox);

const { relativeTime, newestFirst, fullTime } = sandbox;

// A fixed "now" so the test does not depend on when it runs.
const now = new Date(2026, 10, 13, 15, 0, 0).getTime(); // 13 Nov 2026, 15:00 local
const at = (date) => date.getTime();
const secondsAgo = (n) => now - n * 1000;

function checkRelative() {
  // Nothing usable must draw nothing, not 1970.
  assert.strictEqual(relativeTime(null, now), '', 'null drew a time');
  assert.strictEqual(relativeTime(0, now), '', 'zero drew a time');
  assert.strictEqual(relativeTime('nonsense', now), '', 'garbage drew a time');

  assert.strictEqual(relativeTime(secondsAgo(5), now), 'just now', 'five seconds ago is not "just now"');
  assert.strictEqual(
    relativeTime(secondsAgo(40), now),
    'just now',
    'forty seconds ago already reads as minutes'
  );

  // A phone clock a few seconds ahead is normal, not the future.
  assert.strictEqual(relativeTime(now + 5000, now), 'just now', 'clock skew read as the future');

  assert.strictEqual(relativeTime(secondsAgo(90), now), '1 min ago', 'ninety seconds is not one minute');
  assert.strictEqual(relativeTime(secondsAgo(125), now), '2 min ago', 'two minutes was misfigured');
  assert.strictEqual(
    relativeTime(secondsAgo(59 * 60), now),
    '59 min ago',
    'an hour was reached too early'
  );

  // Past an hour: the clock, because "74 min ago" makes the reader do maths.
  const anHourAgo = new Date(2026, 10, 13, 13, 59, 0);
  assert.strictEqual(relativeTime(at(anHourAgo), now), '13:59', 'an older row lost its clock time');

  const yesterday = new Date(2026, 10, 12, 14, 5, 0);
  assert.strictEqual(relativeTime(at(yesterday), now), 'Yesterday 14:05', 'yesterday was not named');

  const threeDaysAgo = new Date(2026, 10, 10, 14, 5, 0);
  assert.strictEqual(relativeTime(at(threeDaysAgo), now), '10 Nov 14:05', 'an old row lost its date');

  const lastYear = new Date(2025, 11, 31, 23, 0, 0);
  assert.strictEqual(
    relativeTime(at(lastYear), now),
    '31 Dec 2025 23:00',
    'a previous year must be named, or two Decembers look alike'
  );

  // The tooltip carries the exact moment, in the reader's own locale. The
  // format is the platform's business, so only the year is asserted here.
  const exact = fullTime(at(yesterday));
  assert.ok(/2026/.test(exact), `the tooltip lost the moment: ${exact}`);
  assert.notStrictEqual(
    fullTime(at(yesterday)),
    fullTime(at(threeDaysAgo)),
    'two different moments produced the same tooltip'
  );
  assert.strictEqual(fullTime(null), '', 'a missing timestamp produced a tooltip');
}

function checkOrder() {
  const older = { id: 'older', timestamp: 1000 };
  const newer = { id: 'newer', timestamp: 3000 };
  const middle = { id: 'middle', timestamp: 2000 };

  // The arrays come from another realm (the vm sandbox), so they are compared
  // by value rather than by prototype identity.
  const sorted = newestFirst([older, newer, middle]);
  assert.strictEqual(
    sorted.map((n) => n.id).join(','),
    'newer,middle,older',
    'the history is not newest first'
  );

  // The phone queues what it could not send; it can arrive behind newer ones.
  const queued = { id: 'queued', timestamp: 500 };
  assert.strictEqual(
    newestFirst([older, newer, queued])[2].id,
    'queued',
    'a notification that arrived late but happened earlier was drawn as new'
  );

  // Equal timestamps keep arrival order, so an updated row does not jump.
  const first = { id: 'first', timestamp: 2000 };
  const second = { id: 'second', timestamp: 2000 };
  assert.strictEqual(
    newestFirst([first, second]).map((n) => n.id).join(','),
    'first,second',
    'equal timestamps were reordered'
  );

  // A record with no timestamp (an old ADB one) must not crash the sort.
  const undated = { id: 'undated' };
  assert.strictEqual(newestFirst([undated, newer])[1].id, 'undated', 'an undated row sorted first');
  assert.strictEqual(newestFirst([]).length, 0, 'an empty history broke');
  assert.strictEqual(newestFirst(null).length, 0, 'a missing history broke');
}

checkRelative();
checkOrder();
console.log('notification time: ok');