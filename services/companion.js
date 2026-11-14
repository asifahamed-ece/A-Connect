'use strict';

const { EventEmitter } = require('events');
const fs = require('fs');
const os = require('os');
const path = require('path');
const tls = require('tls');
const crypto = require('crypto');

const protocol = require('./companion-protocol');
const handshake = require('./companion-handshake');
const { ensureCertificate } = require('./companion-cert');
const { PreviewCache, isSha256 } = require('./preview-cache');

const DEFAULT_PORT = 24443;
const PAIRING_CODE_TTL_MS = 5 * 60 * 1000; // 5 minutes

/**
 * How long to wait for the phone's hash before giving up and just downloading.
 *
 * A phone that is busy hashing a large file, or an older build that does not
 * answer at all, must not leave the user staring at nothing.
 */
const HASH_TIMEOUT_MS = 8 * 1000;

// A link that carries no traffic is dropped by the network (Wi-Fi sleep,
// NAT timeouts). The protocol has PING and PONG for exactly this: the
// computer pings, the phone answers, and a client that stops answering is
// closed instead of lingering as a half-open socket.
const PING_INTERVAL_MS = 30 * 1000;
const PEER_TIMEOUT_MS = 75 * 1000;

/**
 * How long the desktop waits for a folder listing.
 *
 * A listing is one directory read, so this is generous - it is here to stop
 * the UI spinning forever, not to catch a slow folder.
 */
const FILE_LIST_TIMEOUT_MS = 10 * 1000;

/**
 * How long the desktop waits for a search.
 *
 * Longer than a listing because the phone's first search of a session may
 * still be waiting for the storage walk that its connection started. Once that
 * walk is done a search is instant, so the extra patience is only spent once.
 */
const FILE_SEARCH_TIMEOUT_MS = 40 * 1000;

const PAIRED_DEVICES_FILE = path.join(
  process.env.HOME || process.env.USERPROFILE || '.',
  '.config',
  'aplus-connect',
  'companion',
  'paired-devices.json'
);

/**
 * Where the kill switch is remembered.
 *
 * A separate small file rather than a key beside the paired devices: it is
 * read and written on every toggle, and rewriting the secret store each time
 * the switch moves would be a chance to lose it.
 */
const SETTINGS_FILE = path.join(
  process.env.HOME || process.env.USERPROFILE || '.',
  '.config',
  'aplus-connect',
  'companion',
  'settings.json'
);

/** What the user is told while the companion is switched off. */
const OFF_DETAIL = 'Off — the computer is not listening';

/** A log line with the time, so a drop can be placed in a timeline. */
function log(message) {
  console.log(`[companion ${new Date().toISOString().slice(11, 19)}] ${message}`);
}

/**
 * Writes one frame to every paired, live phone, and says how many got it.
 *
 * A plain function rather than a method, because the checks in `scripts/`
 * exercise the message handlers against a stand-in service with only a
 * `clients` array. Every message the desktop sends goes through here, so "is
 * this phone usable" is answered once.
 */
function sendToPhones(service, frame, what = 'a message') {
  let sentCount = 0;
  for (const socket of service.clients) {
    if (socket.handshakeDone && !socket.destroyed) {
      try {
        socket.write(frame);
        sentCount += 1;
      } catch (e) {
        console.error(`[companion] could not send ${what}: ${e.message}`);
      }
    }
  }
  return sentCount;
}

/** The one place a listing - a folder or a search - waits for its answer. */
function awaitListing(service, key, frame, description, timeoutMs = FILE_LIST_TIMEOUT_MS) {
  return new Promise((resolve) => {
    const previous = service.pendingFileLists.get(key);
    if (previous) {
      clearTimeout(previous.timer);
      previous.resolve({ success: false, error: 'superseded' });
    }

    const timer = setTimeout(() => {
      service.pendingFileLists.delete(key);
      resolve({ success: false, error: 'The phone did not answer' });
    }, timeoutMs);
    service.pendingFileLists.set(key, { resolve, timer });

    const sent = sendToPhones(service, protocol.encode(frame), description);

    if (sent === 0) {
      clearTimeout(timer);
      service.pendingFileLists.delete(key);
      resolve({ success: false, error: 'The phone is not connected' });
    }
  });
}

class CompanionService extends EventEmitter {
  constructor(options = {}) {
    super();
    this.port = options.port ?? DEFAULT_PORT;
    this.pcId = options.pcId ?? 'pc-test';
    this.pcName = options.pcName || require('os').hostname();
    this.server = null;
    this.clients = new Set();
    this.status = 'stopped';
    this.lastSentHash = null;
    this.pairedDevices = {};
    this.pairingCode = null;
    this.pairingCodeExpiresAt = 0;
    // The same folder the ADB transport pulls into. Two transports saving the
    // same feature into two different places is how a user ends up unable to
    // find a file they just downloaded.
    this.destinationDir =
      options.destinationDir || path.join(os.homedir(), 'Downloads', 'A+Connect');

    /**
     * The kill switch: false means this desktop is dormant - no listener, no
     * pairing, nothing forwarded. See setEnabled.
     */
    this.enabled = true;

    /** Where the switch is remembered. Overridable so a check can use a temp. */
    this.settingsFile = options.settingsFile || SETTINGS_FILE;

    // Folder listings the user is waiting for, by the path they asked for.
    // A Map rather than a single slot: two folders can be asked for in quick
    // succession, and the answers carry the path back, so they cannot be
    // confused with each other.
    this.pendingFileLists = new Map();

    /** File requests that have not come back yet, by the id we gave them. */
    this.fileRequests = new Map();

    /**
     * The phone's vitals (battery, OS, storage), once it has told us.
     * Null until the first `device-info` arrives.
     */
    this.deviceInfo = null;

    /**
     * The preview cache: files the user has glanced at, keyed by their SHA256.
     * A second look at the same bytes opens the cached copy instead of pulling
     * it across the network again.
     */
    this.previewCache = options.previewCache || new PreviewCache();

    /** Preview requests waiting on the phone's hash, by the id we gave them. */
    this.pendingHashes = new Map();

    this.loadSettings();
    this.loadPairedDevices();
    this.generatePairingCode();
  }

  generatePairingCode() {
    this.pairingCode = String(Math.floor(100000 + Math.random() * 900000));
    this.pairingCodeExpiresAt = Date.now() + PAIRING_CODE_TTL_MS;
    this.emit('pairing-code-updated', {
      code: this.pairingCode,
      expiresAt: this.pairingCodeExpiresAt,
    });
    return this.pairingCode;
  }

  isPairingCodeValid(code) {
    if (!code || typeof code !== 'string') return false;
    return code === this.pairingCode && Date.now() <= this.pairingCodeExpiresAt;
  }

  loadPairedDevices() {
    try {
      if (fs.existsSync(PAIRED_DEVICES_FILE)) {
        this.pairedDevices = JSON.parse(fs.readFileSync(PAIRED_DEVICES_FILE, 'utf8'));
        return;
      }
    } catch (e) {
      console.warn('[companion] could not load paired devices:', e.message);
    }
    this.pairedDevices = {};
  }

  savePairedDevices() {
    try {
      fs.mkdirSync(path.dirname(PAIRED_DEVICES_FILE), { recursive: true });
      fs.writeFileSync(PAIRED_DEVICES_FILE, JSON.stringify(this.pairedDevices, null, 2), 'utf8');
    } catch (e) {
      console.warn('[companion] could not save paired devices:', e.message);
    }
  }

  /**
   * Reads the kill switch. Absent means on: an install from before the switch
   * existed was working, and it should keep working until someone turns it off.
   */
  loadSettings() {
    try {
      if (fs.existsSync(this.settingsFile)) {
        const saved = JSON.parse(fs.readFileSync(this.settingsFile, 'utf8'));
        this.enabled = saved.enabled !== false;
        return;
      }
    } catch (e) {
      console.warn('[companion] could not read the companion settings:', e.message);
    }
    this.enabled = true;
  }

  saveSettings() {
    try {
      fs.mkdirSync(path.dirname(this.settingsFile), { recursive: true });
      fs.writeFileSync(
        this.settingsFile,
        JSON.stringify({ enabled: this.enabled }, null, 2),
        'utf8'
      );
    } catch (e) {
      console.warn('[companion] could not save the companion settings:', e.message);
    }
  }

  get isEnabled() {
    return this.enabled;
  }

  /**
   * The kill switch.
   *
   * Turning it off says `bye` with the dormant reason first, so a connected
   * phone knows this is a planned silence and keeps looking slowly, then
   * closes the listener: a dormant desktop has no port for anything to find.
   * Turning it on opens the listener again, and the phone's own retry finds it.
   *
   * Only the state is persisted - the paired devices and their secrets are
   * untouched, so moving the switch is never a re-pairing.
   */
  setEnabled(enabled) {
    const next = Boolean(enabled);
    if (next === this.enabled) return { enabled: this.enabled };

    this.enabled = next;
    this.saveSettings();

    if (this.enabled) {
      this.start();
      log('companion switched on');
    } else {
      // Say why before going quiet: without it the phone sees a bare drop and
      // redials every five seconds at a port that is no longer open.
      this.disconnectClients(protocol.REASONS.DORMANT, 'let go of');
      this.stop();
      log('companion switched off');
    }

    this.emit('enabled-changed', { enabled: this.enabled });
    this.updateStatus();
    return { enabled: this.enabled };
  }

  unpairDevice(deviceId) {
    if (this.pairedDevices[deviceId]) {
      delete this.pairedDevices[deviceId];
      this.savePairedDevices();
      for (const socket of this.clients) {
        if (socket.deviceId === deviceId) {
          socket.destroy();
        }
      }
      this.updateStatus();
      this.emit('device-unpaired', { deviceId });
      return true;
    }
    return false;
  }

  get isConnected() {
    return Array.from(this.clients).some((c) => c.handshakeDone && !c.destroyed);
  }

  get connectedClients() {
    return Array.from(this.clients)
      .filter((c) => c.handshakeDone && !c.destroyed)
      .map((c) => ({
        deviceId: c.deviceId,
        deviceName: c.deviceName,
        ip: c.clientIp || c.remoteAddress || 'unknown',
        connectedAt: c.connectedAt,
        clipboardAccess: c.clipboardAccess !== false,
      }));
  }

  start(port = this.port) {
    if (this.server) return;
    // A dormant desktop does not open a socket at all: "off" has to mean there
    // is nothing to find, not a listener that refuses politely.
    if (!this.enabled) {
      this.status = 'disabled';
      log('not listening: the companion switch is off');
      this.emit('status', { status: 'disabled', detail: OFF_DETAIL });
      return;
    }
    this.port = port;

    let certData;
    try {
      certData = ensureCertificate();
    } catch (err) {
      console.error('[companion] certificate error:', err.message);
      this.status = 'error';
      this.reportError(err);
      return;
    }

    const { certPath, keyPath, fingerprint } = certData;
    this.fingerprint = fingerprint;

    this.server = tls.createServer(
      { cert: fs.readFileSync(certPath), key: fs.readFileSync(keyPath) },
      (socket) => this.handleConnection(socket)
    );

    this.server.on('error', (err) => {
      // Another copy of the app may already hold the port. That is a
      // situation to report, not a reason to take the whole app down.
      const detail = err.code === 'EADDRINUSE'
        ? `Port ${this.port} is already in use - another copy of A+ Connect is probably running`
        : err.message;
      console.error('[companion] server error:', detail);
      this.status = 'error';
      this.reportError(err, detail);
    });

    this.pingTimer = setInterval(() => this.pingClients(), PING_INTERVAL_MS);
    if (this.pingTimer.unref) this.pingTimer.unref();

    this.server.listen(this.port, '0.0.0.0', () => {
      // The port may have been 0, meaning "any free port": read back what was
      // actually bound, so a caller that asked for 0 learns the real number.
      const address = this.server.address();
      if (address && typeof address === 'object') this.port = address.port;
      this.status = 'listening';
      log(`TLS server listening on ${this.localAddress()}:${this.port}`);
      this.emit('listening', {
        port: this.port,
        address: this.localAddress(),
        code: this.pairingCode,
        fingerprint: this.fingerprint,
      });
      this.emit('status', { status: 'listening', detail: `Code: ${this.pairingCode}` });
    });
  }

  /**
   * Hangs up on the phone at the user's request.
   *
   * Not a plain destroy: the phone redials after any dropped link, so five
   * seconds later it would be back and the button would look broken. Saying
   * `bye` first tells the phone this was deliberate - it stops retrying and
   * shows the user that the computer hung up, and only tapping Reconnect on
   * the phone brings it back.
   *
   * Returns how many links were hung up on.
   */
  disconnectClients(reason = 'the computer disconnected', what = 'hung up on') {
    let hungUp = 0;
    for (const socket of this.clients) {
      if (!socket.handshakeDone || socket.destroyed) continue;
      hungUp += 1;
      try {
        socket.write(
          protocol.encode({
            version: protocol.PROTOCOL_VERSION,
            type: protocol.TYPES.BYE,
            id: crypto.randomUUID(),
            ts: Date.now(),
            payload: { reason },
          })
        );
      } catch (e) {
        log(`could not say bye: ${e.message}`);
      }
      // end() rather than destroy(): the write above must be flushed first.
      // Under TLS 1.3 that flush is what carries the frame before the close,
      // and a destroy() here races it and silently drops the bye.
      socket.end();
      // A peer that never completes the close must not keep the link open.
      const timer = setTimeout(() => {
        if (!socket.destroyed) socket.destroy();
      }, 2000);
      if (typeof timer.unref === 'function') timer.unref();
    }
    if (hungUp > 0) log(`${what} ${hungUp} phone${hungUp === 1 ? '' : 's'}`);
    return hungUp;
  }

  stop() {
    if (this.pingTimer) {
      clearInterval(this.pingTimer);
      this.pingTimer = null;
    }
    for (const socket of this.clients) {
      try {
        socket.destroy();
      } catch {}
    }
    this.clients.clear();

    if (this.server) {
      this.server.close();
      this.server = null;
    }
    this.status = this.enabled ? 'stopped' : 'disabled';
    this.emit('status', {
      status: this.status,
      detail: this.enabled ? null : OFF_DETAIL,
    });
  }

  handleConnection(socket) {
    socket.clientIp = socket.remoteAddress?.replace(/^.*:/, '') || 'unknown';
    socket.buffer = Buffer.alloc(0);
    socket.handshakeDone = false;
    socket.connectedAt = Date.now();
    socket.lastSeen = Date.now();
    this.clients.add(socket);

    socket.on('data', (chunk) => this.handleData(socket, chunk));

    socket.on('error', (err) => {
      console.log(`[companion] socket error (${socket.clientIp}): ${err.message}`);
    });

    socket.on('close', () => {
      if (socket.activeTransfers) {
        for (const t of socket.activeTransfers.values()) {
          try { t.stream.destroy(); } catch {}
        }
        socket.activeTransfers.clear();
      }
      this.clients.delete(socket);
      if (socket.handshakeDone) {
        log(`phone disconnected: ${socket.deviceName || socket.clientIp}`);
        this.failPendingFileLists();
        this.emit('client-disconnected', { deviceId: socket.deviceId, ip: socket.clientIp });
      }
      this.updateStatus();
    });
  }

  /**
   * Reports a failure without taking the app down.
   *
   * Node throws when an EventEmitter emits 'error' and nobody is listening,
   * so the event is only emitted when there is a listener, and the status
   * always carries the detail.
   */
  reportError(err, detail = err.message) {
    this.emit('status', { status: 'error', detail });
    if (this.listenerCount('error') > 0) this.emit('error', err);
  }

  /**
   * The two hooks the shared handshake needs from whichever service runs it.
   *
   * It logs in this service's own format, and a pairing is announced here so
   * the tray, the device list and the settings card keep working exactly as
   * they did when this logic lived inline.
   */
  logLine(text) {
    log(text);
  }

  onPaired(device) {
    this.emit('client-paired', device);
  }

  /** Pings the connected phones and drops the ones that stopped answering. */
  pingClients() {
    const now = Date.now();
    for (const socket of this.clients) {
      if (!socket.handshakeDone || socket.destroyed) continue;

      if (now - (socket.lastSeen || 0) > PEER_TIMEOUT_MS) {
        log(`${socket.deviceName || socket.clientIp} stopped answering, closing`);
        socket.destroy();
        continue;
      }

      try {
        socket.write(
          protocol.encode({
            version: protocol.PROTOCOL_VERSION,
            type: protocol.TYPES.PING,
            id: crypto.randomUUID(),
            ts: now,
            payload: {},
          })
        );
      } catch (e) {
        socket.destroy();
      }
    }
  }

  handleData(socket, chunk) {
    socket.lastSeen = Date.now();
    socket.buffer = Buffer.concat([socket.buffer, chunk]);

    for (;;) {
      if (socket.buffer.length < 4) return;
      const length = socket.buffer.readInt32BE(0);
      if (socket.buffer.length < 4 + length) return;

      const frame = socket.buffer.subarray(0, 4 + length);
      socket.buffer = socket.buffer.subarray(4 + length);

      let message;
      try {
        message = protocol.decode(frame);
      } catch (err) {
        console.log(`[companion] bad frame from ${socket.clientIp}: ${err.message}`);
        socket.destroy();
        return;
      }

      this.handleMessage(socket, message);
    }
  }

  handleMessage(socket, message) {
    if (message.type === protocol.TYPES.HELLO) {
      // The handshake - including proof of possession and re-pairing - lives in
      // one place, because the video socket runs the same one with pairing
      // switched off.
      handshake.handleHello(this, socket, message, { allowPairing: true });
      this.updateStatus();
      return;
    }

    if (!socket.handshakeDone) return;

    if (message.type === protocol.TYPES.CLIPBOARD_SET) {
      const text = message.payload.text;
      const hash = message.payload.hash;
      if (typeof text === 'string' && text) {
        this.lastSentHash = hash;
        this.emit('clipboard', {
          text,
          deviceId: socket.deviceId,
          hash,
        });
      }
      return;
    }

    if (message.type === protocol.TYPES.FILE_START) {
      this.handleFileStart(socket, message.payload);
      return;
    }

    if (message.type === protocol.TYPES.FILE_CHUNK) {
      this.handleFileChunk(socket, message.payload);
      return;
    }

    if (message.type === protocol.TYPES.FILE_DONE) {
      this.handleFileDone(socket, message.payload);
      return;
    }

    if (message.type === protocol.TYPES.FILE_LIST) {
      this.handleFileList(message.payload);
      return;
    }

    if (routeNotification(this, message)) return;
    if (routeMedia(this, message)) return;
    if (routeDeviceInfo(this, message)) return;
    if (routeFileHash(this, message)) return;
    if (routeMirror(this, message)) return;

    if (message.type === protocol.TYPES.PING) {      socket.write(
        protocol.encode({
          version: protocol.PROTOCOL_VERSION,
          type: protocol.TYPES.PONG,
          id: message.id,
          ts: Date.now(),
          payload: {},
        })
      );
    }
  }

  setDestinationDir(dirPath) {
    if (typeof dirPath === 'string' && dirPath.trim()) {
      this.destinationDir = dirPath.trim();
      if (!fs.existsSync(this.destinationDir)) {
        try { fs.mkdirSync(this.destinationDir, { recursive: true }); } catch {}
      }
      this.emit('destination-dir-updated', this.destinationDir);
      return this.destinationDir;
    }
    return this.destinationDir;
  }

  handleFileStart(socket, payload) {
    const { id, name, size } = payload;

    // A file the phone could not send. It arrives as a file-start with the
    // reason instead of as silence, so the desktop can say what happened
    // rather than leave a click looking like it did nothing.
    if (payload.error) {
      this.fileRequests.delete(id);
      console.warn(`[companion] the phone could not send ${name || id}: ${payload.error}`);
      this.emit('file-transfer-error', { id, name: name || 'the file', error: payload.error });
      return;
    }

    const request = this.fileRequests.get(id);
    this.fileRequests.delete(id);
    const forViewing = Boolean(request?.open);
    const contentHash = crypto.createHash('sha256');

    // A preview is cached, not saved: the user asked to look at the file, not
    // to keep it, and dropping every preview into Downloads would turn
    // "open" into "download" behind their back. The cache keys by hash, so the
    // same file looked at twice is one copy.
    let destDir;
    let targetPath;
    if (forViewing && this.previewCache) {
      const reserved = this.previewCache.reserve(null, name);
      targetPath = reserved.targetPath;
      destDir = path.dirname(targetPath);
    } else {
      destDir = this.destinationDir || path.join(os.homedir(), 'Downloads', 'A+Connect');
      if (!fs.existsSync(destDir)) {
        try { fs.mkdirSync(destDir, { recursive: true }); } catch {}
      }

      let safeName = path.basename(name || 'transfer').replace(/[^a-zA-Z0-9._-]/g, '_');
      targetPath = path.join(destDir, safeName);
      let counter = 1;
      const ext = path.extname(safeName);
      const base = path.basename(safeName, ext);
      while (fs.existsSync(targetPath)) {
        safeName = `${base} (${counter++})${ext}`;
        targetPath = path.join(destDir, safeName);
      }
    }

    const stream = fs.createWriteStream(targetPath);
    if (!socket.activeTransfers) socket.activeTransfers = new Map();
    socket.activeTransfers.set(id, {
      id,
      name: path.basename(targetPath),
      targetPath,
      size,
      received: 0,
      stream,
      forViewing,
      contentHash: forViewing ? contentHash : null,
      phonePath: request?.phonePath || '',
      startedAt: Date.now(),
    });

    console.log(`[companion] incoming file from ${socket.deviceName}: ${path.basename(targetPath)} (${size} bytes)`);
    this.emit('file-transfer-start', {
      id,
      name: path.basename(targetPath),
      size,
      forViewing,
      deviceName: socket.deviceName
    });
  }

  handleFileChunk(socket, payload) {
    const { id, chunkIndex, data } = payload;
    const transfer = socket.activeTransfers?.get(id);
    if (!transfer) return;

    try {
      const buffer = Buffer.from(data, 'base64');
      transfer.stream.write(buffer);
      transfer.contentHash?.update(buffer);
      transfer.received += buffer.length;
      this.emit('file-transfer-progress', {
        id,
        received: transfer.received,
        total: transfer.size,
        progress: transfer.size > 0 ? transfer.received / transfer.size : 0,
      });
    } catch (e) {
      console.error(`[companion] error writing file chunk for ${id}:`, e.message);
    }
  }

  handleFileDone(socket, payload) {
    const { id } = payload;
    const transfer = socket.activeTransfers?.get(id);
    if (!transfer) return;

    transfer.stream.end(() => {
      // A finished preview is recorded under the bytes actually received; the
      // phone's earlier hash may be stale if the file changed during transfer.
      if (transfer.forViewing && this.previewCache) {
        const actualHash = transfer.contentHash?.digest('hex');
        if (actualHash) {
          this.previewCache.commit(actualHash, {
            name: transfer.name,
            size: transfer.received,
            phonePath: transfer.phonePath || '',
            sourcePath: transfer.targetPath,
          });
          transfer.targetPath = this.previewCache.pathFor(actualHash, transfer.name);
        }
      }
      console.log(`[companion] file saved successfully to ${transfer.targetPath}`);
      socket.activeTransfers.delete(id);
      this.emit('file-transfer-done', {
        id,
        name: transfer.name,
        path: transfer.targetPath,
        size: transfer.received,
        forViewing: transfer.forViewing,
        cached: false,
        deviceName: socket.deviceName,
      });
    });
  }

  /**
   * Asks the phone for one folder listing.
   *
   * `path` is the document id the phone handed out before, or the empty string
   * for the root of the folder the user shared on the phone. The answer
   * carries the same path, so it is matched on that rather than on arrival
   * order.
   */
  requestFileList(path = '') {
    const payload = protocol.buildFileRequest(protocol.TYPES.FILE_LIST_REQUEST, path);
    return awaitListing(
      this,
      payload.path,
      {
        version: protocol.PROTOCOL_VERSION,
        type: protocol.TYPES.FILE_LIST_REQUEST,
        id: crypto.randomUUID(),
        ts: Date.now(),
        payload,
      },
      'a file list request'
    );
  }

  /**
   * Asks the phone to search its whole storage.
   *
   * The answer is a `file-list` keyed by the query rather than by a path, so
   * two searches in flight cannot be confused with each other or with a folder
   * listing the user opened in the meantime.
   *
   * `fresh` is for the Refresh button, not for typing: it asks the phone to
   * walk its storage again before answering, so a file saved since the last
   * walk is found. A keystroke must never pay for that walk.
   */
  requestFileSearch(query = '', options = {}) {
    const payload = protocol.buildFileSearchRequest(query, options.fresh === true);
    return awaitListing(
      this,
      protocol.searchKey(payload.query),
      {
        version: protocol.PROTOCOL_VERSION,
        type: protocol.TYPES.FILE_SEARCH_REQUEST,
        id: crypto.randomUUID(),
        ts: Date.now(),
        payload,
      },
      'a file search',
      FILE_SEARCH_TIMEOUT_MS
    );
  }

  /**
   * Asks the phone to send one file.
   *
   * Nothing is awaited here: the file arrives as the ordinary
   * file-start/chunk/done transfer, which the desktop already saves, so the
   * progress the user sees is the same progress a file sent from the phone
   * shows.
   */
  requestFileSend(path = '', options = {}) {
    const open = options.open === true;

    // A preview asks the phone for the file's hash first, so a file the user
    // already looked at opens from the cache instead of being pulled again.
    if (open && this.previewCache && !options.skipCache) {
      return this.requestPreviewHash(path);
    }

    // The request carries its own id so the transfer that comes back can be
    // matched to it. Without that, "which of these two files was the one the
    // user asked to preview" has no answer.
    const requestId = `${open ? 'view' : 'file'}-${crypto.randomUUID().slice(0, 8)}`;
    const payload = protocol.buildFileRequest(protocol.TYPES.FILE_SEND_REQUEST, path, {
      id: requestId,
      open,
    });

    this.fileRequests.set(requestId, {
      open,
      phonePath: path,
      requestedAt: Date.now(),
    });
    // Requests that never come back must not pile up for ever.
    setTimeout(() => this.fileRequests.delete(requestId), 5 * 60 * 1000).unref?.();

    const sent = sendToPhones(
      this,
      protocol.encode({
        version: protocol.PROTOCOL_VERSION,
        type: protocol.TYPES.FILE_SEND_REQUEST,
        id: requestId,
        ts: Date.now(),
        payload,
      }),
      'a file send request'
    );
    if (sent === 0) {
      this.fileRequests.delete(requestId);
      return { success: false, error: 'The phone is not connected' };
    }
    return { success: true, id: requestId, open };
  }

  /**
   * Asks the phone for a file's hash, to decide hit or miss for a preview.
   *
   * The phone's answer is handled in `routeFileHash`: a hit opens the cached
   * copy, a miss downloads. A phone that stays silent past the timeout is
   * downloaded from anyway - the cache is an optimisation, never a gate.
   */
  requestPreviewHash(path) {
    const hashId = `hash-${crypto.randomUUID().slice(0, 8)}`;
    const payload = protocol.buildFileHashRequest(path, hashId);

    const timer = setTimeout(() => {
      this.pendingHashes.delete(hashId);
      // No answer in time: fall through to a preview download. The receiver
      // hashes the bytes as they arrive, so this path is still cacheable.
      this.requestFileSend(path, { open: true, skipCache: true });
    }, HASH_TIMEOUT_MS);
    timer.unref?.();

    this.pendingHashes.set(hashId, { path, timer, requestedAt: Date.now() });

    const sent = sendToPhones(
      this,
      protocol.encode({
        version: protocol.PROTOCOL_VERSION,
        type: protocol.TYPES.FILE_HASH_REQUEST,
        id: hashId,
        ts: Date.now(),
        payload,
      }),
      'a file hash request'
    );
    if (sent === 0) {
      clearTimeout(timer);
      this.pendingHashes.delete(hashId);
      return { success: false, error: 'The phone is not connected' };
    }
    return { success: true, id: hashId, open: true, checking: true };
  }

  /** Answers a listing the user asked for, and orders it the way the UI draws it. */
  handleFileList(payload) {
    const listing = protocol.parseFileList(payload);
    if (!listing) {
      console.warn('[companion] the phone sent a file list without entries');
      return;
    }
    listing.entries = protocol.sortFileEntries(listing.entries);
    this.emit('file-list', listing);

    const pending = this.pendingFileLists.get(listing.path);
    if (pending) {
      this.pendingFileLists.delete(listing.path);
      clearTimeout(pending.timer);
      pending.resolve({ success: true, path: listing.path, entries: listing.entries });
    }
  }

  /**
   * Stops every waiting folder request.
   *
   * A phone that disconnects will never answer, and the user would otherwise
   * watch a spinner for ten seconds after the connection is already gone.
   */
  failPendingFileLists(reason = 'The phone disconnected') {
    for (const [, pending] of this.pendingFileLists) {
      clearTimeout(pending.timer);
      pending.resolve({ success: false, error: reason });
    }
    this.pendingFileLists.clear();
  }

  /**
   * Sends a file to the connected phones, in chunks.
   *
   * The chunks are base64 in JSON frames, the same shape the phone uses when
   * it sends a file, so both directions share one code path on the wire.
   */
  sendFile(filePath, mime = 'application/octet-stream') {
    if (typeof filePath !== 'string' || !filePath) return false;

    const targets = Array.from(this.clients).filter((c) => c.handshakeDone && !c.destroyed);
    if (targets.length === 0) return false;

    let stat;
    try {
      stat = fs.statSync(filePath);
    } catch (e) {
      console.error(`[companion] cannot send ${filePath}: ${e.message}`);
      return false;
    }
    if (!stat.isFile()) return false;

    const name = path.basename(filePath);
    const transferId = crypto.randomUUID();
    const chunkSize = 128 * 1024;

    const start = protocol.encode({
      version: protocol.PROTOCOL_VERSION,
      type: protocol.TYPES.FILE_START,
      id: transferId,
      ts: Date.now(),
      payload: { id: transferId, name, size: stat.size, mime },
    });

    try {
      const data = fs.readFileSync(filePath);
      for (const socket of targets) {
        socket.write(start);
        let index = 0;
        for (let offset = 0; offset < data.length; offset += chunkSize) {
          const slice = data.subarray(offset, Math.min(offset + chunkSize, data.length));
          socket.write(
            protocol.encode({
              version: protocol.PROTOCOL_VERSION,
              type: protocol.TYPES.FILE_CHUNK,
              id: transferId,
              ts: Date.now(),
              payload: {
                id: transferId,
                chunkIndex: index,
                data: slice.toString('base64'),
              },
            })
          );
          index += 1;
        }
        socket.write(
          protocol.encode({
            version: protocol.PROTOCOL_VERSION,
            type: protocol.TYPES.FILE_DONE,
            id: transferId,
            ts: Date.now(),
            payload: { id: transferId },
          })
        );
      }
      console.log(`[companion] sent ${name} (${stat.size} bytes) to ${targets.length} device(s)`);
      this.emit('file-sent', { id: transferId, name, path: filePath, size: stat.size, count: targets.length });
      return true;
    } catch (e) {
      console.error(`[companion] could not send ${name}: ${e.message}`);
      return false;
    }
  }

  sendClipboard(text) {
    if (typeof text !== 'string' || !text) return false;
    const payload = protocol.buildClipboardPayload(text);
    if (!protocol.shouldSendClipboard(payload, this.lastSentHash)) return false;
    this.lastSentHash = payload.hash;

    const frame = protocol.encode({
      version: protocol.PROTOCOL_VERSION,
      type: protocol.TYPES.CLIPBOARD_SET,
      id: crypto.randomUUID(),
      ts: Date.now(),
      payload: {
        text: payload.text,
        hash: payload.hash,
        truncated: payload.truncated,
      },
    });

    const sentCount = sendToPhones(this, frame, 'the clipboard');

    if (sentCount > 0) {
      this.emit('clipboard-sent', { text: payload.text, count: sentCount });
      return true;
    }
    return false;
  }

  /**
   * Asks the phone to dismiss one of its notifications, or to fire one of the
   * notification's own buttons (`action` is "dismiss" or a button id).
   *
   * The phone answers by sending `notification-removed` - the same message a
   * swipe on the phone produces - so the desktop list updates by one path, not
   * two, and never loops back.
   */
  dismissNotification(id, action = 'dismiss') {
    if (typeof id !== 'string' || !id) return false;

    const frame = protocol.encode({
      version: protocol.PROTOCOL_VERSION,
      type: protocol.TYPES.NOTIFICATION_ACTION,
      id: crypto.randomUUID(),
      ts: Date.now(),
      payload: {
        type: protocol.TYPES.NOTIFICATION_ACTION,
        id,
        action,
      },
    });

    return sendToPhones(this, frame, 'a notification action') > 0;
  }

  /**
   * Sends a playback command to the phone.
   *
   * The phone applies it to the session it was showing when the state was
   * produced, so a command never lands on whatever happens to play later.
   */
  sendMediaCommand(command, positionMs) {
    if (typeof command !== 'string' || !command) return false;

    const payload = { type: protocol.TYPES.MEDIA_COMMAND, command };
    if (typeof positionMs === 'number') payload.positionMs = positionMs;

    const frame = protocol.encode({
      version: protocol.PROTOCOL_VERSION,
      type: protocol.TYPES.MEDIA_COMMAND,
      id: crypto.randomUUID(),
      ts: Date.now(),
      payload,
    });

    return sendToPhones(this, frame, 'a media command') > 0;
  }

  updateStatus() {
    const connected = this.connectedClients;
    if (!this.enabled) {
      // Off wins over everything: even a socket lingering from the moment
      // before the switch moved must not read as "connected".
      this.status = 'disabled';
      this.emit('status', { status: 'disabled', detail: OFF_DETAIL });
      return;
    }
    if (connected.length > 0) {
      const device = connected[0];
      this.status = 'connected';
      this.emit('status', {
        status: 'connected',
        detail: `Connected to ${device.deviceName || device.deviceId}`,
        device,
      });
    } else if (this.server) {
      this.status = 'listening';
      this.emit('status', { status: 'listening', detail: `Code: ${this.pairingCode}` });
    } else {
      this.status = 'stopped';
      this.emit('status', { status: 'stopped', detail: null });
    }
  }

  localAddress() {
    const nets = os.networkInterfaces();
    for (const name of Object.keys(nets)) {
      for (const net of nets[name] || []) {
        if (net.family === 'IPv4' && !net.internal) return net.address;
      }
    }
    return '127.0.0.1';
  }

  /** Everything the phone needs to pair, in one object. */
  pairingInfo() {
    return protocol.buildPairingPayload({
      pcName: this.pcName || 'This PC',
      host: this.localAddress(),
      port: this.port,
      code: this.pairingCode,
      fingerprint: this.fingerprint || '',
      pcId: this.pcId,
    });
  }

  /**
   * The pairing QR code, as a data URL the desktop can show.
   *
   * Scanning it is the seamless path: the user never types an address, and
   * the phone gets the certificate fingerprint to pin straight away.
   */
  async pairingQrDataUrl() {
    const qrcode = require('qrcode');
    return qrcode.toDataURL(JSON.stringify(this.pairingInfo()), {
      errorCorrectionLevel: 'M',
      margin: 1,
      width: 320,
      color: { dark: '#0f172a', light: '#ffffff' },
    });
  }

  getInfo() {
    return {
      status: this.status,
      port: this.port,
      code: this.pairingCode,
      codeExpiresAt: this.pairingCodeExpiresAt,
      fingerprint: this.fingerprint,
      address: this.localAddress(),
      connected: this.isConnected,
      clients: this.connectedClients,
      pairedDevices: Object.values(this.pairedDevices),
      destinationDir: this.destinationDir,
      // The kill switch, so the UI can show the switch where it left it.
      enabled: this.enabled,
      // The phone's vitals, or null before the first device-info arrives.
      deviceInfo: this.deviceInfo,
    };
  }
}

/**
 * Routes one notification message to the service's events.
 *
 * Exported as a property of the class so the conformance check can drive it
 * without a socket; production calls it from `handleMessage`.
 *
 * Returns true when the message was a notification one.
 */
function routeNotification(service, message) {
  if (message.type === protocol.TYPES.NOTIFICATION) {
    const notif = protocol.parseNotification(message.payload);
    if (!notif) return false;
    service.emit('notification-received', {
      id: notif.id,
      app: notif.app || notif.pkg,
      title: notif.title,
      text: notif.text,
      // The ADB path calls the body `message`; both keys carry the text so the
      // renderer does not care which transport delivered it.
      message: notif.text,
      key: notif.id,
      // The phone stamps a notification with StatusBarNotification.postTime
      // (epoch ms). `timestamp` is the field both transports agree on; the
      // renderer formats it, and the tooltip shows the exact moment.
      timestamp: Number(notif.ts) || Date.now(),
      actions: notif.actions,
      // Whether the phone keeps it up. A media card or a running download is
      // a different kind of thing from a message that arrived and can be
      // swiped away, and the desktop shows the two apart.
      ongoing: notif.ongoing === true,
      fromCompanion: true,
    });
    return true;
  }

  if (message.type === protocol.TYPES.NOTIFICATION_REMOVED) {
    const id = message.payload && message.payload.id;
    if (typeof id !== 'string') return false;
    service.emit('notification-dismissed', { id });
    return true;
  }

  return false;
}

CompanionService.routeNotification = routeNotification;

/**
 * The phone's vitals, which the ADB transport collects over `adb shell` and
 * the companion channel gets as a plain message.
 *
 * Exported as a property of the class so the check can drive it without a
 * socket; production calls it from `handleMessage`.
 */
function routeDeviceInfo(service, message) {
  if (message.type !== protocol.TYPES.DEVICE_INFO) return false;
  const info = protocol.parseDeviceInfo(message.payload);
  if (!info) return false;
  service.deviceInfo = info;
  service.emit('device-info', info);
  return true;
}

CompanionService.routeDeviceInfo = routeDeviceInfo;

/**
 * Routes the phone's answer to a hash request.
 *
 * A hit opens the cached copy and reports the transfer as done without any
 * bytes crossing the wire. A miss, or an error, falls through to an ordinary
 * preview download. Exported so a check can drive it without a socket.
 */
function routeFileHash(service, message) {
  if (message.type !== protocol.TYPES.FILE_HASH) return false;
  const answer = protocol.parseFileHash(message.payload);
  if (!answer) return false;

  // Use the correlation id whenever the peer supplies one. Path fallback is
  // only for legacy peers that omit ids; it must not reinterpret a stale id.
  let pendingKey = answer.id && service.pendingHashes.has(answer.id) ? answer.id : null;
  if (!pendingKey && !answer.id) {
    for (const [key, value] of service.pendingHashes) {
      if (value.path === answer.path) {
        pendingKey = key;
        break;
      }
    }
  }
  if (!pendingKey) {
    // A late answer for a request we already gave up on. Nothing to do.
    return true;
  }

  const pending = service.pendingHashes.get(pendingKey);
  if (pending.timer) clearTimeout(pending.timer);
  if (!pending.timedOut) service.pendingHashes.delete(pendingKey);

  if (!answer.error && isSha256(answer.sha256)) {
    const hit = service.previewCache.lookup(answer.sha256);
    if (hit) {
      // The bytes are already here. Report the same event a finished download
      // would, so the desktop opens it through one path and the user sees no
      // difference except that it was instant.
      service.emit('file-transfer-done', {
        id: `cache-${answer.sha256.slice(0, 8)}`,
        name: hit.name,
        path: hit.path,
        size: hit.size,
        forViewing: true,
        cached: true,
        deviceName: '',
      });
      return true;
    }
    // Miss: download; the desktop hashes the received bytes before committing.
    service.requestFileSend(answer.path, { open: true, skipCache: true });
    return true;
  }

  // The phone could not hash it. Download anyway; the transfer's own error
  // reporting will say what went wrong if it cannot be read either.
  service.requestFileSend(answer.path, { open: true, skipCache: true });
  return true;
}

CompanionService.routeFileHash = routeFileHash;

/**
 * Routes one media-state message to the service's events.
 *
 * Exported as a property of the class so the check can drive it without a
 * socket; production calls it from `handleMessage`.
 */
function routeMedia(service, message) {
  if (message.type !== protocol.TYPES.MEDIA_STATE) return false;
  const state = protocol.parseMediaState(message.payload);
  if (!state) return false;
  service.emit('media-state', state);
  return true;
}

/**
 * Mirroring outcomes, which arrive on the *control* channel.
 *
 * The picture travels on its own socket, but starting, failing and finishing
 * are reported here: this is the connection the user already trusts and the one
 * the UI is watching, so a failure can say what happened instead of leaving an
 * empty canvas.
 *
 * Exported as a property of the class so the checks can drive it without a
 * socket; production calls it from `handleMessage`.
 */
function routeMirror(service, message) {
  if (message.type === protocol.TYPES.MIRROR_STARTED) {
    service.emit('mirror-started', {
      width: message.payload.width || 0,
      height: message.payload.height || 0,
      encoder: message.payload.encoder || '',
    });
    return true;
  }

  if (message.type === protocol.TYPES.MIRROR_STOPPED) {
    service.emit('mirror-stopped', { reason: message.payload.reason || 'requested' });
    return true;
  }

  if (message.type === protocol.TYPES.MIRROR_FAILED) {
    service.emit('mirror-failed', { reason: message.payload.reason || 'no-encoder' });
    return true;
  }

  return false;
}

CompanionService.routeMirror = routeMirror;

/**
 * Asks the phone to start mirroring.
 *
 * The video port travels with the request: the computer listens, so it owns the
 * port, and the phone only ever dials out.
 */
CompanionService.prototype.startMirror = function startMirror(options = {}) {
  const payload = protocol.buildMirrorStart(options);
  const sent = sendToPhones(
    this,
    protocol.encode({
      version: protocol.PROTOCOL_VERSION,
      type: protocol.TYPES.MIRROR_START,
      id: crypto.randomUUID(),
      ts: Date.now(),
      payload,
    }),
    'a mirror request'
  );
  return sent > 0;
};

/** Asks the phone to stop mirroring. */
CompanionService.prototype.stopMirror = function stopMirror(reason) {
  const payload = protocol.buildMirrorStopped(reason);
  const sent = sendToPhones(
    this,
    protocol.encode({
      version: protocol.PROTOCOL_VERSION,
      type: protocol.TYPES.MIRROR_STOP,
      id: crypto.randomUUID(),
      ts: Date.now(),
      payload,
    }),
    'a mirror stop'
  );
  return sent > 0;
};

CompanionService.routeMedia = routeMedia;

module.exports = CompanionService;
