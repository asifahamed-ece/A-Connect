const { spawn, execFile } = require('child_process');
const { promisify } = require('util');
const path = require('path');
const fs = require('fs');
const os = require('os');
const EventEmitter = require('events');

const execFileAsync = promisify(execFile);

// Single-quote a path for the device shell, so names with spaces survive.
function shellQuote(value) {
  return `'${String(value).replace(/'/g, `'\\''`)}'`;
}

class FileTransferService extends EventEmitter {
  constructor(adbPath = 'adb') {
    super();
    this.adbPath = adbPath;
    this.activeTransfers = new Map();
    this.cancelled = new Set();
  }

  /** Stop an in-flight push/pull. Resolves the original promise as cancelled. */
  cancelTransfer(transferId) {
    const proc = this.activeTransfers.get(transferId);
    if (!proc) return false;

    this.cancelled.add(transferId);
    this.activeTransfers.delete(transferId);
    try {
      proc.kill('SIGTERM');
      // adb sometimes needs a firmer nudge.
      setTimeout(() => {
        try {
          proc.kill('SIGKILL');
        } catch {}
      }, 1500).unref?.();
    } catch {}

    this.emit('transfer-cancelled', { id: transferId });
    return true;
  }

  cancelAll() {
    let count = 0;
    for (const id of [...this.activeTransfers.keys()]) {
      if (this.cancelTransfer(id)) count += 1;
    }
    return count;
  }

  async pushFile(deviceId, localFilePath, remoteDestDir = '/sdcard/Download/') {
    const filename = path.basename(localFilePath);
    const transferId = `push_${Date.now()}_${Math.random().toString(36).substring(2, 7)}`;

    let size = 0;
    try {
      size = fs.statSync(localFilePath).size;
    } catch {}

    return new Promise((resolve, reject) => {
      this.emit('transfer-started', {
        id: transferId,
        type: 'push',
        filename,
        size,
        localFilePath,
        remoteDestDir
      });

      const args = [];
      if (deviceId) args.push('-s', deviceId);
      args.push('push', localFilePath, remoteDestDir);

      const proc = spawn(this.adbPath, args);
      this.activeTransfers.set(transferId, proc);

      let output = '';
      proc.stdout.on('data', (d) => { output += d.toString(); });
      proc.stderr.on('data', (d) => { output += d.toString(); });

      proc.on('close', async (code) => {
        this.activeTransfers.delete(transferId);

        if (this.cancelled.has(transferId)) {
          this.cancelled.delete(transferId);
          this.emit('transfer-cancelled', { id: transferId, filename, type: 'push' });
          resolve({ success: false, cancelled: true });
          return;
        }

        if (code === 0) {
          // Trigger ColorOS media scanner so new files appear in Photos / File Manager immediately
          const targetPath = `${remoteDestDir.replace(/\/$/, '')}/${filename}`;
          await this.triggerMediaScan(deviceId, targetPath).catch(() => {});

          // Deliberately no `cmd notification post` here: shell-owned
          // notifications show up as "Shell" and cannot carry a tap intent, so
          // they could never open the file. The media scan is what makes the
          // file appear in Downloads / Photos.

          this.emit('transfer-completed', {
            id: transferId,
            type: 'push',
            filename,
            targetPath,
            output
          });
          resolve({ success: true, targetPath, output });
        } else {
          const err = new Error(`File push failed: ${output}`);
          this.emit('transfer-failed', { id: transferId, error: err.message });
          reject(err);
        }
      });

      proc.on('error', (err) => {
        this.activeTransfers.delete(transferId);
        if (this.cancelled.delete(transferId)) {
          this.emit('transfer-cancelled', { id: transferId, filename, type: 'push' });
          resolve({ success: false, cancelled: true });
          return;
        }
        this.emit('transfer-failed', { id: transferId, error: err.message });
        reject(err);
      });
    });
  }

  async triggerMediaScan(deviceId, remoteFilePath) {
    const args = [];
    if (deviceId) args.push('-s', deviceId);
    args.push('shell', 'am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE', '-d', `file://${remoteFilePath}`);

    return new Promise((resolve) => {
      execFile(this.adbPath, args, { timeout: 5000 }, (error, stdout) => {
        resolve(!error);
      });
    });
  }

  async sendNotificationToDevice(deviceId, title, message, tag = 'aplus_connect') {
    const args = [];
    if (deviceId) args.push('-s', deviceId);
    // One shell string with quoting: passing argv separately lets adb re-split
    // the title, so "Screen mirroring is active" becomes four arguments.
    args.push(
      'shell',
      `cmd notification post -t ${shellQuote(title)} ${shellQuote(tag)} ${shellQuote(message)}`
    );

    return new Promise((resolve) => {
      execFile(this.adbPath, args, { timeout: 3000 }, (error) => {
        resolve(!error);
      });
    });
  }

  /**
   * Directory listing through the adb sync protocol (`adb ls`), not the device
   * shell: the output is "mode size mtime name" with the three numeric fields in
   * hex and the name last, so names containing spaces are unambiguous.
   */
  async listDirectory(deviceId, remotePath = '/sdcard') {
    const args = [];
    if (deviceId) args.push('-s', deviceId);
    args.push('ls', remotePath);

    const { stdout } = await execFileAsync(this.adbPath, args, {
      timeout: 10000,
      maxBuffer: 8 * 1024 * 1024,
    });

    const entries = [];
    for (const line of stdout.split('\n')) {
      const match = line.match(/^([0-9a-fA-F]+)\s+([0-9a-fA-F]+)\s+([0-9a-fA-F]+)\s([\s\S]*)$/);
      if (!match) continue;

      const mode = parseInt(match[1], 16);
      const size = parseInt(match[2], 16);
      const mtime = parseInt(match[3], 16) * 1000;
      const name = match[4];
      if (!name || name === '.' || name === '..') continue;

      entries.push({
        name,
        size,
        mtime,
        isDir: (mode & 0o170000) === 0o040000,
        isLink: (mode & 0o170000) === 0o120000,
        path: remotePath.replace(/\/$/, '') + '/' + name,
      });
    }

    entries.sort((a, b) => {
      if (a.isDir !== b.isDir) return a.isDir ? -1 : 1;
      return a.name.localeCompare(b.name, undefined, { sensitivity: 'base' });
    });

    return entries;
  }

  async deletePath(deviceId, remotePath) {
    const args = [];
    if (deviceId) args.push('-s', deviceId);
    args.push('shell', `rm -rf ${shellQuote(remotePath)}`);
    await execFileAsync(this.adbPath, args, { timeout: 15000 });
    return { success: true };
  }

  async makeDirectory(deviceId, remotePath) {
    const args = [];
    if (deviceId) args.push('-s', deviceId);
    args.push('shell', `mkdir -p ${shellQuote(remotePath)}`);
    await execFileAsync(this.adbPath, args, { timeout: 8000 });
    return { success: true };
  }

  async listRecentPhotos(deviceId, limit = 20) {
    const args = [];
    if (deviceId) args.push('-s', deviceId);
    // Find latest photos from Camera and Screenshots
    args.push('shell', `ls -t /sdcard/DCIM/Camera/* /sdcard/Pictures/Screenshots/* 2>/dev/null | head -n ${limit}`);

    return new Promise((resolve) => {
      execFile(this.adbPath, args, { timeout: 8000 }, (error, stdout) => {
        if (error || !stdout) return resolve([]);
        const files = stdout.trim().split('\n')
          .map(f => f.trim())
          .filter(f => f.length > 0 && !f.includes('No such file'));
        resolve(files);
      });
    });
  }

  async pullFile(deviceId, remoteFilePath, localDestDir = path.join(os.homedir(), 'Downloads', 'A+Connect')) {
    if (!fs.existsSync(localDestDir)) {
      fs.mkdirSync(localDestDir, { recursive: true });
    }

    const filename = path.basename(remoteFilePath);
    const localTarget = path.join(localDestDir, filename);
    const transferId = `pull_${Date.now()}_${Math.random().toString(36).substring(2, 7)}`;

    return new Promise((resolve, reject) => {
      this.emit('transfer-started', {
        id: transferId,
        type: 'pull',
        filename,
        remoteFilePath,
        localTarget
      });

      const args = [];
      if (deviceId) args.push('-s', deviceId);
      args.push('pull', remoteFilePath, localTarget);

      const proc = spawn(this.adbPath, args);
      this.activeTransfers.set(transferId, proc);

      proc.on('close', (code) => {
        this.activeTransfers.delete(transferId);

        if (this.cancelled.has(transferId)) {
          this.cancelled.delete(transferId);
          this.emit('transfer-cancelled', { id: transferId, filename, type: 'pull' });
          resolve({ success: false, cancelled: true });
          return;
        }

        if (code === 0) {
          this.emit('transfer-completed', { id: transferId, type: 'pull', filename, localTarget });
          resolve({ success: true, localTarget });
        } else {
          const err = new Error(`File pull failed with exit code ${code}`);
          this.emit('transfer-failed', { id: transferId, error: err.message });
          reject(err);
        }
      });

      proc.on('error', (err) => {
        this.activeTransfers.delete(transferId);
        if (this.cancelled.delete(transferId)) {
          this.emit('transfer-cancelled', { id: transferId, type: 'pull' });
          resolve({ success: false, cancelled: true });
          return;
        }
        this.emit('transfer-failed', { id: transferId, error: err.message });
        reject(err);
      });

      proc.on('error', (err) => {
        this.activeTransfers.delete(transferId);
        this.emit('transfer-failed', { id: transferId, error: err.message });
        reject(err);
      });
    });
  }
}

module.exports = FileTransferService;
