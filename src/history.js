/*
 * The file history: every file this computer and the phone have shared, sent
 * or received, kept across restarts.
 *
 * The Files page shows only what is moving right now. Once a transfer
 * finishes it belongs here instead, so a file is never listed twice - the
 * duplication the phone app had between its Files tab and its History page.
 *
 * A plain browser script (no module system here) that also runs under Node, so
 * scripts/check-file-history.js can test the rules without a window. Storage
 * is the renderer's job; these functions only shape the list.
 */
(function (root) {
  'use strict';

  /** Enough to scroll back a few weeks; old rows fall off the end. */
  const HISTORY_MAX = 200;

  /** "pull" is the phone sending to this computer; "push" is the other way. */
  function historyDirection(type) {
    if (type === 'pull') return 'received';
    if (type === 'push') return 'sent';
    return null;
  }

  /**
   * The history row for a finished transfer, or null when it does not belong
   * in the history: still running, failed, cancelled, or a file that was only
   * fetched to be looked at ("view") and was never kept.
   */
  function historyEntryFromTransfer(transfer, now) {
    if (!transfer || transfer.status !== 'done') return null;
    const direction = historyDirection(transfer.type);
    if (!direction) return null;
    const name = String(transfer.filename || '').trim();
    if (!name) return null;

    const localPath =
      direction === 'received'
        ? transfer.targetPath || transfer.localTarget || ''
        : transfer.localFilePath || transfer.filePath || '';
    const where =
      direction === 'sent'
        ? transfer.targetPath || 'Phone · Downloads'
        : localPath.replace(/[\\/][^\\/]*$/, '') || '';

    return {
      id: String(transfer.id || `${direction}-${name}-${now}`),
      name,
      direction,
      size: Number(transfer.total || transfer.size) || 0,
      at: Number(now) || Date.now(),
      localPath,
      where,
    };
  }

  /** Newest first; a transfer reported twice replaces its row. */
  function addHistoryEntry(list, entry, max = HISTORY_MAX) {
    if (!entry) return Array.isArray(list) ? list : [];
    const rest = (Array.isArray(list) ? list : []).filter((e) => e && e.id !== entry.id);
    return [entry, ...rest].slice(0, max);
  }

  function historyCounts(list) {
    const counts = { sent: 0, received: 0 };
    for (const e of Array.isArray(list) ? list : []) {
      if (e && (e.direction === 'sent' || e.direction === 'received')) counts[e.direction] += 1;
    }
    return counts;
  }

  /** One direction, newest first. */
  function historyFor(list, direction) {
    return (Array.isArray(list) ? list : [])
      .filter((e) => e && e.direction === direction)
      .sort((a, b) => (Number(b.at) || 0) - (Number(a.at) || 0));
  }

  /** Reads what localStorage held, tolerating anything that is not a list. */
  function parseHistory(raw) {
    try {
      const list = JSON.parse(raw || '[]');
      return Array.isArray(list) ? list.filter((e) => e && typeof e.name === 'string') : [];
    } catch {
      return [];
    }
  }

  /**
   * Whether a transfer still belongs on the Files page: it is moving, or it
   * failed and can be retried. Finished ones have moved to the history.
   */
  function isLiveTransfer(transfer) {
    return Boolean(transfer) && (transfer.status === 'active' || transfer.status === 'queued' || transfer.status === 'failed');
  }

  root.HISTORY_MAX = HISTORY_MAX;
  root.historyDirection = historyDirection;
  root.historyEntryFromTransfer = historyEntryFromTransfer;
  root.addHistoryEntry = addHistoryEntry;
  root.historyCounts = historyCounts;
  root.historyFor = historyFor;
  root.parseHistory = parseHistory;
  root.isLiveTransfer = isLiveTransfer;
})(typeof globalThis !== 'undefined' ? globalThis : this);
