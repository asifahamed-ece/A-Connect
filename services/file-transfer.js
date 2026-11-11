const { spawn, execFile } = require('child_process');
const path = require('path');
const fs = require('fs');
const os = require('os');
const EventEmitter = require('events');

class FileTransferService extends EventEmitter {
  constructor(adbPath = 'adb') {
    super();
    this.adbPath = adbPath;
    this.activeTransfers = new Map();
  }

  async pushFile(deviceId, localFilePath, remoteDestDir = '/sdcard/Download/') {
    const filename = path.basename(localFilePath);
    const transferId = `push_${Date.now()}_${Math.random().toString(36).substring(2, 7)}`;

    return new Promise((resolve, reject) => {
      this.emit('transfer-started', {
        id: transferId,
        type: 'push',
        filename,
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
        if (code === 0) {
          // Trigger ColorOS media scanner so new files appear in Photos / File Manager immediately
          const targetPath = `${remoteDestDir.replace(/\/$/, '')}/${filename}`;
          await this.triggerMediaScan(deviceId, targetPath).catch(() => {});

          // Show notification on phone that file was received
          await this.sendNotificationToDevice(deviceId, '📂 A+ Connect', `File received: ${filename}`).catch(() => {});

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

  async sendNotificationToDevice(deviceId, title, message) {
    const args = [];
    if (deviceId) args.push('-s', deviceId);
    args.push('shell', 'cmd', 'notification', 'post', '-t', title, 'aplus_connect', message);

    return new Promise((resolve) => {
      execFile(this.adbPath, args, { timeout: 3000 }, (error) => {
        resolve(!error);
      });
    });
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
        if (code === 0) {
          this.emit('transfer-completed', { id: transferId, type: 'pull', localTarget });
          resolve({ success: true, localTarget });
        } else {
          const err = new Error(`File pull failed with exit code ${code}`);
          this.emit('transfer-failed', { id: transferId, error: err.message });
          reject(err);
        }
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
