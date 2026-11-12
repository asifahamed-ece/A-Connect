#!/usr/bin/env node
/**
 * The MPRIS mapping, without a session bus.
 *
 * The player interface itself is verified against a real bus with `playerctl`
 * (see the verification notes); what is easy to get wrong and hard to see is
 * the metadata dictionary - MPRIS wants microseconds, a list for the artist,
 * and a track id object path.
 *
 * node scripts/check-mpris.js
 */
'use strict';

const assert = require('assert');
const { toMetadata, playbackStatus } = require('../services/mpris');

const state = {
  app: 'Music',
  title: 'Song',
  artist: 'Band',
  album: 'Album',
  playing: true,
  positionMs: 1000,
  durationMs: 180000,
};

const metadata = toMetadata(state);

assert.strictEqual(metadata['mpris:trackid'].signature, 'o', 'the track id must be an object path');
assert.strictEqual(metadata['xesam:title'].value, 'Song', 'the title did not survive');
assert.deepStrictEqual(metadata['xesam:artist'].value, ['Band'], 'the artist must be a list');
assert.strictEqual(metadata['xesam:album'].value, 'Album', 'the album did not survive');
assert.strictEqual(
  metadata['mpris:length'].value,
  180000000n,
  'the length must be microseconds as a 64 bit integer'
);

assert.strictEqual(playbackStatus(state), 'Playing', 'a playing state was not Playing');
assert.strictEqual(
  playbackStatus({ ...state, playing: false }),
  'Paused',
  'a paused state was not Paused'
);
assert.strictEqual(playbackStatus(null), 'Stopped', 'no state must be Stopped');

// Nothing playing: only the track id, so a shell shows an empty player rather
// than stale text.
const empty = toMetadata(null);
assert.strictEqual(empty['xesam:title'], undefined, 'an empty state must carry no title');

console.log('mpris mapping: ok');