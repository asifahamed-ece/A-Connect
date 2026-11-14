const { app, BrowserWindow, ipcMain, Tray, Menu, dialog, nativeImage, shell } = require('electron');
const path = require('path');
const fs = require('fs');

const AdbService = require('./services/adb');
const ScrcpyService = require('./services/scrcpy');
const FileTransferService = require('./services/file-transfer');
const ClipboardSyncService = require('./services/clipboard');
const NotificationService = require('./services/notifications');
const CompanionService = require('./services/companion');
const MirrorService = require('./services/mirror');
const h264 = require('./services/h264');
const { MprisService } = require('./services/mpris');

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

/**
 * The live screen mirroring session, if any.
 *
 * The picture arrives on its own socket (services/mirror.js); starting, failing
 * and finishing are reported on the control channel, which is the connection
 * the UI is already watching - so a failure can say what happened instead of
 * leaving an empty canvas.
 */
let mirror = null;
let mirrorCodec = null;
let mirrorInfo = { state: 'idle', reason: null, width: 0, height: 0, encoder: '', port: 0 };

// The phone's media session, published as an MPRIS player so the desktop shell
// (Caelestia, Waybar, playerctl) can show and control it. The app has no media
// panel of its own on purpose.
const mpris = new MprisService();

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

/**
 * Starts one screen mirroring session.
 *
 * The listener is opened before the phone is asked, because the phone dials
 * out: it has to have somewhere to dial. The port is the control port plus one,
 * and it travels in the request so the phone never needs its own configuration.
 */
async function startMirror() {
  if (mirror) return { success: false, reason: 'already-mirroring' };
  if (!companion.isConnected) return { success: false, reason: 'no-control-link' };

  const service = new MirrorService({
    pcId: companion.pcId,
    fingerprint: companion.fingerprint,
    // The same store, the same code check and the same proof as the control
    // channel: one trust implementation, two sockets.
    pairedDevices: companion.pairedDevices,
    isPairingCodeValid: (code) => companion.isPairingCodeValid(code),
    savePairedDevices: () => companion.savePairedDevices(),
  });

  try {
    await service.start(companion.port + 1);
  } catch (err) {
    console.error('[mirror] could not listen for video:', err.message);
    return { success: false, reason: 'video-connect-failed' };
  }

  service.on('frame', onMirrorFrame);
  service.on('client-closed', () => {
    if (mirror === service) stopMirror('link-lost', { tellPhone: false });
  });
  // The picture went quiet: a phone out of range, or a link that has died
  // without saying so. The socket is already gone; end the session here so the
  // card stops claiming to stream.
  service.on('stalled', () => {
    if (mirror === service) stopMirror('link-lost', { tellPhone: false });
  });

  mirror = service;
  mirrorCodec = null;

  const sent = companion.startMirror({
    videoPort: service.port,
    maxLongSide: 1280,
    fps: 30,
    bitrate: 4_000_000,
    keyframeSeconds: 1,
  });

  if (!sent) {
    stopMirror('no-control-link', { tellPhone: false });
    return { success: false, reason: 'no-control-link' };
  }

  mirrorInfo = { state: 'starting', reason: null, width: 0, height: 0, encoder: '', port: service.port };
  sendToRenderer('event:mirror-state', mirrorInfo);
  return { success: true, port: service.port };
}

/**
 * Ends mirroring for every reason there is.
 *
 * One function because there are eight triggers - the Stop button, Disconnect,
 * the phone's notification action, the system chip, the screen sleeping, the
 * video socket closing, the phone app dying, and this app quitting - and eight
 * copies of a teardown is how a codec gets left running.
 */
function stopMirror(reason = 'requested', options = {}) {
  const tellPhone = options.tellPhone !== false;
  const wasMirroring = Boolean(mirror);

  if (mirror) {
    mirror.stop();
    mirror = null;
  }
  mirrorCodec = null;

  if (tellPhone && wasMirroring) companion.stopMirror(reason);

  mirrorInfo = {
    state: 'idle',
    reason: wasMirroring ? reason : null,
    width: 0,
    height: 0,
    encoder: '',
    port: 0,
  };
  if (wasMirroring) sendToRenderer('event:mirror-state', mirrorInfo);
  return { success: true, wasMirroring };
}

/**
 * One video frame on its way to the renderer.
 *
 * The codec string is read from the first keyframe's SPS, once per session, and
 * travels with that frame so the renderer knows how to configure its decoder.
 * Deltas that arrive before the first keyframe are simply not shown - there is
 * nothing to decode them against.
 */
function onMirrorFrame(frame) {
  if (!mirror) return;

  let codec;
  if (!mirrorCodec && frame.keyframe) {
    mirrorCodec = h264.codecStringFromAnnexB(frame.payload) || h264.FALLBACK_CODEC;
    codec = mirrorCodec;
  }

  sendToRenderer('event:mirror-frame', {
    keyframe: frame.keyframe,
    ptsMicros: frame.ptsMicros,
    codec,
    data: frame.payload,
  });
}

/**
 * A late reason, arriving after the picture is already gone.
 *
 * The video socket and the control channel are two connections: the video one
 * can close a few milliseconds before the phone's "stopped" message lands. A
 * deliberate stop on the phone would then leave the PC showing "the connection
 * was lost" - true about the socket, wrong about why. The control channel is
 * authoritative about the reason, so it corrects an idle session here.
 */
function correctMirrorReason(reason) {
  if (!reason || mirrorInfo.state !== 'idle' || mirrorInfo.reason === reason) return;
  mirrorInfo = { ...mirrorInfo, reason };
  sendToRenderer('event:mirror-state', mirrorInfo);
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
  } else if (!companion.isEnabled) {
    // "No device connected" would read as a problem. This one is a decision.
    deviceLabel = '📴 Companion off';
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
    // One transport per event: while the phone is connected over the companion
    // channel its notifications come over that channel. The debugging poller
    // must not start again just because adb rediscovered the phone - which it
    // does whenever the device id changes (USB to Wi-Fi, a reconnect) - or
    // every notification pops twice, once per transport.
    if (!companion.isConnected) notifications.start(device.id);
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

  companion.on('enabled-changed', (data) => {
    updateTrayMenu();
    sendToRenderer('event:companion-enabled', data);
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
    // A phone that is not on the control channel cannot be streaming to us;
    // the video socket may linger for a moment, so end the session here.
    if (mirror) stopMirror('link-lost', { tellPhone: false });
    // Nothing is playing on a phone that is not there.
    mpris.clear();
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

  companion.on('media-state', (state) => {
    // An idle state means the phone stopped playing: withdraw the player
    // rather than leaving the shell showing a track that ended minutes ago.
    if (state && state.idle) {
      mpris.clear();
      return;
    }
    mpris.update(state);
  });

  mpris.on('command', (name, positionMs) => {
    companion.sendMediaCommand(name, positionMs);
  });

  // ---- screen mirroring ---------------------------------------------------
  //
  // The picture has its own socket; everything the user can see about it is
  // reported here, on the control channel, so one place answers "what is
  // happening".

  companion.on('mirror-started', (info) => {
    if (!mirror) return;
    mirrorInfo = { ...mirrorInfo, state: 'streaming', width: info.width, height: info.height, encoder: info.encoder };
    mirror.log(`streaming ${info.width}x${info.height} with ${info.encoder || 'an unnamed encoder'}`);
    sendToRenderer('event:mirror-state', mirrorInfo);
  });

  companion.on('mirror-stopped', ({ reason }) => {
    if (mirror) {
      stopMirror(reason, { tellPhone: false });
      return;
    }
    correctMirrorReason(reason);
  });

  companion.on('mirror-failed', ({ reason }) => {
    if (mirror) {
      stopMirror(reason, { tellPhone: false });
      return;
    }
    correctMirrorReason(reason);
  });

  companion.on('file-transfer-start', (data) => {
    // The renderer's transfer list speaks one vocabulary for both transports,
    // so the companion's names are mapped here rather than taught to it.
    sendToRenderer('event:transfer-progress', {
      status: 'started',
      type: 'pull',
      id: data.id,
      filename: data.name,
      size: data.size,
    });
  });

  companion.on('file-transfer-progress', (data) => {
    sendToRenderer('event:transfer-progress', {
      status: 'progress',
      id: data.id,
      received: data.received,
      total: data.total,
    });
  });

  companion.on('file-transfer-done', (data) => {
    sendToRenderer('event:transfer-progress', {
      status: 'completed',
      type: 'pull',
      id: data.id,
      filename: data.name,
      targetPath: data.path,
    });
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

  // The kill switch. Turning it off closes the listener and tells the phone
  // why, so it keeps looking instead of giving up; turning it on reopens the
  // listener and the phone finds it on its own.
  ipcMain.handle('companion:getEnabled', async () => ({ enabled: companion.isEnabled }));
  ipcMain.handle('companion:setEnabled', async (_, enabled) => {
    const result = companion.setEnabled(enabled === true);
    sendToRenderer('event:companion-enabled', result);
    return result;
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
  ipcMain.handle('companion:listFiles', async (_, path) => companion.requestFileList(path));
  ipcMain.handle('companion:searchFiles', async (_, query, fresh) =>
    companion.requestFileSearch(query, { fresh: fresh === true })
  );
  ipcMain.handle('companion:downloadFile', async (_, path) => companion.requestFileSend(path));
  // Screen mirroring. The picture travels on its own socket; these two calls
  // are the whole of what the renderer can ask for.
  ipcMain.handle('mirror:start', async () => startMirror());
  ipcMain.handle('mirror:stop', async () => stopMirror('requested'));
  ipcMain.handle('mirror:info', async () => mirrorInfo);

  ipcMain.handle('companion:disconnect', async () => {
    // Hanging up on the phone also ends the picture: the video socket has no
    // way to mean anything once the control channel is gone.
    if (mirror) stopMirror('disconnected', { tellPhone: false });
    const hungUp = companion.disconnectClients();
    return { success: hungUp > 0, hungUp };
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
  mpris.start().catch((e) => console.error('[mpris] start failed:', e.message));

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on('before-quit', () => {
  // Tell the phone to stop capturing before this process disappears: otherwise
  // it holds a projection and an encoder with nobody to send to.
  if (mirror) stopMirror('requested');
  isQuitting = true;
  adb.stopPolling();
  scrcpy.stop();
  companion.stop();
  clipboardSync.stop();
  notifications.stop();
  mpris.stop();
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin' && isQuitting) {
    app.quit();
  }
});
