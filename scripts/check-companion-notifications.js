#!/usr/bin/env node
/**
 * The companion channel's notifications.
 *
 * A `notification` message must become a `notification-received` event shaped
 * like the one the ADB path already emits, and a `notification-removed` must
 * become a `notification-dismissed` with the same id. The handler is the real
 * one the service uses, exported so it can be exercised without a socket.
 *
 * There is no test runner in this repo: node scripts/check-companion-notifications.js
 */
'use strict';

const assert = require('assert');
const { EventEmitter } = require('events');

const protocol = require('../services/companion-protocol');
const CompanionService = require('../services/companion');

function message(type, payload) {
  return { type, id: 'msg-1', ts: Date.now(), payload };
}

/**
 * Dismissing from the desktop: the action goes to the phone, and the phone's
 * own `notification-removed` is what updates the desktop list - one path, the
 * same one a swipe on the phone uses.
 */
function checkDismiss() {
  const service = new EventEmitter();
  const written = [];
  service.clients = [
    {
      handshakeDone: true,
      destroyed: false,
      clientIp: '127.0.0.1',
      write: (buf) => written.push(Buffer.from(buf)),
    },
  ];

  const dismissed = [];
  service.on('notification-dismissed', (d) => dismissed.push(d));

  const sent = CompanionService.prototype.dismissNotification.call(service, 'com.example:7:null');
  assert.strictEqual(sent, true, 'the dismiss was not sent to the phone');
  assert.strictEqual(written.length, 1, 'no notification-action frame was written');

  const action = protocol.decode(written[0]);
  assert.strictEqual(
    action.type,
    protocol.TYPES.NOTIFICATION_ACTION,
    'the frame is not a notification-action'
  );
  assert.strictEqual(action.payload.id, 'com.example:7:null', 'the action id did not match');
  assert.strictEqual(action.payload.action, 'dismiss', 'the action was not a dismiss');

  // The phone dismisses it and says so.
  if (CompanionService.routeNotification) {
    CompanionService.routeNotification(
      service,
      message(protocol.TYPES.NOTIFICATION_REMOVED, {
        type: protocol.TYPES.NOTIFICATION_REMOVED,
        id: 'com.example:7:null',
      })
    );
  }
  assert.strictEqual(dismissed.length, 1, 'the desktop did not register the phone dismissing it');
  assert.strictEqual(dismissed[0].id, 'com.example:7:null', 'the dismissed id did not match');
}

/**
 * One row per notification, and one popup per new message.
 *
 * The phone can post the same notification again: a chat updates its text as
 * messages arrive, and the phone re-sends what it queued when it reconnects.
 * The same words must not stack or pop twice; different words must replace the
 * row, or the desktop keeps showing the first message of a conversation.
 */
function checkOneRowPerNotification() {
  const NotificationService = require('../services/notifications');
  const service = new NotificationService();
  const received = [];
  const popped = [];
  service.on('notification-received', (n) => received.push(n));
  service.sendDesktopNotification = (n) => popped.push(n);

  const first = {
    id: 'com.example:7:null',
    key: 'com.example:7:null',
    app: 'Example',
    title: 'Ada',
    text: 'hello',
    message: 'hello',
    time: 1,
    fromCompanion: true,
  };

  service.addNotification(first);
  assert.strictEqual(received.length, 1, 'the first notification did not arrive');
  assert.strictEqual(popped.length, 1, 'the first notification did not pop up');

  // The same notification again, word for word: nothing new to say.
  service.addNotification({ ...first, time: 2 });
  assert.strictEqual(received.length, 1, 'an unchanged re-post was announced again');
  assert.strictEqual(popped.length, 1, 'an unchanged re-post popped up twice');
  assert.strictEqual(service.history.length, 1, 'an unchanged re-post stacked a second row');

  // The same notification with new words: one row, updated.
  service.addNotification({ ...first, text: 'hello again', message: 'hello again', time: 3 });
  assert.strictEqual(received.length, 2, 'an updated notification was not announced');
  assert.strictEqual(service.history.length, 1, 'an updated notification stacked a second row');
  assert.strictEqual(service.history[0].text, 'hello again', 'the row kept the old text');
}

function run() {
  const service = new EventEmitter();
  const received = [];
  const dismissed = [];
  service.on('notification-received', (n) => received.push(n));
  service.on('notification-dismissed', (d) => dismissed.push(d));

  const notification = message(
    protocol.TYPES.NOTIFICATION,
    protocol.buildNotification({
      id: 'com.example:7:null',
      pkg: 'com.example',
      app: 'Example',
      title: 'Ada',
      text: 'hello',
      ts: 1700000000000,
      ongoing: false,
      actions: [{ id: '0', title: 'Reply' }],
    })
  );

  if (CompanionService.routeNotification) CompanionService.routeNotification(service, notification);
  assert.strictEqual(received.length, 1, 'no notification event');
  const notif = received[0];
  assert.strictEqual(notif.id, 'com.example:7:null', 'the id did not survive');
  assert.strictEqual(notif.app, 'Example', 'the app did not survive');
  assert.strictEqual(notif.title, 'Ada', 'the title did not survive');
  assert.strictEqual(notif.text, 'hello', 'the text did not survive');

  // The row has to say when it arrived. The phone stamps the notification with
  // StatusBarNotification.postTime; the event must carry it in milliseconds as
  // `timestamp`, because a raw epoch number in `time` drew as a thirteen-digit
  // string in the history.
  assert.strictEqual(
    notif.timestamp,
    1700000000000,
    'the arrival time did not survive the companion path'
  );
  assert.notStrictEqual(
    typeof notif.time,
    'number',
    'the raw epoch leaked into `time`, which the renderer draws verbatim'
  );

  const removed = message(protocol.TYPES.NOTIFICATION_REMOVED, {
    type: protocol.TYPES.NOTIFICATION_REMOVED,
    id: 'com.example:7:null',
  });

  if (CompanionService.routeNotification) CompanionService.routeNotification(service, removed);
  assert.strictEqual(dismissed.length, 1, 'no notification-dismissed event');
  assert.strictEqual(dismissed[0].id, 'com.example:7:null', 'the dismissed id did not match');

  checkDismiss();
  checkOneRowPerNotification();

  console.log('companion notifications: ok');
}

run();
