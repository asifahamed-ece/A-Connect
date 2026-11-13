#!/usr/bin/env node
/**
 * The desktop's phone file browser.
 *
 * Asking for a folder must put a `file-list-request` on the wire carrying the
 * folder's path in `path`, and the phone's `file-list` must resolve exactly
 * that request - matched on the path, not on arrival order, because the user
 * can click two folders faster than the phone answers.
 *
 * A search is a different request whose answer is an ordinary `file-list`
 * keyed by `search:<query>`, so the same map resolves it and a folder listing
 * and a search can never resolve each other.
 *
 * There is no test runner in this repo: node scripts/check-companion-files.js
 */
'use strict';

const assert = require('assert');
const { EventEmitter } = require('events');

const protocol = require('../services/companion-protocol');
const CompanionService = require('../services/companion');

/** A service with one connected phone that records what was written to it. */
function connectedService() {
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
  service.pendingFileLists = new Map();
  service.written = written;
  return service;
}

function answer(path, entries) {
  return {
    type: protocol.TYPES.FILE_LIST,
    id: 'msg-1',
    ts: Date.now(),
    payload: protocol.buildFileList(path, entries),
  };
}

async function checkListing() {
  const service = connectedService();
  const pending = CompanionService.prototype.requestFileList.call(service, '');

  assert.strictEqual(service.written.length, 1, 'no file-list-request was written');
  const request = protocol.decode(service.written[0]);
  assert.strictEqual(
    request.type,
    protocol.TYPES.FILE_LIST_REQUEST,
    'the frame is not a file-list-request'
  );
  assert.strictEqual(request.payload.path, '', 'the root was not asked for as the empty path');

  // The phone answers with the same path, out of order on purpose: a folder
  // must still come first in what the desktop draws.
  CompanionService.prototype.handleFileList.call(
    service,
    answer('', [
      { name: 'zeta.txt', size: 10, isDir: false, mime: 'text/plain' },
      { name: 'Music', size: 0, isDir: true, mime: '' },
      { name: 'alpha.txt', size: 20, isDir: false, mime: 'text/plain' },
    ]).payload
  );

  const result = await pending;
  assert.strictEqual(result.success, true, `the listing did not resolve: ${result.error}`);
  assert.strictEqual(result.path, '', 'the answer lost the path');
  assert.deepStrictEqual(
    result.entries.map((e) => e.name),
    ['Music', 'alpha.txt', 'zeta.txt'],
    'the desktop must draw folders before files'
  );
}

async function checkMatching() {
  const service = connectedService();
  const first = CompanionService.prototype.requestFileList.call(service, 'doc:1');
  const second = CompanionService.prototype.requestFileList.call(service, 'doc:2');

  // The answer for the second folder arrives first. It must resolve the second
  // request and leave the first one waiting.
  CompanionService.prototype.handleFileList.call(
    service,
    answer('doc:2', [{ name: 'b.txt', size: 1, isDir: false, mime: 'text/plain' }]).payload
  );
  const secondResult = await second;
  assert.strictEqual(secondResult.success, true, 'the answered request did not resolve');
  assert.strictEqual(secondResult.path, 'doc:2', 'the wrong folder was resolved');

  CompanionService.prototype.handleFileList.call(
    service,
    answer('doc:1', [{ name: 'a.txt', size: 1, isDir: false, mime: 'text/plain' }]).payload
  );
  const firstResult = await first;
  assert.strictEqual(firstResult.path, 'doc:1', 'the first folder got the second folder\'s answer');
  assert.strictEqual(firstResult.entries[0].name, 'a.txt', 'the first folder got the wrong entries');
}

async function checkNoPhone() {
  const service = connectedService();
  service.clients = [];
  const result = await CompanionService.prototype.requestFileList.call(service, '');
  assert.strictEqual(result.success, false, 'a listing with no phone connected reported success');
  assert.strictEqual(result.error, 'The phone is not connected', 'the reason was not explained');
  assert.strictEqual(service.pendingFileLists.size, 0, 'a waiting request was left behind');

  const send = CompanionService.prototype.requestFileSend.call(service, 'doc:1');
  assert.strictEqual(send.success, false, 'a file request with no phone connected reported success');
}

async function checkBadAnswer() {
  const service = connectedService();
  const pending = CompanionService.prototype.requestFileList.call(service, '');
  const before = service.pendingFileLists.size;

  // A listing without entries is a protocol mistake, not an empty folder.
  CompanionService.prototype.handleFileList.call(service, { path: '', entries: null });
  assert.strictEqual(
    service.pendingFileLists.size,
    before,
    'a broken listing resolved the request as if the folder were empty'
  );

  // A disconnect must stop the spinner instead of leaving it for ten seconds.
  CompanionService.prototype.failPendingFileLists.call(service);
  const result = await pending;
  assert.strictEqual(result.success, false, 'a disconnect resolved the request as success');
  assert.strictEqual(result.error, 'The phone disconnected', 'the disconnect was not explained');
}

async function checkSearch() {
  const service = connectedService();
  const pending = CompanionService.prototype.requestFileSearch.call(service, 'dune');

  assert.strictEqual(service.written.length, 1, 'no search was written');
  const request = protocol.decode(service.written[0]);
  assert.strictEqual(
    request.type,
    protocol.TYPES.FILE_SEARCH_REQUEST,
    'the frame is not a file-search-request'
  );
  assert.strictEqual(request.payload.query, 'dune', 'the query did not reach the wire');

  // A folder listing arriving while a search is in flight must not resolve it.
  CompanionService.prototype.handleFileList.call(
    service,
    answer('Download', [{ name: 'a.pdf', size: 1, isDir: false, mime: 'application/pdf' }]).payload
  );
  assert.strictEqual(
    service.pendingFileLists.size,
    1,
    'a folder listing resolved a search it had nothing to do with'
  );

  // The answer is an ordinary file list, keyed by the query.
  CompanionService.prototype.handleFileList.call(
    service,
    answer(protocol.searchKey('dune'), [
      { id: 'Download/books/Dune.epub', name: 'Dune.epub', size: 42, isDir: false, mime: 'application/epub+zip' },
      { id: 'Download/books', name: 'books', size: 0, isDir: true, mime: '' },
    ]).payload
  );

  const result = await pending;
  assert.strictEqual(result.success, true, `the search did not resolve: ${result.error}`);
  assert.strictEqual(result.path, 'search:dune', 'the answer lost the query it belongs to');
  assert.deepStrictEqual(
    result.entries.map((e) => e.name),
    ['books', 'Dune.epub'],
    'search results must still be drawn folders first'
  );
  // Every hit carries the path that opens it, relative to the phone's root.
  assert.strictEqual(result.entries[1].id, 'Download/books/Dune.epub', 'a hit lost its path');
}

async function checkSearchWithNoPhone() {
  const service = connectedService();
  service.clients = [];
  const result = await CompanionService.prototype.requestFileSearch.call(service, 'dune');
  assert.strictEqual(result.success, false, 'a search with no phone reported success');
  assert.strictEqual(result.error, 'The phone is not connected', 'the reason was not explained');
  assert.strictEqual(service.pendingFileLists.size, 0, 'a waiting search was left behind');
}

async function checkSendRequest() {
  const service = connectedService();
  const result = CompanionService.prototype.requestFileSend.call(service, 'doc:9');
  assert.strictEqual(result.success, true, 'the file request was not sent');
  const request = protocol.decode(service.written[0]);
  assert.strictEqual(request.type, protocol.TYPES.FILE_SEND_REQUEST, 'the frame type changed');
  assert.strictEqual(request.payload.path, 'doc:9', 'the file id did not survive');
}

async function run() {
  await checkListing();
  await checkMatching();
  await checkNoPhone();
  await checkBadAnswer();
  await checkSendRequest();
  await checkSearch();
  await checkSearchWithNoPhone();
  console.log('companion files: ok');
}

run().catch((e) => {
  console.error(e.message);
  process.exit(1);
});
