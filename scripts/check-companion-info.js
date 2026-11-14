#!/usr/bin/env node
/**
 * The companion channel's device info.
 *
 * A `device-info` message must become a `device-info` event carrying the
 * battery, OS version, storage and model, and the service must remember it so
 * `getInfo()` hands it to the renderer. Without this the device cards sit on
 * dashes whenever only the encrypted channel is connected, which is the whole
 * point of the feature.
 *
 * node scripts/check-companion-info.js
 */
'use strict';

const assert = require('assert');
const { EventEmitter } = require('events');

const protocol = require('../services/companion-protocol');
const CompanionService = require('../services/companion');

function run() {
  const service = new EventEmitter();

  const received = [];
  service.on('device-info', (info) => received.push(info));

  const payload = protocol.buildDeviceInfo({
    batteryLevel: 82,
    isCharging: true,
    androidVersion: '14',
    colorOsVersion: 'ColorOS 14.0',
    storageFree: '37 GB',
    storageTotal: '128 GB',
    model: 'CPH2737',
  });

  if (CompanionService.routeDeviceInfo) {
    CompanionService.routeDeviceInfo(service, {
      type: protocol.TYPES.DEVICE_INFO,
      id: 'd1',
      ts: Date.now(),
      payload,
    });
  }

  assert.strictEqual(received.length, 1, 'no device-info event');
  assert.strictEqual(received[0].batteryLevel, 82, 'the battery level did not survive');
  assert.strictEqual(received[0].isCharging, true, 'the charging flag did not survive');
  assert.strictEqual(received[0].androidVersion, '14', 'the Android version did not survive');
  assert.strictEqual(received[0].colorOsVersion, 'ColorOS 14.0', 'the ColorOS version did not survive');
  assert.strictEqual(received[0].storageFree, '37 GB', 'the free storage did not survive');
  assert.strictEqual(received[0].model, 'CPH2737', 'the model did not survive');

  // The service remembers it, so a renderer that asks for the info gets the
  // vitals alongside the connection state.
  assert.ok(service.deviceInfo, 'the service did not remember the device info');
  assert.strictEqual(service.deviceInfo.batteryLevel, 82, 'the remembered battery is wrong');

  // A second publish updates in place - the battery moves, the rest stays.
  CompanionService.routeDeviceInfo(service, {
    type: protocol.TYPES.DEVICE_INFO,
    id: 'd2',
    ts: Date.now(),
    payload: protocol.buildDeviceInfo({
      batteryLevel: 80,
      isCharging: false,
      androidVersion: '14',
      colorOsVersion: 'ColorOS 14.0',
      storageFree: '36 GB',
      storageTotal: '128 GB',
      model: 'CPH2737',
    }),
  });
  assert.strictEqual(received.length, 2, 'the second publish was dropped');
  assert.strictEqual(received[1].batteryLevel, 80, 'the battery update did not land');
  assert.strictEqual(service.deviceInfo.storageFree, '36 GB', 'the storage update did not land');

  // A malformed message is ignored rather than producing a blank card.
  const before = received.length;
  CompanionService.routeDeviceInfo(service, {
    type: protocol.TYPES.DEVICE_INFO,
    id: 'd3',
    ts: Date.now(),
    payload: null,
  });
  assert.strictEqual(received.length, before, 'a message with no payload produced an event');

  // And a message that is not device-info is not claimed.
  const claimed = CompanionService.routeDeviceInfo(service, {
    type: protocol.TYPES.MEDIA_STATE,
    id: 'x',
    ts: Date.now(),
    payload: { title: 'Song' },
  });
  assert.strictEqual(claimed, false, 'a media-state was claimed as device-info');

  console.log('companion info: ok');
}

run();
