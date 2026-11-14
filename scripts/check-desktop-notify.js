#!/usr/bin/env node
/**
 * Desktop notifications for mirrored phone notifications.
 *
 * Two things have to be true, and each one is a case here:
 *
 *   - a notification that keeps updating (a hotspot counter, a download)
 *     updates its one bubble instead of stacking a new one every few
 *     seconds - the flood the user saw;
 *   - the drawer shows only what is still live on the phone: a notification
 *     the phone cleared closes its bubble, while the app's own history keeps
 *     the row.
 *
 * The desktop never writes back to the phone, so nothing here may send a
 * dismiss to the device.
 *
 * There is no test runner in this repo:
 *   node scripts/check-desktop-notify.js
 */
'use strict';

const assert = require('assert');
const { EventEmitter } = require('events');

const { DesktopNotify } = require('../services/desktop-notify');
const NotificationService = require('../services/notifications');

/** Flush the promises a fire-and-forget desktop notification leaves behind. */
function settle() {
  return new Promise((resolve) => setImmediate(resolve));
}

/**
 * A stand-in for the daemon's `org.freedesktop.Notifications` interface.
 *
 * It records every call and hands back the same id for a replace, which is
 * exactly the contract the real daemon offers and the one the flood depends on.
 */
function fakeDaemon() {
  const calls = { notify: [], close: [] };
  const handlers = new Map();
  let nextId = 100;
  return {
    calls,
    async Notify(appName, replacesId, icon, summary, body, actions, hints, timeout) {
      calls.notify.push({ appName, replacesId, icon, summary, body, actions, hints, timeout });
      // A replace keeps the id it was asked to replace; a fresh post gets one.
      return replacesId || nextId++;
    },
    async CloseNotification(id) {
      calls.close.push(id);
    },
    on(event, handler) {
      handlers.set(event, handler);
    },
    /** The daemon telling us the user (or its own timeout) closed a bubble. */
    emitClosed(id, reason) {
      const handler = handlers.get('NotificationClosed');
      if (handler) handler(id, reason);
    },
  };
}

function attached(daemon = fakeDaemon()) {
  const notifier = new DesktopNotify({ daemon });
  return { daemon, notifier };
}

/**
 * The flood: the same phone notification re-posting must reuse one bubble.
 */
async function checkUpdatesReplaceInsteadOfStacking() {
  const { daemon, notifier } = attached();

  const first = await notifier.notify('com.phone:1:null', { summary: 'Hotspot', body: '1 device' });
  assert.strictEqual(first, 100, 'the first post did not return the daemon id');
  assert.strictEqual(daemon.calls.notify.length, 1, 'the first post was not sent');
  assert.strictEqual(
    daemon.calls.notify[0].replacesId,
    0,
    'a brand new notification was sent as a replace'
  );

  const second = await notifier.notify('com.phone:1:null', { summary: 'Hotspot', body: '2 devices' });
  assert.strictEqual(second, 100, 'the update did not land on the same bubble');
  assert.strictEqual(
    daemon.calls.notify[1].replacesId,
    100,
    'an update was sent as a new bubble, which is the flood'
  );
  assert.strictEqual(daemon.calls.notify[1].body, '2 devices', 'the update kept the stale body');

  // A different notification is still its own bubble.
  await notifier.notify('com.phone:2:null', { summary: 'Other', body: 'hi' });
  assert.strictEqual(
    daemon.calls.notify[2].replacesId,
    0,
    'an unrelated notification hijacked another bubble'
  );
}

/**
 * Clearing on the phone must take the bubble with it.
 */
async function checkCloseReleasesTheBubble() {
  const { daemon, notifier } = attached();
  await notifier.notify('com.phone:1:null', { summary: 'a', body: 'b' });

  assert.strictEqual(await notifier.close('com.phone:1:null'), true, 'a tracked bubble did not close');
  assert.deepStrictEqual(daemon.calls.close, [100], 'the bubble was closed by the wrong id');

  // Once closed, the key is forgotten: a later post starts a new bubble
  // rather than trying to replace one the drawer no longer has.
  assert.strictEqual(await notifier.close('com.phone:1:null'), false, 'a closed key closed twice');
  await notifier.notify('com.phone:1:null', { summary: 'a', body: 'c' });
  const last = daemon.calls.notify.at(-1);
  assert.strictEqual(last.replacesId, 0, 'a re-post after a close tried to replace a dead bubble');
}

/** An id we never posted is not ours to close, and must not throw. */
async function checkUnknownKeyIsIgnored() {
  const { daemon, notifier } = attached();
  assert.strictEqual(await notifier.close('never-posted'), false, 'an unknown key reported a close');
  assert.strictEqual(daemon.calls.close.length, 0, 'an unknown key reached the daemon');
}

/**
 * The daemon can close a bubble itself (the user dismissed it, or it
 * expired). Our map has to follow, or the next update replaces a dead id.
 */
async function checkDaemonClosedForgetsTheKey() {
  const { daemon, notifier } = attached();
  const closed = [];
  notifier.on('closed', (event) => closed.push(event));

  await notifier.notify('com.phone:1:null', { summary: 'a', body: 'b' });
  daemon.emitClosed(100, 2);

  assert.strictEqual(closed.length, 1, 'the daemon closing a bubble was not reported');
  assert.strictEqual(closed[0].key, 'com.phone:1:null', 'the closed event lost the key');
  assert.strictEqual(closed[0].reason, 2, 'the closed event lost the reason');

  await notifier.notify('com.phone:1:null', { summary: 'a', body: 'c' });
  assert.strictEqual(
    daemon.calls.notify.at(-1).replacesId,
    0,
    'a bubble closed by the daemon was still treated as live'
  );
}

/**
 * No session bus (a bare X11 box, a container): the popup still has to work,
 * just without replace or close.
 */
async function checkFallsBackWithoutADaemon() {
  const sent = [];
  const notifier = new DesktopNotify({
    busFactory: () => {
      throw new Error('no session bus');
    },
    notifySend: (args) => sent.push(args),
  });

  assert.strictEqual(await notifier.start(), false, 'a missing session bus was reported as a start');
  const id = await notifier.notify('k', { summary: 'Title', body: 'Body' });
  assert.strictEqual(id, null, 'a fallback post claimed a daemon id');
  assert.strictEqual(sent.length, 1, 'the fallback popup was not sent');
  assert.ok(sent[0].includes('Title'), 'the fallback popup lost the title');
  assert.strictEqual(await notifier.close('k'), false, 'the fallback claimed a close');
}

// ---------------------------------------------------------------------------
// Policy: what the mirroring service does with a notification.
// ---------------------------------------------------------------------------

function fakeMirror() {
  const posted = [];
  const closed = [];
  return {
    posted,
    closed,
    start: async () => true,
    notify: (key, options) => {
      posted.push({ key, options });
    },
    close: (key) => {
      closed.push(key);
    },
  };
}

function mirrored(overrides = {}) {
  return {
    id: 'com.phone:1:null',
    key: 'com.phone:1:null',
    app: 'Phone',
    title: 'Hotspot',
    text: '1 device connected',
    message: '1 device connected',
    timestamp: Date.now(),
    ongoing: false,
    fromCompanion: true,
    ...overrides,
  };
}

/**
 * An ongoing card is not news on every tick: it is recorded in the app's list
 * and never raises a desktop popup.
 */
async function checkOngoingNeverPops() {
  const mirror = fakeMirror();
  const service = new NotificationService({ desktopNotify: mirror });

  service.addNotification(mirrored({ ongoing: true }));
  await settle();

  assert.strictEqual(mirror.posted.length, 0, 'an ongoing notification popped up');
  assert.strictEqual(service.history.length, 1, 'an ongoing notification was not recorded');

  // A message still pops.
  service.addNotification(mirrored({ id: 'com.phone:9:null', key: 'com.phone:9:null', ongoing: false }));
  await settle();
  assert.strictEqual(mirror.posted.length, 1, 'a normal notification did not pop');
}

/** Updates to one notification reuse their bubble key, so it replaces in place. */
async function checkUpdatesKeepOneKey() {
  const mirror = fakeMirror();
  const service = new NotificationService({ desktopNotify: mirror });

  service.addNotification(mirrored({ text: '2 devices', message: '2 devices' }));
  await settle();
  service.addNotification(mirrored({ text: '3 devices', message: '3 devices' }));
  await settle();

  assert.strictEqual(mirror.posted.length, 2, 'an update did not reach the drawer');
  assert.strictEqual(
    mirror.posted[0].key,
    mirror.posted[1].key,
    'an update used a new bubble key, so it would stack'
  );
}

/**
 * The phone clearing a notification closes the drawer bubble and leaves the
 * app's history alone: we recorded it, we keep the record.
 */
async function checkPhoneClearKeepsHistory() {
  const mirror = fakeMirror();
  const service = new NotificationService({ desktopNotify: mirror });

  service.addNotification(mirrored());
  await settle();
  assert.strictEqual(service.history.length, 1, 'the notification was not recorded');

  const closed = service.closeDesktop('com.phone:1:null');
  await settle();

  assert.strictEqual(closed, true, 'the phone clearing it did not close the bubble');
  assert.deepStrictEqual(mirror.closed, ['com.phone:1:null'], 'the bubble closed by the wrong key');
  assert.strictEqual(service.history.length, 1, 'the phone clearing it erased the app history');
}

/** Removing a row in the app is local: the row goes, and the bubble with it. */
async function checkManualRemoveIsLocal() {
  const mirror = fakeMirror();
  const service = new NotificationService({ desktopNotify: mirror });

  service.addNotification(mirrored());
  await settle();

  assert.strictEqual(service.dismiss('com.phone:1:null'), true, 'the row was not removed');
  await settle();
  assert.strictEqual(service.history.length, 0, 'the row survived a manual remove');
  assert.deepStrictEqual(mirror.closed, ['com.phone:1:null'], 'the bubble outlived its row');
}

/** An id we never recorded has no row and no bubble to close. */
async function checkUnknownDismissIsANoOp() {
  const mirror = fakeMirror();
  const service = new NotificationService({ desktopNotify: mirror });

  assert.strictEqual(service.dismiss('never-seen'), false, 'an unknown id reported a removal');
  assert.strictEqual(service.history.length, 0, 'an unknown id conjured a row');
  assert.strictEqual(mirror.closed.length, 0, 'an unknown id reached the drawer');
}

// ---------------------------------------------------------------------------
// The USB path is a dumpsys snapshot rather than an event stream, so the
// ongoing flag has to be read out of the dump for the same rule to hold there.
// ---------------------------------------------------------------------------

const DUMPSYS = [
  'NotificationRecord(0x1 pkg=com.coloros.phonemanager id=1 tag=null)',
  '  flags=0x00000002',
  '  android.title=String (Hotspot)',
  '  android.text=String (2 devices are connected)',
  '  when=1700000000000',
  '',
  'NotificationRecord(0x2 pkg=com.whatsapp id=7 tag=null)',
  '  flags=0x00000040',
  '  android.title=String (Ada)',
  '  android.text=String (hello)',
  '  when=1700000001000',
].join('\n');

function checkDumpsysReadsTheOngoingFlag() {
  const service = new NotificationService();

  const found = service.parse(DUMPSYS);
  assert.strictEqual(found.length, 2, 'the dump did not yield both notifications');

  const hotspot = found.find((n) => n.pkg === 'com.coloros.phonemanager');
  const message = found.find((n) => n.pkg === 'com.whatsapp');
  assert.strictEqual(hotspot.ongoing, true, 'the ongoing card was not recognised in the dump');
  assert.strictEqual(message.ongoing, false, 'a plain message was marked ongoing');

  // The dump can spell the flag out instead of printing the bit.
  assert.strictEqual(
    service.isOngoing('  flags=0x00000000\n  ONGOING_EVENT\n'),
    true,
    'the named ongoing flag was missed'
  );
  assert.strictEqual(service.isOngoing('  flags=0x00000040\n'), false, 'a plain flag read as ongoing');
  assert.strictEqual(service.isOngoing(''), false, 'an empty block read as ongoing');
}

/**
 * Nothing is filtered by package: what the phone puts in its shade, the
 * desktop may show. The noise is handled by the ongoing rule, not by dropping
 * whole apps, so a system card the user is looking for cannot go missing.
 */
function checkNoPackageIsFiltered() {
  const service = new NotificationService();
  const dump = [
    'NotificationRecord(0x1 pkg=com.android.systemui id=1 tag=null)',
    '  flags=0x00000040',
    '  android.title=String (Charging)',
    '  android.text=String (80%)',
    '  when=1700000000000',
    '',
    'NotificationRecord(0x2 pkg=android id=2 tag=null)',
    '  flags=0x00000040',
    '  android.title=String (USB)',
    '  android.text=String (connected)',
    '  when=1700000001000',
    '',
    'NotificationRecord(0x3 pkg=com.android.settings id=3 tag=null)',
    '  flags=0x00000040',
    '  android.title=String (Hotspot)',
    '  android.text=String (on)',
    '  when=1700000002000',
  ].join('\n');

  const found = service.parse(dump);
  assert.strictEqual(found.length, 3, 'a system package was filtered out of the mirror');
  for (const pkg of ['com.android.systemui', 'android', 'com.android.settings']) {
    assert.ok(found.some((n) => n.pkg === pkg), `${pkg} was dropped from the mirror`);
  }
}

async function run() {
  await checkUpdatesReplaceInsteadOfStacking();
  await checkCloseReleasesTheBubble();
  await checkUnknownKeyIsIgnored();
  await checkDaemonClosedForgetsTheKey();
  await checkFallsBackWithoutADaemon();

  await checkOngoingNeverPops();
  await checkUpdatesKeepOneKey();
  await checkPhoneClearKeepsHistory();
  await checkManualRemoveIsLocal();
  await checkUnknownDismissIsANoOp();
  checkDumpsysReadsTheOngoingFlag();
  checkNoPackageIsFiltered();

  console.log('desktop notifications: ok');
}

run().catch((err) => {
  console.error(err.message);
  process.exit(1);
});
