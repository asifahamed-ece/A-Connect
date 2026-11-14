/*
 * When something arrived, in words a person reads at a glance.
 *
 * The notifications view keeps a history, and a row that says "14:32" is
 * useless when the user is asking "did that arrive while I was away?" - what
 * they want is "2 min ago" for the recent ones. Older rows switch to a clock,
 * because "3 days ago" is less useful than the actual time.
 *
 * A plain browser script (no module system here) that also runs under Node, so
 * scripts/check-notification-time.js can test the boundaries without a window.
 */
(function (root) {
  'use strict';

  const MONTHS = [
    'Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
    'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec',
  ];

  /** Under a minute: there is no useful number below this. */
  const JUST_NOW_MS = 45 * 1000;

  /** Up to an hour, a count of minutes is what the user can act on. */
  const ONE_HOUR_MS = 60 * 60 * 1000;

  function pad2(value) {
    return String(value).padStart(2, '0');
  }

  /** 24-hour clock, e.g. "09:05". */
  function clock(date) {
    return `${pad2(date.getHours())}:${pad2(date.getMinutes())}`;
  }

  /** Midnight of the day a date falls on, for day comparisons. */
  function dayStart(date) {
    return new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime();
  }

  /**
   * When a row arrived: "just now", "2 min ago", "14:32", "Yesterday 14:32",
   * "12 Nov 14:32", or with the year when it is not this year.
   *
   * An empty string means "no usable time", which the caller draws as nothing
   * rather than as 1970.
   */
  function relativeTime(timestamp, now) {
    const then = Number(timestamp);
    if (!Number.isFinite(then) || then <= 0) return '';

    const at = Number.isFinite(Number(now)) && Number(now) > 0 ? Number(now) : Date.now();
    const elapsed = at - then;

    // A phone clock a few seconds ahead of the PC is normal; it is not the
    // future, and "in 3 seconds" would be nonsense.
    if (elapsed < JUST_NOW_MS) return 'just now';
    if (elapsed < ONE_HOUR_MS) {
      const minutes = Math.max(1, Math.floor(elapsed / 60000));
      return `${minutes} min ago`;
    }

    const thenDate = new Date(then);
    const atDate = new Date(at);
    const days = Math.round((dayStart(atDate) - dayStart(thenDate)) / 86400000);

    if (days === 0) return clock(thenDate);
    if (days === 1) return `Yesterday ${clock(thenDate)}`;
    if (thenDate.getFullYear() === atDate.getFullYear()) {
      return `${thenDate.getDate()} ${MONTHS[thenDate.getMonth()]} ${clock(thenDate)}`;
    }
    return `${thenDate.getDate()} ${MONTHS[thenDate.getMonth()]} ${thenDate.getFullYear()} ${clock(thenDate)}`;
  }

  /** The exact moment, for the row's tooltip. */
  function fullTime(timestamp) {
    const then = Number(timestamp);
    if (!Number.isFinite(then) || then <= 0) return '';
    return new Date(then).toLocaleString();
  }

  /**
   * The list, newest first.
   *
   * A phone that queued notifications while the PC was away can deliver them
   * slightly out of order, so the rows are ordered by their own timestamp
   * rather than by however they reached us. Equal or missing timestamps keep
   * their arrival order, so a chat that updates its row does not jump around.
   */
  function newestFirst(items) {
    if (!Array.isArray(items)) return [];
    return items
      .map((item, index) => ({ item, index }))
      .sort((a, b) => {
        const at = Number(b.item && b.item.timestamp) || 0;
        const bt = Number(a.item && a.item.timestamp) || 0;
        return at - bt || a.index - b.index;
      })
      .map(({ item }) => item);
  }

  /**
   * The pairing QR button is one button with two states: it names what
   * pressing it will do, so it reads "Hide QR" while the code is showing.
   */
  function qrToggleLabel(qrVisible) {
    return qrVisible ? 'Hide QR' : 'Show QR';
  }

  root.relativeTime = relativeTime;
  root.qrToggleLabel = qrToggleLabel;
  root.fullTime = fullTime;
  root.newestFirst = newestFirst;
})(typeof globalThis !== 'undefined' ? globalThis : this);