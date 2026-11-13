#!/usr/bin/env node
/**
 * The companion channel's playback control.
 *
 * A `media-state` message must become a `media-state` event with the fields the
 * card renders, and asking the desktop to send a command must put a
 * `media-command` frame on the wire - with the command name, not a guess.
 *
 * node scripts/check-companion-media.js
 */
'use strict';

const assert = require('assert');
const { EventEmitter } = require('events');

const protocol = require('../services/companion-protocol');
const CompanionService = require('../services/companion');

function run() {
  const service = new EventEmitter();
  const written = [];
  service.clients = [
    {
      handshakeDone: true,
      destroyed: false,
      clientIp: '127.0.0.1',
      write: (buf) => written.push(Buffer.from(buf)),
    },
  ];

  const states = [];
  service.on('media-state', (s) => states.push(s));

  if (CompanionService.routeMedia) {
    CompanionService.routeMedia(service, {
      type: protocol.TYPES.MEDIA_STATE,
      id: 'm1',
      ts: Date.now(),
      payload: protocol.buildMediaState({
        app: 'Music',
        title: 'Song',
        artist: 'Band',
        album: 'Album',
        playing: true,
        positionMs: 1000,
        durationMs: 180000,
      }),
    });
  }

  assert.strictEqual(states.length, 1, 'no media-state event');
  assert.strictEqual(states[0].title, 'Song', 'the title did not survive');
  assert.strictEqual(states[0].playing, true, 'the playing flag did not survive');
  assert.ok(!states[0].idle, 'a playing track was marked idle');

  // "Nothing is playing any more" is its own state, not an empty track: the
  // desktop answers it by withdrawing the player. Without it the shell kept
  // the last track - cover, clock and all - after the phone stopped playing.
  if (CompanionService.routeMedia) {
    CompanionService.routeMedia(service, {
      type: protocol.TYPES.MEDIA_STATE,
      id: 'm2',
      ts: Date.now(),
      payload: protocol.buildMediaState({ idle: true, title: '', playing: false }),
    });
  }
  assert.strictEqual(states.length, 2, 'the idle state did not arrive');
  assert.strictEqual(states[1].idle, true, 'the idle flag did not survive the wire');

  const sent = CompanionService.prototype.sendMediaCommand.call(service, 'next');
  assert.strictEqual(sent, true, 'the command was not sent to the phone');
  assert.strictEqual(written.length, 1, 'no media-command frame was written');

  const frame = protocol.decode(written[0]);
  assert.strictEqual(frame.type, protocol.TYPES.MEDIA_COMMAND, 'the frame is not a media-command');
  assert.strictEqual(frame.payload.command, 'next', 'the command name did not survive');

  // A seek carries its position; a plain command does not pretend to have one.
  written.length = 0;
  CompanionService.prototype.sendMediaCommand.call(service, 'seek', 42000);
  const seek = protocol.decode(written[0]);
  assert.strictEqual(seek.payload.positionMs, 42000, 'the seek position did not survive');

  console.log('companion media: ok');
}

run();