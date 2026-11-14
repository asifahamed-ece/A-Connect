#!/usr/bin/env node
/**
 * One phone, one session.
 *
 * A phone that redials - a dropped Wi-Fi link, a retry after a timeout - used to
 * leave the previous socket open. The desktop then held two or three sessions
 * for one device, so every broadcast went out once per session and the phone
 * answered once per session. Three live sockets from one phone were observed on
 * a real link, which is what this file exists to stop.
 *
 * The retired socket must not report a disconnect either: main.js stops
 * mirroring and clears MPRIS on `client-disconnected`, and the phone that just
 * reconnected is still there.
 *
 * There is no test runner in this repo: node scripts/check-companion-sessions.js
 */
'use strict';

const assert = require('assert');
const { EventEmitter } = require('events');

const CompanionService = require('../services/companion');

/** A service object with the real prototype, minus a listening server. */
function serviceWith(clients = []) {
  const service = new EventEmitter();
  Object.setPrototypeOf(service, CompanionService.prototype);
  service.clients = new Set(clients);
  service.pairedDevices = {};
  service.paired = [];
  service.failedLists = 0;
  service.disconnects = [];
  service.on('client-paired', (device) => service.paired.push(device));
  service.on('client-disconnected', (data) => service.disconnects.push(data));
  service.updateStatus = () => {};
  service.failPendingFileLists = () => { service.failedLists += 1; };
  return service;
}

/** A socket as handleConnection expects it: events, write, destroy. */
function connection(deviceId, deviceName = 'Phone') {
  const socket = new EventEmitter();
  socket.remoteAddress = '::ffff:192.168.1.38';
  socket.destroyed = false;
  socket.write = () => true;
  socket.end = () => {};
  socket.destroy = () => {
    if (socket.destroyed) return;
    socket.destroyed = true;
    socket.emit('close');
  };
  socket.handshakeDone = true;
  socket.deviceId = deviceId;
  socket.deviceName = deviceName;
  return socket;
}

function check(name, fn) {
  fn();
  console.log(`  ok  ${name}`);
}

console.log('companion sessions:');

check('a redial retires every older session for the same device', () => {
  const old1 = connection('dev-1');
  const old2 = connection('dev-1');
  const old3 = connection('dev-1');
  const service = serviceWith([old1, old2, old3]);

  const newest = connection('dev-1');
  service.clients.add(newest);
  service.onPaired({ deviceId: 'dev-1', deviceName: 'Phone', isNew: false }, newest);

  assert.ok(old1.destroyed, 'first older session should be closed');
  assert.ok(old2.destroyed, 'second older session should be closed');
  assert.ok(old3.destroyed, 'third older session should be closed');
  assert.ok(!newest.destroyed, 'the session that just handshook must survive');
  assert.strictEqual(service.paired.length, 1, 'client-paired still fires once');
});

check('a retired session is marked superseded', () => {
  const old = connection('dev-1');
  const service = serviceWith([old]);
  const newest = connection('dev-1');
  service.clients.add(newest);
  service.onPaired({ deviceId: 'dev-1', deviceName: 'Phone' }, newest);
  assert.strictEqual(old.superseded, true, 'close handler keys off this flag');
});

check('another phone is left alone', () => {
  const mine = connection('dev-1');
  const theirs = connection('dev-2', 'Other phone');
  const service = serviceWith([mine, theirs]);

  const newest = connection('dev-1');
  service.clients.add(newest);
  service.onPaired({ deviceId: 'dev-1', deviceName: 'Phone' }, newest);

  assert.ok(mine.destroyed, 'the older session of the same device closes');
  assert.ok(!theirs.destroyed, 'a different device must not be touched');
});

check('a superseded close reports no disconnect', () => {
  const service = serviceWith();
  const socket = connection('dev-1');
  service.handleConnection(socket);
  // handleConnection starts every socket untrusted; this one finished its hello.
  socket.handshakeDone = true;

  socket.superseded = true;
  socket.destroy();

  assert.strictEqual(service.disconnects.length, 0, 'no client-disconnected');
  assert.strictEqual(service.failedLists, 0, 'pending file lists are not failed');
  assert.strictEqual(service.clients.has(socket), false, 'the socket is forgotten');
});

check('a real drop still reports a disconnect', () => {
  const service = serviceWith();
  const socket = connection('dev-1');
  service.handleConnection(socket);
  socket.handshakeDone = true;

  socket.destroy();

  assert.strictEqual(service.disconnects.length, 1, 'one client-disconnected');
  assert.strictEqual(service.disconnects[0].deviceId, 'dev-1');
  assert.strictEqual(service.failedLists, 1, 'pending file lists are failed');
});

check('a handshake with no device id retires nothing', () => {
  const other = connection('dev-1');
  const service = serviceWith([other]);
  const newest = connection(undefined);
  service.clients.add(newest);
  service.onPaired({ deviceId: undefined, deviceName: 'Phone' }, newest);
  assert.ok(!other.destroyed, 'an unknown device cannot claim an existing session');
});

console.log('companion sessions: ok');
