const { execFile, spawn } = require('child_process');
const EventEmitter = require('events');

class AdbService extends EventEmitter {
  constructor(adbPath = 'adb') {
    super();
    this.adbPath = adbPath;
    this.pollInterval = null;
    this.connectedDevice = null;
    this.isPolling = false;
  }

  exec(args) {
    return new Promise((resolve, reject) => {
      execFile(this.adbPath, args, { timeout: 10000 }, (error, stdout, stderr) => {
        if (error) {
          reject(new Error(stderr.trim() || error.message));
        } else {
          resolve(stdout.trim());
        }
      });
    });
  }

  async getDevices() {
    try {
      const output = await this.exec(['devices', '-l']);
      const lines = output.split('\n').slice(1);
      const devices = [];

      for (const line of lines) {
        const trimmed = line.trim();
        if (!trimmed) continue;
        const parts = trimmed.split(/\s+/);
        if (parts.length >= 2) {
          const id = parts[0];
          const state = parts[1];

          // Skip if not in 'device' state (unauthorized, offline, etc.)
          if (state !== 'device') continue;

          const extra = {};
          for (let i = 2; i < parts.length; i++) {
            const [k, v] = parts[i].split(':');
            if (k && v) extra[k] = v;
          }

          // Detect if wireless: contains port number or mDNS suffix
          const isWifi = id.includes(':') || id.includes('._adb') || id.includes('._tcp');

          devices.push({
            id,
            state,
            isWifi,
            model: extra.model || 'Android Device',
            product: extra.product || '',
            device: extra.device || ''
          });
        }
      }

      // Sort: prioritize wireless devices over USB
      devices.sort((a, b) => {
        if (a.isWifi && !b.isWifi) return -1;
        if (!a.isWifi && b.isWifi) return 1;
        return 0;
      });

      return devices;
    } catch (err) {
      console.error('Failed to list devices:', err);
      return [];
    }
  }

  async getDeviceInfo(deviceId) {
    try {
      const [model, brand, marketName, oplusRom, androidVer, batteryRaw, storageRaw] = await Promise.allSettled([
        this.exec(['-s', deviceId, 'shell', 'getprop', 'ro.product.model']),
        this.exec(['-s', deviceId, 'shell', 'getprop', 'ro.product.brand']),
        this.exec(['-s', deviceId, 'shell', 'getprop', 'ro.oplus.market.name']),
        this.exec(['-s', deviceId, 'shell', 'getprop', 'ro.build.version.oplusrom']),
        this.exec(['-s', deviceId, 'shell', 'getprop', 'ro.build.version.release']),
        this.exec(['-s', deviceId, 'shell', 'dumpsys', 'battery']),
        this.exec(['-s', deviceId, 'shell', 'df', '-h', '/sdcard'])
      ]);

      const battery = { level: 100, isCharging: false, tempC: 25 };
      if (batteryRaw.status === 'fulfilled' && batteryRaw.value) {
        const levelMatch = batteryRaw.value.match(/level:\s*(\d+)/i);
        const statusMatch = batteryRaw.value.match(/status:\s*(\d+)/i);
        const tempMatch = batteryRaw.value.match(/temperature:\s*(\d+)/i);
        if (levelMatch) battery.level = parseInt(levelMatch[1], 10);
        if (statusMatch) battery.isCharging = parseInt(statusMatch[1], 10) === 2; // 2 = BatteryManager.BATTERY_STATUS_CHARGING
        if (tempMatch) battery.tempC = Math.round(parseInt(tempMatch[1], 10) / 10);
      }

      let storage = { size: 'N/A', used: 'N/A', free: 'N/A', percent: 'N/A' };
      if (storageRaw.status === 'fulfilled' && storageRaw.value) {
        const lines = storageRaw.value.split('\n');
        if (lines.length > 1) {
          const parts = lines[1].trim().split(/\s+/);
          if (parts.length >= 5) {
            storage = {
              size: parts[1],
              used: parts[2],
              free: parts[3],
              percent: parts[4]
            };
          }
        }
      }

      const cleanModel = (marketName.status === 'fulfilled' && marketName.value)
        ? marketName.value
        : (model.status === 'fulfilled' && model.value ? model.value : 'OPPO Device');

      return {
        id: deviceId,
        brand: (brand.status === 'fulfilled' && brand.value ? brand.value : 'OPPO').toUpperCase(),
        model: cleanModel,
        colorOsVersion: oplusRom.status === 'fulfilled' && oplusRom.value ? oplusRom.value : 'ColorOS',
        androidVersion: androidVer.status === 'fulfilled' && androidVer.value ? androidVer.value : 'Android',
        battery,
        storage,
        isWifi: deviceId.includes(':')
      };
    } catch (err) {
      console.error('Error fetching device info:', err);
      return { id: deviceId, model: 'OPPO Reno', battery: { level: 0, isCharging: false } };
    }
  }

  async connectWifi(ip, port = 5555) {
    const target = `${ip.trim()}:${port}`;
    try {
      const output = await this.exec(['connect', target]);
      if (output.includes('connected to') || output.includes('already connected')) {
        return { success: true, message: output, target };
      } else {
        return { success: false, message: output, target };
      }
    } catch (err) {
      return { success: false, message: err.message, target };
    }
  }

  async pairWifi(ip, port, code) {
    const target = `${ip.trim()}:${port}`;
    try {
      const output = await this.exec(['pair', target, code.trim()]);
      if (output.toLowerCase().includes('successfully paired')) {
        return { success: true, message: output };
      }
      return { success: false, message: output };
    } catch (err) {
      return { success: false, message: err.message };
    }
  }

  async enableTcpip(deviceId, port = 5555) {
    try {
      const args = deviceId ? ['-s', deviceId, 'tcpip', String(port)] : ['tcpip', String(port)];
      const output = await this.exec(args);
      return { success: true, message: output };
    } catch (err) {
      return { success: false, message: err.message };
    }
  }

  async getDeviceWifiIp(deviceId) {
    try {
      const output = await this.exec(['-s', deviceId, 'shell', 'ip', '-f', 'inet', 'addr', 'show', 'wlan0']);
      const match = output.match(/inet\s+(\d+\.\d+\.\d+\.\d+)/);
      return match ? match[1] : null;
    } catch (err) {
      return null;
    }
  }

  async disconnect(target) {
    try {
      const output = await this.exec(['disconnect', target]);
      return { success: true, message: output };
    } catch (err) {
      return { success: false, message: err.message };
    }
  }

  startPolling(intervalMs = 3000) {
    if (this.isPolling) return;
    this.isPolling = true;

    const check = async () => {
      try {
        const devices = await this.getDevices();
        const active = devices.find(d => d.state === 'device');
        if (active) {
          if (!this.connectedDevice || this.connectedDevice.id !== active.id) {
            const info = await this.getDeviceInfo(active.id);
            this.connectedDevice = info;
            this.emit('device-connected', info);
          } else {
            // Update live telemetry (battery etc)
            const info = await this.getDeviceInfo(active.id);
            this.connectedDevice = info;
            this.emit('telemetry-updated', info);
          }
        } else {
          if (this.connectedDevice) {
            const last = this.connectedDevice;
            this.connectedDevice = null;
            this.emit('device-disconnected', last);
          }
        }
      } catch (e) {
        console.error('Polling error:', e);
      }
    };

    check();
    this.pollInterval = setInterval(check, intervalMs);
  }

  stopPolling() {
    if (this.pollInterval) {
      clearInterval(this.pollInterval);
      this.pollInterval = null;
    }
    this.isPolling = false;
  }
}

module.exports = AdbService;
