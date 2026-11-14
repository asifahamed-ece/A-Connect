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
const fs = require('fs');
const os = require('os');
const path = require('path');
const { EventEmitter } = require('events');

const protocol = require('../services/companion-protocol');
const CompanionService = require('../services/companion');
const { PreviewCache } = require('../services/preview-cache');

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
  // The service remembers which file request is a preview and which is a
  // download; a fake without it cannot answer requestFileSend at all.
  service.fileRequests = new Map();
  // requestFileSend hands a preview to the hash-first flow, so the fake needs
  // that method too, not only the one the check calls by name.
  service.requestPreviewHash = CompanionService.prototype.requestPreviewHash;
  service.requestFileSend = CompanionService.prototype.requestFileSend;
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
  assert.strictEqual(
    request.payload.fresh,
    false,
    'typing asked the phone to walk its storage again for every keystroke'
  );

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

/**
 * Refresh means "look again now", and it is the only thing that walks the
 * phone's storage. Without this a file that had just arrived stayed invisible
 * to search until the index aged out - the bug the user hit.
 */
async function checkRefreshIsFresh() {
  const service = connectedService();
  const pending = CompanionService.prototype.requestFileSearch.call(service, 'dune', {
    fresh: true,
  });

  const request = protocol.decode(service.written[0]);
  assert.strictEqual(request.payload.fresh, true, 'Refresh did not ask for a fresh walk');
  assert.strictEqual(request.payload.query, 'dune', 'the query was lost with the flag');

  CompanionService.prototype.handleFileList.call(
    service,
    answer(protocol.searchKey('dune'), [
      { id: 'Download/dune.txt', name: 'dune.txt', size: 3, isDir: false, mime: 'text/plain' },
    ]).payload
  );
  const result = await pending;
  assert.strictEqual(result.success, true, `the refreshed search did not resolve: ${result.error}`);
  assert.strictEqual(result.entries[0].name, 'dune.txt', 'the refreshed search lost its answer');
}

/**
 * Disconnect must be deliberate, not a dropped link.
 *
 * The phone redials after any drop, so a bare destroy would be undone within
 * five seconds. The computer says `bye` first, and that frame has to be on the
 * wire before the socket closes - under TLS 1.3, closing early loses it.
 */
async function checkHangUp() {
  const service = new EventEmitter();
  const written = [];
  let ended = 0;
  let destroyed = 0;
  service.clients = [
    {
      handshakeDone: true,
      destroyed: false,
      clientIp: '127.0.0.1',
      write: (buf) => written.push(Buffer.from(buf)),
      end: () => {
        ended += 1;
      },
      destroy: () => {
        destroyed += 1;
      },
    },
  ];

  const hungUp = CompanionService.prototype.disconnectClients.call(service);

  assert.strictEqual(hungUp, 1, 'the connected phone was not hung up on');
  assert.strictEqual(written.length, 1, 'no bye frame was written');
  const frame = protocol.decode(written[0]);
  assert.strictEqual(frame.type, protocol.TYPES.BYE, 'a disconnect did not say bye');
  assert.strictEqual(ended, 1, 'the socket was not ended after the bye');

  // The bye must come before the close, or the phone sees a dropped link and
  // redials instead of staying disconnected.
  assert.strictEqual(destroyed, 0, 'the socket was destroyed before the bye could be flushed');

  // A socket that never finished its handshake is not a phone to hang up on.
  service.clients = [{ handshakeDone: false, destroyed: false }];
  assert.strictEqual(
    CompanionService.prototype.disconnectClients.call(service),
    0,
    'a phone that never paired was counted as hung up on'
  );
}

async function checkSendRequest() {
  const service = connectedService();
  const result = CompanionService.prototype.requestFileSend.call(service, 'doc:9');
  assert.strictEqual(result.success, true, 'the file request was not sent');
  const request = protocol.decode(service.written[0]);
  assert.strictEqual(request.type, protocol.TYPES.FILE_SEND_REQUEST, 'the frame type changed');
  assert.strictEqual(request.payload.path, 'doc:9', 'the file id did not survive');
  assert.ok(request.payload.id, 'the request went out without an id to match the answer to');
  assert.strictEqual(request.payload.open, undefined, 'a plain download asked to be opened');
}

/**
 * Keeping a file and looking at one are two different intentions.
 *
 * A download goes to the folder the user chose; a preview goes to a cache and
 * is opened there, because dropping every file the user glances at into
 * Downloads turns "open" into "download" behind their back.
 */
async function checkPreviewGoesToTheCacheNotTheDownloads() {
  const service = connectedService();
  service.destinationDir = fs.mkdtempSync(path.join(os.tmpdir(), 'aplus-dl-'));
  // The preview cache is what decides where a viewed file lands, so the fake
  // service needs a real one pointed at a temp folder.
  service.previewCache = new PreviewCache({ dir: fs.mkdtempSync(path.join(os.tmpdir(), 'aplus-pv-')) });
  service.pendingHashes = new Map();
  const socket = service.clients[0];
  socket.activeTransfers = new Map();

  const download = CompanionService.prototype.requestFileSend.call(service, 'doc:1');
  const downloadFrame = protocol.decode(service.written.at(-1));
  CompanionService.prototype.handleFileStart.call(service, socket, {
    id: downloadFrame.payload.id,
    name: 'keep.txt',
    size: 3,
  });
  const kept = socket.activeTransfers.get(downloadFrame.payload.id);
  assert.ok(
    kept.targetPath.startsWith(service.destinationDir),
    'a download did not land in the destination folder'
  );
  assert.strictEqual(kept.forViewing, false, 'a download was marked as a preview');

  // A preview asks for the hash first, so a file already in the cache never
  // crosses the wire again.
  const view = CompanionService.prototype.requestFileSend.call(service, 'doc:2', { open: true });
  assert.strictEqual(view.open, true, 'the preview request did not report itself as a preview');
  const hashFrame = protocol.decode(service.written.at(-1));
  assert.strictEqual(
    hashFrame.type,
    protocol.TYPES.FILE_HASH_REQUEST,
    'a preview did not start by asking for the file hash'
  );
  assert.strictEqual(hashFrame.payload.path, 'doc:2', 'the hash request asked for the wrong file');

  // The phone says the bytes are not cached yet, so the download is requested.
  const sha = 'e'.repeat(64);
  CompanionService.routeFileHash(service, {
    type: protocol.TYPES.FILE_HASH,
    id: 'h',
    ts: Date.now(),
    payload: protocol.buildFileHash('doc:2', sha, 3, undefined, hashFrame.payload.id),
  });
  const viewFrame = protocol.decode(service.written.at(-1));
  assert.strictEqual(viewFrame.type, protocol.TYPES.FILE_SEND_REQUEST, 'a miss did not download');
  assert.strictEqual(viewFrame.payload.open, true, 'a preview did not say it was for viewing');

  CompanionService.prototype.handleFileStart.call(service, socket, {
    id: viewFrame.payload.id,
    name: 'look.txt',
    size: 3,
  });
  const viewed = socket.activeTransfers.get(viewFrame.payload.id);
  assert.ok(
    !viewed.targetPath.startsWith(service.destinationDir),
    'a preview was saved into the user\'s downloads folder'
  );
  assert.ok(
    viewed.targetPath.startsWith(service.previewCache.dir),
    'a preview was not saved into the preview cache'
  );
  assert.strictEqual(viewed.forViewing, true, 'a preview was not marked as one');
  assert.ok(viewed.contentHash, 'a preview did not prepare to hash the received bytes');
}

/** A phone that cannot send the file says so, instead of going quiet. */
async function checkSendFailureIsReported() {
  const service = connectedService();
  const socket = service.clients[0];
  socket.activeTransfers = new Map();
  const failures = [];
  service.on('file-transfer-error', (e) => failures.push(e));

  CompanionService.prototype.handleFileStart.call(service, socket, {
    id: 'view-1234',
    name: 'gone.jpg',
    size: 0,
    error: 'that file is not on the phone any more',
  });

  assert.strictEqual(failures.length, 1, 'the phone\'s reason never reached the desktop');
  assert.strictEqual(failures[0].error, 'that file is not on the phone any more', 'the reason changed');
  assert.strictEqual(failures[0].name, 'gone.jpg', 'the name was lost');
  assert.strictEqual(socket.activeTransfers.size, 0, 'a failed transfer left a file open');
}

async function run() {
  await checkListing();
  await checkMatching();
  await checkNoPhone();
  await checkBadAnswer();
  await checkSendRequest();
  await checkPreviewGoesToTheCacheNotTheDownloads();
  await checkSendFailureIsReported();
  await checkSearch();
  await checkSearchWithNoPhone();
  await checkRefreshIsFresh();
  await checkHangUp();
  console.log('companion files: ok');
}

run().catch((e) => {
  console.error(e.message);
  process.exit(1);
});
