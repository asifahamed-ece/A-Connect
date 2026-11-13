const { contextBridge, ipcRenderer, webUtils } = require('electron');

contextBridge.exposeInMainWorld('api', {
  // Device & ADB
  getDevices: () => ipcRenderer.invoke('adb:getDevices'),
  getConnectedDevice: () => ipcRenderer.invoke('adb:getConnectedDevice'),
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
  listDirectory: (deviceId, remotePath) => ipcRenderer.invoke('file:listDirectory', deviceId, remotePath),
  deletePath: (deviceId, remotePath) => ipcRenderer.invoke('file:delete', deviceId, remotePath),
  makeDirectory: (deviceId, remotePath) => ipcRenderer.invoke('file:mkdir', deviceId, remotePath),
  searchFiles: (deviceId, query) => ipcRenderer.invoke('file:search', deviceId, query),
  listRecentFiles: (deviceId) => ipcRenderer.invoke('file:recent', deviceId),
  openLocalFile: (localPath) => ipcRenderer.invoke('file:openLocal', localPath),
  confirmAction: (options) => ipcRenderer.invoke('ui:confirm', options),

  // Clipboard & Notifications
  setClipboardSync: (enabled) => ipcRenderer.invoke('clipboard:toggle', enabled),
  getClipboardStatus: () => ipcRenderer.invoke('clipboard:getStatus'),
  getClipboardHistory: () => ipcRenderer.invoke('clipboard:getHistory'),
  clearClipboardHistory: () => ipcRenderer.invoke('clipboard:clearHistory'),
  sendToPhoneClipboard: (text) => ipcRenderer.invoke('clipboard:sendToPhone', text),
  getNotificationHistory: () => ipcRenderer.invoke('notifications:getHistory'),
  clearNotificationHistory: () => ipcRenderer.invoke('notifications:clearHistory'),
  dismissNotification: (id) => ipcRenderer.invoke('notifications:dismiss', id),

  // Companion
  getCompanionInfo: () => ipcRenderer.invoke('companion:getInfo'),
  setCompanionCode: (code) => ipcRenderer.invoke('companion:setCode', code),
  startCompanion: (port) => ipcRenderer.invoke('companion:start', port),
  stopCompanion: () => ipcRenderer.invoke('companion:stop'),
  getCompanionQr: () => ipcRenderer.invoke('companion:qr'),
  sendFileToPhone: (filePath) => ipcRenderer.invoke('companion:sendFile', filePath),
  pickAndSendFileToPhone: () => ipcRenderer.invoke('companion:pickAndSendFile'),
  setCompanionDestinationDir: (dir) => ipcRenderer.invoke('companion:setDestinationDir', dir),
  selectCompanionDestinationDir: () => ipcRenderer.invoke('companion:selectDestinationDir'),
  openCompanionDestinationDir: () => ipcRenderer.invoke('companion:openCompanionDestinationDir'),

  // The phone's own storage, over the companion channel. `path` is a folder
  // relative to the phone's storage root, as the phone handed it out; '' is
  // that root. Searching covers every folder the phone has granted access to.
  // `fresh` asks the phone to re-walk its storage first; only the Refresh
  // button passes it, because a keystroke must not pay for a walk.
  listPhoneFiles: (path) => ipcRenderer.invoke('companion:listFiles', path),
  searchPhoneFiles: (query, fresh) => ipcRenderer.invoke('companion:searchFiles', query, fresh === true),
  downloadPhoneFile: (path) => ipcRenderer.invoke('companion:downloadFile', path),

  // Hangs up on the phone over the companion channel. The phone is told this
  // was deliberate, so it stops retrying until the user reconnects it.
  disconnectCompanion: () => ipcRenderer.invoke('companion:disconnect'),

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
  onClipboardHistory: (callback) => ipcRenderer.on('event:clipboard-history', (_, data) => callback(data)),
  onCompanionStatus: (callback) => ipcRenderer.on('event:companion-status', (_, data) => callback(data)),
  onCompanionPaired: (callback) => ipcRenderer.on('event:companion-paired', (_, data) => callback(data)),
  onCompanionDisconnected: (callback) => ipcRenderer.on('event:companion-disconnected', (_, data) => callback(data))
});
