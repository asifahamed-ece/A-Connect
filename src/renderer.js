/* A+ Connect — renderer
   Single source of truth for the UI. No inline handlers, no duplicated
   listeners: every signal is wired exactly once in init(). */

const state = {
  device: null,
  // Which of the two connections the user is working with: 'adb' (the phone
  // over the debugging connection) or 'companion' (the same phone over the
  // encrypted channel). Both can be live at once, so the user picks.
  target: 'adb',
  view: 'connect',
  transfers: new Map(),
  // Every file shared either way, newest first, kept across restarts. The
  // Files page shows only what is moving; finished files live here.
  history: { list: [], tab: 'sent' },
  // Whether the pairing QR is on screen. The one QR button follows it.
  qrVisible: false,
  notifications: [],
  unreadNotifications: 0,
  clipboard: [],
  clipboardStatus: { status: 'idle', enabled: true },
  casting: false,
  // Screen mirroring over the companion channel: the live state, and the reason
  // it last ended. Kept apart from `casting`, which is the scrcpy/ADB path.
  mirror: { state: 'idle', reason: null, width: 0, height: 0, encoder: '', frames: 0 },
  // The phone connected over the companion channel (TLS), if any.
  companion: null,
  // The last companion info block, which carries the device-info vitals.
  companionInfo: null,
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
  // The phone's whole storage, listed over the companion channel. `path` is
  // relative to the phone's storage root ('' is that root), as the phone hands
  // it out; the phone is the authority on what a path means. `results` is the
  // search answer, null while the folder listing is what is on screen.
  phone: {
    path: '',
    entries: [],
    results: null,
    query: '',
    searching: false,
    loading: false,
    loaded: false,
    error: null,
    seq: 0,
    searchSeq: 0,
  },
  selectedFiles: {
    adb: new Map(),
    companion: new Map(),
  },
};

const THEME_KEY = 'aplus-theme';
const CAST_CONFIRM_KEY = 'aplus-cast-confirm';
const HISTORY_KEY = 'aplus-file-history';
// Appearance, local to this computer (no IPC). DESIGN.md §5.3 names the
// transparency key; clarity is the glass slider, 0-100.
const TRANSPARENCY_KEY = 'aplus-transparency';
const MOTION_KEY = 'aplus-motion';
const CLARITY_KEY = 'aplus-clarity';
const CLARITY_DEFAULT = 55;
const RECENT_DAYS = 30;

const $ = (id) => document.getElementById(id);

function fileSelectionMode() {
  return state.target === 'companion' && state.companion ? 'companion' : 'adb';
}

function fileSelectionMap(mode = fileSelectionMode()) {
  return state.selectedFiles[mode];
}

function isFileSelected(path, mode = fileSelectionMode()) {
  return fileSelectionMap(mode).has(path);
}

function toggleFileSelection(path, name, selected, mode = fileSelectionMode()) {
  const selection = fileSelectionMap(mode);
  if (selected) selection.set(path, name);
  else selection.delete(path);
  renderFileSelectionActions();
}

function clearFileSelection(mode = fileSelectionMode()) {
  fileSelectionMap(mode).clear();
  renderFileSelectionActions();
  renderBrowser();
  renderPhoneBrowser();
}

function renderFileSelectionActions() {
  const selection = fileSelectionMap();
  const count = selection.size;
  const actions = $('file-selection-actions');
  const label = $('file-selection-count');
  const download = $('btn-download-selected');
  const clear = $('btn-clear-selection');
  if (actions) actions.hidden = count === 0;
  if (label) label.textContent = `${count} ${count === 1 ? 'file' : 'files'} selected`;
  if (download) download.disabled = count === 0;
  if (clear) clear.hidden = count === 0;
}

async function downloadSelectedFiles() {
  const mode = fileSelectionMode();
  const selected = [...fileSelectionMap(mode)];
  if (!selected.length) return;
  fileSelectionMap(mode).clear();
  renderFileSelectionActions();
  renderBrowser();
  renderPhoneBrowser();

  if (mode === 'companion') {
    for (const [path, name] of selected) await pullPhoneFile(path, name);
    return;
  }

  if (!state.device) {
    toast('Connect a device first', 'err');
    return;
  }
  for (const [path] of selected) {
    try {
      await downloadEntry(path);
    } catch (err) {
      toast(`Download failed: ${err.message}`, 'err');
    }
  }
}

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

/** A click after dragging to select text must not activate the surrounding row. */
function hasSelectedText() {
  return Boolean(window.getSelection?.()?.toString());
}

function formatBytes(bytes) {
  if (!bytes && bytes !== 0) return '';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  return `${(bytes / 1024 / 1024 / 1024).toFixed(1)} GB`;
}

const TOAST_MS = { ok: 4000, err: 7000 };
const TOAST_MAX = 3;

function toast(message, kind = 'ok') {
  const host = $('toasts');
  const isError = kind === 'err';
  const el = document.createElement('div');
  el.className = `toast ${isError ? 'err' : ''}`;
  // Errors interrupt; everything else waits its turn.
  el.setAttribute('role', isError ? 'alert' : 'status');
  el.innerHTML = `${icon(isError ? 'alert' : 'check')}<span>${escapeHtml(message)}</span>`;
  host.appendChild(el);

  // At most three on screen: the oldest leaves first.
  while (host.children.length > TOAST_MAX) host.firstElementChild.remove();

  let timer = null;
  const leave = () => {
    el.classList.add('is-leaving');
    el.addEventListener('animationend', () => el.remove(), { once: true });
    // Reduced motion ends the animation at once; this is the safety net.
    setTimeout(() => el.remove(), 400);
  };
  const arm = () => { timer = setTimeout(leave, TOAST_MS[isError ? 'err' : 'ok']); };
  // Hovering a toast keeps it, so it can be read.
  el.addEventListener('mouseenter', () => clearTimeout(timer));
  el.addEventListener('mouseleave', arm);
  arm();
}

/* ---------- Theme ---------- */

function initTheme() {
  const media = window.matchMedia('(prefers-color-scheme: dark)');

  // `choice` is what the user picked: light, dark, or system (nothing saved).
  const apply = () => {
    const choice = localStorage.getItem(THEME_KEY) || 'system';
    const resolved = choice === 'system' ? (media.matches ? 'dark' : 'light') : choice;
    document.documentElement.dataset.theme = resolved;
    document.querySelectorAll('[data-theme-choice]').forEach((btn) => {
      const on = btn.dataset.themeChoice === choice;
      btn.classList.toggle('is-active', on);
      btn.setAttribute('aria-checked', String(on));
    });
  };
  const choose = (choice) => {
    if (choice === 'system') localStorage.removeItem(THEME_KEY);
    else localStorage.setItem(THEME_KEY, choice);
    apply();
  };

  apply();
  media.addEventListener?.('change', apply);

  $('theme-toggle').addEventListener('click', () => {
    choose(document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark');
  });
  document.querySelectorAll('[data-theme-choice]').forEach((btn) => {
    btn.addEventListener('click', () => choose(btn.dataset.themeChoice));
  });
}

/* ---------- Appearance: glass, transparency, motion ---------- */

function applyClarity(value) {
  const clarity = Math.max(0, Math.min(100, Number(value)));
  const safe = Number.isFinite(clarity) ? clarity : CLARITY_DEFAULT;
  document.documentElement.style.setProperty('--clarity', String(safe / 100));
  const range = $('settings-clarity');
  if (range) {
    range.value = String(safe);
    range.style.setProperty('--fill', `${safe}%`);
  }
  const label = $('settings-clarity-value');
  if (label) label.textContent = `${safe}%`;
}

function initAppearance() {
  const root = document.documentElement;
  const saved = localStorage.getItem(CLARITY_KEY);
  applyClarity(saved === null ? CLARITY_DEFAULT : saved);

  const range = $('settings-clarity');
  range?.addEventListener('input', () => applyClarity(range.value));
  range?.addEventListener('change', () => localStorage.setItem(CLARITY_KEY, range.value));

  const transparency = $('settings-reduce-transparency');
  const applyTransparency = (reduce) => {
    if (reduce) root.dataset.transparency = 'reduce';
    else delete root.dataset.transparency;
    if (transparency) transparency.checked = reduce;
  };
  applyTransparency(localStorage.getItem(TRANSPARENCY_KEY) === 'reduce');
  transparency?.addEventListener('change', () => {
    if (transparency.checked) localStorage.setItem(TRANSPARENCY_KEY, 'reduce');
    else localStorage.removeItem(TRANSPARENCY_KEY);
    applyTransparency(transparency.checked);
  });

  const motion = $('settings-reduce-motion');
  const applyMotion = (reduce) => {
    if (reduce) root.dataset.motion = 'reduce';
    else delete root.dataset.motion;
    if (motion) motion.checked = reduce;
  };
  applyMotion(localStorage.getItem(MOTION_KEY) === 'reduce');
  motion?.addEventListener('change', () => {
    if (motion.checked) localStorage.setItem(MOTION_KEY, 'reduce');
    else localStorage.removeItem(MOTION_KEY);
    applyMotion(motion.checked);
  });

  const castConfirm = $('settings-cast-confirm');
  if (castConfirm) {
    castConfirm.checked = localStorage.getItem(CAST_CONFIRM_KEY) !== 'off';
    castConfirm.addEventListener('change', () => {
      if (castConfirm.checked) localStorage.removeItem(CAST_CONFIRM_KEY);
      else localStorage.setItem(CAST_CONFIRM_KEY, 'off');
    });
  }
}

/* ---------- This computer ---------- */

/** Two letters for the avatar, taken from the computer's name. */
function pcInitials(name) {
  const words = String(name || '')
    .replace(/([a-z])([A-Z])/g, '$1 $2')
    .split(/[^A-Za-z0-9]+/)
    .filter(Boolean);
  if (words.length === 0) return 'PC';
  if (words.length === 1) return words[0].slice(0, 2).toUpperCase();
  return (words[0][0] + words[1][0]).toUpperCase();
}

const AVATAR_KEY = 'aplus-avatar';
const AVATAR_SIZE = 256;

/** Shows the saved picture (or the initials) on every avatar. */
function renderAvatars() {
  const picture = localStorage.getItem(AVATAR_KEY);
  const initials = pcInitials(state.pcName);
  for (const id of ['pc-avatar', 'settings-avatar']) {
    const el = $(id);
    if (!el) continue;
    const img = el.querySelector('.avatar-img');
    const letters = el.querySelector('.avatar-initials');
    if (letters) letters.textContent = picture ? '' : initials;
    if (img) {
      if (picture) img.src = picture;
      else img.removeAttribute('src');
      img.hidden = !picture;
    }
  }
  const remove = $('btn-avatar-remove');
  if (remove) remove.hidden = !picture;
}

/** Crops the picked image to a centred square and shrinks it for storage. */
function imageFileToAvatar(file) {
  // Read as a data: URL - the page's content policy allows data: images
  // but not blob: ones.
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = () => reject(new Error('unreadable'));
    reader.onload = () => {
      const img = new Image();
      img.onload = () => {
        const side = Math.min(img.naturalWidth, img.naturalHeight);
        const canvas = document.createElement('canvas');
        canvas.width = AVATAR_SIZE;
        canvas.height = AVATAR_SIZE;
        canvas.getContext('2d').drawImage(
          img,
          (img.naturalWidth - side) / 2, (img.naturalHeight - side) / 2, side, side,
          0, 0, AVATAR_SIZE, AVATAR_SIZE,
        );
        resolve(canvas.toDataURL('image/jpeg', 0.88));
      };
      img.onerror = () => reject(new Error('unreadable'));
      img.src = reader.result;
    };
    reader.readAsDataURL(file);
  });
}

function initAvatar() {
  const input = $('avatar-input');
  const pick = () => input && input.click();
  $('pc-avatar')?.addEventListener('click', pick);
  $('btn-avatar-upload')?.addEventListener('click', pick);

  input?.addEventListener('change', async () => {
    const file = input.files && input.files[0];
    input.value = '';
    if (!file) return;
    if (!file.type.startsWith('image/')) {
      toast('Choose an image file', 'err');
      return;
    }
    if (file.size > 15 * 1024 * 1024) {
      toast('That image is too large (15 MB max)', 'err');
      return;
    }
    try {
      localStorage.setItem(AVATAR_KEY, await imageFileToAvatar(file));
      renderAvatars();
      toast('Profile picture updated');
    } catch {
      toast('Could not read that image', 'err');
    }
  });

  $('btn-avatar-remove')?.addEventListener('click', () => {
    localStorage.removeItem(AVATAR_KEY);
    renderAvatars();
    toast('Profile picture removed');
  });

  renderAvatars();
}

function applyPcName(name) {
  if (!name) return;
  state.pcName = name;
  const nameEl = $('pc-name');
  if (nameEl) {
    nameEl.textContent = name;
    nameEl.title = name;
  }
  renderAvatars();
  for (const id of ['settings-pc-name', 'settings-pc-name-2']) {
    const el = $(id);
    if (el) el.textContent = name;
  }
}

/* ---------- Views ---------- */

const VIEWS = ['connect', 'files', 'history', 'clipboard', 'notifications', 'settings'];
// Older names still arrive from the tray (`route:navigate`) and old links.
const VIEW_ALIASES = { empty: 'connect', device: 'connect' };

/** True when either connection to the phone is live. */
function hasPhone() {
  return Boolean(state.device) || Boolean(state.companionConnected);
}

function showView(requested) {
  const name = VIEW_ALIASES[requested] || requested;
  if (!VIEWS.includes(name)) return;

  // No forced redirect any more (DESIGN.md §9): every page is reachable and
  // shows its own offline state with a "Connect a phone" action.
  state.view = name;

  for (const view of VIEWS) {
    const el = $(`view-${view}`);
    if (el) el.hidden = view !== name;
  }

  // History is a sub-page of Files, so Files stays lit while it is open.
  const navFor = name === 'history' ? 'files' : name;
  document.querySelectorAll('.nav-item[data-view]').forEach((el) => {
    const on = el.dataset.view === navFor;
    el.classList.toggle('active', on);
    if (on) el.setAttribute('aria-current', 'page');
    else el.removeAttribute('aria-current');
  });

  const main = $('main');
  if (main) main.scrollTop = 0;

  if (name === 'notifications') {
    state.unreadNotifications = 0;
    renderBadges();
    startNotifClock();
    loadNotificationHistory();
  }
  if (name === 'clipboard') {
    loadClipboardHistory();
    refreshClipboardStatus();
  }
  if (name === 'connect') {
    renderDevice();
  }
  if (name === 'files') {
    updateFilesTransport();
    if (state.device && state.target === 'adb') refreshBrowser();
  }
  if (name === 'history') {
    renderHistory();
  }
  if (name === 'settings') {
    refreshClipboardStatus();
    refreshCompanionInfo();
  }
}

/**
 * The Files view follows the connection the user picked.
 *
 * The debugging connection has its own browser, drop zone and folder actions.
 * The companion channel has the phone's shared folder instead - listed
 * read-only, one file pulled at a time - and the same transfer list for
 * whatever arrives.
 */
function updateFilesTransport() {
  const companionOnly = state.target === 'companion' && Boolean(state.companion);
  const overAdb = state.target === 'adb' && Boolean(state.device);
  const offline = !companionOnly && !overAdb;

  const offlineCard = $('files-offline');
  if (offlineCard) offlineCard.hidden = !offline;
  const sendButton = $('btn-send-file');
  if (sendButton) sendButton.disabled = offline;

  const phoneLabel = $('files-phone-label');
  if (phoneLabel) {
    phoneLabel.textContent = offline
      ? 'no phone yet'
      : (companionOnly ? state.companion.deviceName : state.device.model) || 'your phone';
  }
  const destLabel = $('files-dest-label');
  if (destLabel) destLabel.textContent = companionOnly ? 'Downloads' : '/sdcard/Download';

  const dropzone = $('dropzone');
  if (dropzone) {
    dropzone.hidden = offline;
    const name = phoneLabel ? phoneLabel.textContent : 'the phone';
    dropzone.setAttribute('aria-label', `Choose or drop files to send to ${name}`);
  }
  const adbBrowser = $('adb-browser');
  if (adbBrowser) adbBrowser.hidden = !overAdb;

  const phoneBrowser = $('phone-browser');
  if (phoneBrowser) phoneBrowser.hidden = !companionOnly;

  // The action bar counts the connection the user is looking at, so it must
  // follow a transport switch rather than stay on the other side's total.
  renderFileSelectionActions();

  // Open the root the first time the phone is shown, not on every render: an
  // empty folder would otherwise be re-requested forever.
  if (companionOnly && !state.phone.loaded && !state.phone.loading) {
    loadPhoneDirectory('');
  }
}

/** Loading is never a sentence on a blank panel: skeleton rows (§8.10). */
function skeletonRows(count = 4) {
  return Array.from({ length: count }, (_, i) => `
    <div class="skeleton-row" aria-hidden="true">
      <span class="skeleton icon-tile"></span>
      <span class="skeleton line${i % 2 ? ' short' : ''}"></span>
    </div>`).join('') + '<span class="sr-only" role="status">Loading…</span>';
}

/** The empty / error block used inside lists. */
function emptyBlock(iconName, title, sub, action) {
  return `
    <div class="empty-block">
      <div class="empty-block-icon">${icon(iconName)}</div>
      <div class="empty-block-title">${escapeHtml(title)}</div>
      ${sub ? `<div class="empty-block-sub">${escapeHtml(sub)}</div>` : ''}
      ${action ? `<button class="btn-primary" data-view="${escapeHtml(action.view)}">${escapeHtml(action.label)}</button>` : ''}
    </div>`;
}

/** Lists one folder of the phone's storage. `path` is relative to its root. */
function loadPhoneDirectory(path) {
  state.phone.path = path;
  state.phone.loading = true;
  state.phone.error = null;
  renderPhoneBrowser();

  if (!window.api.listPhoneFiles) {
    state.phone.loading = false;
    state.phone.error = 'This build cannot browse the phone.';
    renderPhoneBrowser();
    return;
  }

  const seq = ++state.phone.seq;
  window.api
    .listPhoneFiles(path)
    .then((result) => {
      if (seq !== state.phone.seq) return;
      state.phone.loading = false;
      if (!result || !result.success) {
        state.phone.entries = [];
        state.phone.error = (result && result.error) || 'The phone did not answer.';
      } else {
        state.phone.entries = result.entries || [];
        state.phone.loaded = true;
      }
      renderPhoneBrowser();
    })
    .catch((e) => {
      if (seq !== state.phone.seq) return;
      state.phone.loading = false;
      state.phone.entries = [];
      state.phone.error = e.message;
      renderPhoneBrowser();
    });
}

/** The folder a phone path lives in, or '' when it is already at the root. */
function phoneParentOf(path) {
  const cut = String(path || '').lastIndexOf('/');
  return cut === -1 ? '' : path.slice(0, cut);
}

/**
 * Searches every folder the phone has granted.
 *
 * The phone walks once and filters in memory, so a keystroke is cheap; the
 * sequence number drops an answer that arrives after a newer query.
 *
 * `fresh` is the Refresh button's word for "look again now": the phone walks
 * its storage before answering, so a file saved a moment ago is found. Typing
 * never passes it - a keystroke must not pay for a walk.
 */
function searchPhoneFiles(query, options = {}) {
  const needle = String(query || '').trim();
  const fresh = options.fresh === true;
  state.phone.query = query;
  if (!needle) {
    state.phone.results = null;
    state.phone.searching = false;
    renderPhoneBrowser();
    return;
  }

  if (!window.api.searchPhoneFiles) {
    state.phone.results = [];
    state.phone.error = 'This build cannot search the phone.';
    renderPhoneBrowser();
    return;
  }

  state.phone.searching = true;
  state.phone.error = null;
  renderPhoneBrowser();

  const seq = ++state.phone.searchSeq;
  window.api
    .searchPhoneFiles(needle, fresh)
    .then((result) => {
      if (seq !== state.phone.searchSeq) return;
      state.phone.searching = false;
      if (!result || !result.success) {
        state.phone.results = [];
        state.phone.error = (result && result.error) || 'The phone did not answer.';
      } else {
        state.phone.results = result.entries || [];
      }
      renderPhoneBrowser();
    })
    .catch((e) => {
      if (seq !== state.phone.searchSeq) return;
      state.phone.searching = false;
      state.phone.results = [];
      state.phone.error = e.message;
      renderPhoneBrowser();
    });
}

/** Debounces the search box: one request per pause, not per keystroke. */
let phoneSearchTimer = null;
function schedulePhoneSearch(query) {
  if (phoneSearchTimer) clearTimeout(phoneSearchTimer);
  phoneSearchTimer = setTimeout(() => {
    phoneSearchTimer = null;
    searchPhoneFiles(query);
  }, 220);
}

function renderPhoneBrowser() {
  const list = $('phone-browser-list');
  if (!list) return;

  const { path, entries, results, loading, searching, query, error } = state.phone;
  const searching0 = results !== null || Boolean(query.trim());
  const crumb = $('phone-path');
  if (crumb) {
    crumb.textContent = searching0 ? `Search: ${query.trim()}` : `/${path}`;
  }
  const up = $('phone-up');
  if (up) up.disabled = searching0 || path === '';
  const clear = $('phone-search-clear');
  if (clear) clear.hidden = !query;

  if (loading || searching) {
    list.innerHTML = skeletonRows();
    return;
  }
  if (error) {
    list.innerHTML = `<div class="list-empty">${escapeHtml(error)}</div>`;
    return;
  }

  if (searching0) {
    if (!results || !results.length) {
      list.innerHTML = `<div class="list-empty">Nothing on the phone matches “${escapeHtml(
        query.trim(),
      )}”.</div>`;
      return;
    }
    list.innerHTML = results
      .map((entry) => {
        const where = phoneParentOf(entry.id) || '/';
        return `
      <div class="browse-row ${entry.isDir ? 'dir' : 'file'}" data-id="${escapeHtml(entry.id)}" data-dir="${entry.isDir ? '1' : '0'}">
        ${entry.isDir ? '' : fileSelectionControl(entry.id, entry.name, 'companion')}
        ${icon(entry.isDir ? 'folder' : 'file')}
        <div class="browse-name" title="${escapeHtml(entry.id)}">${escapeHtml(entry.name)}</div>
        <div class="browse-meta">${entry.isDir ? 'Folder' : formatBytes(entry.size)} · ${escapeHtml(where)}</div>
        ${
          entry.isDir
            ? ''
            : `<button class="icon-btn sm" data-open="${escapeHtml(entry.id)}" title="Open on this PC">
                 <svg class="icon"><use href="#i-eye"/></svg>
               </button>
               <button class="icon-btn sm" data-pull="${escapeHtml(entry.id)}" title="Download">
                 <svg class="icon"><use href="#i-download"/></svg>
               </button>`
        }
      </div>`;
      })
      .join('');
    return;
  }

  if (!entries.length) {
    list.innerHTML = path
      ? '<div class="list-empty">This folder is empty</div>'
      : emptyBlock(
          'folder',
          'Nothing to browse yet',
          'On the phone, open A+ Connect → Files and turn on “Let the computer browse this phone”.',
        );
    return;
  }

  list.innerHTML = entries
    .map(
      (entry) => `
      <div class="browse-row ${entry.isDir ? 'dir' : 'file'}" data-id="${escapeHtml(entry.id)}" data-dir="${entry.isDir ? '1' : '0'}">
        ${entry.isDir ? '' : fileSelectionControl(entry.id, entry.name, 'companion')}
        ${icon(entry.isDir ? 'folder' : 'file')}
        <div class="browse-name" title="${escapeHtml(entry.name)}">${escapeHtml(entry.name)}</div>
        <div class="browse-meta">${entry.isDir ? 'Folder' : formatBytes(entry.size)}</div>
        ${
          entry.isDir
            ? ''
            : `<button class="icon-btn sm" data-open="${escapeHtml(entry.id)}" title="Open on this PC">
                 <svg class="icon"><use href="#i-eye"/></svg>
               </button>
               <button class="icon-btn sm" data-pull="${escapeHtml(entry.id)}" title="Download">
                 <svg class="icon"><use href="#i-download"/></svg>
               </button>`
        }
      </div>`,
    )
    .join('');
}

/**
 * Downloads one file from the phone, over the companion channel.
 *
 * The phone sends it as the ordinary file-start/chunk/done transfer, so the
 * progress row in the transfer list is the same one a file sent from the
 * desktop shows. The request itself only says whether the phone was
 * reachable - the file itself arrives afterwards.
 *
 * `path` is the id the phone handed out with the listing: its path relative
 * to the phone's storage root.
 */
async function pullPhoneFile(path, name) {
  if (!path) return;
  if (!window.api.downloadPhoneFile) {
    toast('Downloading from the phone is not available on this transport', 'err');
    return;
  }
  try {
    const result = await window.api.downloadPhoneFile(path);
    if (!result || result.success === false) {
      toast(`Could not ask the phone for ${name || 'the file'}: ${result?.error || 'no answer'}`, 'err');
      return;
    }
    toast(`The phone is sending ${name || 'the file'}`);
  } catch (err) {
    toast(`Download failed: ${err.message}`, 'err');
  }
}

/**
 * Opens one file from the phone on this PC, without keeping it.
 *
 * The file is fetched over the same transfer a download uses, but into a cache
 * folder, and the desktop opens it with whatever handles that kind of file.
 * Nothing lands in Downloads: looking at a file is not asking to keep it.
 */
async function previewPhoneFile(path, name) {
  if (!path) return;
  if (!window.api.openPhoneFile) {
    toast('Opening from the phone is not available on this transport', 'err');
    return;
  }
  try {
    const result = await window.api.openPhoneFile(path);
    if (!result || result.success === false) {
      toast(`Could not ask the phone for ${name || 'the file'}: ${result?.error || 'no answer'}`, 'err');
      return;
    }
    toast(`Opening ${name || 'the file'}`);
  } catch (err) {
    toast(`Could not open ${name || 'the file'}: ${err.message}`, 'err');
  }
}

/** Opens a folder of the phone. `id` is its path relative to the phone's root. */
function openPhoneFolder(id) {
  if (!id) return;
  clearPhoneSearch();
  loadPhoneDirectory(id);
}

/** Goes back up one folder, unless a search is showing. */
function phoneUp() {
  if (state.phone.results !== null || state.phone.query.trim()) return;
  if (state.phone.path === '') return;
  loadPhoneDirectory(phoneParentOf(state.phone.path));
}

/** Empties the search box and goes back to the folder listing. */
function clearPhoneSearch() {
  const box = $('phone-search');
  if (box) box.value = '';
  state.phone.query = '';
  state.phone.results = null;
  state.phone.searching = false;
}

/* ---------- Device ---------- */

function renderDevice() {
  const device = state.device;
  const companion = state.companion;
  const sub = $('profile-sub');

  // Both transports can be live at once: the phone over the debugging
  // connection, and the same phone over the companion channel.
  // The selection follows what is actually connected: no debugging device
  // means the companion is the one to work with.
  if (!device && companion) state.target = 'companion';
  if (!companion && device) state.target = 'adb';

  const connected = Boolean(device || companion);
  const onCompanion = state.target === 'companion' && Boolean(companion);
  const shown = onCompanion ? companion : device;

  // The connection picker only appears when there is a choice to make.
  const picker = $('transport-picker');
  if (picker) {
    picker.hidden = !(device && companion);
    picker.querySelectorAll('[data-target]').forEach((btn) => {
      const on = btn.dataset.target === state.target;
      btn.classList.toggle('is-active', on);
      btn.setAttribute('aria-checked', String(on));
    });
  }

  for (const id of ['telemetry', 'mirror-card']) {
    const el = $(id);
    if (el) el.hidden = !connected;
  }
  const hero = $('connect-empty');
  if (hero) hero.hidden = connected;
  const disconnect = $('btn-disconnect');
  if (disconnect) disconnect.hidden = !connected;

  const pill = $('connect-pill');
  const dot = $('connect-dot');
  const setStatus = (label, kind) => {
    if (pill) {
      pill.textContent = label;
      pill.className = `pill ${kind}`;
    }
    if (dot) {
      dot.className = `status-dot ${kind === 'success' ? 'connected' : kind === 'warn' ? 'listening' : ''}`;
      dot.setAttribute('aria-label', label);
      dot.title = label;
    }
  };

  if (!connected) {
    if (sub) sub.textContent = 'Not connected';
    $('device-title').textContent = 'No phone connected';
    $('device-sub').textContent = 'Pair a phone to mirror it, move files and share the clipboard.';
    const listening = state.companionInfo && state.companionInfo.status === 'listening';
    setStatus(listening ? 'Waiting for phone' : 'Not connected', listening ? 'warn' : 'disconnected');
    renderCast();
    const note = $('companion-capability-note');
    if (note) note.hidden = true;
    return;
  }

  if (sub) sub.textContent = `Connected to ${shown.model || shown.deviceName || 'phone'}`;
  setStatus('Connected', 'success');

  $('device-title').textContent = onCompanion
    ? (companion.deviceName || 'Phone')
    : (device.model || 'Phone');
  const subLine = $('device-sub');
  if (onCompanion) {
    const where = companion.ip ? `${companion.ip}${state.companionInfo?.port ? `:${state.companionInfo.port}` : ''}` : '';
    subLine.innerHTML = `Phone connection · Encrypted${where ? ` · <span class="mono">${escapeHtml(where)}</span>` : ''}`;
  } else {
    subLine.innerHTML = `${device.isWifi ? 'Wireless debugging' : 'USB debugging'}${
      device.id ? ` · <span class="mono">${escapeHtml(device.id)}</span>` : ''
    }`;
  }

  // Say plainly which features this transport can carry.
  const note = $('companion-capability-note');
  if (note) {
    note.hidden = !companion;
    const text = $('companion-capability-text');
    if (text) {
      text.textContent = onCompanion
        ? 'Clipboard, files, notifications and view-only mirroring work over this connection. ' +
          'Touch control and write access to the phone need wireless debugging.'
        : 'The companion connection is live too - pick it above to work with the phone over the encrypted channel.';
    }
  }

  // The phone details: over DEBUG the ADB transport collects them; over the
  // companion channel the phone sends them itself (device-info). Either way
  // the cards fill in, instead of the dashes that used to sit there while only
  // the encrypted channel was connected.
  const cinfo = (state.companionInfo && state.companionInfo.deviceInfo) || {};
  const battery = onCompanion ? {} : (device.battery || {});
  const batteryLevel = onCompanion ? cinfo.batteryLevel : battery.level;
  $('t-battery').textContent =
    batteryLevel != null && batteryLevel !== '' ? `${batteryLevel}%` : '—';
  $('t-android').textContent = onCompanion
    ? (cinfo.androidVersion || '—')
    : (device.androidVersion || '—');
  $('t-storage').textContent = onCompanion
    ? (cinfo.storageFree || '—')
    : (device.storage?.free || '—');
  $('t-rom').textContent = onCompanion
    ? (cinfo.colorOsVersion || '—')
    : (device.colorOsVersion || '—');

  renderCast();
}

/**
 * Chooses which connection the app acts on.
 *
 * The same phone can be reachable twice at once: over the debugging
 * connection and over the encrypted companion channel. Each carries different
 * things, so the user picks which one they are working with.
 */
function selectTarget(target) {
  if (state.target === target) return;
  state.target = target;

  renderDevice();
  updateFilesTransport();

  // The panels that depend on the connection reload for the new one. The
  // phone's folder is opened by updateFilesTransport itself.
  if (state.view === 'files' && state.target === 'adb') refreshBrowser();
  if (state.view === 'clipboard') {
    loadClipboardHistory();
    refreshClipboardStatus();
  }
  if (state.view === 'notifications') loadNotificationHistory();
}

function setDevice(device) {
  const isSame = state.device && device && state.device.id === device.id;
  state.device = device;

  if (!device) {
    state.casting = false;
    renderCast();
    renderDevice();
    updateFilesTransport();
    return;
  }

  renderDevice();
  updateFilesTransport();
  if (!isSame && state.view === 'files' && state.target === 'adb') refreshBrowser();
}

function renderCast() {
  const start = $('btn-cast');
  const stop = $('btn-cast-stop');
  const label = $('cast-state');
  const canvas = $('cast-canvas');
  const companion = state.target === 'companion' && Boolean(state.companion);

  const mirroring = state.mirror.state === 'starting' || state.mirror.state === 'streaming';
  // The companion mirror draws in this window; scrcpy opens its own, so the
  // canvas only appears for the former.
  const showCanvas = companion && mirroring;
  if (canvas) canvas.hidden = !showCanvas;

  start.hidden = state.casting || (companion && mirroring);
  stop.hidden = !(state.casting || (companion && mirroring));

  if (companion && state.mirror.state !== 'idle') {
    label.textContent = describeMirror(state.mirror);
    return;
  }
  if (companion && state.mirror.reason) {
    label.textContent = describeMirror(state.mirror);
    return;
  }
  label.textContent = state.casting ? 'Mirroring in its own window' : 'Not mirroring';
}

/**
 * The mirroring state in one line, for the card.
 *
 * Every ending has a reason and every reason is shown: "it stopped" with no
 * explanation is the sort of thing a user cannot act on.
 */
function describeMirror(mirror) {
  switch (mirror.state) {
    case 'starting':
      return 'Waiting for the phone to allow capture...';
    case 'streaming':
      return `${mirror.width}x${mirror.height} · ${mirror.encoder || 'encoder'} · ${mirror.frames} frames`;
    default:
      break;
  }
  switch (mirror.reason) {
    case 'consent-refused':
      return 'Stopped: capture was not allowed on the phone.';
    case 'no-encoder':
      return 'Stopped: the phone has no usable H.264 encoder.';
    case 'video-connect-failed':
      return 'Stopped: the desktop could not open the video port.';
    case 'not-trusted':
      return 'Stopped: the phone could not be verified.';
    case 'projection-stopped':
      return 'Stopped: screen sharing was ended on the phone.';
    case 'screen-slept':
      return 'Stopped: the phone screen went to sleep.';
    case 'link-lost':
      return 'Stopped: the connection was lost.';
    case 'disconnected':
      return 'Stopped: the phone was disconnected.';
    case 'already-mirroring':
      return 'Already mirroring.';
    case 'no-control-link':
      return 'The phone is not connected.';
    case 'requested':
      return 'Stopped.';
    default:
      return mirror.reason ? `Stopped: ${mirror.reason}` : 'Not mirroring';
  }
}

/* ---------- Files ---------- */

function transferRow(t) {
  const active = t.status === 'active';
  // The companion path reports bytes as they arrive; ADB pushes do not, so
  // those keep the indeterminate bar.
  const total = Number(t.total || t.size) || 0;
  const received = Number(t.received) || 0;
  const pct = active && total > 0 && received > 0 ? Math.min(100, Math.round((received / total) * 100)) : null;
  const toPhone = t.type !== 'pull';
  const meta =
    t.status === 'active'
      ? pct !== null
        ? `${formatBytes(received)} of ${formatBytes(total)} · ${pct}%`
        : `${formatBytes(t.size)} · ${toPhone ? 'sending…' : 'receiving…'}`
      : `Didn't ${toPhone ? 'send' : 'arrive'}${t.error ? ` · ${t.error}` : ''}`;
  const canRetry = t.status === 'failed' && toPhone && Boolean(t.localFilePath);

  return `
    <div class="transfer ${escapeHtml(t.status || '')}" data-id="${escapeHtml(t.id)}">
      <div class="transfer-icon">${icon(t.status === 'failed' ? 'alert' : toPhone ? 'upload' : 'download')}</div>
      <div class="transfer-body">
        <div class="transfer-name">${escapeHtml(t.filename || 'File')}</div>
        <div class="transfer-meta">${escapeHtml(meta)}</div>
        ${active
          ? pct !== null
            ? `<div class="bar is-determinate" role="progressbar" aria-label="${escapeHtml(t.filename)}" aria-valuemin="0" aria-valuemax="100" aria-valuenow="${pct}"><span style="width:${pct}%"></span></div>`
            : `<div class="bar" role="progressbar" aria-label="${escapeHtml(t.filename)}"><span></span></div>`
          : ''}
      </div>
      ${active ? `<button class="link-btn" data-cancel="${escapeHtml(t.id)}">Cancel</button>` : ''}
      ${canRetry ? `<button class="link-btn" data-retry="${escapeHtml(t.id)}">Retry</button>` : ''}
      ${t.status === 'failed' ? `<button class="icon-btn sm" data-dismiss="${escapeHtml(t.id)}" aria-label="Dismiss" title="Dismiss">${icon('close')}</button>` : ''}
    </div>`;
}

/** The Files page lists only live transfers; finished ones are in History. */
function renderTransfers() {
  const list = $('transfer-list');
  const items = [...state.transfers.values()].filter(isLiveTransfer).reverse();
  const activeCount = items.filter((t) => t.status === 'active').length;

  const section = $('transfers-section');
  if (section) section.hidden = items.length === 0;
  if (list) list.innerHTML = items.map(transferRow).join('');

  $('btn-cancel-all').hidden = activeCount === 0;

  const badge = $('badge-files');
  badge.hidden = activeCount === 0;
  badge.textContent = String(activeCount);
}

function upsertTransfer(patch) {
  const existing = state.transfers.get(patch.id) || { id: patch.id };
  const next = { ...existing, ...patch };
  state.transfers.set(patch.id, next);

  // A finished transfer moves to the history, and off the Files page.
  if (next.status === 'done' || next.status === 'cancelled') {
    if (next.status === 'done') recordHistory(next);
    state.transfers.delete(patch.id);
  }
  renderTransfers();
}

/* ---------- History ---------- */

function loadHistory() {
  state.history.list = parseHistory(localStorage.getItem(HISTORY_KEY));
}

function saveHistory() {
  try {
    localStorage.setItem(HISTORY_KEY, JSON.stringify(state.history.list));
  } catch {
    /* storage full: the list still works for this session */
  }
}

function recordHistory(transfer) {
  const entry = historyEntryFromTransfer(transfer, Date.now());
  if (!entry) return;
  state.history.list = addHistoryEntry(state.history.list, entry);
  saveHistory();
  if (state.view === 'history') renderHistory();
}

function historyRow(e) {
  const sent = e.direction === 'sent';
  const bits = [];
  if (e.size) bits.push(formatBytes(e.size));
  bits.push(relativeTime(e.at, Date.now()));
  if (e.where) bits.push(sent ? `to ${e.where}` : e.where);
  const canOpen = Boolean(e.localPath);
  return `
    <div class="hist ${sent ? 'sent' : 'received'}" data-hist="${escapeHtml(e.id)}" ${canOpen ? 'role="button" tabindex="0"' : ''} title="${escapeHtml(e.localPath || e.name)}">
      <div class="hist-icon">${icon(sent ? 'upload' : 'download')}</div>
      <div class="hist-body">
        <div class="hist-name">${escapeHtml(e.name)}</div>
        <div class="hist-meta">${escapeHtml(bits.join(' · '))}</div>
      </div>
      <div class="hist-actions">
        ${canOpen ? `<button class="icon-btn sm" data-hist-open="${escapeHtml(e.id)}" aria-label="Open ${escapeHtml(e.name)}" title="Open">${icon('eye')}</button>` : ''}
        <button class="icon-btn sm" data-hist-remove="${escapeHtml(e.id)}" aria-label="Remove ${escapeHtml(e.name)} from history" title="Remove from history">${icon('close')}</button>
      </div>
    </div>`;
}

function renderHistory() {
  const counts = historyCounts(state.history.list);
  $('history-count-sent').textContent = String(counts.sent);
  $('history-count-received').textContent = String(counts.received);
  document.querySelectorAll('#history-tabs [data-history]').forEach((btn) => {
    const on = btn.dataset.history === state.history.tab;
    btn.classList.toggle('is-active', on);
    btn.setAttribute('aria-selected', String(on));
  });
  $('btn-history-clear').hidden = state.history.list.length === 0;

  const rows = historyFor(state.history.list, state.history.tab);
  const list = $('history-list');
  if (!rows.length) {
    list.innerHTML = state.history.tab === 'sent'
      ? emptyBlock('upload', 'Nothing sent yet', 'Files this computer sends to the phone show up here.')
      : emptyBlock('download', 'Nothing received yet', 'Files the phone sends to this computer show up here.');
    return;
  }
  list.innerHTML = rows.map(historyRow).join('');
}

async function openHistoryEntry(id) {
  const entry = state.history.list.find((e) => e.id === id);
  if (!entry || !entry.localPath) return;
  try {
    const result = await window.api.openLocalFile(entry.localPath);
    if (result && result.success === false) {
      toast(`Could not open ${entry.name}: ${result.message || result.error || 'it may have been moved or deleted'}`, 'err');
    }
  } catch (err) {
    toast(`Could not open ${entry.name}: ${err.message}`, 'err');
  }
}

function initHistory() {
  loadHistory();

  document.querySelectorAll('#history-tabs [data-history]').forEach((btn) => {
    btn.addEventListener('click', () => {
      state.history.tab = btn.dataset.history;
      renderHistory();
    });
  });

  $('btn-history-folder').addEventListener('click', async () => {
    const result = await window.api.openCompanionDestinationDir?.();
    if (result && result.success === false) toast(result.error || 'Could not open the folder', 'err');
  });

  $('btn-history-clear').addEventListener('click', async () => {
    const confirmed = await window.api.confirmAction({
      message: 'Clear the file history?',
      detail: 'Only the list is cleared. The files stay where they are on both devices.',
      confirmLabel: 'Clear',
    });
    if (!confirmed) return;
    state.history.list = [];
    saveHistory();
    renderHistory();
    toast('History cleared');
  });

  const list = $('history-list');
  list.addEventListener('click', (e) => {
    const remove = e.target.closest('[data-hist-remove]');
    if (remove) {
      state.history.list = state.history.list.filter((x) => x.id !== remove.dataset.histRemove);
      saveHistory();
      renderHistory();
      return;
    }
    const row = e.target.closest('[data-hist]');
    if (row && !hasSelectedText()) openHistoryEntry(row.dataset.hist);
  });
  list.addEventListener('keydown', (e) => {
    if (e.key !== 'Enter' && e.key !== ' ') return;
    const row = e.target.closest('[data-hist]');
    if (!row || e.target !== row) return;
    e.preventDefault();
    openHistoryEntry(row.dataset.hist);
  });
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

async function sendCompanionFiles(paths) {
  if (!state.companion || !Array.isArray(paths) || paths.length === 0) return;
  if (!window.api.sendFileToPhone) {
    toast('Sending files over Companion is not available', 'err');
    return;
  }

  for (const filePath of paths) {
    try {
      const result = await window.api.sendFileToPhone(filePath);
      if (!result || !result.success) {
        toast(`Could not send ${filePath.split(/[\\/]/).pop()} to the phone`, 'err');
        continue;
      }
      toast(`Sending ${filePath.split(/[\\/]/).pop()} to the phone`);
    } catch (err) {
      toast(`Send failed: ${err.message}`, 'err');
    }
  }
}

async function browseCompanionFiles() {
  if (!state.companion) {
    toast('Connect a phone first', 'err');
    return;
  }
  if (!window.api.pickAndSendFileToPhone) {
    toast('Sending files over Companion is not available', 'err');
    return;
  }

  try {
    const result = await window.api.pickAndSendFileToPhone();
    if (result?.canceled) return;
    if (result?.success) {
      const name = result.name || result.filePath?.split(/[\\/]/).pop() || 'file';
      toast(`Sending ${name} to the phone`);
    } else {
      toast('Could not send the file', 'err');
    }
  } catch (err) {
    toast(`Send failed: ${err.message}`, 'err');
  }
}

async function browseFilesForTarget() {
  if (state.target === 'companion' && state.companion) return browseCompanionFiles();
  return browseFiles();
}

async function sendDroppedFiles(paths) {
  if (state.target === 'companion' && state.companion) return sendCompanionFiles(paths);
  return sendFiles(paths);
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

function fileSelectionControl(path, name, mode = fileSelectionMode()) {
  const checked = isFileSelected(path, mode) ? 'checked' : '';
  return `<input class="file-select" type="checkbox" data-select-file="${escapeHtml(path)}" data-select-name="${escapeHtml(name)}" aria-label="Select ${escapeHtml(name)}" ${checked}>`;
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
      ${entry.isDir ? '' : fileSelectionControl(entry.path, entry.name, 'adb')}
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
    list.innerHTML = skeletonRows();
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
        ${entry.isDir ? '' : fileSelectionControl(entry.path, entry.name, 'adb')}
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
    parts.push(skeletonRows(3));
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
    list.innerHTML = skeletonRows();
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

/* Simple text prompt (Electron has no window.prompt). `multiline` swaps the
   single-line field for a text area, where Enter adds a line and Ctrl+Enter
   submits. */
function askText(title, placeholder, { okLabel = 'Create', multiline = false } = {}) {
  return new Promise((resolve) => {
    const field = $(multiline ? 'prompt-textarea' : 'prompt-input');
    $('prompt-input').hidden = multiline;
    $('prompt-textarea').hidden = !multiline;
    $('prompt-title').textContent = title;
    $('prompt-ok').textContent = okLabel;
    field.value = '';
    field.placeholder = placeholder || '';
    openModal('modal-prompt');
    field.focus();

    const finish = (value) => {
      $('prompt-ok').removeEventListener('click', onOk);
      field.removeEventListener('keydown', onKey);
      closeModal('modal-prompt');
      resolve(value);
    };
    const onOk = () => finish(multiline ? field.value || null : field.value.trim() || null);
    const onKey = (e) => {
      if (e.key === 'Enter' && (!multiline || e.ctrlKey || e.metaKey)) {
        e.preventDefault();
        onOk();
      }
      if (e.key === 'Escape') finish(null);
    };

    $('prompt-ok').addEventListener('click', onOk);
    field.addEventListener('keydown', onKey);
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

/**
 * The notifications, newest first, each saying when it arrived.
 *
 * The history is unshifted on arrival, but a phone that queued a notification
 * while the PC was away can deliver them slightly out of order, so the rows are
 * ordered by their own timestamp rather than by however they reached us.
 */
function renderNotifications() {
  const list = $('notifications-list');
  if (state.notifications.length === 0) {
    list.innerHTML = hasPhone()
      ? emptyBlock('bell', 'No notifications yet', "If this stays empty, turn on notification access on the phone app's Connect page.")
      : emptyBlock('bell', 'No phone connected', "Connect a phone to see its notifications here.", { view: 'connect', label: 'Connect a phone' });
    return;
  }

  const rows = newestFirst(state.notifications);

  // Persistent notifications are a different kind of thing from a message.
  // The phone keeps them up on purpose - a media card, a running download, a
  // timer - and a row that cannot be dismissed, mixed in above the message
  // the user came to read, makes the list harder to scan. So they get their
  // own section, and the newest first still applies inside each one.
  const ongoing = rows.filter((n) => n.ongoing);
  const recent = rows.filter((n) => !n.ongoing);

  const section = (title, items) =>
    items.length
      ? `<div class="list-section-title">${escapeHtml(title)}</div>${items.map(notificationRow).join('')}`
      : '';

  list.innerHTML = section('Ongoing', ongoing) + section('Recent', recent);
}

/** One notification row. Shared by both sections, so they cannot drift. */
function notificationRow(n) {
  const when = notifWhen(n);
  const exact = fullTime(n.timestamp);
  return `
      <div class="notif${n.ongoing ? ' ongoing' : ''}">
        <div class="notif-icon">${icon('bell')}</div>
        <div class="notif-body">
          <div class="notif-title">${escapeHtml(n.title || n.app)}</div>
          ${n.message ? `<div class="notif-message">${escapeHtml(n.message)}</div>` : ''}
          <div class="notif-meta"><span>${escapeHtml(n.app || n.pkg || '')}</span>${
            when ? `<span>·</span><span title="${escapeHtml(exact)}">${escapeHtml(when)}</span>` : ''
          }</div>
        </div>
        <button class="icon-btn sm" data-notif-dismiss="${escapeHtml(n.id)}" title="Remove from history" aria-label="Remove notification">
          <svg class="icon"><use href="#i-close"/></svg>
        </button>
      </div>`;
}

/**
 * When a notification arrived, in words.
 *
 * The companion path used to put the raw epoch in `time`, which drew as a
 * thirteen-digit number. `timestamp` is the one field both transports agree on;
 * `time` is only a formatted string on the ADB path, so it is the fallback.
 */
function notifWhen(notif) {
  if (Number(notif.timestamp) > 0) return relativeTime(notif.timestamp, Date.now());
  return typeof notif.time === 'string' ? notif.time : '';
}

/**
 * Keeps "2 min ago" true while the user is looking at it.
 *
 * Only the notifications view needs this, and only while it is open, so the
 * timer is started when that view is shown rather than at boot.
 */
let notifClockTimer = null;
function startNotifClock() {
  if (notifClockTimer) return;
  notifClockTimer = setInterval(() => {
    if (state.view === 'notifications') renderNotifications();
  }, 30 * 1000);
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
  renderLastClip();
  $('btn-clear-clip').hidden = state.clipboard.length === 0;
  if (state.clipboard.length === 0) {
    list.innerHTML = hasPhone()
      ? emptyBlock('clipboard', 'Nothing synced yet', 'Copy something on either device to see it here.')
      : emptyBlock('clipboard', 'No phone connected', 'Connect a phone to share the clipboard.', { view: 'connect', label: 'Connect a phone' });
    return;
  }
  list.innerHTML = state.clipboard.map(clipRow).join('');
}

/** The newest clip, with which way it went (§10.3 "Last synced"). */
function renderLastClip() {
  const box = $('clip-last');
  if (!box) return;
  const last = state.clipboard[0];
  box.hidden = !last;
  if (!last) return;
  const fromPhone = last.direction === 'phone-to-desktop';
  $('clip-last-meta').innerHTML =
    `${icon(fromPhone ? 'download' : 'upload', 'icon icon-sm')}<span>Last synced · ${fromPhone ? 'From phone' : 'To phone'}</span>` +
    (last.time ? `<span>·</span><span>${escapeHtml(last.time)}</span>` : '');
  $('clip-last-text').textContent = last.preview || last.text || '';
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

  const reasons = {
    syncing: 'Copies cross over in both directions.',
    connecting: 'Reaching the phone…',
    idle: hasPhone() ? 'Turned on, waiting for the next copy.' : 'Starts syncing when a phone connects.',
    error: state.clipboardStatus.error
      ? `Sync stopped: ${state.clipboardStatus.error}`
      : 'Sync stopped. Check that wl-paste/wl-copy (Wayland) or xclip (X11) is installed.',
    disconnected: 'Starts syncing when a phone connects.',
  };
  const reason = $('clip-reason');
  if (reason) {
    reason.textContent = state.clipboardStatus.enabled === false
      ? 'Off - nothing is shared between the devices.'
      : reasons[status] || '';
  }

  const dot = $('clipboard-dot');
  dot.className = `status-dot ${status}`;
  const dotLabel = `Clipboard sync: ${(labels[status] || status).toLowerCase()}`;
  dot.setAttribute('aria-label', dotLabel);
  dot.title = dotLabel;

  for (const id of ['clip-toggle', 'settings-clip-toggle']) {
    const toggle = $(id);
    if (toggle) toggle.checked = state.clipboardStatus.enabled !== false;
  }
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
  // The phone's vitals ride alongside the connection info. Kept on the state
  // so the device cards can show them, and refreshed by the live event as the
  // phone republishes every minute.
  state.companionInfo = info;

  // A phone that went away takes its folder with it: forget what was listed,
  // so the next connection starts at the root instead of showing a stale
  // folder the phone no longer answers for.
  if (!state.companionConnected) {
    state.phone.path = '';
    state.phone.entries = [];
    state.phone.results = null;
    state.phone.query = '';
    state.phone.searching = false;
    state.phone.loaded = false;
    state.phone.error = null;
    state.phone.seq++;
    state.phone.searchSeq++;
    const box = $('phone-search');
    if (box) box.value = '';
    const clear = $('phone-search-clear');
    if (clear) clear.hidden = true;
  }

  renderDevice();
  updateFilesTransport();
  if (state.view === 'notifications') renderNotifications();
  if (state.view === 'clipboard') renderClipboard();

  const pill = $('companion-pill');
  const detail = $('companion-detail');
  const badge = $('companion-badge');
  const destEl = $('companion-dest-path');
  const pairBlock = $('pair-block');
  const keyboardPill = $('keyboard-pill');
  const keyboardBanner = $('keyboard-banner');
  const setPill = (label, kind) => {
    if (!pill) return;
    pill.textContent = label;
    pill.className = `pill ${kind}`;
  };

  if (destEl && info.destinationDir) destEl.textContent = info.destinationDir;

  // The kill switch. The checkbox is the truth about the state, not a guess:
  // the desktop remembers it, and a restarted app must come back the way the
  // user left it.
  for (const id of ['companion-switch', 'settings-companion-switch']) {
    const killSwitch = $(id);
    if (killSwitch && document.activeElement !== killSwitch) {
      killSwitch.checked = info.enabled !== false;
    }
  }

  const client = info.connected ? info.clients?.[0] : null;
  const noKeyboard = Boolean(client && client.clipboardAccess === false);
  if (keyboardPill) keyboardPill.hidden = !noKeyboard;
  if (keyboardBanner) keyboardBanner.hidden = !noKeyboard;

  if (info.status === 'disabled') {
    setPill('Off', 'disconnected');
    setQrVisible(false);
    if (pairBlock) pairBlock.hidden = true;
    if (detail) {
      detail.textContent =
        'Off — this computer is not listening. The phone keeps looking and will ' +
        'reconnect on its own when you switch it back on.';
    }
    return;
  }

  if (info.connected) {
    setPill('Connected', 'success');
    // Nothing to pair while a phone is already connected.
    setQrVisible(false);
    if (pairBlock) pairBlock.hidden = true;
    if (detail) {
      detail.textContent = `On — ${client?.deviceName || 'the phone'} is connected over an encrypted link (TLS 1.3).`;
    }
    return;
  }

  if (pairBlock) pairBlock.hidden = false;
  if (info.status === 'listening') {
    setPill('Waiting for phone', 'connecting');
    // Nothing is paired yet: show the pairing code straight away, once. After
    // that the button is the user's: "Hide QR" keeps it hidden.
    if (!state.qrAutoShown) {
      state.qrAutoShown = true;
      setQrVisible(true);
    }
    if (detail) {
      detail.textContent = `On — the phone can reach this computer at ${info.address}:${info.port}.`;
    }
    if (badge) {
      badge.innerHTML = `Code: <strong id="companion-code">${escapeHtml(info.code || '—')}</strong>`;
    }
  } else {
    setPill('Offline', 'disconnected');
    setQrVisible(false);
    if (detail) {
      detail.textContent = 'The phone connection is not running on this computer.';
    }
  }
}

/**
 * Shows or hides the pairing QR. There is one button for both: it reads
 * "Hide QR" while the code is showing and "Show QR" while it is not.
 */
function setQrVisible(visible) {
  state.qrVisible = Boolean(visible);
  const card = $('companion-qr-card');
  if (card) card.hidden = !state.qrVisible;
  const button = $('btn-qr-toggle');
  if (button) {
    const label = button.querySelector('.btn-label');
    if (label) label.textContent = qrToggleLabel(state.qrVisible);
    button.setAttribute('aria-expanded', String(state.qrVisible));
  }
  if (state.qrVisible) refreshPairingQr();
}

async function toggleQr() {
  if (state.qrVisible) {
    setQrVisible(false);
    return;
  }
  if (!window.api.getCompanionQr) return;
  const result = await refreshPairingQr();
  if (!result) {
    toast('Could not create the pairing code', 'err');
    return;
  }
  setQrVisible(true);
}

async function loadClipboardHistory() {
  try {
    state.clipboard = await window.api.getClipboardHistory();
    renderClipboard();
  } catch {
    /* ignore */
  }
}

/**
 * The pairing QR: shown in the "add a device" dialog and, when nothing is
 * connected yet, right in the clipboard panel.
 *
 * Top level, not nested in initNavigation(): applyCompanionInfo() calls it
 * when the desktop starts listening, and a nested function was out of its
 * reach (a ReferenceError that stopped the rest of that update).
 */
async function refreshPairingQr() {
  if (!window.api.getCompanionQr) return null;
  try {
    const result = await window.api.getCompanionQr();
    if (!result || !result.success) return null;

    for (const id of ['pair-qr-image', 'companion-qr']) {
      const image = $(id);
      if (image) image.src = result.dataUrl;
    }
    const code = $('pair-qr-code');
    if (code) code.textContent = result.info?.code || '------';
    applyPcName(result.info?.pcName);
    for (const id of ['pair-qr-hint', 'companion-qr-hint']) {
      const hint = $(id);
      if (hint) hint.textContent = `${result.info?.pcName || 'This PC'} at ${result.info?.host}:${result.info?.port}`;
    }
    return result;
  } catch {
    return null;
  }
}

/* ---------- Modals ---------- */

// Where focus was before a dialog opened, so closing it puts the user back.
let modalOpener = null;

const FOCUSABLE =
  'button:not([disabled]):not([hidden]), input:not([disabled]), [href], select, textarea, [tabindex]:not([tabindex="-1"])';

function modalFocusables(root) {
  return [...root.querySelectorAll(FOCUSABLE)].filter((el) => el.offsetParent !== null);
}

function openModal(id) {
  const root = $(id);
  if (!root.hidden) return;
  if (!document.querySelector('.modal-root:not([hidden])')) modalOpener = document.activeElement;
  root.hidden = false;
  // First field, else the first button that is not the close cross.
  const items = modalFocusables(root);
  const first =
    items.find((el) => el.matches('input')) ||
    items.find((el) => !el.matches('.modal-close')) ||
    items[0];
  if (first) first.focus();
}

function restoreModalFocus() {
  if (document.querySelector('.modal-root:not([hidden])')) return;
  const target = modalOpener;
  modalOpener = null;
  if (target && document.contains(target) && typeof target.focus === 'function') target.focus();
}

function closeModal(id) {
  $(id).hidden = true;
  restoreModalFocus();
}

function closeAllModals() {
  document.querySelectorAll('.modal-root').forEach((el) => {
    el.hidden = true;
  });
  restoreModalFocus();
}

// Tab stays inside the open dialog.
document.addEventListener('keydown', (e) => {
  if (e.key !== 'Tab') return;
  const root = document.querySelector('.modal-root:not([hidden])');
  if (!root) return;
  const items = modalFocusables(root);
  if (items.length === 0) return;
  const first = items[0];
  const last = items[items.length - 1];
  if (e.shiftKey && (document.activeElement === first || !root.contains(document.activeElement))) {
    e.preventDefault();
    last.focus();
  } else if (!e.shiftKey && (document.activeElement === last || !root.contains(document.activeElement))) {
    e.preventDefault();
    first.focus();
  }
});

/* ---------- Init ---------- */

function initWindowControls() {
  $('win-min').addEventListener('click', () => window.api.minimizeWindow());
  $('win-close').addEventListener('click', () => window.api.closeWindow());
  $('win-max').addEventListener('click', () => window.api.toggleMaximize?.());
}

function initNavigation() {
  // Delegated, so buttons rendered later ("Connect a phone" in an empty
  // state) navigate too.
  document.addEventListener('click', (e) => {
    const target = e.target.closest('[data-view]');
    if (!target || target.closest('.modal-root')) return;
    showView(target.dataset.view);
  });

  // Only one panel may show at a time - set that up before the first click.
  {
    const active = document.querySelector('.tab.active');
    const activeTab = active ? active.dataset.tab : 'qr';
    $('tab-qr').hidden = activeTab !== 'qr';
    $('tab-wireless').hidden = activeTab !== 'wireless';
    $('tab-usb').hidden = activeTab !== 'usb';
  }

  for (const id of ['btn-add-device', 'btn-add-device-2']) {
    const el = $(id);
    if (el) {
      el.addEventListener('click', () => {
        openModal('modal-device');
        refreshPairingQr();
      });
    }
  }

  document.querySelectorAll('[data-close-modal]').forEach((el) => {
    el.addEventListener('click', closeAllModals);
  });

  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
      closeAllModals();
      return;
    }
    // Page shortcuts (DESIGN.md §8.13): Ctrl+1…4 in sidebar order, Ctrl+, Settings.
    const typing = e.target.closest?.('input, textarea, [contenteditable="true"]');
    if (!(e.ctrlKey || e.metaKey) || e.altKey || e.shiftKey || typing) return;
    if (document.querySelector('.modal-root:not([hidden])')) return;
    const pages = { 1: 'connect', 2: 'files', 3: 'clipboard', 4: 'notifications', ',': 'settings' };
    const page = pages[e.key];
    if (page) {
      e.preventDefault();
      showView(page);
    } else if (e.key.toLowerCase() === 'o' && state.view === 'files') {
      e.preventDefault();
      browseFilesForTarget();
    }
  });

  // The connection picker on Connect, when both transports are live.
  $('transport-picker')?.addEventListener('click', (e) => {
    const btn = e.target.closest('[data-target]');
    if (btn) selectTarget(btn.dataset.target);
  });

  // The tray's "Open Files" asks for a page by name (`route:navigate`).
  window.api.onNavigate?.((view) => showView(view));
}

function initDeviceActions() {
  $('btn-disconnect').addEventListener('click', async () => {
    // "Disconnect" means the connection the user is looking at. The same phone
    // can be reachable twice, and dropping the wrong one - or calling adb at a
    // phone that is only here over TLS - is what made this button appear dead.
    if (state.target === 'companion' && state.companion && window.api.disconnectCompanion) {
      const result = await window.api.disconnectCompanion();
      if (!result || !result.success) {
        toast('The phone was not connected', 'err');
        return;
      }
      toast('Disconnected');
      return;
    }

    if (!state.device) return;
    await window.api.disconnectDevice(state.device.id);
    toast('Disconnected');
  });

  $('btn-qr-toggle')?.addEventListener('click', toggleQr);

  $('btn-cast').addEventListener('click', () => {
    // Over the companion channel there is no ADB device to check, and no
    // confirmation to show here: the phone asks the user itself.
    if (state.target === 'companion' && state.companion) {
      startCompanionCast();
      return;
    }
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
      const setting = $('settings-cast-confirm');
      if (setting) setting.checked = false;
    }
    closeModal('modal-cast');
    startCast();
  });

  $('btn-cast-stop').addEventListener('click', async () => {
    if (state.mirror.state === 'starting' || state.mirror.state === 'streaming') {
      await stopCompanionCast();
      return;
    }
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
    toast(result.message || 'Could not start mirroring', 'err');
    return;
  }
  state.casting = true;
  renderCast();
}

/* ---------- Screen mirroring over the companion channel ---------- */

/**
 * The decoder and the canvas, and nothing else.
 *
 * WebCodecs needs a codec string before it will open, and the desktop learns
 * that string from the first keyframe's SPS. So frames that arrive before it are
 * counted and dropped rather than decoded: there is nothing to decode them
 * against, and a delta frame alone is not a picture.
 */
const mirrorView = {
  decoder: null,
  configured: false,
  waitingForCodec: 0,
  drawn: 0,
};

/** Starts a companion mirroring session. */
async function startCompanionCast() {
  const result = await window.api.startCompanionMirror();
  if (!result || !result.success) {
    toast(describeMirror({ state: 'idle', reason: result && result.reason }), 'err');
    renderCast();
    return null;
  }
  return result;
}

/** Stops it. The phone is told, and it releases the projection immediately. */
async function stopCompanionCast() {
  await window.api.stopCompanionMirror();
  resetMirrorView();
}

function resetMirrorView() {
  if (mirrorView.decoder) {
    try {
      mirrorView.decoder.close();
    } catch {
      /* already closed */
    }
  }
  mirrorView.decoder = null;
  mirrorView.configured = false;
  mirrorView.waitingForCodec = 0;
  // Per session, not per page: an accumulating count would read as a session
  // that started before the user pressed anything.
  mirrorView.drawn = 0;
  state.mirror = { state: 'idle', reason: state.mirror.reason, width: 0, height: 0, encoder: '', frames: 0 };

  const canvas = $('cast-canvas');
  if (canvas) {
    const ctx = canvas.getContext('2d');
    if (ctx) ctx.clearRect(0, 0, canvas.width, canvas.height);
  }
  renderCast();
}

/**
 * One frame from the phone.
 *
 * `data` arrives as a Uint8Array over IPC. The first keyframe also carries the
 * codec string, which is when the decoder can finally be created.
 */
function onCompanionFrame(frame) {
  if (typeof VideoDecoder === 'undefined') return;

  if (frame.codec) {
    if (mirrorView.decoder) {
      try {
        mirrorView.decoder.close();
      } catch {
        /* already closed */
      }
    }
    mirrorView.configured = false;
    mirrorView.decoder = new VideoDecoder({
      output: drawFrame,
      error: (err) => {
        // A decoder that gives up must not wedge the session: the next
        // keyframe reconfigures it, and the picture comes back.
        console.warn('mirror decoder error:', err && err.message);
        mirrorView.configured = false;
        mirrorView.decoder = null;
      },
    });
    try {
      mirrorView.decoder.configure({
        codec: frame.codec,
        optimizeForLatency: true,
      });
      mirrorView.configured = true;
    } catch (err) {
      console.warn('mirror decoder would not open:', err && err.message);
      mirrorView.decoder = null;
      return;
    }
  }

  if (!mirrorView.decoder || !mirrorView.configured) {
    mirrorView.waitingForCodec += 1;
    return;
  }

  try {
    mirrorView.decoder.decode(
      new EncodedVideoChunk({
        type: frame.keyframe ? 'key' : 'delta',
        timestamp: frame.ptsMicros,
        data: frame.data,
      })
    );
  } catch (err) {
    console.warn('mirror frame rejected:', err && err.message);
  }
}

/**
 * Paints one decoded frame, and gives it straight back to the decoder.
 *
 * The frame counter in the card is updated here rather than per frame: a
 * label re-render at video rate would be wasted work, and twice a second is
 * indistinguishable to a reader.
 */
function drawFrame(frame) {
  const canvas = $('cast-canvas');
  try {
    if (canvas && (canvas.width !== frame.displayWidth || canvas.height !== frame.displayHeight)) {
      canvas.width = frame.displayWidth;
      canvas.height = frame.displayHeight;
    }
    const ctx = canvas && canvas.getContext('2d');
    if (ctx) {
      ctx.drawImage(frame, 0, 0, canvas.width, canvas.height);
      mirrorView.drawn += 1;

      if (mirrorView.drawn % 30 === 0) {
        state.mirror.frames = mirrorView.drawn;
        const label = $('cast-state');
        if (label) label.textContent = describeMirror(state.mirror);
      }
    }
  } finally {
    frame.close();
  }
}

function initPairing() {
  const tabs = [...document.querySelectorAll('#modal-device .tab')];
  const selectTab = (tab, focus = false) => {
    tabs.forEach((t) => {
      const on = t === tab;
      t.classList.toggle('active', on);
      t.setAttribute('aria-selected', on ? 'true' : 'false');
      // Roving tabindex: one stop for the tab list, arrows move inside it.
      t.tabIndex = on ? 0 : -1;
    });
    $('tab-qr').hidden = tab.dataset.tab !== 'qr';
    $('tab-wireless').hidden = tab.dataset.tab !== 'wireless';
    $('tab-usb').hidden = tab.dataset.tab !== 'usb';
    if (focus) tab.focus();
  };
  tabs.forEach((tab, index) => {
    tab.setAttribute('aria-controls', `tab-${tab.dataset.tab}`);
    tab.addEventListener('click', () => selectTab(tab));
    tab.addEventListener('keydown', (e) => {
      let next = null;
      if (e.key === 'ArrowRight') next = tabs[(index + 1) % tabs.length];
      if (e.key === 'ArrowLeft') next = tabs[(index - 1 + tabs.length) % tabs.length];
      if (e.key === 'Home') next = tabs[0];
      if (e.key === 'End') next = tabs[tabs.length - 1];
      if (next) {
        e.preventDefault();
        selectTab(next, true);
      }
    });
  });
  selectTab(tabs.find((t) => t.classList.contains('active')) || tabs[0]);

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

  // Exactly one entry point: the zone itself. Companion and ADB retain their
  // own picker and send paths, while drag-and-drop uses the same routing.
  const chooseFilesForTarget = () => browseFilesForTarget();

  zone.addEventListener('click', chooseFilesForTarget);
  zone.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      chooseFilesForTarget();
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
    return sendDroppedFiles(paths);
  });

  $('btn-send-file').addEventListener('click', chooseFilesForTarget);

  $('transfer-list').addEventListener('click', (e) => {
    const cancel = e.target.closest('[data-cancel]');
    if (cancel) {
      window.api.cancelTransfer(cancel.dataset.cancel);
      return;
    }
    const retry = e.target.closest('[data-retry]');
    if (retry) {
      const t = state.transfers.get(retry.dataset.retry);
      state.transfers.delete(retry.dataset.retry);
      renderTransfers();
      if (t && t.localFilePath) sendDroppedFiles([t.localFilePath]);
      return;
    }
    const dismiss = e.target.closest('[data-dismiss]');
    if (dismiss) {
      state.transfers.delete(dismiss.dataset.dismiss);
      renderTransfers();
    }
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
    // A checkbox is a selection, not an open: it must not fall through to the
    // row underneath, or ticking a file would also open it.
    if (e.target.closest('[data-select-file]')) return;

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
    if (!row || hasSelectedText()) return;

    if (row.dataset.dir === '1') {
      loadDirectory(row.dataset.path);
    } else {
      openEntry(row.dataset.path);
    }
  });

  // The checkboxes only change a set, so the list is left standing: re-rendering
  // it would drop focus and scroll position on every tick.
  $('browser-list').addEventListener('change', (e) => {
    const box = e.target.closest('[data-select-file]');
    if (!box) return;
    toggleFileSelection(box.dataset.selectFile, box.dataset.selectName, box.checked, 'adb');
  });

  $('btn-download-selected').addEventListener('click', downloadSelectedFiles);
  $('btn-clear-selection').addEventListener('click', () => clearFileSelection());

  // The one way a failure with no row of its own reaches the window: a phone
  // that would not send a file, or a file the desktop would not open.
  window.api.onToast?.((data) => {
    if (data && data.message) toast(data.message, data.kind || 'err');
  });

  window.api.onTransferProgress((data) => {
    switch (data.status) {
      case 'started':
        upsertTransfer({
          id: data.id,
          filename: data.filename,
          size: data.size,
          type: data.type,
          localFilePath: data.localFilePath,
          status: 'active',
        });
        break;
      case 'progress':
        // A transfer that is still running: keep its row alive. Only a row
        // that was announced by `started` is updated, so a stray progress
        // event cannot create a nameless row.
        if (state.transfers.has(data.id)) {
          const patch = { id: data.id, status: 'active' };
          if (data.received != null) patch.received = data.received;
          if (data.total != null) patch.total = data.total;
          upsertTransfer(patch);
        }
        break;
      case 'completed': {
        const name = data.filename || String(data.targetPath || data.localTarget || '').split('/').pop();
        // A file fetched only to be looked at is not kept, so it is not
        // history either: its row just goes away.
        if (data.type === 'view') {
          state.transfers.delete(data.id);
          renderTransfers();
          break;
        }
        upsertTransfer({
          id: data.id,
          filename: name,
          targetPath: data.targetPath || data.localTarget,
          localFilePath: data.localFilePath || state.transfers.get(data.id)?.localFilePath,
          size: data.size || state.transfers.get(data.id)?.size,
          type: data.type,
          status: 'done',
        });
        toast(data.type === 'pull' ? `Received ${name}` : `Sent ${name}`);
        break;
      }
      case 'failed':
        upsertTransfer({
          id: data.id,
          status: 'failed',
          error: data.error,
          ...(data.filename ? { filename: data.filename } : {}),
          ...(data.type ? { type: data.type } : {}),
        });
        break;
      case 'cancelled':
        upsertTransfer({ id: data.id, status: 'cancelled' });
        break;
    }
  });
}

function initNotifications() {
  window.api.onNotificationReceived((notif) => {
    // One row per notification: a notification that is posted again (an app
    // updating its text, or the phone re-sending after a reconnect) replaces
    // its row instead of stacking a second copy of the same message.
    state.notifications = [
      notif,
      ...state.notifications.filter((n) => n.id !== notif.id),
    ].slice(0, 50);

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

  for (const id of ['clip-toggle', 'settings-clip-toggle']) {
    $(id).addEventListener('change', async (e) => {
      const result = await window.api.setClipboardSync(e.target.checked);
      applyClipboardStatus({ enabled: result.enabled });
    });
  }

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
    btnOpenDest.addEventListener('click', async () => {
      const result = await window.api.openCompanionDestinationDir?.();
      if (result && result.success === false) {
        toast(result.error || 'Could not open the folder', 'err');
      }
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

  $('btn-send-text')?.addEventListener('click', async () => {
    if (!hasPhone()) {
      toast('Connect a phone first', 'err');
      return;
    }
    const text = await askText('Send text to the phone', 'Type or paste text. It lands on the phone\'s clipboard.', {
      okLabel: 'Send',
      multiline: true,
    });
    if (!text) return;
    try {
      const result = await window.api.sendToPhoneClipboard(text);
      if (result && result.success === false) {
        toast(result.error || 'The phone did not take the text', 'err');
        return;
      }
      toast("Sent to the phone's clipboard");
    } catch (err) {
      toast(`Could not send: ${err.message}`, 'err');
    }
  });

  const phoneUpButton = $('phone-up');
  if (phoneUpButton) phoneUpButton.addEventListener('click', phoneUp);

  const phoneRefreshButton = $('phone-refresh');
  if (phoneRefreshButton) {
    phoneRefreshButton.addEventListener('click', () => {
      // A refresh means "show me what is there now": re-run the search, or
      // re-read the folder the user is standing in. A search is re-run
      // against a fresh walk, or it would answer from names the phone
      // remembered rather than from what is on it.
      if (state.phone.results !== null || state.phone.query.trim()) {
        searchPhoneFiles(state.phone.query, { fresh: true });
      } else {
        loadPhoneDirectory(state.phone.path);
      }
    });
  }

  // The search box: one request per pause, not one per keystroke.
  const phoneSearch = $('phone-search');
  if (phoneSearch) {
    phoneSearch.addEventListener('input', (event) => {
      const value = event.target.value;
      const clear = $('phone-search-clear');
      if (clear) clear.hidden = !value;
      schedulePhoneSearch(value);
    });
    phoneSearch.addEventListener('keydown', (event) => {
      if (event.key === 'Escape') {
        clearPhoneSearch();
        renderPhoneBrowser();
      }
    });
  }
  const phoneSearchClear = $('phone-search-clear');
  if (phoneSearchClear) {
    phoneSearchClear.addEventListener('click', () => {
      clearPhoneSearch();
      renderPhoneBrowser();
    });
  }

  // One listener for the whole list: the rows are rebuilt on every listing.
  const phoneList = $('phone-browser-list');
  if (phoneList) {
    phoneList.addEventListener('click', (event) => {
      if (event.target.closest('[data-select-file]')) return;
      const open = event.target.closest('[data-open]');
      if (open) {
        const row = open.closest('.browse-row');
        const name = row?.querySelector('.browse-name')?.textContent || 'the file';
        previewPhoneFile(open.dataset.open, name);
        return;
      }
      const pull = event.target.closest('[data-pull]');
      if (pull) {
        const row = pull.closest('.browse-row');
        const name = row?.querySelector('.browse-name')?.textContent || 'the file';
        pullPhoneFile(pull.dataset.pull, name);
        return;
      }
      const row = event.target.closest('.browse-row');
      if (row && row.dataset.dir === '1' && !hasSelectedText()) {
        // A search result opens the folder it lives in; the breadcrumb then
        // shows a real path instead of the query.
        openPhoneFolder(row.dataset.id);
      }
    });

    phoneList.addEventListener('change', (event) => {
      const box = event.target.closest('[data-select-file]');
      if (!box) return;
      toggleFileSelection(box.dataset.selectFile, box.dataset.selectName, box.checked, 'companion');
    });
  }

  if (window.api.onCompanionStatus) {
    window.api.onCompanionStatus(() => refreshCompanionInfo());
  }
  if (window.api.onCompanionEnabled) {
    window.api.onCompanionEnabled(() => refreshCompanionInfo());
  }

  // The kill switch. Moving it is the user saying "this computer is off", so
  // the desktop stops listening and the phone is told why - it keeps looking
  // slowly instead of giving up until someone presses Reconnect.
  const killSwitches = ['companion-switch', 'settings-companion-switch'].map($).filter(Boolean);
  for (const killSwitch of window.api.setCompanionEnabled ? killSwitches : []) {
    killSwitch.addEventListener('change', async () => {
      const wanted = killSwitch.checked;
      try {
        const result = await window.api.setCompanionEnabled(wanted);
        if (result && result.enabled !== wanted) {
          // The desktop disagreed - put the switch back where the truth is.
          killSwitch.checked = Boolean(result.enabled);
        }
      } catch {
        killSwitch.checked = !wanted;
      }
      refreshCompanionInfo();
    });
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
  // The phone republishes its vitals every minute. Merge them into the stored
  // info block and redraw, so the battery moves without a full refresh.
  if (window.api.onCompanionDeviceInfo) {
    window.api.onCompanionDeviceInfo((deviceInfo) => {
      if (!deviceInfo) return;
      state.companionInfo = { ...(state.companionInfo || {}), deviceInfo };
      renderDevice();
    });
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

  // The phone's screen: state changes and frames, from the mirror service.
  window.api.onMirrorState((info) => {
    state.mirror = {
      ...state.mirror,
      ...info,
      frames: info.state === 'streaming' ? state.mirror.frames : 0,
    };
    if (info.state === 'idle') resetMirrorView();
    else renderCast();
  });

  window.api.onMirrorFrame(onCompanionFrame);

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
  initAppearance();
  initAvatar();
  initWindowControls();
  initNavigation();
  initDeviceActions();
  initPairing();
  initFiles();
  initNotifications();
  initClipboard();
  initHistory();
  initDeviceEvents();

  renderBadges();
  renderTransfers();
  renderNotifications();
  renderClipboard();
  refreshClipboardStatus();
  // The pairing info carries this computer's name for the sidebar.
  refreshPairingQr();

  // Connect is the home page; with nothing connected it shows pairing.
  showView('connect');

  // A reload loses the device event, so pick the connection back up.
  window.api.getConnectedDevice?.().then((device) => {
    if (device && !state.device) setDevice(device);
  });
}

window.addEventListener('DOMContentLoaded', init);
