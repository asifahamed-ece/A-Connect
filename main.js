const { app, BrowserWindow, ipcMain, Tray, Menu, dialog, nativeImage } = require('electron');
const path = require('path');
const fs = require('fs');

const AdbService = require('./services/adb');
const ScrcpyService = require('./services/scrcpy');
const FileTransferService = require('./services/file-transfer');
const ClipboardSyncService = require('./services/clipboard');
const NotificationService = require('./services/notifications');

let mainWindow = null;
let tray = null;
let isQuitting = false;

// Initialize Core Services
const adb = new AdbService();
const scrcpy = new ScrcpyService();
const fileTransfer = new FileTransferService();
const clipboardSync = new ClipboardSyncService();
const notifications = new NotificationService();

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
  const isConnected = !!device;
  const isMirroring = scrcpy.isRunning;

  const contextMenu = Menu.buildFromTemplate([
    {
      label: isConnected ? `📱 ${device.model} (${device.battery?.level || 0}%)` : '📱 No Device Connected',
      enabled: false
    },
    { type: 'separator' },
    {
      label: isMirroring ? '⏹ Stop Screen Mirroring' : '🖥 Start Screen Mirroring',
      enabled: isConnected,
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
      enabled: isConnected,
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
      enabled: isConnected,
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
  ipcMain.handle('adb:getDeviceInfo', async (_, id) => adb.getDeviceInfo(id));
  ipcMain.handle('adb:connectWifi', async (_, ip, port) => adb.connectWifi(ip, port));
  ipcMain.handle('adb:pairWifi', async (_, ip, port, code) => adb.pairWifi(ip, port, code));
  ipcMain.handle('adb:enableTcpip', async (_, id, port) => adb.enableTcpip(id, port));
  ipcMain.handle('adb:disconnectDevice', async (_, target) => adb.disconnect(target));

  // Scrcpy Handlers
  ipcMain.handle('scrcpy:start', async (_, deviceId, options) => {
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
}

app.whenReady().then(() => {
  createWindow();
  createTray();
  setupServiceEvents();
  setupIpcHandlers();

  // Start continuous device discovery
  adb.startPolling(2500);

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on('before-quit', () => {
  isQuitting = true;
  adb.stopPolling();
  scrcpy.stop();
  clipboardSync.stop();
  notifications.stop();
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin' && isQuitting) {
    app.quit();
  }
});
