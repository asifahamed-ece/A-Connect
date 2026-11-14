#!/usr/bin/env node
/**
 * The mechanical half of "does this work without ADB?".
 *
 * The human checklist is in scripts/verify-without-adb.md. This script checks
 * the parts that can be checked from outside the app, so the checklist starts
 * from a known state instead of a guess:
 *
 *   1. No phone is attached over ADB. This is the assertion the whole thing
 *      rests on: with nothing on ADB, no clipboard, notification, file or
 *      picture can have come down the debugging path by accident.
 *   2. The desktop's companion port is open and serves the certificate the
 *      phone has pinned - the fingerprint is compared, not just the port, so
 *      "some other process is listening" cannot pass for the app.
 *   3. The kill switch is on. Off means dormant, and nothing else works.
 *   4. The phone is still paired, so the check does not need a re-pair.
 *
 * It does not check the features themselves: those need eyes and hands, and
 * pretending otherwise is how a checklist stops being believable.
 *
 *   node scripts/check-without-adb.js
 */
'use strict';

const fs = require('fs');
const os = require('os');
const path = require('path');
const tls = require('tls');
const { execFileSync } = require('child_process');

const { fingerprintOfDer } = require('../services/companion-cert');

const COMPANION_DIR = path.join(
  process.env.HOME || process.env.USERPROFILE || '.',
  '.config',
  'aplus-connect',
  'companion'
);
const CERT_FILE = path.join(COMPANION_DIR, 'cert.pem');
const SETTINGS_FILE = path.join(COMPANION_DIR, 'settings.json');
const PAIRED_FILE = path.join(COMPANION_DIR, 'paired-devices.json');

const DEFAULT_PORT = 24443;

const results = [];

/** Records one check and prints it straight away. */
function check(name, ok, detail) {
  results.push({ name, ok, detail });
  const mark = ok === null ? '?' : ok ? '✓' : '✗';
  console.log(`${mark} ${name}${detail ? ` — ${detail}` : ''}`);
}

/**
 * Is a phone attached over ADB right now?
 *
 * Any listed device counts, whatever its state. "unauthorized" and "offline"
 * are still a phone on the cable, and counting only the healthy ones would
 * let a plugged-in phone pass as "nothing attached".
 */
function adbDevices() {
  try {
    const output = execFileSync('adb', ['devices'], {
      encoding: 'utf8',
      timeout: 10_000,
      stdio: ['ignore', 'pipe', 'ignore'],
    });
    return output
      .split('\n')
      .slice(1)
      .map((line) => line.trim())
      .filter((line) => line && /\s/.test(line))
      .map((line) => {
        const [serial, state] = line.split(/\s+/);
        return { serial, state };
      });
  } catch (err) {
    // adb missing or refusing to start: that is not a pass, it is an unknown.
    return null;
  }
}

/**
 * Connects to the companion port and reads the certificate it serves.
 *
 * The certificate is compared with the one on disk, so a squatter on the port
 * cannot pass for the app.
 */
function probePort(port, timeoutMs = 4000) {
  return new Promise((resolve) => {
    const socket = tls.connect({
      port,
      host: '127.0.0.1',
      rejectUnauthorized: false,
      timeout: timeoutMs,
    });
    let done = false;
    const finish = (value) => {
      if (done) return;
      done = true;
      try {
        socket.destroy();
      } catch {}
      resolve(value);
    };

    socket.on('secureConnect', () => {
      try {
        const peer = socket.getPeerCertificate();
        finish({ ok: true, fingerprint: fingerprintOfDer(peer.raw) });
      } catch (err) {
        finish({ ok: false, error: err.message });
      }
    });
    socket.on('error', (err) => finish({ ok: false, error: err.code || err.message }));
    socket.on('timeout', () => finish({ ok: false, error: 'timeout' }));
  });
}

async function run() {
  console.log('A+ Connect — is this working WITHOUT ADB?\n');

  // 1. Nothing on ADB.
  const devices = adbDevices();
  if (devices === null) {
    check('No phone attached over ADB', null, 'adb is not available to check');
  } else if (devices.length === 0) {
    check('No phone attached over ADB', true, 'adb sees no device');
  } else {
    check(
      'No phone attached over ADB',
      false,
      `adb sees ${devices.length}: ${devices.map((d) => `${d.serial} (${d.state})`).join(', ')}`
    );
  }

  // 2. The companion port, and the certificate behind it.
  const port = (() => {
    try {
      const settings = JSON.parse(fs.readFileSync(SETTINGS_FILE, 'utf8'));
      return settings.port || DEFAULT_PORT;
    } catch {
      return DEFAULT_PORT;
    }
  })();

  const probe = await probePort(port);
  if (!probe.ok) {
    check('The companion port is open', false, `port ${port}: ${probe.error}`);
  } else {
    let expected = null;
    try {
      const pem = fs.readFileSync(CERT_FILE, 'utf8');
      const der = Buffer.from(
        pem.replace(/-----(BEGIN|END) CERTIFICATE-----/g, '').replace(/\s+/g, ''),
        'base64'
      );
      expected = fingerprintOfDer(der);
    } catch {
      expected = null;
    }

    if (!expected) {
      check('The companion port is open', true, `port ${port} (no certificate on disk to compare)`);
    } else if (probe.fingerprint === expected) {
      check(
        'The companion port serves the pinned certificate',
        true,
        `${probe.fingerprint.slice(0, 17)}… on port ${port}`
      );
    } else {
      check(
        'The companion port serves the pinned certificate',
        false,
        `the port serves ${probe.fingerprint.slice(0, 17)}…, the phone pinned ${expected.slice(0, 17)}…`
      );
    }
  }

  // 3. The kill switch.
  let enabled = true;
  try {
    enabled = JSON.parse(fs.readFileSync(SETTINGS_FILE, 'utf8')).enabled !== false;
  } catch {
    enabled = true;
  }
  check('The companion switch is on', enabled, enabled ? '' : 'the desktop is dormant');

  // 4. The phone is still paired.
  let paired = [];
  try {
    paired = Object.values(JSON.parse(fs.readFileSync(PAIRED_FILE, 'utf8')));
  } catch {
    paired = [];
  }
  check(
    'The phone is still paired',
    paired.length > 0,
    paired.length ? paired.map((d) => d.deviceName || d.deviceId).join(', ') : 'no paired device'
  );

  console.log('');
  const failed = results.filter((r) => r.ok === false);
  const unknown = results.filter((r) => r.ok === null);
  if (failed.length === 0 && unknown.length === 0) {
    console.log('The preconditions hold. Now walk scripts/verify-without-adb.md.');
  } else if (failed.length === 0) {
    console.log('The preconditions that could be checked hold. Some could not be.');
  } else {
    console.log(`${failed.length} precondition(s) failed - fix those before the walkthrough.`);
  }
  console.log('');
}

run().catch((e) => {
  console.error(e.message);
  process.exit(1);
});
