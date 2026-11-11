#!/usr/bin/env node
/**
 * Fails fast with a helpful message when npm dependencies are missing.
 * Run automatically by `npm start` (prestart hook).
 */
const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..');
const required = [
  '@yume-chan/adb',
  '@yume-chan/adb-scrcpy',
  '@yume-chan/adb-server-node-tcp',
  '@yume-chan/scrcpy',
];

const missing = required.filter(
  (name) => !fs.existsSync(path.join(root, 'node_modules', name)),
);

if (missing.length > 0) {
  console.error('\nMissing npm dependencies:\n');
  for (const name of missing) console.error(`  - ${name}`);
  console.error('\nRun `npm install` in the project directory first.\n');
  process.exit(1);
}
