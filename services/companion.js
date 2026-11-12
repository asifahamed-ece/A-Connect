'use strict';

const { EventEmitter } = require('events');
const fs = require('fs');
const os = require('os');
const path = require('path');
const tls = require('tls');
const crypto = require('crypto');

const protocol = require('./companion-protocol');
const { ensureCertificate } = require('./companion-cert');

const DEFAULT_PORT = 24443;
const PAIRING_CODE_TTL_MS = 5 * 60 * 1000; // 5 minutes

// A link that carries no traffic is dropped by the network (Wi-Fi sleep,
// NAT timeouts). The protocol has PING and PONG for exactly this: the
// computer pings, the phone answers, and a client that stops answering is
// closed instead of lingering as a half-open socket.
const PING_INTERVAL_MS = 30 * 1000;
const PEER_TIMEOUT_MS = 75 * 1000;

const PAIRED_DEVICES_FILE = path.join(
  process.env.HOME || process.env.USERPROFILE || '.',
  '.config',
  'aplus-connect',
  'companion',
  'paired-devices.json'
);

/** A log line with the time, so a drop can be placed in a timeline. */
function log(message) {
  console.log(`[companion ${new Date().toISOString().slice(11, 19)}] ${message}`);
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
    this.destinationDir = options.destinationDir || path.join(os.homedir(), 'Downloads');

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
    this.status = 'stopped';
    this.emit('status', { status: 'stopped', detail: null });
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
      const deviceId = message.payload.deviceId;
      const deviceName = message.payload.deviceName || 'Phone';
      const isAlreadyPaired = Boolean(this.pairedDevices[deviceId]);

      if (!isAlreadyPaired) {
        const isCodeValid = this.isPairingCodeValid(message.payload.code);
        const secret = message.payload.secret;
        if (!isCodeValid || !secret) {
          log(`refusing connection from ${socket.clientIp}: invalid or expired pairing code`);
          socket.write(
            protocol.encode({
              version: protocol.PROTOCOL_VERSION,
              type: protocol.TYPES.HELLO_ERROR,
              id: 'err',
              ts: Date.now(),
              payload: { reason: protocol.REASONS.BAD_CODE },
            })
          );
          socket.end();
          return;
        }

        // Paired with the code: keep the phone's secret so it can prove it is
        // the same phone next time.
        this.pairedDevices[deviceId] = {
          deviceId,
          deviceName,
          pairedAt: Date.now(),
          secret: String(secret),
        };
        this.savePairedDevices();
        log(`newly paired with ${deviceName} (${deviceId})`);
      } else {
        // A known device has to prove it still holds the secret from the
        // pairing. Knowing the device id is not enough: ids leak through logs
        // and backups, and a leaked id would otherwise hand over the whole
        // clipboard.
        const paired = this.pairedDevices[deviceId];
        const secret = paired.secret ? Buffer.from(paired.secret, 'base64') : null;
        const proof = message.payload.proof;
        const nonce = message.payload.nonce;

        const proofIsValid =
          secret && proof && protocol.verifyProof(secret, deviceId, nonce, proof);

        if (!proofIsValid) {
          // A phone that lost its secret (or one that was paired before the
          // secret existed) can pair again with the live code - the same
          // five minute window the first pairing uses. Without a valid code
          // there is nothing to fall back on, so the connection is refused.
          const canRePair = this.isPairingCodeValid(message.payload.code) && message.payload.secret;
          if (canRePair) {
            this.pairedDevices[deviceId] = {
              deviceId,
              deviceName,
              pairedAt: Date.now(),
              secret: String(message.payload.secret),
            };
            this.savePairedDevices();
            log(`re-paired ${deviceName} (${deviceId}) with the live code`);
          } else {
            console.log(`[companion] refusing ${deviceName} (${deviceId}): no valid proof of possession`);
            socket.write(
              protocol.encode({
                version: protocol.PROTOCOL_VERSION,
                type: protocol.TYPES.HELLO_ERROR,
                id: 'err',
                ts: Date.now(),
                payload: { reason: protocol.REASONS.AUTH_FAILED },
              })
            );
            socket.end();
            return;
          }
        } else {
          log(`recognized returning paired device: ${deviceName} (${deviceId})`);
        }
      }

      socket.handshakeDone = true;
      socket.deviceId = deviceId;
      socket.deviceName = deviceName;

      socket.write(
        protocol.encode({
          version: protocol.PROTOCOL_VERSION,
          type: protocol.TYPES.HELLO_ACK,
          id: 'ack',
          ts: Date.now(),
          payload: {
            sessionId: 's-' + Date.now(),
            pcId: this.pcId,
            fingerprint: this.fingerprint,
            nonce: message.payload.nonce,
          },
        })
      );

      this.emit('client-paired', {
        deviceId: socket.deviceId,
        deviceName: socket.deviceName,
        ip: socket.clientIp,
        isNew: !isAlreadyPaired,
      });
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

    if (routeNotification(this, message)) return;
    if (routeMedia(this, message)) return;

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
    const destDir = this.destinationDir || path.join(os.homedir(), 'Downloads');
    if (!fs.existsSync(destDir)) {
      try { fs.mkdirSync(destDir, { recursive: true }); } catch {}
    }

    let safeName = path.basename(name || 'transfer').replace(/[^a-zA-Z0-9._-]/g, '_');
    let targetPath = path.join(destDir, safeName);
    let counter = 1;
    const ext = path.extname(safeName);
    const base = path.basename(safeName, ext);
    while (fs.existsSync(targetPath)) {
      safeName = `${base} (${counter++})${ext}`;
      targetPath = path.join(destDir, safeName);
    }

    const stream = fs.createWriteStream(targetPath);
    if (!socket.activeTransfers) socket.activeTransfers = new Map();
    socket.activeTransfers.set(id, {
      id,
      name: safeName,
      targetPath,
      size,
      received: 0,
      stream,
      startedAt: Date.now(),
    });

    console.log(`[companion] incoming file from ${socket.deviceName}: ${safeName} (${size} bytes)`);
    this.emit('file-transfer-start', {
      id,
      name: safeName,
      size,
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
      console.log(`[companion] file saved successfully to ${transfer.targetPath}`);
      socket.activeTransfers.delete(id);
      this.emit('file-transfer-done', {
        id,
        name: transfer.name,
        path: transfer.targetPath,
        size: transfer.received,
        deviceName: socket.deviceName,
      });
    });
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
      this.emit('file-sent', { name, size: stat.size, count: targets.length });
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

    let sentCount = 0;
    for (const socket of this.clients) {
      if (socket.handshakeDone && !socket.destroyed) {
        try {
          socket.write(frame);
          sentCount += 1;
        } catch (e) {
          console.error(`[companion] error sending clipboard to ${socket.clientIp || socket.remoteAddress}:`, e.message);
        }
      }
    }

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

    let sentCount = 0;
    for (const socket of this.clients) {
      if (socket.handshakeDone && !socket.destroyed) {
        try {
          socket.write(frame);
          sentCount += 1;
        } catch (e) {
          console.error(`[companion] could not send a notification action: ${e.message}`);
        }
      }
    }
    return sentCount > 0;
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

    let sentCount = 0;
    for (const socket of this.clients) {
      if (socket.handshakeDone && !socket.destroyed) {
        try {
          socket.write(frame);
          sentCount += 1;
        } catch (e) {
          console.error(`[companion] could not send a media command: ${e.message}`);
        }
      }
    }
    return sentCount > 0;
  }

  updateStatus() {
    const connected = this.connectedClients;
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
      time: notif.ts,
      actions: notif.actions,
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

CompanionService.routeMedia = routeMedia;

module.exports = CompanionService;
