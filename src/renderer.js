/* A+ Connect — renderer
   Single source of truth for the UI. No inline handlers, no duplicated
   listeners: every signal is wired exactly once in init(). */

const state = {
  device: null,
  view: 'empty',
  transfers: new Map(),
  notifications: [],
  unreadNotifications: 0,
  clipboard: [],
  clipboardStatus: { status: 'idle', enabled: true },
  casting: false,
};

const $ = (id) => document.getElementById(id);

/* ---------- Small helpers ---------- */

function icon(name, cls = 'icon') {
  return `<svg class="${cls}" aria-hidden="true"><use href="#i-${name}"/></svg>`;
}

function escapeHtml(text) {
  return String(text ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

function formatBytes(bytes) {
  if (!bytes && bytes !== 0) return '';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  return `${(bytes / 1024 / 1024 / 1024).toFixed(1)} GB`;
}

function toast(message, kind = 'ok') {
  const host = $('toasts');
  const el = document.createElement('div');
  el.className = `toast ${kind === 'err' ? 'err' : ''}`;
  el.innerHTML = `${icon(kind === 'err' ? 'info' : 'check')}<span>${escapeHtml(message)}</span>`;
  host.appendChild(el);
  setTimeout(() => el.remove(), 3200);
}

/* ---------- Views ---------- */

const VIEWS = ['empty', 'device', 'files', 'notifications', 'clipboard'];

function showView(name) {
  if (!VIEWS.includes(name)) return;

  // Without a device, the only useful screen is the empty state.
  if (!state.device && name !== 'empty') {
    name = 'empty';
  }

  state.view = name;

  for (const view of VIEWS) {
    const el = $(`view-${view}`);
    if (el) el.hidden = view !== name;
  }

  document.querySelectorAll('.nav-item[data-view]').forEach((el) => {
    el.classList.toggle('active', el.dataset.view === name);
  });

  if (name === 'notifications') {
    state.unreadNotifications = 0;
    renderBadges();
    loadNotificationHistory();
  }
  if (name === 'clipboard') {
    loadClipboardHistory();
    refreshClipboardStatus();
  }
  if (name === 'device') {
    renderDevice();
  }
}

/* ---------- Device ---------- */

function renderDevice() {
  const device = state.device;
  const list = $('device-list');
  const sub = $('profile-sub');

  if (!device) {
    list.innerHTML = '<div class="device-empty">No device connected</div>';
    sub.textContent = 'Not connected';
    showView('empty');
    return;
  }

  list.innerHTML = `
    <button class="device-row active" type="button">
      ${icon('phone')}
      <span class="device-row-text">
        <span class="device-row-name">${escapeHtml(device.model || 'Phone')}</span>
        <span class="device-row-meta">${device.isWifi ? 'Wi-Fi' : 'USB'} · connected</span>
      </span>
    </button>`;

  sub.textContent = device.model || 'Connected';

  $('device-title').textContent = device.model || 'Phone';
  $('device-sub').textContent = `${device.brand || 'OPPO'} · ${device.colorOsVersion || 'ColorOS'} · ${device.isWifi ? 'Wireless' : 'USB'}`;

  const transport = $('device-transport');
  transport.innerHTML = `${icon(device.isWifi ? 'wifi' : 'usb')}<span>${device.isWifi ? 'Wi-Fi' : 'USB'}</span>`;

  const battery = device.battery || {};
  $('t-battery').textContent = battery.level != null ? `${battery.level}%` : '—';
  $('t-android').textContent = device.androidVersion || '—';
  $('t-storage').textContent = device.storage?.free || '—';
  $('t-rom').textContent = device.colorOsVersion || '—';

  $('files-dest-label').textContent = '/sdcard/Download';
}

function setDevice(device) {
  const isSame = state.device && device && state.device.id === device.id;
  state.device = device;

  if (!device) {
    state.casting = false;
    renderCast();
    renderDevice();
    return;
  }

  renderDevice();
  if (!isSame || state.view === 'empty') showView('device');
}

function renderCast() {
  const start = $('btn-cast');
  const stop = $('btn-cast-stop');
  const label = $('cast-state');

  start.hidden = state.casting;
  stop.hidden = !state.casting;
  label.textContent = state.casting ? 'Casting in a separate window' : 'Not casting';
}

/* ---------- Files ---------- */

function transferRow(t) {
  const active = t.status === 'active';
  const meta =
    t.status === 'active'
      ? `${formatBytes(t.size)} · sending…`
      : t.status === 'done'
        ? `Sent · ${t.targetPath || ''}`
        : t.status === 'cancelled'
          ? 'Cancelled'
          : `Failed${t.error ? ` · ${escapeHtml(t.error)}` : ''}`;

  return `
    <div class="transfer" data-id="${escapeHtml(t.id)}">
      <div class="transfer-icon">${icon(t.status === 'done' ? 'check' : 'upload')}</div>
      <div class="transfer-body">
        <div class="transfer-name">${escapeHtml(t.filename)}</div>
        <div class="transfer-meta">${escapeHtml(meta)}</div>
        ${active ? '<div class="bar"><span></span></div>' : ''}
      </div>
      ${active ? `<button class="link-btn" data-cancel="${escapeHtml(t.id)}">Cancel</button>` : ''}
    </div>`;
}

function renderTransfers() {
  const list = $('transfer-list');
  const items = [...state.transfers.values()].reverse();
  const activeCount = items.filter((t) => t.status === 'active').length;

  if (items.length === 0) {
    list.innerHTML = '<div class="list-empty">No transfers yet</div>';
  } else {
    list.innerHTML = items.map(transferRow).join('');
  }

  $('btn-cancel-all').hidden = activeCount === 0;

  const badge = $('badge-files');
  badge.hidden = activeCount === 0;
  badge.textContent = String(activeCount);
}

function upsertTransfer(patch) {
  const existing = state.transfers.get(patch.id) || { id: patch.id };
  state.transfers.set(patch.id, { ...existing, ...patch });
  renderTransfers();
}

async function sendFiles(paths) {
  if (!state.device) {
    toast('Connect a device first', 'err');
    return;
  }
  if (!paths || paths.length === 0) return;

  for (const filePath of paths) {
    window.api.pushFile(state.device.id, filePath, '/sdcard/Download/').catch((err) => {
      toast(`Send failed: ${err.message}`, 'err');
    });
  }
}

async function browseFiles() {
  if (!state.device) {
    toast('Connect a device first', 'err');
    return;
  }
  const paths = await window.api.openFileDialog();
  await sendFiles(paths);
}

/* ---------- Notifications ---------- */

function renderNotifications() {
  const list = $('notifications-list');
  if (state.notifications.length === 0) {
    list.innerHTML = '<div class="list-empty">No notifications yet</div>';
    return;
  }

  list.innerHTML = state.notifications
    .map(
      (n) => `
      <div class="notif">
        <div class="notif-icon">${icon('bell')}</div>
        <div class="notif-body">
          <div class="notif-title">${escapeHtml(n.title || n.app)}</div>
          ${n.message ? `<div class="notif-message">${escapeHtml(n.message)}</div>` : ''}
          <div class="notif-meta"><span>${escapeHtml(n.app || n.pkg || '')}</span>${n.time ? `<span>·</span><span>${escapeHtml(n.time)}</span>` : ''}</div>
        </div>
      </div>`,
    )
    .join('');
}

function renderBadges() {
  const badge = $('badge-notifications');
  badge.hidden = state.unreadNotifications === 0;
  badge.textContent = state.unreadNotifications > 9 ? '9+' : String(state.unreadNotifications);
}

async function loadNotificationHistory() {
  try {
    state.notifications = await window.api.getNotificationHistory();
    renderNotifications();
  } catch {
    /* ignore */
  }
}

/* ---------- Clipboard ---------- */

function clipRow(entry) {
  const fromPhone = entry.direction === 'phone-to-desktop';
  return `
    <div class="clip">
      <div class="clip-icon">${icon(fromPhone ? 'download' : 'upload')}</div>
      <div class="clip-body">
        <div class="clip-text">${escapeHtml(entry.preview || entry.text || '')}</div>
        <div class="clip-meta">
          <span>${fromPhone ? 'From phone' : 'To phone'}</span>
          ${entry.time ? `<span>·</span><span>${escapeHtml(entry.time)}</span>` : ''}
        </div>
      </div>
      <button class="link-btn" data-copy="${escapeHtml(entry.id)}">Copy</button>
    </div>`;
}

function renderClipboard() {
  const list = $('clipboard-list');
  if (state.clipboard.length === 0) {
    list.innerHTML = '<div class="list-empty">Nothing synced yet</div>';
    return;
  }
  list.innerHTML = state.clipboard.map(clipRow).join('');
}

function applyClipboardStatus(payload) {
  state.clipboardStatus = { ...state.clipboardStatus, ...payload };
  const status = state.clipboardStatus.status || 'idle';

  const labels = {
    syncing: 'Syncing',
    connecting: 'Connecting…',
    idle: 'Idle',
    error: 'Error',
    disconnected: 'Disconnected',
  };

  const pill = $('clip-status');
  pill.textContent = labels[status] || status;
  pill.className = `pill ${status}`;

  const settingsPill = $('settings-clip-status');
  settingsPill.textContent = labels[status] || status;
  settingsPill.className = `pill ${status}`;

  $('settings-clip-detail').textContent = state.clipboardStatus.enabled
    ? 'Reading and writing the desktop clipboard through wl-paste/wl-copy'
    : 'Turned off';

  const dot = $('clipboard-dot');
  dot.className = `status-dot ${status}`;

  const toggle = $('clip-toggle');
  if (toggle) toggle.checked = state.clipboardStatus.enabled !== false;
}

async function refreshClipboardStatus() {
  try {
    applyClipboardStatus(await window.api.getClipboardStatus());
  } catch {
    /* ignore */
  }
}

async function loadClipboardHistory() {
  try {
    state.clipboard = await window.api.getClipboardHistory();
    renderClipboard();
  } catch {
    /* ignore */
  }
}

/* ---------- Modals ---------- */

function openModal(id) {
  $(id).hidden = false;
  const focusable = $(id).querySelector('input, button');
  if (focusable) focusable.focus();
}

function closeModal(id) {
  $(id).hidden = true;
}

function closeAllModals() {
  document.querySelectorAll('.modal-root').forEach((el) => {
    el.hidden = true;
  });
}

/* ---------- Init ---------- */

function initWindowControls() {
  $('win-min').addEventListener('click', () => window.api.minimizeWindow());
  $('win-close').addEventListener('click', () => window.api.closeWindow());
  $('win-max').addEventListener('click', () => window.api.toggleMaximize?.());
}

function initNavigation() {
  document.querySelectorAll('[data-view]').forEach((el) => {
    el.addEventListener('click', () => showView(el.dataset.view));
  });

  for (const id of ['btn-add-device', 'btn-add-device-2', 'btn-add-device-3']) {
    const el = $(id);
    if (el) el.addEventListener('click', () => openModal('modal-device'));
  }

  $('btn-settings').addEventListener('click', () => {
    refreshClipboardStatus();
    openModal('modal-settings');
  });

  document.querySelectorAll('[data-close-modal]').forEach((el) => {
    el.addEventListener('click', closeAllModals);
  });

  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') closeAllModals();
  });
}

function initDeviceActions() {
  $('btn-disconnect').addEventListener('click', async () => {
    if (!state.device) return;
    await window.api.disconnectDevice(state.device.id);
    toast('Disconnected');
  });

  $('btn-cast').addEventListener('click', async () => {
    if (!state.device) return;
    const result = await window.api.startMirroring(state.device.id);
    if (result && result.success === false) {
      toast(result.message || 'Could not start casting', 'err');
      return;
    }
    state.casting = true;
    renderCast();
  });

  $('btn-cast-stop').addEventListener('click', async () => {
    await window.api.stopMirroring();
    state.casting = false;
    renderCast();
  });

  renderCast();
}

function initPairing() {
  document.querySelectorAll('.tab').forEach((tab) => {
    tab.addEventListener('click', () => {
      document.querySelectorAll('.tab').forEach((t) => t.classList.toggle('active', t === tab));
      $('tab-wireless').hidden = tab.dataset.tab !== 'wireless';
      $('tab-usb').hidden = tab.dataset.tab !== 'usb';
    });
  });

  const note = $('pair-status');

  $('btn-pair').addEventListener('click', async () => {
    const ip = $('pair-ip').value.trim();
    const port = $('pair-port').value.trim();
    const code = $('pair-code').value.trim();

    if (!ip || !port || !code) {
      note.className = 'modal-note err';
      note.textContent = 'Enter the IP, port and pairing code shown on the phone.';
      return;
    }

    note.className = 'modal-note';
    note.textContent = 'Pairing…';
    const result = await window.api.pairWifi(ip, port, code);
    note.className = `modal-note ${result.success ? 'ok' : 'err'}`;
    note.textContent = result.success
      ? 'Paired. Now enter the connect port from the Wireless debugging screen.'
      : `Pairing failed: ${result.message}`;

    if (result.success) {
      $('connect-ip').value = ip;
    }
  });

  $('btn-connect-wifi').addEventListener('click', async () => {
    const ip = $('connect-ip').value.trim();
    const port = $('connect-port').value.trim() || '5555';

    if (!ip) {
      note.className = 'modal-note err';
      note.textContent = 'Enter the IP address shown on the phone.';
      return;
    }

    note.className = 'modal-note';
    note.textContent = 'Connecting…';
    const result = await window.api.connectWifi(ip, port);

    if (result.success) {
      note.className = 'modal-note ok';
      note.textContent = 'Connected.';
      setTimeout(closeAllModals, 700);
      toast('Device connected');
    } else {
      note.className = 'modal-note err';
      note.textContent = `Could not connect: ${result.message}`;
    }
  });
}

function initFiles() {
  const zone = $('dropzone');

  // Exactly one entry point: the zone itself. The button inside it does not
  // have its own handler, so a click can never open two pickers.
  zone.addEventListener('click', browseFiles);
  zone.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      browseFiles();
    }
  });

  zone.addEventListener('dragover', (e) => {
    e.preventDefault();
    zone.classList.add('drag-over');
  });
  zone.addEventListener('dragleave', () => zone.classList.remove('drag-over'));

  zone.addEventListener('drop', (e) => {
    e.preventDefault();
    zone.classList.remove('drag-over');

    const paths = [...e.dataTransfer.files]
      .map((file) => file.path || window.api.getPathForFile?.(file))
      .filter(Boolean);

    if (paths.length === 0) {
      toast('Could not read the dropped files', 'err');
      return;
    }
    sendFiles(paths);
  });

  $('transfer-list').addEventListener('click', (e) => {
    const btn = e.target.closest('[data-cancel]');
    if (btn) window.api.cancelTransfer(btn.dataset.cancel);
  });

  $('btn-cancel-all').addEventListener('click', () => window.api.cancelAllTransfers());

  window.api.onTransferProgress((data) => {
    switch (data.status) {
      case 'started':
        upsertTransfer({ id: data.id, filename: data.filename, size: data.size, status: 'active' });
        break;
      case 'completed':
        upsertTransfer({ id: data.id, filename: data.filename, targetPath: data.targetPath, status: 'done' });
        toast(`Sent ${data.filename}`);
        break;
      case 'failed':
        upsertTransfer({ id: data.id, status: 'failed', error: data.error });
        break;
      case 'cancelled':
        upsertTransfer({ id: data.id, status: 'cancelled' });
        break;
    }
  });
}

function initNotifications() {
  window.api.onNotificationReceived((notif) => {
    state.notifications.unshift(notif);
    if (state.notifications.length > 50) state.notifications.pop();

    if (state.view === 'notifications') {
      renderNotifications();
    } else {
      state.unreadNotifications += 1;
      renderBadges();
    }
  });

  $('btn-clear-notifs').addEventListener('click', async () => {
    await window.api.clearNotificationHistory();
    state.notifications = [];
    renderNotifications();
  });
}

function initClipboard() {
  window.api.onClipboardStatus(applyClipboardStatus);

  // One subscription owns the list. `clipboard-synced` only drives feedback,
  // otherwise every sync would be rendered twice.
  window.api.onClipboardHistory((entry) => {
    state.clipboard.unshift(entry);
    if (state.clipboard.length > 50) state.clipboard.pop();
    if (state.view === 'clipboard') renderClipboard();
  });

  window.api.onClipboardSynced((entry) => {
    const label = entry.direction === 'phone-to-desktop' ? 'Copied from phone' : 'Sent to phone';
    const preview = entry.text.length > 40 ? `${entry.text.slice(0, 40)}…` : entry.text;
    toast(`${label}: ${preview}`);
  });

  $('clip-toggle').addEventListener('change', async (e) => {
    const result = await window.api.setClipboardSync(e.target.checked);
    applyClipboardStatus({ enabled: result.enabled });
  });

  $('btn-clear-clip').addEventListener('click', async () => {
    await window.api.clearClipboardHistory();
    state.clipboard = [];
    renderClipboard();
  });

  $('clipboard-list').addEventListener('click', async (e) => {
    const btn = e.target.closest('[data-copy]');
    if (!btn) return;
    const entry = state.clipboard.find((c) => c.id === btn.dataset.copy);
    if (!entry) return;
    try {
      await navigator.clipboard.writeText(entry.text);
      toast('Copied to clipboard');
    } catch {
      toast('Could not copy', 'err');
    }
  });
}

function initDeviceEvents() {
  window.api.onDeviceConnected((device) => {
    setDevice(device);
    toast(`${device.model || 'Device'} connected`);
  });

  window.api.onDeviceDisconnected(() => {
    setDevice(null);
    toast('Device disconnected', 'err');
  });

  window.api.onTelemetryUpdated((device) => {
    if (!state.device) return;
    state.device = { ...state.device, ...device };
    renderDevice();
  });

  window.api.onMirrorStarted(() => {
    state.casting = true;
    renderCast();
  });

  window.api.onMirrorStopped(() => {
    state.casting = false;
    renderCast();
  });
}

function init() {
  initWindowControls();
  initNavigation();
  initDeviceActions();
  initPairing();
  initFiles();
  initNotifications();
  initClipboard();
  initDeviceEvents();

  renderBadges();
  renderTransfers();
  renderNotifications();
  renderClipboard();
  refreshClipboardStatus();

  // Nothing is connected yet: the empty state is the honest default.
  showView('empty');
}

window.addEventListener('DOMContentLoaded', init);
