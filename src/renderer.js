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
  // The phone connected over the companion channel (TLS), if any.
  companion: null,
  browse: {
    path: '/sdcard',
    entries: [],
    loading: false,
    mode: 'browse', // 'browse' | 'recent'
    query: '',
    results: null, // null while a deep search is in flight
    searchSeq: 0,
    recent: null,
    recentLoading: false,
  },
};

const THEME_KEY = 'aplus-theme';
const CAST_CONFIRM_KEY = 'aplus-cast-confirm';
const RECENT_DAYS = 30;

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

/* ---------- Theme ---------- */

function initTheme() {
  const saved = localStorage.getItem(THEME_KEY);
  const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
  document.documentElement.dataset.theme = saved || (prefersDark ? 'dark' : 'light');

  $('theme-toggle').addEventListener('click', () => {
    const next = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
    document.documentElement.dataset.theme = next;
    localStorage.setItem(THEME_KEY, next);
  });
}

/* ---------- Views ---------- */

const VIEWS = ['empty', 'device', 'files', 'notifications', 'clipboard'];

function showView(name) {
  if (!VIEWS.includes(name)) return;

  // Without a device or companion, only the empty screen is available (clipboard is allowed if companion is active)
  if (!state.device && !state.companionConnected && name !== 'empty') {
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
  if (name === 'files') {
    updateCompanionNotes();
    if (state.device) refreshBrowser();
  }
}

/**
 * The phone file browser needs the debugging connection. Over the companion
 * channel the app can send and receive files, so say that instead of showing
 * an empty browser.
 */
function updateCompanionNotes() {
  const note = $('companion-files-note');
  if (!note) return;

  const companionOnly = !state.device && state.companion;
  note.style.display = companionOnly ? '' : 'none';
  if (companionOnly) {
    const text = $('companion-files-text');
    if (text) {
      text.textContent =
        'You can send files to the phone and pull files from it. ' +
        'Listing and browsing the phone folders still needs the debugging connection.';
    }
  }

  // The browser and the drop zone both drive the debugging connection, so
  // they are hidden rather than left spinning forever.
  const dropzone = $('dropzone');
  if (dropzone) dropzone.hidden = !state.device;
  document.querySelectorAll('#view-files .browser').forEach((el) => {
    el.hidden = !state.device;
  });
}

/* ---------- Device ---------- */

function renderDevice() {
  const device = state.device;
  const companion = state.companion;
  const list = $('device-list');
  const sub = $('profile-sub');

  // Both transports can be live at once: the phone over the debugging
  // connection, and the same phone over the companion channel.
  const rows = [];
  if (device) {
    rows.push(`
      <button class="device-row active" type="button">
        ${icon('phone')}
        <span class="device-row-text">
          <span class="device-row-name">${escapeHtml(device.model || 'Phone')}</span>
          <span class="device-row-meta">${device.isWifi ? 'Wi-Fi' : 'USB'} · connected</span>
        </span>
      </button>`);
  }
  if (companion) {
    rows.push(`
      <button class="device-row ${device ? '' : 'active'}" type="button">
        ${icon('phone')}
        <span class="device-row-text">
          <span class="device-row-name">${escapeHtml(companion.deviceName || 'Phone')}</span>
          <span class="device-row-meta">Companion · TLS 1.3 · connected</span>
        </span>
      </button>`);
  }

  if (rows.length === 0) {
    list.innerHTML = '<div class="device-empty">No device connected</div>';
    sub.textContent = 'Not connected';
    showView('empty');
    return;
  }

  list.innerHTML = rows.join('');

  const shown = device || companion;
  sub.textContent = shown.model || shown.deviceName || 'Connected';

  $('device-title').textContent = device ? (device.model || 'Phone') : (companion.deviceName || 'Phone');
  $('device-sub').textContent = device
    ? `${device.brand || 'OPPO'} · ${device.colorOsVersion || 'ColorOS'} · ${device.isWifi ? 'Wireless' : 'USB'}`
    : `Connected over the companion channel · ${companion.ip || ''}`;

  const transport = $('device-transport');
  transport.innerHTML = device
    ? `${icon(device.isWifi ? 'wifi' : 'usb')}<span>${device.isWifi ? 'Wi-Fi' : 'USB'}</span>`
    : `${icon('plug')}<span>Companion</span>`;

  // Say plainly which features this transport can carry.
  const note = $('companion-capability-note');
  if (note) {
    const companionOnly = !device && companion;
    note.style.display = companionOnly ? '' : 'none';
    if (companionOnly) {
      const text = $('companion-capability-text');
      if (text) {
        text.textContent =
          'The clipboard and file transfers work over this connection. ' +
          'Screen mirroring and the phone file browser still need the debugging connection.';
      }
    }
  }

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

async function sendFiles(paths, destDir = '/sdcard/Download/') {
  if (!state.device) {
    toast('Connect a device first', 'err');
    return;
  }
  if (!paths || paths.length === 0) return;

  for (const filePath of paths) {
    window.api.pushFile(state.device.id, filePath, destDir).catch((err) => {
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

/* ---------- Phone storage browser ---------- */

function parentPath(current) {
  const trimmed = current.replace(/\/+$/, '');
  if (!trimmed) return '/';
  const idx = trimmed.lastIndexOf('/');
  if (idx <= 0) return '/';
  return trimmed.slice(0, idx);
}

function joinPath(base, name) {
  return `${base.replace(/\/+$/, '')}/${name}`;
}

function formatAge(ms) {
  if (!ms) return '';
  const diff = Date.now() - ms;
  if (diff < 60_000) return 'just now';
  const mins = Math.floor(diff / 60_000);
  if (mins < 60) return `${mins} min ago`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours} h ago`;
  const days = Math.floor(hours / 24);
  if (days < 30) return `${days} d ago`;
  const months = Math.floor(days / 30);
  if (months < 12) return `${months} mo ago`;
  return `${Math.floor(months / 12)} y ago`;
}

// Two-line row used by the search results and the recent list, where the
// containing folder matters.
function richRow(entry, { showParent = false, showAge = false, reveal = false } = {}) {
  const parent = parentPath(entry.path);
  const bits = [];
  if (showParent) bits.push(parent);
  if (!entry.isDir) bits.push(formatBytes(entry.size));
  if (showAge) bits.push(formatAge(entry.mtime));

  return `
    <div class="browse-row ${entry.isDir ? 'dir' : 'file'} rich" data-path="${escapeHtml(entry.path)}" data-dir="${entry.isDir ? '1' : '0'}">
      ${icon(entry.isDir ? 'folder' : 'file')}
      <div class="browse-body">
        <div class="browse-name" title="${escapeHtml(entry.name)}">${escapeHtml(entry.name)}</div>
        <div class="browse-sub">${escapeHtml(bits.filter(Boolean).join(' · '))}</div>
      </div>
      ${
        reveal
          ? `<button class="icon-btn sm" data-reveal="${escapeHtml(parent)}" title="Show in folder">
               <svg class="icon"><use href="#i-folder-open"/></svg>
             </button>`
          : ''
      }
      ${
        entry.isDir
          ? ''
          : `<button class="icon-btn sm" data-download="${escapeHtml(entry.path)}" title="Download">
               <svg class="icon"><use href="#i-download"/></svg>
             </button>`
      }
      <button class="icon-btn sm danger" data-delete="${escapeHtml(entry.path)}" title="Delete">
        <svg class="icon"><use href="#i-trash"/></svg>
      </button>
    </div>`;
}

// Keep the chrome (tabs, nav bar, clear button) in sync with the mode.
function syncBrowserChrome() {
  const { query, mode } = state.browse;
  const searching = query.trim().length > 0;

  $('browse-tab-browse').classList.toggle('is-active', mode === 'browse');
  $('browse-tab-recent').classList.toggle('is-active', mode === 'recent');
  $('browse-tab-browse').setAttribute('aria-selected', String(mode === 'browse'));
  $('browse-tab-recent').setAttribute('aria-selected', String(mode === 'recent'));

  $('browse-nav').hidden = searching;
  $('browse-newfolder').hidden = mode === 'recent';
  $('browse-upload').hidden = mode === 'recent';
  // Going "up" makes no sense in the recent list.
  $('browse-up').hidden = mode === 'recent';
  $('browse-up').disabled = state.browse.path === '/';
  $('browse-search-clear').hidden = !searching;
}

function renderBrowser() {
  syncBrowserChrome();

  // The breadcrumb describes what is listed, so it must not keep pointing at
  // the last folder that was opened.
  $('browse-path').textContent =
    state.browse.mode === 'recent' ? 'Recent files' : state.browse.path;

  if (state.browse.query.trim()) return renderSearch();
  if (state.browse.mode === 'recent') return renderRecent();
  return renderDirectory();
}

function renderDirectory() {
  const list = $('browser-list');
  const { path, entries, loading } = state.browse;
  $('browse-path').textContent = path;

  if (!state.device) {
    list.innerHTML = '<div class="list-empty">Connect a phone to browse its files</div>';
    return;
  }
  if (loading) {
    list.innerHTML = '<div class="list-empty">Loading…</div>';
    return;
  }
  if (!entries.length) {
    list.innerHTML = '<div class="list-empty">This folder is empty</div>';
    return;
  }

  list.innerHTML = entries
    .map(
      (entry) => `
      <div class="browse-row ${entry.isDir ? 'dir' : 'file'}" data-path="${escapeHtml(entry.path)}" data-dir="${entry.isDir ? '1' : '0'}">
        ${icon(entry.isDir ? 'folder' : 'file')}
        <div class="browse-name" title="${escapeHtml(entry.name)}">${escapeHtml(entry.name)}</div>
        <div class="browse-meta">${entry.isDir ? 'Folder' : formatBytes(entry.size)}</div>
        ${
          entry.isDir
            ? ''
            : `<button class="icon-btn sm" data-download="${escapeHtml(entry.path)}" title="Download">
                 <svg class="icon"><use href="#i-download"/></svg>
               </button>`
        }
        <button class="icon-btn sm danger" data-delete="${escapeHtml(entry.path)}" title="Delete">
          <svg class="icon"><use href="#i-trash"/></svg>
        </button>
      </div>`,
    )
    .join('');
}

function renderSearch() {
  const list = $('browser-list');
  const needle = state.browse.query.trim().toLowerCase();

  if (!state.device) {
    list.innerHTML = '<div class="list-empty">Connect a phone to search its files</div>';
    return;
  }

  // Matches inside the folder that is open are instant; the phone-wide
  // results arrive a second later from the media index.
  const local = state.browse.entries.filter((e) => e.name.toLowerCase().includes(needle));
  const localPaths = new Set(local.map((e) => e.path));
  const results = state.browse.results;
  const deep = results ? results.filter((e) => !localPaths.has(e.path)) : [];

  const parts = [];

  if (local.length) {
    parts.push(`<div class="list-group-title">In this folder · ${local.length}</div>`);
    parts.push(local.map((e) => richRow(e, { showAge: true })).join(''));
  }

  if (results === null) {
    parts.push('<div class="list-empty">Searching the phone…</div>');
  } else if (deep.length) {
    parts.push(`<div class="list-group-title">Everywhere on the phone · ${deep.length}</div>`);
    parts.push(
      deep.map((e) => richRow(e, { showParent: true, showAge: true, reveal: true })).join(''),
    );
  } else if (!local.length) {
    parts.push(
      `<div class="list-empty">Nothing matches “${escapeHtml(state.browse.query.trim())}”</div>`,
    );
  }

  list.innerHTML = parts.join('');
}

function renderRecent() {
  const list = $('browser-list');
  const { recent, recentLoading } = state.browse;

  if (!state.device) {
    list.innerHTML = '<div class="list-empty">Connect a phone to see its recent files</div>';
    return;
  }
  if (recentLoading) {
    list.innerHTML = '<div class="list-empty">Reading recent files…</div>';
    return;
  }
  if (!recent || recent.length === 0) {
    list.innerHTML = `<div class="list-empty">Nothing changed on the phone in the last ${RECENT_DAYS} days</div>`;
    return;
  }

  list.innerHTML =
    `<div class="list-group-title">Newest on the phone · ${recent.length}</div>` +
    recent.map((e) => richRow(e, { showParent: true, showAge: true, reveal: true })).join('');
}

async function loadDirectory(path) {
  state.browse.path = path;

  if (!state.device) {
    state.browse.entries = [];
    state.browse.loading = false;
    renderBrowser();
    return;
  }

  state.browse.loading = true;
  renderBrowser();

  try {
    state.browse.entries = await window.api.listDirectory(state.device.id, path);
  } catch {
    state.browse.entries = [];
    toast(`Cannot open ${path}`, 'err');
  } finally {
    state.browse.loading = false;
    renderBrowser();
  }
}

async function loadRecent(force = false) {
  if (!state.device) {
    state.browse.recent = null;
    renderBrowser();
    return;
  }
  // The list is cached, but the view still has to be repainted.
  if (!force && state.browse.recent) {
    renderBrowser();
    return;
  }

  state.browse.recentLoading = true;
  renderBrowser();

  try {
    state.browse.recent = await window.api.listRecentFiles(state.device.id);
  } catch (err) {
    state.browse.recent = [];
    toast(`Could not read recent files: ${err.message}`, 'err');
  } finally {
    state.browse.recentLoading = false;
    renderBrowser();
  }
}

// Deep search over the phone's media index. Results from an older query are
// discarded, so fast typing never shows stale matches.
async function runSearch(query) {
  if (!state.device) {
    state.browse.results = [];
    renderBrowser();
    return;
  }

  const seq = ++state.browse.searchSeq;
  state.browse.results = null;
  renderBrowser();

  try {
    const found = await window.api.searchFiles(state.device.id, query);
    if (seq !== state.browse.searchSeq) return;
    state.browse.results = found;
  } catch (err) {
    if (seq !== state.browse.searchSeq) return;
    state.browse.results = [];
    toast(`Search failed: ${err.message}`, 'err');
  } finally {
    if (seq === state.browse.searchSeq) renderBrowser();
  }
}

// Whatever is on screen right now gets reloaded.
function refreshBrowser() {
  if (state.browse.query.trim()) return runSearch(state.browse.query.trim());
  if (state.browse.mode === 'recent') return loadRecent(true);
  return loadDirectory(state.browse.path);
}

function switchMode(mode) {
  if (mode === state.browse.mode) return;

  state.browse.mode = mode;
  state.browse.query = '';
  state.browse.results = null;
  state.browse.searchSeq++;
  $('browse-search').value = '';

  // Always repaint first: the data loaders may return early when their list
  // is already cached, and the tab must still switch.
  renderBrowser();

  if (mode === 'recent') {
    loadRecent();
  } else if (!state.browse.entries.length) {
    loadDirectory(state.browse.path);
  }
}

// Open the folder that contains a search or recent hit.
function revealEntry(dir) {
  state.browse.mode = 'browse';
  state.browse.query = '';
  state.browse.results = null;
  state.browse.searchSeq++;
  $('browse-search').value = '';
  loadDirectory(dir);
}

async function downloadEntry(remotePath) {
  try {
    const result = await window.api.pullFile(state.device.id, remotePath);
    if (result && result.success) return result.localTarget;
  } catch (err) {
    toast(`Download failed: ${err.message}`, 'err');
  }
  return null;
}

// Clicking a file pulls it to ~/Downloads/A+Connect and opens it with the
// desktop's default application.
async function openEntry(remotePath) {
  if (!state.device) return;

  const name = remotePath.split('/').pop();
  const localPath = await downloadEntry(remotePath);
  if (!localPath) return;

  const opened = await window.api.openLocalFile(localPath);
  if (opened && opened.success) {
    toast(`Opened ${name}`);
  } else {
    toast(`Saved to ${localPath} (no application registered for this type)`, 'err');
  }
}

async function deleteEntry(remotePath) {
  const name = remotePath.split('/').pop();
  const confirmed = await window.api.confirmAction({
    message: `Delete "${name}" from the phone?`,
    detail: 'This cannot be undone.',
    confirmLabel: 'Delete',
  });
  if (!confirmed) return;

  try {
    await window.api.deletePath(state.device.id, remotePath);
    toast(`Deleted ${name}`);
    refreshBrowser();
  } catch (err) {
    toast(`Delete failed: ${err.message}`, 'err');
  }
}

/* Simple text prompt (Electron has no window.prompt). */
function askText(title, placeholder) {
  return new Promise((resolve) => {
    const input = $('prompt-input');
    $('prompt-title').textContent = title;
    input.value = '';
    input.placeholder = placeholder || '';
    openModal('modal-prompt');
    input.focus();

    const finish = (value) => {
      $('prompt-ok').removeEventListener('click', onOk);
      input.removeEventListener('keydown', onKey);
      closeModal('modal-prompt');
      resolve(value);
    };
    const onOk = () => finish(input.value.trim() || null);
    const onKey = (e) => {
      if (e.key === 'Enter') onOk();
      if (e.key === 'Escape') finish(null);
    };

    $('prompt-ok').addEventListener('click', onOk);
    input.addEventListener('keydown', onKey);
  });
}

async function createFolder() {
  if (!state.device) return;
  const name = await askText('New folder', 'Folder name');
  if (!name) return;

  try {
    await window.api.makeDirectory(state.device.id, joinPath(state.browse.path, name));
    toast(`Created ${name}`);
    loadDirectory(state.browse.path);
  } catch (err) {
    toast(`Could not create folder: ${err.message}`, 'err');
  }
}

async function uploadHere() {
  if (!state.device) return;
  const paths = await window.api.openFileDialog();
  await sendFiles(paths, state.browse.path);
  setTimeout(refreshBrowser, 1200);
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
        <button class="icon-btn sm" data-notif-dismiss="${escapeHtml(n.id)}" title="Remove from history" aria-label="Remove notification">
          <svg class="icon"><use href="#i-close"/></svg>
        </button>
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

async function refreshCompanionInfo() {
  try {
    if (!window.api.getCompanionInfo) return;
    const info = await window.api.getCompanionInfo();
    applyCompanionInfo(info);
  } catch {
    /* ignore */
  }
}

function applyCompanionInfo(info) {
  if (!info) return;
  state.companionConnected = Boolean(info.connected);
  state.companion = state.companionConnected ? (info.clients && info.clients[0]) || null : null;
  renderDevice();
  updateCompanionNotes();
  const pill = $('companion-pill');
  const detail = $('companion-detail');
  const badge = $('companion-badge');
  const codeEl = $('companion-code');
  const destEl = $('companion-dest-path');

  if (codeEl) codeEl.textContent = info.code || '—';
  if (destEl && info.destinationDir) destEl.textContent = info.destinationDir;

  if (info.connected) {
    const client = info.clients?.[0];
    if (pill) {
      pill.textContent = 'Connected';
      pill.className = 'pill syncing';
    }
    // Nothing to pair while a phone is already connected.
    if (qrCard) qrCard.style.display = 'none';
    if (detail) {
      detail.textContent = `Connected to ${client?.deviceName || 'Phone'} (${client?.ip || info.address}) over TLS 1.3`;
    }
    if (badge) {
      badge.innerHTML = `🔒 <strong>TLS Pinned</strong>`;
    }
  } else if (info.status === 'listening') {
    if (pill) {
      pill.textContent = 'Listening';
      pill.className = 'pill idle';
    }
    // Nothing is paired yet: show the pairing code straight away.
    if (qrCard && qrCard.style.display === 'none') {
      qrCard.style.display = '';
      refreshPairingQr();
    }
    if (detail) {
      detail.textContent = `Ready on ${info.address}:${info.port}. Enter pairing code on phone.`;
    }
    if (badge) {
      badge.innerHTML = `Code: <strong id="companion-code">${info.code || '—'}</strong>`;
    }
  } else {
    if (pill) {
      pill.textContent = 'Offline';
      pill.className = 'pill disconnected';
    }
    if (qrCard) qrCard.style.display = 'none';
    if (detail) {
      detail.textContent = 'Companion TLS server is not running.';
    }
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

  // The pairing QR: shown in the "add a device" dialog and, when nothing is
  // connected yet, right in the clipboard panel.
  async function refreshPairingQr() {
    if (!window.api.getCompanionQr) return null;
    try {
      const result = await window.api.getCompanionQr();
      if (!result || !result.success) return null;

      const image = $('pair-qr-image');
      if (image) image.src = result.dataUrl;
      const code = $('pair-qr-code');
      if (code) code.textContent = result.info?.code || '------';
      const hint = $('pair-qr-hint');
      if (hint) {
        hint.textContent = `${result.info?.pcName || 'This PC'} at ${result.info?.host}:${result.info?.port}`;
      }
      return result;
    } catch {
      return null;
    }
  }

  // Only one panel may show at a time - set that up before the first click.
  {
    const active = document.querySelector('.tab.active');
    const activeTab = active ? active.dataset.tab : 'qr';
    $('tab-qr').hidden = activeTab !== 'qr';
    $('tab-wireless').hidden = activeTab !== 'wireless';
    $('tab-usb').hidden = activeTab !== 'usb';
  }

  for (const id of ['btn-add-device', 'btn-add-device-2', 'btn-add-device-3']) {
    const el = $(id);
    if (el) {
      el.addEventListener('click', () => {
        openModal('modal-device');
        refreshPairingQr();
      });
    }
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

  $('btn-cast').addEventListener('click', () => {
    if (!state.device) return;
    if (localStorage.getItem(CAST_CONFIRM_KEY) === 'off') {
      startCast();
      return;
    }
    openModal('modal-cast');
  });

  $('btn-cast-confirm').addEventListener('click', () => {
    if ($('cast-confirm-skip').checked) {
      localStorage.setItem(CAST_CONFIRM_KEY, 'off');
    }
    closeModal('modal-cast');
    startCast();
  });

  $('btn-cast-stop').addEventListener('click', async () => {
    await window.api.stopMirroring();
    state.casting = false;
    renderCast();
  });

  renderCast();
}

async function startCast() {
  if (!state.device) return;

  const result = await window.api.startMirroring(state.device.id);
  if (result && result.success === false) {
    toast(result.message || 'Could not start casting', 'err');
    return;
  }
  state.casting = true;
  renderCast();
}

function initPairing() {
  document.querySelectorAll('.tab').forEach((tab) => {
    tab.addEventListener('click', () => {
      document.querySelectorAll('.tab').forEach((t) => t.classList.toggle('active', t === tab));
      $('tab-qr').hidden = tab.dataset.tab !== 'qr';
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

  // Phone storage browser
  $('browse-up').addEventListener('click', () => loadDirectory(parentPath(state.browse.path)));
  $('browse-refresh').addEventListener('click', () => refreshBrowser());
  $('browse-newfolder').addEventListener('click', createFolder);
  $('browse-upload').addEventListener('click', uploadHere);

  $('browse-tab-browse').addEventListener('click', () => switchMode('browse'));
  $('browse-tab-recent').addEventListener('click', () => switchMode('recent'));

  // Typing filters the open folder instantly; the phone-wide search runs
  // once the user pauses.
  let searchTimer = null;
  const searchInput = $('browse-search');

  searchInput.addEventListener('input', () => {
    state.browse.query = searchInput.value;
    state.browse.searchSeq++; // discard any in-flight result
    state.browse.results = null;

    clearTimeout(searchTimer);
    const query = state.browse.query.trim();

    if (!query) {
      renderBrowser();
      return;
    }

    renderBrowser();
    searchTimer = setTimeout(() => runSearch(query), 450);
  });

  searchInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      clearTimeout(searchTimer);
      runSearch(searchInput.value.trim());
    }
    if (e.key === 'Escape') {
      clearTimeout(searchTimer);
      searchInput.value = '';
      state.browse.query = '';
      state.browse.results = null;
      renderBrowser();
    }
  });

  $('browse-search-clear').addEventListener('click', () => {
    clearTimeout(searchTimer);
    searchInput.value = '';
    state.browse.query = '';
    state.browse.results = null;
    searchInput.focus();
    renderBrowser();
  });

  $('browser-list').addEventListener('click', (e) => {
    const download = e.target.closest('[data-download]');
    if (download) {
      downloadEntry(download.dataset.download);
      return;
    }

    const del = e.target.closest('[data-delete]');
    if (del) {
      deleteEntry(del.dataset.delete);
      return;
    }

    const reveal = e.target.closest('[data-reveal]');
    if (reveal) {
      revealEntry(reveal.dataset.reveal);
      return;
    }

    const row = e.target.closest('.browse-row');
    if (!row) return;

    if (row.dataset.dir === '1') {
      loadDirectory(row.dataset.path);
    } else {
      openEntry(row.dataset.path);
    }
  });

  window.api.onTransferProgress((data) => {
    switch (data.status) {
      case 'started':
        upsertTransfer({ id: data.id, filename: data.filename, size: data.size, status: 'active' });
        break;
      case 'completed': {
        const name = data.filename || String(data.targetPath || data.localTarget || '').split('/').pop();
        upsertTransfer({
          id: data.id,
          filename: name,
          targetPath: data.targetPath || data.localTarget,
          status: 'done',
        });
        toast(data.type === 'pull' ? `Downloaded ${name}` : `Sent ${name}`);
        break;
      }
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

  // Remove a single notification from the history.
  $('notifications-list').addEventListener('click', async (e) => {
    const btn = e.target.closest('[data-notif-dismiss]');
    if (!btn) return;
    const id = btn.dataset.notifDismiss;
    await window.api.dismissNotification(id);
    state.notifications = state.notifications.filter((n) => n.id !== id);
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

  const btnOpenDest = $('btn-open-dest');
  if (btnOpenDest) {
    btnOpenDest.addEventListener('click', () => {
      window.api.openCompanionDestinationDir?.();
    });
  }
  const btnChangeDest = $('btn-change-dest');
  if (btnChangeDest) {
    btnChangeDest.addEventListener('click', async () => {
      const newDir = await window.api.selectCompanionDestinationDir?.();
      if (newDir) {
        const destEl = $('companion-dest-path');
        if (destEl) destEl.textContent = newDir;
        toast(`Destination set to ${newDir}`);
      }
    });
  }

  const showQrButton = $('btn-show-qr');
  const hideQrButton = $('btn-hide-qr');
  const qrCard = $('companion-qr-card');
  const qrImage = $('companion-qr');

  if (showQrButton) {
    showQrButton.addEventListener('click', async () => {
      if (!window.api.getCompanionQr) return;
      const result = await window.api.getCompanionQr();
      if (!result || !result.success) {
        toast('Could not create the pairing code', 'err');
        return;
      }
      if (qrImage) qrImage.src = result.dataUrl;
      if (qrCard) qrCard.style.display = '';
    });
  }

  if (hideQrButton) {
    hideQrButton.addEventListener('click', () => {
      if (qrCard) qrCard.style.display = 'none';
    });
  }

  const sendFileButton = $('btn-send-file-to-phone');
  if (sendFileButton) {
    sendFileButton.addEventListener('click', async () => {
      if (!window.api.pickAndSendFileToPhone) return;
      const result = await window.api.pickAndSendFileToPhone();
      if (result && result.canceled) return;
      if (result && result.success) {
        toast(`Sent ${result.name} to the phone`);
      } else {
        toast('Could not send the file', 'err');
      }
    });
  }

  const sendButton = $('btn-send-to-phone');
  if (sendButton) {
    sendButton.addEventListener('click', async () => {
      if (!window.api.pickAndSendFileToPhone) return;
      const result = await window.api.pickAndSendFileToPhone();
      if (result && result.canceled) return;
      if (result && result.success) {
        toast(`Sent ${result.name} to the phone`);
      } else {
        toast('Could not send the file', 'err');
      }
    });
  }

  if (window.api.onCompanionStatus) {
    window.api.onCompanionStatus(() => refreshCompanionInfo());
  }
  if (window.api.onCompanionPaired) {
    window.api.onCompanionPaired((d) => {
      toast(`Phone paired: ${d.deviceName || d.deviceId}`);
      refreshCompanionInfo();
    });
  }
  if (window.api.onCompanionDisconnected) {
    window.api.onCompanionDisconnected(() => refreshCompanionInfo());
  }
  refreshCompanionInfo();
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
  initTheme();
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

  // A reload loses the device event, so pick the connection back up.
  window.api.getConnectedDevice?.().then((device) => {
    if (device && !state.device) setDevice(device);
  });
}

window.addEventListener('DOMContentLoaded', init);
