#!/usr/bin/env node
/**
 * Clears previews that have not been looked at for a while.
 *
 * Run by the systemd timer (or by hand): every entry in the preview cache whose
 * last access is older than the window is deleted, along with a single
 * `index.json` rewrite. Files the user opened again inside the window keep
 * their full fresh window, because `lookup` refreshes `accessedAt` on every
 * hit - that is the whole point of a 30-day clock rather than a birthday.
 *
 *   node scripts/preview-cleanup.js [--days N] [--dir PATH] [--dry-run]
 */
'use strict';

const { PreviewCache, DEFAULT_DIR, DEFAULT_MAX_AGE_DAYS } = require('../services/preview-cache');

function parseArgs(argv) {
  const options = { days: DEFAULT_MAX_AGE_DAYS, dir: DEFAULT_DIR, dryRun: false };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '--days') options.days = Number(argv[++i]) || DEFAULT_MAX_AGE_DAYS;
    else if (arg.startsWith('--days=')) options.days = Number(arg.slice(7)) || DEFAULT_MAX_AGE_DAYS;
    else if (arg === '--dir') options.dir = argv[++i] || DEFAULT_DIR;
    else if (arg.startsWith('--dir=')) options.dir = arg.slice(6) || DEFAULT_DIR;
    else if (arg === '--dry-run') options.dryRun = true;
  }
  return options;
}

function main() {
  const { days, dir, dryRun } = parseArgs(process.argv.slice(2));
  const cache = new PreviewCache({ dir });

  const before = cache.size();
  if (dryRun) {
    // Nothing is deleted; just say what a real run would do.
    const stale = [];
    const cutoff = Date.now() - days * 24 * 60 * 60 * 1000;
    for (const [sha256, entry] of Object.entries(cache.load())) {
      const last = Number(entry.accessedAt) || Number(entry.createdAt) || 0;
      if (last <= cutoff) stale.push(entry.name || sha256);
    }
    console.log(`[preview-cleanup] dry run: ${stale.length} of ${before} would be removed`);
    for (const name of stale) console.log(`  - ${name}`);
    return;
  }

  const removed = cache.sweep(days);
  console.log(
    `[preview-cleanup] removed ${removed.length} of ${before} previews older than ${days} days (kept ${cache.size()})`
  );
  for (const item of removed) console.log(`  - ${item.name}`);
}

main();
