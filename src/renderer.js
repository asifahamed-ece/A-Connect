// Renderer Process - UI Logic
let currentDevice = null;
let isMirroring = false;
let currentView = 'connection';

// Initialize app on load
window.addEventListener('DOMContentLoaded', () => {
  console.log('A+ Connect initializing...');
  setupEventListeners();
  checkForDevices();
  setupFileDrop();

  // Poll for device discovery every 3 seconds
  setInterval(checkForDevices, 3000);
});

// Setup IPC event listeners from main process
function setupEventListeners() {
  window.api.onDeviceConnected((device) => {
    console.log('Device connected:', device);
    currentDevice = device;
    showDeviceCard();
  });

  window.api.onDeviceDisconnected((device) => {
    console.log('Device disconnected');
    currentDevice = null;
    showConnectionPanel();
  });

  window.api.onTelemetryUpdated((device) => {
    currentDevice = device;
    updateTelemetry(device);
  });

  window.api.onMirrorStarted(() => {
    isMirroring = true;
    updateMirrorButton();
  });

  window.api.onMirrorStopped(() => {
    isMirroring = false;
    updateMirrorButton();
  });

  window.api.onNotificationReceived((notif) => {
    addNotificationToList(notif);
  });

  window.api.onTransferProgress((data) => {
    updateTransferLog(data);
  });
}

async function checkForDevices() {
  try {
    const devices = await window.api.getDevices();
    const activeDevice = devices.find(d => d.state === 'device');

    if (activeDevice && !currentDevice) {
      const info = await window.api.getDeviceInfo(activeDevice.id);
      currentDevice = info;
      showDeviceCard();
    } else if (!activeDevice && currentDevice) {
      currentDevice = null;
      showConnectionPanel();
    }
  } catch (err) {
    console.error('Error checking devices:', err);
  }
}

function showDeviceCard() {
  document.getElementById('connection-panel').style.display = 'none';
  document.getElementById('device-card').style.display = 'block';
  document.getElementById('file-panel').style.display = 'none';
  document.getElementById('notifications-panel').style.display = 'none';

  if (currentDevice) {
    document.getElementById('device-brand').textContent = currentDevice.brand || 'OPPO';
    document.getElementById('device-model').textContent = currentDevice.model || 'Reno 14 5G';
    updateTelemetry(currentDevice);
  }

  currentView = 'device';
}

function showConnectionPanel() {
  document.getElementById('device-card').style.display = 'none';
  document.getElementById('connection-panel').style.display = 'block';
  document.getElementById('file-panel').style.display = 'none';
  document.getElementById('notifications-panel').style.display = 'none';
  currentView = 'connection';
}

function showHome() {
  if (currentDevice) {
    showDeviceCard();
  } else {
    showConnectionPanel();
  }
}

function updateTelemetry(device) {
  if (!device) return;

  const batteryEl = document.getElementById('battery-level');
  const colorosEl = document.getElementById('coloros-version');
  const connectionEl = document.getElementById('device-connection');

  if (batteryEl) {
    const level = device.battery?.level || 0;
    const charging = device.battery?.isCharging ? '⚡' : '';
    batteryEl.textContent = `${level}% ${charging}`;
  }

  if (colorosEl) {
    colorosEl.textContent = device.colorOsVersion || 'ColorOS';
  }

  if (connectionEl) {
    connectionEl.textContent = device.isWifi ? '📡 Wi-Fi' : '🔌 USB';
  }
}

function updateMirrorButton() {
  const btn = document.getElementById('btn-mirror');
  if (btn) {
    const label = btn.querySelector('.btn-label');
    if (isMirroring) {
      label.textContent = 'Stop Mirror';
      btn.classList.add('active');
    } else {
      label.textContent = 'Screen Mirror';
      btn.classList.remove('active');
    }
  }
}

async function toggleMirroring() {
  if (!currentDevice) return;

  try {
    if (isMirroring) {
      await window.api.stopMirroring();
    } else {
      await window.api.startMirroring(currentDevice.id, {
        turnScreenOff: true,
        stayAwake: true,
        maxFps: 60,
        bitRate: '16M',
        audio: true,
        windowTitle: `${currentDevice.model} - A+ Connect`,
        alwaysOnTop: false
      });
    }
  } catch (err) {
    console.error('Mirroring error:', err);
    alert(`Mirror ${isMirroring ? 'stop' : 'start'} failed: ${err.message}`);
  }
}

function selectMode(mode) {
  document.getElementById('mode-wifi').classList.remove('active');
  document.getElementById('mode-usb').classList.remove('active');

  if (mode === 'wifi') {
    document.getElementById('mode-wifi').classList.add('active');
    document.getElementById('wifi-setup').style.display = 'block';
    document.getElementById('usb-setup').style.display = 'none';
  } else {
    document.getElementById('mode-usb').classList.add('active');
    document.getElementById('wifi-setup').style.display = 'none';
    document.getElementById('usb-setup').style.display = 'block';
  }
}

async function pairDevice() {
  const ip = document.getElementById('pair-ip').value.trim();
  const port = document.getElementById('pair-port').value.trim();
  const code = document.getElementById('pair-code').value.trim();

  if (!ip || !port || !code) {
    alert('Please enter IP, port, and pairing code');
    return;
  }

  const btn = document.getElementById('btn-pair');
  btn.disabled = true;
  btn.innerHTML = '<span>🔐 Pairing...</span>';

  try {
    const result = await window.api.pairWifi(ip, port, code);
    if (result.success) {
      alert(`✅ Pairing successful!\n\n${result.message}\n\nNow proceed to Step 4 to connect.`);
      // Auto-fill the connect IP
      document.getElementById('connect-ip').value = ip;
    } else {
      alert(`❌ Pairing failed:\n\n${result.message}`);
    }
  } catch (err) {
    alert(`Error: ${err.message}`);
  } finally {
    btn.disabled = false;
    btn.innerHTML = '<span>🔐 Pair Device</span>';
  }
}

async function connectWifi() {
  const ip = document.getElementById('connect-ip').value.trim();
  const port = document.getElementById('connect-port').value.trim() || '5555';

  if (!ip) {
    alert('Please enter the device IP address');
    return;
  }

  const btn = document.getElementById('btn-connect');
  btn.disabled = true;
  btn.innerHTML = '<span>✨ Connecting...</span>';

  try {
    const result = await window.api.connectWifi(ip, port);
    if (result.success) {
      alert(`✅ Connected!\n\n${result.message}\n\nYour device is now connected wirelessly.`);
      // The device will be auto-detected within seconds
    } else {
      alert(`❌ Connection failed:\n\n${result.message}\n\nMake sure wireless debugging is still enabled and try again.`);
    }
  } catch (err) {
    alert(`Error: ${err.message}`);
  } finally {
    btn.disabled = false;
    btn.innerHTML = '<span>✨ Connect via Wi-Fi</span>';
  }
}

async function checkUsbDevices() {
  try {
    const devices = await window.api.getDevices();
    const usbDevices = devices.filter(d => !d.isWifi && d.state === 'device');

    if (usbDevices.length > 0) {
      alert(`✅ Found ${usbDevices.length} USB device(s):\n\n${usbDevices.map(d => `• ${d.model || d.id}`).join('\n')}`);
    } else {
      alert('❌ No USB devices found.\n\nMake sure:\n• Device is plugged in\n• USB debugging is enabled\n• You authorized this computer on your device');
    }
  } catch (err) {
    alert(`Error: ${err.message}`);
  }
}

// File Transfer Panel
function showFileTransfer() {
  document.getElementById('device-card').style.display = 'none';
  document.getElementById('file-panel').style.display = 'block';
  document.getElementById('notifications-panel').style.display = 'none';
  currentView = 'files';
}

function setupFileDrop() {
  const dropZone = document.getElementById('drop-zone');

  dropZone.addEventListener('click', browseFiles);

  dropZone.addEventListener('dragover', (e) => {
    e.preventDefault();
    dropZone.classList.add('drag-over');
  });

  dropZone.addEventListener('dragleave', () => {
    dropZone.classList.remove('drag-over');
  });

  dropZone.addEventListener('drop', async (e) => {
    e.preventDefault();
    dropZone.classList.remove('drag-over');

    if (!currentDevice) {
      alert('No device connected');
      return;
    }

    const files = Array.from(e.dataTransfer.files);
    for (const file of files) {
      await pushFileToDevice(file.path);
    }
  });
}

async function browseFiles() {
  if (!currentDevice) {
    alert('No device connected');
    return;
  }

  try {
    const filePaths = await window.api.openFileDialog();
    for (const path of filePaths) {
      await pushFileToDevice(path);
    }
  } catch (err) {
    console.error('Browse error:', err);
  }
}

async function pushFileToDevice(filePath) {
  if (!currentDevice) return;

  try {
    await window.api.pushFile(currentDevice.id, filePath, '/sdcard/Download/');
  } catch (err) {
    console.error('Push error:', err);
  }
}

function updateTransferLog(data) {
  const log = document.getElementById('transfer-log');

  if (data.status === 'started') {
    const item = document.createElement('div');
    item.className = 'transfer-item';
    item.id = `transfer-${data.id}`;
    item.innerHTML = `
      <span>${data.filename}</span>
      <span class="transfer-status">Transferring...</span>
    `;
    log.prepend(item);
  } else if (data.status === 'completed') {
    const item = document.getElementById(`transfer-${data.id}`);
    if (item) {
      item.querySelector('.transfer-status').textContent = '✅ Complete';
      item.querySelector('.transfer-status').classList.add('success');
    }
  } else if (data.status === 'failed') {
    const item = document.getElementById(`transfer-${data.id}`);
    if (item) {
      item.querySelector('.transfer-status').textContent = '❌ Failed';
      item.querySelector('.transfer-status').classList.add('failed');
    }
  }
}

// Notifications Panel
function showNotifications() {
  document.getElementById('device-card').style.display = 'none';
  document.getElementById('file-panel').style.display = 'none';
  document.getElementById('notifications-panel').style.display = 'block';
  currentView = 'notifications';
  loadNotificationHistory();
}

async function loadNotificationHistory() {
  try {
    const history = await window.api.getNotificationHistory();
    const list = document.getElementById('notifications-list');

    if (history.length === 0) {
      list.innerHTML = '<div class="empty-state">No notifications yet</div>';
      return;
    }

    list.innerHTML = '';
    for (const notif of history) {
      addNotificationToList(notif);
    }
  } catch (err) {
    console.error('Load notifications error:', err);
  }
}

function addNotificationToList(notif) {
  const list = document.getElementById('notifications-list');

  // Remove empty state
  const emptyState = list.querySelector('.empty-state');
  if (emptyState) emptyState.remove();

  const item = document.createElement('div');
  item.className = 'notification-item';
  item.innerHTML = `
    <div class="notif-icon">🔔</div>
    <div class="notif-content">
      <div class="notif-title">${notif.title}</div>
      <div class="notif-message">${notif.message}</div>
    </div>
    <div class="notif-time">${notif.time}</div>
  `;

  list.prepend(item);

  // Limit to 50 visible notifications
  const items = list.querySelectorAll('.notification-item');
  if (items.length > 50) {
    items[items.length - 1].remove();
  }
}

async function clearNotifications() {
  try {
    await window.api.clearNotificationHistory();
    const list = document.getElementById('notifications-list');
    list.innerHTML = '<div class="empty-state">No notifications yet</div>';
  } catch (err) {
    console.error('Clear notifications error:', err);
  }
}
