const { clipboard } = require('electron');
const { EventEmitter } = require('events');
const { fork, spawn, execFile } = require('child_process');
const { promisify } = require('util');
const path = require('path');
const fs = require('fs');

const execFileAsync = promisify(execFile);

// Electron's clipboard API is not usable as a bridge on Linux: on Wayland the
// browser only observes the selection while it holds seat focus, so it reads ""
// while other applications copy, and its own writes never reach the compositor.
// Use the native clipboard tools instead (wl-clipboard on Wayland, xclip on X11)
// and keep the Electron API only as a last-resort fallback.
function hasBinary(name) {
  const dirs = (process.env.PATH ?? '').split(path.delimiter).filter(Boolean);
  return dirs.some((dir) => {
    try {
      fs.accessSync(path.join(dir, name), fs.constants.X_OK);
      return true;
    } catch {
      return false;
    }
  });
}

function detectClipboardBackend() {
  if (process.env.WAYLAND_DISPLAY && hasBinary('wl-paste') && hasBinary('wl-copy')) {
    return 'wayland';
  }
  if (hasBinary('xclip')) return 'x11';
  return 'electron';
}

/**
 * A+ Connect - Clipboard Sync Service (orchestration layer)
 *
 * Owns the bridge child process (services/clipboard-bridge.mjs), watches the
 * desktop clipboard and mirrors it to the phone, and mirrors phone clipboard
 * changes back to the desktop.
 *
 * Loop prevention: both directions compare against the last text seen on the
 * opposite side, so a value that we just pushed is never pushed back.
 */
class ClipboardSyncService extends EventEmitter {
  constructor(options = {}) {
    super();
    this.pollInterval = options.pollInterval ?? 300;
    this.maxTextLength = options.maxTextLength ?? 1024 * 1024;
    this.bridgePath = options.bridgePath ?? path.join(__dirname, 'clipboard-bridge.mjs');
    this.backend = options.backend ?? detectClipboardBackend();
    this.enabled = true;
    this.status = 'idle';
    this.deviceId = null;
    this.child = null;
    this.pollTimer = null;
    this.polling = false;
    this.writing = 0;
    this.lastDesktopText = '';
    this.lastPhoneText = '';
    this.history = [];
  }

  setStatus(status, detail) {
    this.status = status;
    this.emit('status', { status, detail: detail ?? null });
  }

  async start(deviceId) {
    this.stop();
    this.deviceId = deviceId;
    this.lastPhoneText = '';

    // Seed the baseline before spawning, so whatever is already on the desktop
    // is not immediately pushed to the phone on the first poll.
    this.lastDesktopText = await this.readClipboard();
    if (this.deviceId !== deviceId) return; // stopped while the read was in flight

    this.spawnBridge();

    this.pollTimer = setInterval(() => this.pollDesktopClipboard(), this.pollInterval);
  }

  stop() {
    if (this.pollTimer) {
      clearInterval(this.pollTimer);
      this.pollTimer = null;
    }

    if (this.child) {
      const child = this.child;
      this.child = null;
      try {
        child.send({ cmd: 'disconnect' });
        child.send({ cmd: 'quit' });
      } catch {
        // Bridge already gone.
      }
      child.kill();
    }

    this.deviceId = null;
    this.setStatus('idle');
  }

  async setEnabled(enabled) {
    this.enabled = Boolean(enabled);
    if (!this.enabled) {
      this.lastDesktopText = await this.readClipboard();
    }
    this.emit('enabled', this.enabled);
    return this.enabled;
  }

  getHistory() {
    return this.history;
  }

  clearHistory() {
    this.history = [];
    this.emit('history-cleared');
  }

  // Electron 44+ exposes the clipboard API as Promise-based (readText() returns a
  // Promise and availableFormats() is gone); earlier versions are synchronous.
  // Normalise both here, and never let a non-string escape: a Promise reaching
  // IPC serialises to `{}`, which silently sends an empty clipboard to the phone.
  async readClipboard() {
    try {
      if (this.backend === 'wayland') {
        // Ask for text explicitly: a clipboard holding an image then fails
        // instead of dumping raw binary into the sync loop.
        const { stdout } = await execFileAsync(
          'wl-paste',
          ['-n', '-t', 'text/plain;charset=utf-8'],
          { timeout: 2000, maxBuffer: 4 * 1024 * 1024 },
        );
        return typeof stdout === 'string' && !stdout.includes('\u0000') ? stdout : '';
      }

      if (this.backend === 'x11') {
        const { stdout } = await execFileAsync('xclip', ['-o', '-selection', 'clipboard'], {
          timeout: 2000,
          maxBuffer: 4 * 1024 * 1024,
        });
        return typeof stdout === 'string' ? stdout : '';
      }

      const value = clipboard.readText();
      const text = value && typeof value.then === 'function' ? await value : value;
      return typeof text === 'string' ? text : '';
    } catch {
      // No text on the clipboard (an image, or nothing at all).
      return '';
    }
  }

  async writeClipboard(text) {
    if (typeof text !== 'string' || !text) return false;

    this.writing += 1;
    try {
      if (this.backend === 'wayland') {
        const started = await this.writeWithBinary('wl-copy', [], text);
        if (!started) return false;
        // Wait until the compositor actually serves the new value. Without this
        // the next poll still reads the previous clipboard and bounces it back.
        return await this.waitForClipboardText(text);
      }

      if (this.backend === 'x11') {
        const started = await this.writeWithBinary('xclip', ['-i', '-selection', 'clipboard'], text);
        if (!started) return false;
        return await this.waitForClipboardText(text);
      }

      const result = clipboard.writeText(text);
      if (result && typeof result.then === 'function') await result;
      return true;
    } catch {
      return false;
    } finally {
      this.writing -= 1;
    }
  }

  // Condition-based wait (no fixed sleep): poll until the clipboard holds the
  // text we just wrote, or give up.
  async waitForClipboardText(text, timeoutMs = 1500) {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      if ((await this.readClipboard()) === text) return true;
      await new Promise((resolve) => setTimeout(resolve, 30));
    }
    return false;
  }

  // Native clipboard tools stay resident to serve the selection, so they must be
  // detached: they own the clipboard independently of this app's lifetime.
  writeWithBinary(binary, args, text) {
    return new Promise((resolve) => {
      let settled = false;
      const done = (value) => {
        if (settled) return;
        settled = true;
        resolve(value);
      };

      let child;
      try {
        child = spawn(binary, args, { detached: true, stdio: ['pipe', 'ignore', 'ignore'] });
      } catch {
        done(false);
        return;
      }

      child.on('error', () => done(false));
      child.stdin.on('error', () => done(false));
      child.on('spawn', () => {
        child.stdin.end(text);
        child.unref();
        done(true);
      });
    });
  }

  spawnBridge() {
    let child;
    try {
      child = fork(this.bridgePath, [], { stdio: ['ignore', 'pipe', 'pipe', 'ipc'] });
    } catch (e) {
      this.setStatus('error', `Could not start clipboard bridge: ${e.message}`);
      return;
    }

    this.child = child;
    this.setStatus('connecting');

    child.on('message', (message) => this.handleBridgeMessage(message));

    child.on('error', (e) => {
      this.setStatus('error', e.message);
    });

    child.on('exit', (code) => {
      if (this.child === child) {
        this.child = null;
        this.setStatus('disconnected', `Clipboard bridge stopped (code ${code})`);
      }
    });

    child.stderr?.on('data', (data) => {
      console.error('[clipboard-bridge]', data.toString().trim());
    });

    child.send({ cmd: 'connect', serial: this.deviceId });
  }

  handleBridgeMessage(message) {
    if (!message || typeof message !== 'object') return;

    switch (message.evt) {
      case 'ready':
        if (message.status === 'idle') {
          this.setStatus('connecting');
        } else {
          this.setStatus('syncing', message.serial);
        }
        break;

      case 'clipboard':
        void this.receiveFromPhone(message.text);
        break;

      case 'error':
        this.setStatus('error', message.message);
        break;

      case 'closed':
        this.setStatus('disconnected', message.reason);
        break;
    }
  }

  async receiveFromPhone(text) {
    if (typeof text !== 'string' || !text) return;

    // The device can emit the same clipboard value more than once, and a second
    // copy can arrive while the first is still being written. Dedupe before any
    // await, otherwise the value is written twice and logged twice.
    if (text === this.lastPhoneText) return;

    // Remember it first so the desktop poll does not push it straight back.
    this.lastPhoneText = text;

    if (text === this.lastDesktopText) {
      // Already on the desktop (this is our own echo coming back).
      return;
    }

    if (await this.writeClipboard(text)) {
      this.lastDesktopText = text;
      this.record('phone-to-desktop', text);
      this.emit('synced', { direction: 'phone-to-desktop', text });
    }
  }

  async pollDesktopClipboard() {
    // A clipboard read is asynchronous on Electron 44+, so overlapping polls
    // would race each other and report the same value more than once. Polls are
    // also suspended while a write is in flight, so a half-applied write cannot
    // be mistaken for a fresh user copy.
    if (!this.enabled || !this.child || this.polling || this.writing > 0) return;

    this.polling = true;
    try {
      const text = await this.readClipboard();
      if (!text || text === this.lastDesktopText) return;

      this.lastDesktopText = text;

      // Skip values we just received from the phone.
      if (text === this.lastPhoneText) return;

      if (text.length > this.maxTextLength) return;

      this.lastPhoneText = text;
      try {
        this.child.send({ cmd: 'set', text });
        this.record('desktop-to-phone', text);
        this.emit('synced', { direction: 'desktop-to-phone', text });
      } catch {
        // Bridge died between the check and the send.
      }
    } finally {
      this.polling = false;
    }
  }

  pushToPhone(text) {
    if (typeof text !== 'string' || !text) return false;
    if (!this.child) return false;
    try {
      this.child.send({ cmd: 'set', text });
      this.lastPhoneText = text;
      this.record('desktop-to-phone', text);
      this.emit('synced', { direction: 'desktop-to-phone', text });
      return true;
    } catch {
      return false;
    }
  }

  record(direction, text) {
    // Defence in depth: everything that reaches the renderer must be a plain
    // string. A Promise here serialises to `{}` (or throws) across IPC.
    if (typeof text !== 'string' || !text) return;

    const entry = {
      id: `clip_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`,
      direction,
      text,
      preview: text.length > 160 ? `${text.slice(0, 160)}…` : text,
      time: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
    };
    this.history.unshift(entry);
    if (this.history.length > 50) this.history.pop();
    this.emit('history-updated', entry);
  }
}

module.exports = ClipboardSyncService;
