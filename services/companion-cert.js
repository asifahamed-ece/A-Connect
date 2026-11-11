'use strict';

/**
 * The PC's self-signed certificate for the companion TLS server, plus its
 * SHA-256 fingerprint. The phone pins that fingerprint, so it is generated
 * once and reused for every connection.
 */

const { execFileSync } = require('child_process');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const DEFAULT_DIR = path.join(
  process.env.HOME || process.env.USERPROFILE || '.',
  '.config',
  'aplus-connect',
  'companion'
);

function ensureCertificate(dir = DEFAULT_DIR) {
  fs.mkdirSync(dir, { recursive: true });

  const certPath = path.join(dir, 'cert.pem');
  const keyPath = path.join(dir, 'key.pem');

  if (!fs.existsSync(certPath) || !fs.existsSync(keyPath)) {
    execFileSync('openssl', [
      'req', '-x509', '-newkey', 'rsa:2048', '-nodes',
      '-keyout', keyPath,
      '-out', certPath,
      '-days', '3650',
      // No spaces and no "+" in the subject: OpenSSL 4 parses a space as the
      // end of an RDN and "+" as a multi-valued RDN separator.
      '-subj', '/CN=APlusConnectCompanion',
    ], { stdio: 'ignore' });
  }

  return { certPath, keyPath, fingerprint: fingerprintOfFile(certPath) };
}

/** SHA-256 of the DER encoded certificate, uppercase colon separated hex. */
function fingerprintOfFile(certPath) {
  const pem = fs.readFileSync(certPath, 'utf8');
  const der = Buffer.from(
    pem.replace(/-----(BEGIN|END) CERTIFICATE-----/g, '').replace(/\s+/g, ''),
    'base64'
  );
  return fingerprintOfDer(der);
}

function fingerprintOfDer(der) {
  return crypto
    .createHash('sha256')
    .update(der)
    .digest('hex')
    .toUpperCase()
    .replace(/(..)(?=.)/g, '$1:');
}

module.exports = { ensureCertificate, fingerprintOfDer, DEFAULT_DIR };
