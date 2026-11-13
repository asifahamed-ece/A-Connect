'use strict';

const fs = require('fs');
const tls = require('tls');
const { EventEmitter } = require('events');

const protocol = require('./companion-protocol');
const handshake = require('./companion-handshake');
const { ensureCertificate } = require('./companion-cert');

/**
 * How long the picture may be silent before the link is presumed dead.
 *
 * TCP is patient: a phone that walks out of Wi-Fi range leaves the socket open
 * until a keepalive or the control channel times out, which took over a minute
 * in testing. Until then the canvas shows the last frame and the card still
 * says "streaming" - a frozen picture nobody explains.
 *
 * The encoder on the target phone produces frames continuously, even on a
 * still screen, so six seconds of silence is a dead link and not a still one.
 */
const FRAME_STALL_MS = 6000;

/**
 * The video socket.
 *
 * Screen mirroring gets its own connection rather than sharing the control
 * channel: a two megabyte keyframe sitting in the same TCP stream as a clipboard
 * copy delays it, and a file transfer next to a video makes both stutter. One
 * socket per job, one policy per socket.
 *
 * What it does *not* get is its own trust story. The listener answers with the
 * same desktop certificate the phone has already pinned, and a phone gets on
 * only through the same handshake the control channel uses - with pairing
 * turned off, because a pairing code belongs where the user is looking at it.
 */
class MirrorService extends EventEmitter {
  constructor(options = {}) {
    super();
    this.port = options.port ?? 0;
    this.pcId = options.pcId ?? 'pc-test';
    this.fingerprint = options.fingerprint ?? '';
    this.pairedDevices = options.pairedDevices ?? {};
    this.isPairingCodeValid = options.isPairingCodeValid ?? (() => false);
    this.savePairedDevices = options.savePairedDevices ?? (() => {});

    /** Overridable so the check does not have to wait six real seconds. */
    this.frameStallMs = options.frameStallMs ?? FRAME_STALL_MS;

    this.server = null;
    this.sockets = new Set();
    this.status = 'stopped';

    /** Frames counted per session, for the log and for the checks. */
    this.frames = 0;
    this.dropped = 0;
  }

  /** The host the shared handshake needs; the two services keep one store. */
  host() {
    return {
      pairedDevices: this.pairedDevices,
      pcId: this.pcId,
      fingerprint: this.fingerprint,
      isPairingCodeValid: this.isPairingCodeValid,
      savePairedDevices: this.savePairedDevices,
      logLine: (text) => this.log(text),
      onPaired: (device) => this.emit('client-paired', device),
    };
  }

  log(message) {
    console.log(`[mirror ${new Date().toISOString().slice(11, 19)}] ${message}`);
  }

  /**
   * Starts listening. Resolves with the port actually bound, which is how a
   * caller asking for port 0 finds out what it got - the checks lean on that.
   */
  start(port = this.port) {
    return new Promise((resolve, reject) => {
      if (this.server) {
        resolve(this.port);
        return;
      }

      let certPath;
      let keyPath;
      try {
        const cert = ensureCertificate();
        certPath = cert.certPath;
        keyPath = cert.keyPath;
      } catch (err) {
        console.error('[mirror] certificate error:', err.message);
        reject(err);
        return;
      }

      this.server = tls.createServer(
        {
          cert: fs.readFileSync(certPath),
          key: fs.readFileSync(keyPath),
        },
        (socket) => this.handleConnection(socket)
      );

      this.server.on('error', (err) => {
        this.status = 'error';
        // A busy port is a report, not a reason to take the app down: the
        // caller turns it into "screen mirroring could not start" and stops.
        if (!this.server || this.status === 'error') {
          console.error('[mirror] listener error:', err.message);
        }
        reject(err);
      });

      this.server.listen(port, '0.0.0.0', () => {
        this.port = this.server.address().port;
        this.status = 'listening';
        this.log(`video listening on ${this.port}`);
        resolve(this.port);
      });
    });
  }

  stop() {
    for (const socket of this.sockets) {
      if (socket.watchdog) clearInterval(socket.watchdog);
      socket.watchdog = null;
      try {
        socket.destroy();
      } catch {}
    }
    this.sockets.clear();

    if (this.server) {
      try {
        this.server.close();
      } catch {}
      this.server = null;
    }
    this.status = 'stopped';
    this.frames = 0;
    this.dropped = 0;
  }

  handleConnection(socket) {
    socket.clientIp = socket.remoteAddress?.replace(/^.*:/, '') || 'unknown';
    socket.buffer = Buffer.alloc(0);
    socket.handshakeDone = false;
    this.sockets.add(socket);

    socket.on('data', (chunk) => this.handleData(socket, chunk));
    socket.on('error', (err) => {
      this.log(`socket error (${socket.clientIp}): ${err.message}`);
    });
    socket.on('close', () => {
      if (socket.watchdog) clearInterval(socket.watchdog);
      socket.watchdog = null;
      this.sockets.delete(socket);
      if (socket.handshakeDone) {
        this.log(`video socket closed: ${socket.deviceName || socket.clientIp}`);
        this.emit('client-closed', { deviceId: socket.deviceId, ip: socket.clientIp });
      }
    });
  }

  /**
   * Reads whole frames out of the stream, one at a time.
   *
   * Both kinds can arrive here: JSON for the handshake, binary for video. The
   * envelope is the same one the control channel uses, so the reader is the
   * same shape - only what it does with the payload differs.
   */
  handleData(socket, chunk) {
    socket.buffer = Buffer.concat([socket.buffer, chunk]);

    while (socket.buffer.length >= 4) {
      const length = socket.buffer.readUInt32BE(0);
      if (length < 1 || length > protocol.MAX_FRAME_BYTES) {
        this.log(`illegal frame length ${length}; closing the video socket`);
        socket.destroy();
        return;
      }
      if (socket.buffer.length < 4 + length) return;

      const frameBytes = socket.buffer.subarray(0, 4 + length);
      socket.buffer = socket.buffer.subarray(4 + length);

      const kind = frameBytes.readUInt8(4);
      if (kind === protocol.KIND_JSON) {
        let message;
        try {
          message = protocol.decode(frameBytes);
        } catch (err) {
          this.log(`undecodable message: ${err.message}`);
          continue;
        }
        this.handleMessage(socket, message);
        continue;
      }

      // Binary: video, and nothing else yet.
      const body = frameBytes.subarray(5);
      this.handleVideo(socket, body);
    }
  }

  handleMessage(socket, message) {
    if (message.type === protocol.TYPES.HELLO) {
      // Same handshake as the control channel, minus the ability to pair.
      const trusted = handshake.handleHello(this.host(), socket, message, { allowPairing: false });
      if (trusted) this.armWatchdog(socket);
      return;
    }

    if (!socket.handshakeDone) return;

    // The video socket carries video. Anything else is ignored rather than
    // answered, so there is no second control protocol to keep in step.
  }

  /**
   * Watches a trusted socket for silence.
   *
   * Armed only after the handshake: before that the socket is allowed to sit
   * through a slow TLS setup without being called dead.
   */
  armWatchdog(socket) {
    socket.lastFrameAt = Date.now();
    socket.watchdog = setInterval(() => {
      if (!socket.handshakeDone) return;
      const silentMs = Date.now() - socket.lastFrameAt;
      if (silentMs < this.frameStallMs) return;

      this.log(`no frames for ${Math.round(silentMs / 1000)}s; treating the picture as lost`);
      // The caller decides what the user sees; this socket has no way back.
      this.emit('stalled', { deviceId: socket.deviceId });
      socket.destroy();
    }, 1000);
  }

  /**
   * One video frame: parse it, count it, hand it on.
   *
   * A frame that cannot be parsed is dropped and the socket stays open. Dying
   * on one bad packet would end the whole session for a problem the user did
   * not cause.
   */
  handleVideo(socket, body) {
    if (!socket.handshakeDone) return;

    const frame = protocol.parseVideoFrame(body);
    if (!frame) {
      this.dropped += 1;
      return;
    }
    this.frames += 1;
    socket.lastFrameAt = Date.now();
    this.emit('frame', {
      keyframe: frame.keyframe,
      ptsMicros: frame.ptsMicros,
      payload: frame.payload,
    });
  }
}

module.exports = MirrorService;