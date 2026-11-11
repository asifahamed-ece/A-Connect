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

const PAIRED_DEVICES_FILE = path.join(
  process.env.HOME || process.env.USERPROFILE || '.',
  '.config',
  'aplus-connect',
  'companion',
  'paired-devices.json'
);

class CompanionService extends EventEmitter {
  constructor(options = {}) {
    super();
    this.port = options.port ?? DEFAULT_PORT;
    this.pcId = options.pcId ?? 'pc-test';
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
      this.emit('error', err);
      this.emit('status', { status: 'error', detail: err.message });
      return;
    }

    const { certPath, keyPath, fingerprint } = certData;
    this.fingerprint = fingerprint;

    this.server = tls.createServer(
      { cert: fs.readFileSync(certPath), key: fs.readFileSync(keyPath) },
      (socket) => this.handleConnection(socket)
    );

    this.server.on('error', (err) => {
      console.error('[companion] server error:', err.message);
      this.status = 'error';
      this.emit('error', err);
      this.emit('status', { status: 'error', detail: err.message });
    });

    this.server.listen(this.port, '0.0.0.0', () => {
      this.status = 'listening';
      console.log(`[companion] TLS server listening on ${this.localAddress()}:${this.port}`);
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
        console.log(`[companion] phone disconnected: ${socket.deviceName || socket.clientIp}`);
        this.emit('client-disconnected', { deviceId: socket.deviceId, ip: socket.clientIp });
      }
      this.updateStatus();
    });
  }

  handleData(socket, chunk) {
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
        if (!isCodeValid) {
          console.log(`[companion] refusing connection from ${socket.clientIp}: invalid or expired pairing code`);
          socket.write(
            protocol.encode({
              version: 1,
              type: protocol.TYPES.HELLO_ERROR,
              id: 'err',
              ts: Date.now(),
              payload: { reason: protocol.REASONS.BAD_CODE },
            })
          );
          socket.end();
          return;
        }

        // Successfully paired with code
        this.pairedDevices[deviceId] = {
          deviceId,
          deviceName,
          pairedAt: Date.now(),
        };
        this.savePairedDevices();
        console.log(`[companion] newly paired with ${deviceName} (${deviceId})`);
      } else {
        console.log(`[companion] recognized returning paired device: ${deviceName} (${deviceId})`);
      }

      socket.handshakeDone = true;
      socket.deviceId = deviceId;
      socket.deviceName = deviceName;

      socket.write(
        protocol.encode({
          version: 1,
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

    if (message.type === protocol.TYPES.PING) {
      socket.write(
        protocol.encode({
          version: 1,
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

  sendClipboard(text) {
    if (typeof text !== 'string' || !text) return false;
    const payload = protocol.buildClipboardPayload(text);
    if (!protocol.shouldSendClipboard(payload, this.lastSentHash)) return false;
    this.lastSentHash = payload.hash;

    const frame = protocol.encode({
      version: 1,
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

module.exports = CompanionService;
