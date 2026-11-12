const { app, BrowserWindow, ipcMain, Tray, Menu, dialog, nativeImage, shell } = require('electron');
const path = require('path');
const fs = require('fs');

const AdbService = require('./services/adb');
const ScrcpyService = require('./services/scrcpy');
const FileTransferService = require('./services/file-transfer');
const ClipboardSyncService = require('./services/clipboard');
const NotificationService = require('./services/notifications');
const CompanionService = require('./services/companion');

let mainWindow = null;
let tray = null;
let isQuitting = false;

// Initialize Core Services
const adb = new AdbService();
const scrcpy = new ScrcpyService();
const fileTransfer = new FileTransferService();
const clipboardSync = new ClipboardSyncService();
const notifications = new NotificationService();
const companion = new CompanionService();

// Connect companion to clipboard sync
clipboardSync.setCompanion(companion);

// The renderer can reload or be destroyed while a backend event is in flight;
// sending at that moment throws "Render frame was disposed". Route every
// main -> renderer message through here so a dead window can never break a
// running sync loop.
function sendToRenderer(channel, payload) {
  if (!mainWindow) return;
  if (mainWindow.isDestroyed() || mainWindow.webContents.isDestroyed()) return;
  try {
    mainWindow.webContents.send(channel, payload);
  } catch (e) {
    console.error(`Failed to send ${channel} to renderer:`, e.message);
  }
}

function createWindow() {
  const iconPath = path.join(__dirname, 'assets', 'icon.png');

  mainWindow = new BrowserWindow({
    width: 980,
    height: 680,
    minWidth: 840,
    minHeight: 580,
    title: 'A+ Connect',
    icon: fs.existsSync(iconPath) ? iconPath : undefined,
    frame: false, // Custom sleek titlebar matching O+ Connect style
    titleBarStyle: 'hidden',
    backgroundColor: '#0F1216',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false
    }
  });

  mainWindow.loadFile(path.join(__dirname, 'src', 'index.html'));

  mainWindow.on('close', (event) => {
    if (!isQuitting) {
      event.preventDefault();
      mainWindow.hide();
    }
  });
}

function createTray() {
  const iconPath = path.join(__dirname, 'assets', 'icon.png');
  const trayIcon = fs.existsSync(iconPath)
    ? nativeImage.createFromPath(iconPath).resize({ width: 22, height: 22 })
    : nativeImage.createEmpty();

  tray = new Tray(trayIcon);
  tray.setToolTip('A+ Connect - OPPO Device Companion');

  updateTrayMenu();

  tray.on('click', () => {
    if (mainWindow) {
      if (mainWindow.isVisible()) {
        mainWindow.hide();
      } else {
        mainWindow.show();
        mainWindow.focus();
      }
    }
  });
}

function updateTrayMenu() {
  if (!tray) return;

  const device = adb.connectedDevice;
  const isCompanionConnected = companion.isConnected;
  const isConnected = !!device || isCompanionConnected;
  const isMirroring = scrcpy.isRunning;

  let deviceLabel = '📱 No Device Connected';
  if (device) {
    deviceLabel = `📱 ${device.model} (${device.battery?.level || 0}%)`;
  } else if (isCompanionConnected) {
    const compDevice = companion.connectedClients[0];
    deviceLabel = `📱 ${compDevice?.deviceName || 'Phone'} (Companion)`;
  }

  const contextMenu = Menu.buildFromTemplate([
    {
      label: deviceLabel,
      enabled: false
    },
    { type: 'separator' },
    {
      label: isMirroring ? '⏹ Stop Screen Mirroring' : '🖥 Start Screen Mirroring',
      enabled: !!device,
      click: () => {
        if (isMirroring) {
          scrcpy.stop();
        } else if (device) {
          scrcpy.start(device.id, { turnScreenOff: true, stayAwake: true });
        }
      }
    },
    {
      label: '📂 Open File Drop',
      enabled: !!device,
      click: () => {
        if (mainWindow) {
          mainWindow.show();
          sendToRenderer('route:navigate', 'files');
        }
      }
    },
    {
      label: '📋 Clipboard Sync',
      type: 'checkbox',
      checked: clipboardSync.enabled,
      enabled: true,
      click: (item) => {
        clipboardSync.setEnabled(item.checked);
      }
    },
    { type: 'separator' },
    {
      label: 'Open A+ Connect',
      click: () => {
        if (mainWindow) {
          mainWindow.show();
          mainWindow.focus();
        }
      }
    },
    {
      label: 'Quit',
      click: () => {
        isQuitting = true;
        app.quit();
      }
    }
  ]);

  tray.setContextMenu(contextMenu);
}

// Wire Event Relays
function setupServiceEvents() {
  adb.on('device-connected', (device) => {
    console.log('Device connected:', device.model);
    clipboardSync.start(device.id).catch((e) => {
      console.error('Clipboard sync failed to start:', e.message);
    });
    notifications.start(device.id);
    updateTrayMenu();
    sendToRenderer('event:device-connected', device);
  });

  adb.on('device-disconnected', (device) => {
    console.log('Device disconnected');
    clipboardSync.stop();
    notifications.stop();
    scrcpy.stop();
    updateTrayMenu();
    sendToRenderer('event:device-disconnected', device);
  });

  adb.on('telemetry-updated', (device) => {
    updateTrayMenu();
    sendToRenderer('event:telemetry-updated', device);
  });

  scrcpy.on('started', (data) => {
    updateTrayMenu();
    sendToRenderer('event:mirror-started', data);
  });

  scrcpy.on('error', (err) => {
    // Without this listener Node treats an unhandled 'error' event as fatal
    // and the whole app dies with a JavaScript error dialog.
    console.error('[scrcpy] error:', err.message);
    sendToRenderer('event:mirror-error', { message: err.message });
    updateTrayMenu();
  });

  scrcpy.on('stopped', (data) => {
    updateTrayMenu();
    sendToRenderer('event:mirror-stopped', data);
  });

  notifications.on('notification-received', (notif) => {
    sendToRenderer('event:notification-received', notif);
  });

  clipboardSync.on('status', (state) => {
    console.log('Clipboard sync status:', state.status, state.detail ? `- ${state.detail}` : '');
    updateTrayMenu();
    sendToRenderer('event:clipboard-status', state);
  });

  clipboardSync.on('synced', (entry) => {
    const preview = entry.text.length > 40 ? `${entry.text.slice(0, 40)}…` : entry.text;
    console.log(`Clipboard ${entry.direction}: ${JSON.stringify(preview)}`);
    sendToRenderer('event:clipboard-synced', entry);
  });

  clipboardSync.on('history-updated', (entry) => {
    sendToRenderer('event:clipboard-history', entry);
  });

  fileTransfer.on('transfer-started', (data) => {
    sendToRenderer('event:transfer-progress', { status: 'started', ...data });
  });

  fileTransfer.on('transfer-completed', (data) => {
    sendToRenderer('event:transfer-progress', { status: 'completed', ...data });
  });

  fileTransfer.on('transfer-failed', (data) => {
    sendToRenderer('event:transfer-progress', { status: 'failed', ...data });
  });

  fileTransfer.on('transfer-cancelled', (data) => {
    sendToRenderer('event:transfer-progress', { status: 'cancelled', ...data });
  });

  companion.on('listening', (data) => {
    sendToRenderer('event:companion-listening', data);
  });

  companion.on('status', (data) => {
    updateTrayMenu();
    sendToRenderer('event:companion-status', data);
  });

  companion.on('client-paired', (data) => {
    console.log(`[companion] paired phone: ${data.deviceName} (${data.deviceId})`);
    // One transport per event: once the companion is up, notifications come
    // over it, so the ADB poller stands down rather than reporting everything
    // twice.
    notifications.stop();
    updateTrayMenu();
    sendToRenderer('event:companion-paired', data);
  });

  companion.on('error', (err) => {
    // The companion server failing (a port clash, a missing certificate)
    // must never take the desktop app down with it.
    console.error('[companion] error:', err.message);
    updateTrayMenu();
  });

  companion.on('client-disconnected', (data) => {
    console.log(`[companion] disconnected phone: ${data.deviceId}`);
    // The companion is gone. If a phone is still on USB, the ADB poller takes
    // the notifications back.
    adb.getDevices()
      .then((devices) => {
        if (devices && devices.length > 0) notifications.start(devices[0].id);
      })
      .catch(() => {});
    updateTrayMenu();
    sendToRenderer('event:companion-disconnected', data);
  });

  // The companion channel's notifications join the same history and the same
  // renderer event the ADB path uses, so the Notifications panel needs no
  // change at all.
  companion.on('notification-received', (notif) => {
    notifications.addFromCompanion(notif);
  });

  companion.on('notification-dismissed', ({ id }) => {
    notifications.removeById(id);
  });

  companion.on('file-transfer-start', (data) => {
    sendToRenderer('event:transfer-progress', { status: 'transferring', progress: 0, ...data });
  });

  companion.on('file-transfer-progress', (data) => {
    sendToRenderer('event:transfer-progress', { status: 'transferring', ...data });
  });

  companion.on('file-transfer-done', (data) => {
    sendToRenderer('event:transfer-progress', { status: 'completed', progress: 100, ...data });
    try {
      execFile('notify-send', ['-a', 'A+ Connect', 'File Received', `${data.name} saved to Downloads`]);
    } catch {}
  });
}

// IPC Handlers
function setupIpcHandlers() {
  // Window controls
  ipcMain.on('window:minimize', () => { if (mainWindow) mainWindow.minimize(); });
  ipcMain.on('window:maximize', () => {
    if (!mainWindow) return;
    if (mainWindow.isMaximized()) mainWindow.unmaximize();
    else mainWindow.maximize();
  });
  ipcMain.on('window:close', () => { if (mainWindow) mainWindow.close(); });

  // ADB Handlers
  ipcMain.handle('adb:getDevices', async () => adb.getDevices());
  // The renderer can be reloaded (devtools, crash) while the main process
  // keeps the connection, so it asks for the current device instead of
  // waiting for an event that already happened.
  ipcMain.handle('adb:getConnectedDevice', async () => adb.connectedDevice);
  ipcMain.handle('adb:getDeviceInfo', async (_, id) => adb.getDeviceInfo(id));
  ipcMain.handle('adb:connectWifi', async (_, ip, port) => adb.connectWifi(ip, port));
  ipcMain.handle('adb:pairWifi', async (_, ip, port, code) => adb.pairWifi(ip, port, code));
  ipcMain.handle('adb:enableTcpip', async (_, id, port) => adb.enableTcpip(id, port));
  ipcMain.handle('adb:disconnectDevice', async (_, target) => adb.disconnect(target));

  // Scrcpy Handlers
  ipcMain.handle('scrcpy:start', async (_, deviceId, options) => {
    // Note: ADB capture cannot ask the phone for consent (that needs
    // MediaProjection inside an installed app), so the confirmation happens in
    // the app before this handler runs. A phone-side notification was tried but
    // shell notifications cannot be cancelled afterwards, leaving a stale
    // "mirroring is active" message, so it is deliberately not posted.
    return scrcpy.start(deviceId, options);
  });
  ipcMain.handle('scrcpy:stop', async () => scrcpy.stop());
  ipcMain.handle('scrcpy:status', async () => ({ isRunning: scrcpy.isRunning }));

  // File Transfer Handlers
  ipcMain.handle('file:push', async (_, deviceId, filePath, destDir) => {
    return fileTransfer.pushFile(deviceId, filePath, destDir);
  });
  ipcMain.handle('file:pull', async (_, deviceId, remotePath, localDir) => {
    return fileTransfer.pullFile(deviceId, remotePath, localDir);
  });
  ipcMain.handle('file:listRecentPhotos', async (_, deviceId) => {
    return fileTransfer.listRecentPhotos(deviceId);
  });
  ipcMain.handle('file:openDialog', async () => {
    const result = await dialog.showOpenDialog(mainWindow, {
      properties: ['openFile', 'multiSelections']
    });
    return result.filePaths;
  });
  ipcMain.handle('file:cancel', async (_, transferId) => ({
    success: fileTransfer.cancelTransfer(transferId)
  }));
  ipcMain.handle('file:cancelAll', async () => ({ cancelled: fileTransfer.cancelAll() }));
  ipcMain.handle('file:listDirectory', async (_, deviceId, remotePath) => {
    return fileTransfer.listDirectory(deviceId, remotePath);
  });
  ipcMain.handle('file:delete', async (_, deviceId, remotePath) => {
    return fileTransfer.deletePath(deviceId, remotePath);
  });
  ipcMain.handle('file:mkdir', async (_, deviceId, remotePath) => {
    return fileTransfer.makeDirectory(deviceId, remotePath);
  });
  ipcMain.handle('file:search', async (_, deviceId, query) => {
    return fileTransfer.searchFiles(deviceId, query);
  });
  ipcMain.handle('file:recent', async (_, deviceId) => {
    return fileTransfer.recentFiles(deviceId);
  });
  ipcMain.handle('file:openLocal', async (_, localPath) => {
    const error = await shell.openPath(localPath);
    return { success: !error, message: error || null };
  });

  // Clipboard & Notifications
  ipcMain.handle('clipboard:toggle', async (_, enabled) => {
    clipboardSync.setEnabled(enabled);
    return { success: true, enabled: clipboardSync.enabled };
  });
  ipcMain.handle('clipboard:getStatus', async () => ({
    status: clipboardSync.status,
    enabled: clipboardSync.enabled,
    deviceId: clipboardSync.deviceId
  }));
  ipcMain.handle('clipboard:getHistory', async () => clipboardSync.getHistory());
  ipcMain.handle('clipboard:clearHistory', async () => {
    clipboardSync.clearHistory();
    return { success: true };
  });
  ipcMain.handle('clipboard:sendToPhone', async (_, text) => {
    if (typeof text !== 'string' || !text) return { success: false };
    clipboardSync.pushToPhone(text);
    return { success: true };
  });
  ipcMain.handle('notifications:getHistory', async () => notifications.history);
  ipcMain.handle('notifications:clearHistory', async () => {
    notifications.clearHistory();
    return { success: true };
  });
  ipcMain.handle('notifications:dismiss', async (_, id) => {
    // A notification that came from the phone has to be dismissed on the phone
    // too, or it stays in the shade and comes straight back.
    const entry = notifications.history.find((n) => n.id === id);
    if (entry && entry.fromCompanion) companion.dismissNotification(id);
    return { success: notifications.dismiss(id) };
  });

  // Companion IPC Handlers
  ipcMain.handle('companion:getInfo', async () => companion.getInfo());
  ipcMain.handle('companion:start', async (_, port) => {
    companion.start(port);
    return companion.getInfo();
  });
  ipcMain.handle('companion:qr', async () => {
    try {
      const dataUrl = await companion.pairingQrDataUrl();
      return { success: true, dataUrl, info: companion.pairingInfo() };
    } catch (err) {
      return { success: false, message: err.message };
    }
  });
  ipcMain.handle('companion:sendFile', async (_, filePath) => {
    const sent = companion.sendFile(filePath);
    return { success: sent, filePath };
  });
  ipcMain.handle('companion:pickAndSendFile', async () => {
    const result = await dialog.showOpenDialog(mainWindow, {
      title: 'Send a file to the phone',
      properties: ['openFile'],
    });
    if (result.canceled || result.filePaths.length === 0) return { success: false, canceled: true };

    const filePath = result.filePaths[0];
    const sent = companion.sendFile(filePath);
    return { success: sent, filePath, name: require('path').basename(filePath) };
  });
  ipcMain.handle('companion:stop', async () => {
    companion.stop();
    return companion.getInfo();
  });
  ipcMain.handle('companion:setCode', async (_, code) => {
    companion.pairingCode = code;
    companion.saveSettings();
    return companion.getInfo();
  });
  ipcMain.handle('companion:setDestinationDir', async (_, dirPath) => {
    return companion.setDestinationDir(dirPath);
  });
  ipcMain.handle('companion:selectDestinationDir', async () => {
    if (!mainWindow) return null;
    const result = await dialog.showOpenDialog(mainWindow, {
      title: 'Select Destination Folder for Received Files',
      defaultPath: companion.destinationDir,
      properties: ['openDirectory', 'createDirectory'],
    });
    if (!result.canceled && result.filePaths.length > 0) {
      return companion.setDestinationDir(result.filePaths[0]);
    }
    return companion.destinationDir;
  });
  ipcMain.handle('companion:openDestinationDir', async () => {
    const { shell } = require('electron');
    if (fs.existsSync(companion.destinationDir)) {
      shell.openPath(companion.destinationDir);
      return true;
    }
    return false;
  });

  ipcMain.handle('ui:confirm', async (_, options = {}) => {
    if (!mainWindow) return false;
    const result = await dialog.showMessageBox(mainWindow, {
      type: 'question',
      buttons: ['Cancel', options.confirmLabel || 'OK'],
      defaultId: 1,
      cancelId: 0,
      message: options.message || 'Are you sure?',
      detail: options.detail || ''
    });
    return result.response === 1;
  });
}

app.whenReady().then(() => {
  createWindow();
  createTray();
  setupServiceEvents();
  setupIpcHandlers();

  // Start continuous device discovery and companion server
  adb.startPolling(2500);
  companion.start();

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on('before-quit', () => {
  isQuitting = true;
  adb.stopPolling();
  scrcpy.stop();
  companion.stop();
  clipboardSync.stop();
  notifications.stop();
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin' && isQuitting) {
    app.quit();
  }
});
