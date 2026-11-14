#!/usr/bin/env node
/**
 * The preview cache: hit, miss, the refreshed clock, and the sweep.
 *
 * The whole point is that looking at the same file twice pulls it once. That
 * depends on four things being true, and each one is a case here:
 *
 *   - a miss downloads and is recorded;
 *   - a hit opens the cached copy without touching the phone;
 *   - opening a cached file again resets its clock, so it is not deleted on
 *     its original birthday;
 *   - a file the user deleted or edited on disk is a miss again, not a broken hit.
 *
 * node scripts/check-preview-cache.js
 */
'use strict';

const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { EventEmitter } = require('events');

const protocol = require('../services/companion-protocol');
const CompanionService = require('../services/companion');
const { PreviewCache, sha256File } = require('../services/preview-cache');

function tempDir() {
  return fs.mkdtempSync(path.join(os.tmpdir(), 'aplus-preview-'));
}

/** A service wired to a private cache, with one connected phone. */
function serviceWithCache() {
  const service = new EventEmitter();
  const written = [];
  service.clients = [
    {
      handshakeDone: true,
      destroyed: false,
      deviceName: 'Test Phone',
      clientIp: '127.0.0.1',
      write: (buf) => written.push(Buffer.from(buf)),
    },
  ];
  service.pendingFileLists = new Map();
  service.fileRequests = new Map();
  service.pendingHashes = new Map();
  service.previewCache = new PreviewCache({ dir: tempDir() });
  service.written = written;
  // routeFileHash calls back into the service's own methods, so the fake needs
  // the real ones rather than throwing "not a function".
  service.requestFileSend = CompanionService.prototype.requestFileSend;
  service.requestPreviewHash = CompanionService.prototype.requestPreviewHash;
  service.handleFileStart = CompanionService.prototype.handleFileStart;
  service.handleFileChunk = CompanionService.prototype.handleFileChunk;
  service.handleFileDone = CompanionService.prototype.handleFileDone;
  return service;
}

/** The last frame the service put on the wire, decoded. */
function lastFrame(service) {
  return protocol.decode(service.written[service.written.length - 1]);
}

/** Waits for the async stream flush that follows a file-done. */
function settle(ms = 60) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function checkMissThenHit() {
  const service = serviceWithCache();
  const dir = service.previewCache.dir;
  const sha = '3a6eb0790f39ac87c94f3856b2dd2c5d110e6811602261a9a923d3bb23adc8b7';

  // --- a miss: the hash comes back, nothing cached, so a download is asked for
  const preview = CompanionService.prototype.requestPreviewHash.call(service, 'doc:trace');
  assert.strictEqual(preview.success, true, 'the hash request was not sent');
  const hashFrame = lastFrame(service);
  assert.strictEqual(hashFrame.type, protocol.TYPES.FILE_HASH_REQUEST, 'wrong frame type');
  assert.strictEqual(hashFrame.payload.path, 'doc:trace', 'the path was lost');

  const hashId = hashFrame.payload.id;
  assert.ok(hashId, 'the hash request went out with no id to match the answer to');

  CompanionService.routeFileHash(service, {
    type: protocol.TYPES.FILE_HASH,
    id: 'h1',
    ts: Date.now(),
    payload: protocol.buildFileHash('doc:trace', sha, 4096, undefined, hashId),
  });

  const sendFrame = lastFrame(service);
  assert.strictEqual(sendFrame.type, protocol.TYPES.FILE_SEND_REQUEST,
    'a cache miss did not fall through to a download');
  assert.strictEqual(sendFrame.payload.path, 'doc:trace', 'the download asked for the wrong path');

  // --- the download lands: it goes into the cache, under the hash
  const requestId = sendFrame.payload.id;
  const socket = service.clients[0];
  socket.activeTransfers = new Map();
  CompanionService.prototype.handleFileStart.call(service, socket, {
    id: requestId,
    name: 'trace.txt',
    size: 4,
  });
  const transfer = socket.activeTransfers.get(requestId);
  assert.ok(
    transfer.targetPath.startsWith(dir),
    'the preview was not written inside its private cache directory'
  );
  CompanionService.prototype.handleFileChunk.call(service, socket, {
    id: requestId,
    chunkIndex: 0,
    data: Buffer.from('data').toString('base64'),
  });
  CompanionService.prototype.handleFileDone.call(service, socket, { id: requestId });
  await settle();

  const contentPath = path.join(dir, '3a6eb0790f39ac87c94f3856b2dd2c5d110e6811602261a9a923d3bb23adc8b7', 'trace.txt');
  assert.ok(fs.existsSync(contentPath),
    'the verified preview was not moved under the SHA256 of its received bytes');
  assert.strictEqual(sha256File(contentPath), '3a6eb0790f39ac87c94f3856b2dd2c5d110e6811602261a9a923d3bb23adc8b7',
    'the cache directory does not match the hash of the file it contains');
  assert.strictEqual(service.previewCache.size(), 1, 'the finished preview was not recorded');

  // --- a hit: the same hash comes back, and no download is asked for
  const before = service.written.length;
  const again = CompanionService.prototype.requestPreviewHash.call(service, 'doc:trace');
  assert.strictEqual(again.success, true, 'the second hash request was not sent');
  assert.strictEqual(service.written.length, before + 1, 'a hit sent more than the hash request');
  const secondHashId = lastFrame(service).payload.id;

  const done = [];
  service.on('file-transfer-done', (d) => done.push(d));
  CompanionService.routeFileHash(service, {
    type: protocol.TYPES.FILE_HASH,
    id: 'h2',
    ts: Date.now(),
    payload: protocol.buildFileHash('doc:trace', sha, 4096, undefined, secondHashId),
  });

  assert.strictEqual(done.length, 1, 'a cache hit did not report a finished transfer');
  assert.strictEqual(done[0].cached, true, 'a cache hit was not marked as cached');
  assert.strictEqual(done[0].forViewing, true, 'a cache hit would not be opened');
  assert.strictEqual(done[0].name, 'trace.txt', 'the cached file lost its name');
  assert.ok(fs.existsSync(done[0].path), 'the cached file the hit pointed at is not there');
  // Crucially, no second download was requested.
  const types = service.written.map((b) => protocol.decode(b).type);
  assert.strictEqual(
    types.filter((t) => t === protocol.TYPES.FILE_SEND_REQUEST).length,
    1,
    'a cache hit pulled the file across the network again'
  );
}

function checkAccessRefreshesTheClock() {
  const service = serviceWithCache();
  const sha = 'c35b21d6ca39aa7cc3b79a705d989f1a6e88b99ab43988d74048799e3db926a3';
  const name = 'look.pdf';

  const reserved = service.previewCache.reserve(sha, name);
  fs.mkdirSync(path.dirname(reserved.targetPath), { recursive: true });
  fs.writeFileSync(reserved.targetPath, 'pdf');
  service.previewCache.commit(sha, { name, size: 3, phonePath: 'doc:look' });

  // Pretend it was created and last seen on the eve of the window closing.
  const entries = service.previewCache.load();
  const old = Date.now() - 29 * 24 * 60 * 60 * 1000;
  entries[sha].createdAt = old;
  entries[sha].accessedAt = old;
  service.previewCache.save();

  // A sweep with a 30-day window must keep it: 29 days is inside.
  assert.strictEqual(service.previewCache.sweep(30).length, 0, 'a 29-day-old preview was swept');

  // Opening it now resets the clock, so its old birthday cannot evict it.
  const hit = service.previewCache.lookup(sha);
  assert.ok(hit, 'the cached file was not found');
  assert.ok(hit.accessedAt > old, 'looking at a cached file did not refresh its clock');

  // With the clock refreshed, a much tighter window still keeps it.
  assert.strictEqual(
    service.previewCache.sweep(7).length,
    0,
    'a re-accessed preview was evicted on its original birthday'
  );
}

function checkSweepRemovesStale() {
  const service = serviceWithCache();
  const sha = '2d711642b726b04401627ca9fbac32f5c8530fb1903cc4db02258717921a4881';
  const reserved = service.previewCache.reserve(sha, 'stale.bin');
  fs.mkdirSync(path.dirname(reserved.targetPath), { recursive: true });
  fs.writeFileSync(reserved.targetPath, 'x');
  service.previewCache.commit(sha, { name: 'stale.bin', size: 1, phonePath: 'doc:stale' });

  const entries = service.previewCache.load();
  entries[sha].accessedAt = Date.now() - 40 * 24 * 60 * 60 * 1000;
  entries[sha].createdAt = entries[sha].accessedAt;
  service.previewCache.save();

  const removed = service.previewCache.sweep(30);
  assert.strictEqual(removed.length, 1, 'a 40-day-old preview survived the sweep');
  assert.strictEqual(removed[0].name, 'stale.bin', 'the wrong file was reported');
  assert.strictEqual(service.previewCache.size(), 0, 'the index still holds the swept entry');
  assert.ok(!fs.existsSync(path.dirname(reserved.targetPath)), 'the swept file is still on disk');
}

function checkModifiedCacheFileIsAMiss() {
  const service = serviceWithCache();
  const sha = '52c3935626c104b2cbc9031291a1c4d56614c38f52072a361d658a58a9c48698';
  const reserved = service.previewCache.reserve(sha, 'edited.txt');
  fs.mkdirSync(path.dirname(reserved.targetPath), { recursive: true });
  fs.writeFileSync(reserved.targetPath, 'original bytes');
  service.previewCache.commit(sha, { name: 'edited.txt', size: 14, phonePath: 'doc:edited' });

  fs.writeFileSync(reserved.targetPath, 'user edited this');
  assert.strictEqual(service.previewCache.lookup(sha), null,
    'a cache file changed since it was indexed was reported as a hit');
}

function checkDeletedFileIsAMiss() {
  const service = serviceWithCache();
  const sha = 'a1fce4363854ff888cff4b8e7875d600c2682390412a8cf79b37d0b11148b0fa';
  const reserved = service.previewCache.reserve(sha, 'gone.txt');
  fs.mkdirSync(path.dirname(reserved.targetPath), { recursive: true });
  fs.writeFileSync(reserved.targetPath, 'y');
  service.previewCache.commit(sha, { name: 'gone.txt', size: 1, phonePath: 'doc:gone' });

  // The user removed it from their file manager: the index must not pretend.
  fs.rmSync(path.dirname(reserved.targetPath), { recursive: true, force: true });
  assert.strictEqual(service.previewCache.lookup(sha), null, 'a deleted file was reported as a hit');
  assert.strictEqual(service.previewCache.size(), 0, 'the stale index entry was left behind');
}

function checkInvalidHashCannotEscapeCache() {
  const outside = path.join(os.tmpdir(), `aplus-outside-${process.pid}`);
  const cache = new PreviewCache({ dir: path.join(outside, 'cache') });
  assert.throws(
    () => cache.reserve('../escaped', 'attack.txt'),
    /invalid SHA256/i,
    'a malformed phone hash must not choose a path outside the cache'
  );
  assert.strictEqual(fs.existsSync(outside), false, 'reserving an invalid hash created an outside path');

  fs.mkdirSync(cache.dir, { recursive: true });
  const sentinel = path.join(outside, 'keep.txt');
  fs.writeFileSync(sentinel, 'must remain outside the cache');
  fs.writeFileSync(cache.indexFile, JSON.stringify({
    entries: {
      ['f'.repeat(64)]: {
        name: 'attack.txt',
        path: sentinel,
        createdAt: 0,
        accessedAt: 0,
      },
    },
  }));
  cache.entries = null;
  cache.sweep(0);
  assert.strictEqual(fs.readFileSync(sentinel, 'utf8'), 'must remain outside the cache',
    'cleanup trusted a persisted path outside the cache directory');
  fs.rmSync(outside, { recursive: true, force: true });
}

function checkChangedBytesAreIndexedByTheirActualHash() {
  const service = serviceWithCache();
  const sha = 'e'.repeat(64);
  const requestId = 'view-changed';
  const socket = service.clients[0];
  socket.activeTransfers = new Map();
  service.fileRequests.set(requestId, {
    open: true,
    sha256: sha,
    phonePath: 'doc:changing',
  });

  CompanionService.prototype.handleFileStart.call(service, socket, {
    id: requestId,
    name: 'changed.txt',
    size: 4,
  });
  CompanionService.prototype.handleFileChunk.call(service, socket, {
    id: requestId,
    chunkIndex: 0,
    data: Buffer.from('new!').toString('base64'),
  });
  CompanionService.prototype.handleFileDone.call(service, socket, { id: requestId });

  return settle().then(() => {
    assert.strictEqual(service.previewCache.lookup('bdd1e524e5c90bee91a4f1ac4a087ca0012e36235ab24b5136d2a6388e7ad58b')?.name,
      'changed.txt', 'downloaded bytes were not indexed under their actual SHA256');
    assert.strictEqual(service.previewCache.lookup(sha), null,
      'different downloaded bytes remained indexed under the phone’s earlier hash');
  });
}

function checkUnknownHashIdCannotResolveAnotherSamePathRequest() {
  const service = serviceWithCache();
  const firstTimer = setTimeout(() => {}, 10_000);
  const secondTimer = setTimeout(() => {}, 10_000);
  service.pendingHashes.set('second-request', { path: 'doc:same', timer: secondTimer });

  const before = service.written.length;
  CompanionService.routeFileHash(service, {
    type: protocol.TYPES.FILE_HASH,
    id: 'stale-response',
    ts: Date.now(),
    payload: protocol.buildFileHash('doc:same', '8'.repeat(64), 5, undefined, 'first-request'),
  });

  clearTimeout(firstTimer);
  clearTimeout(secondTimer);
  assert.strictEqual(service.written.length, before,
    'a stale response with an unknown request id triggered a download for another request');
  assert.ok(service.pendingHashes.has('second-request'),
    'a stale response with an unknown id consumed the newer same-path request');
}

function checkMalformedPhoneHashFallsBackToDownload() {
  const service = serviceWithCache();
  const hashId = 'hash-invalid';
  const timer = setTimeout(() => {}, 10_000);
  service.pendingHashes.set(hashId, { path: 'doc:bad', timer });

  CompanionService.routeFileHash(service, {
    type: protocol.TYPES.FILE_HASH,
    id: 'bad-hash',
    ts: Date.now(),
    payload: protocol.buildFileHash('doc:bad', '../outside', 3, undefined, hashId),
  });

  clearTimeout(timer);
  assert.strictEqual(lastFrame(service).type, protocol.TYPES.FILE_SEND_REQUEST,
    'a malformed phone hash did not fall through to a normal file request');
  assert.ok(service.fileRequests.has(lastFrame(service).payload.id),
    'the malformed hash response did not create a normal file request');
}

function checkUnhashedPreviewStillGetsContentAddressed() {
  const service = serviceWithCache();
  const requestId = 'view-timeout';
  const socket = service.clients[0];
  socket.activeTransfers = new Map();
  service.fileRequests.set(requestId, {
    open: true,
    sha256: null,
    phonePath: 'doc:slow',
  });

  CompanionService.prototype.handleFileStart.call(service, socket, {
    id: requestId,
    name: 'slow.txt',
    size: 10,
  });
  CompanionService.prototype.handleFileChunk.call(service, socket, {
    id: requestId,
    chunkIndex: 0,
    data: Buffer.from('slow bytes').toString('base64'),
  });
  CompanionService.prototype.handleFileDone.call(service, socket, { id: requestId });

  return settle().then(() => {
    assert.strictEqual(service.previewCache.lookup('e1035a369d9bd614588c5d4aa01ffd69ac46390cb1d7246e4868fe0629eeac37')?.name,
      'slow.txt', 'a hash-timeout fallback preview was not cached by its received content');
  });
}

function checkLateHashStillUsesTheCache() {
  const service = serviceWithCache();
  const requestId = 'hash-slow';
  const sha = '3673014e72b67383be302485694555a57ad393afdebaed6ded110a775bd0556d';
  const timer = setTimeout(() => {}, 10_000);
  service.pendingHashes.set(requestId, { path: 'doc:slow', timer });

  const cacheFile = service.previewCache.reserve(sha, 'slow.txt').targetPath;
  fs.mkdirSync(path.dirname(cacheFile), { recursive: true });
  fs.writeFileSync(cacheFile, 'cached');
  service.previewCache.commit(sha, { name: 'slow.txt', size: 6, phonePath: 'doc:slow' });

  const before = service.written.length;
  const done = [];
  service.on('file-transfer-done', (event) => done.push(event));
  CompanionService.routeFileHash(service, {
    type: protocol.TYPES.FILE_HASH,
    id: 'late-hash',
    ts: Date.now(),
    payload: protocol.buildFileHash('doc:slow', sha, 6, undefined, requestId),
  });

  clearTimeout(timer);
  assert.strictEqual(service.written.length, before, 'a late hash triggered a redundant file download');
  assert.strictEqual(done.length, 1, 'a late hash failed to open the cached preview');
  assert.strictEqual(done[0].cached, true, 'a late hash did not use the cache');
}

/** A phone that cannot hash: the preview still downloads. */
function checkNoHashFallsThrough() {
  const service = serviceWithCache();
  service.pendingHashes.set('kept', { path: 'doc:raw', timer: setTimeout(() => {}, 1000) });
  const before = service.written.length;

  CompanionService.routeFileHash(service, {
    type: protocol.TYPES.FILE_HASH,
    id: 'h3',
    ts: Date.now(),
    payload: protocol.buildFileHash('doc:raw', null, null, 'unreadable', 'kept'),
  });

  assert.ok(service.written.length > before, 'a hash failure did not fall through to a download');
  const frame = lastFrame(service);
  assert.strictEqual(frame.type, protocol.TYPES.FILE_SEND_REQUEST, 'the fallback was not a download');
}

function checkCleanupTimerCadence() {
  const timerPath = path.join(__dirname, 'systemd', 'aplus-preview-cleanup.timer');
  const timer = fs.readFileSync(timerPath, 'utf8');
  assert.match(timer, /^OnUnitActiveSec=1d$/m, 'cleanup timer must check for expiry daily');
  assert.match(timer, /^OnBootSec=1h$/m, 'cleanup timer must run once after boot');
  assert.doesNotMatch(timer, /^OnCalendar=/m, 'cleanup timer must use a boot-relative cadence');
}

function checkCleanupServiceQuotesPaths() {
  const servicePath = path.join(__dirname, 'systemd', 'aplus-preview-cleanup.service');
  const service = fs.readFileSync(servicePath, 'utf8');
  assert.match(
    service,
    /^ExecStart="__NODE__" "__PROJECT__\/scripts\/preview-cleanup\.js" --days 30$/m,
    'cleanup service must quote executable and script paths that may contain spaces'
  );
  assert.doesNotMatch(service, /^Documentation=/m, 'cleanup service must not reference missing documentation');
}

function run() {
  checkMissThenHit()
    .then(() => {
      checkAccessRefreshesTheClock();
      checkSweepRemovesStale();
      checkModifiedCacheFileIsAMiss();
      checkDeletedFileIsAMiss();
      checkInvalidHashCannotEscapeCache();
      checkNoHashFallsThrough();
      checkUnknownHashIdCannotResolveAnotherSamePathRequest();
      checkMalformedPhoneHashFallsBackToDownload();
      return checkChangedBytesAreIndexedByTheirActualHash();
    })
    .then(() => checkUnhashedPreviewStillGetsContentAddressed())
    .then(() => {
      checkLateHashStillUsesTheCache();
      checkCleanupTimerCadence();
      checkCleanupServiceQuotesPaths();
      console.log('preview cache: ok');
    })
    .catch((err) => {
      console.error(err.message);
      process.exit(1);
    });
}

run();
