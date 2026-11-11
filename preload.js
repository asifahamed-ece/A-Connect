const { contextBridge, ipcRenderer, webUtils } = require('electron');

contextBridge.exposeInMainWorld('api', {
  // Device & ADB
  getDevices: () => ipcRenderer.invoke('adb:getDevices'),
  getDeviceInfo: (id) => ipcRenderer.invoke('adb:getDeviceInfo', id),
  connectWifi: (ip, port) => ipcRenderer.invoke('adb:connectWifi', ip, port),
  pairWifi: (ip, port, code) => ipcRenderer.invoke('adb:pairWifi', ip, port, code),
  enableTcpip: (id, port) => ipcRenderer.invoke('adb:enableTcpip', id, port),
  disconnectDevice: (target) => ipcRenderer.invoke('adb:disconnectDevice', target),

  // Scrcpy Screen Cast
  startMirroring: (deviceId, options) => ipcRenderer.invoke('scrcpy:start', deviceId, options),
  stopMirroring: () => ipcRenderer.invoke('scrcpy:stop'),
  getMirroringStatus: () => ipcRenderer.invoke('scrcpy:status'),

  // File Transfer
  pushFile: (deviceId, filePath, destDir) => ipcRenderer.invoke('file:push', deviceId, filePath, destDir),
  pullFile: (deviceId, remotePath, localDir) => ipcRenderer.invoke('file:pull', deviceId, remotePath, localDir),
  listRecentPhotos: (deviceId) => ipcRenderer.invoke('file:listRecentPhotos', deviceId),
  openFileDialog: () => ipcRenderer.invoke('file:openDialog'),
  cancelTransfer: (transferId) => ipcRenderer.invoke('file:cancel', transferId),
  cancelAllTransfers: () => ipcRenderer.invoke('file:cancelAll'),

  // Clipboard & Notifications
  setClipboardSync: (enabled) => ipcRenderer.invoke('clipboard:toggle', enabled),
  getClipboardStatus: () => ipcRenderer.invoke('clipboard:getStatus'),
  getClipboardHistory: () => ipcRenderer.invoke('clipboard:getHistory'),
  clearClipboardHistory: () => ipcRenderer.invoke('clipboard:clearHistory'),
  sendToPhoneClipboard: (text) => ipcRenderer.invoke('clipboard:sendToPhone', text),
  getNotificationHistory: () => ipcRenderer.invoke('notifications:getHistory'),
  clearNotificationHistory: () => ipcRenderer.invoke('notifications:clearHistory'),

  // System & Window
  minimizeWindow: () => ipcRenderer.send('window:minimize'),
  toggleMaximize: () => ipcRenderer.send('window:maximize'),
  closeWindow: () => ipcRenderer.send('window:close'),
  getPathForFile: (file) => webUtils.getPathForFile(file),

  // Event Listeners
  onDeviceConnected: (callback) => ipcRenderer.on('event:device-connected', (_, data) => callback(data)),
  onDeviceDisconnected: (callback) => ipcRenderer.on('event:device-disconnected', (_, data) => callback(data)),
  onTelemetryUpdated: (callback) => ipcRenderer.on('event:telemetry-updated', (_, data) => callback(data)),
  onMirrorStarted: (callback) => ipcRenderer.on('event:mirror-started', (_, data) => callback(data)),
  onMirrorStopped: (callback) => ipcRenderer.on('event:mirror-stopped', (_, data) => callback(data)),
  onNotificationReceived: (callback) => ipcRenderer.on('event:notification-received', (_, data) => callback(data)),
  onTransferProgress: (callback) => ipcRenderer.on('event:transfer-progress', (_, data) => callback(data)),
  onClipboardStatus: (callback) => ipcRenderer.on('event:clipboard-status', (_, data) => callback(data)),
  onClipboardSynced: (callback) => ipcRenderer.on('event:clipboard-synced', (_, data) => callback(data)),
  onClipboardHistory: (callback) => ipcRenderer.on('event:clipboard-history', (_, data) => callback(data))
});
