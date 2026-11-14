'use strict';

/**
 * The preview cache: the files the user has glanced at, kept until they age out.
 *
 * A preview is not a download. Looking at a file on the phone should not put it
 * in Downloads, and looking at the same file twice should not pull it across
 * the network twice. So previewed files land here, keyed by their SHA256, and
 * the hash the phone reports is what decides a hit.
 *
 * The cache is content addressed, so it can answer "do we already have these
 * exact bytes" without knowing the phone's filename, and it can hand the file
 * straight to the desktop's opener because the real name and extension are
 * kept alongside. Everything lives under one directory so a single sweep can
 * reclaim it.
 *
 * No part of this touches the network or the phone: given a hash and a name it
 * says where the file should live and whether it is already there.
 */

const crypto = require('crypto');
const fs = require('fs');
const os = require('os');
const path = require('path');

/** Where the cache lives when nothing else is said. */
const DEFAULT_DIR = path.join(
  process.env.XDG_CACHE_HOME || path.join(os.homedir(), '.cache'),
  'a-plus-connect',
  'preview'
);

/** How long an untouched preview is kept. */
const DEFAULT_MAX_AGE_DAYS = 30;
const SHA256_PATTERN = /^[a-f0-9]{64}$/;

function isSha256(value) {
  return typeof value === 'string' && SHA256_PATTERN.test(value);
}

function sha256File(filePath) {
  const hash = crypto.createHash('sha256');
  const buffer = Buffer.allocUnsafe(64 * 1024);
  const fd = fs.openSync(filePath, 'r');
  try {
    let bytesRead;
    let position = 0;
    while ((bytesRead = fs.readSync(fd, buffer, 0, buffer.length, position)) > 0) {
      hash.update(buffer.subarray(0, bytesRead));
      position += bytesRead;
    }
    return hash.digest('hex');
  } finally {
    fs.closeSync(fd);
  }
}

/**
 * A filename that cannot escape the cache directory.
 *
 * The name comes from the phone, so it is untrusted: a name with `..` or a
 * slash in it must not be able to write outside the entry's own folder.
 */
function safeName(name) {
  const base = path.basename(String(name || 'file'));
  const cleaned = base.replace(/[^a-zA-Z0-9._ -]/g, '_').trim();
  // "." and ".." are not filenames, whatever path.basename returned.
  if (!cleaned || cleaned === '.' || cleaned === '..') return 'file';
  return cleaned;
}

class PreviewCache {
  constructor(options = {}) {
    this.dir = options.dir || DEFAULT_DIR;
    this.indexFile = path.join(this.dir, 'index.json');
    /** Parsed once and written through; the file is the durable copy. */
    this.entries = null;
  }

  /** The in-memory index, loaded from disk on first use. */
  load() {
    if (this.entries) return this.entries;
    try {
      const raw = fs.readFileSync(this.indexFile, 'utf8');
      const parsed = JSON.parse(raw);
      this.entries = parsed && typeof parsed.entries === 'object' ? parsed.entries : {};
    } catch {
      // A missing or unreadable index is an empty cache, not an error: the
      // data itself is on disk, and a lost index only costs a re-download.
      this.entries = {};
    }
    return this.entries;
  }

  /** Writes the index back. Best effort: a failed write is not fatal. */
  save() {
    try {
      fs.mkdirSync(this.dir, { recursive: true });
      const tmp = `${this.indexFile}.${process.pid}.tmp`;
      fs.writeFileSync(tmp, JSON.stringify({ format: 1, entries: this.entries }, null, 2));
      fs.renameSync(tmp, this.indexFile);
    } catch (e) {
      console.error(`[preview-cache] could not save the index: ${e.message}`);
    }
  }

  /** Where one hash's file lives: its own folder, under its real name. */
  pathFor(sha256, name) {
    if (!isSha256(sha256)) throw new Error('invalid SHA256 cache key');
    return path.join(this.dir, sha256, safeName(name));
  }

  /**
   * The cached file for a hash, or null.
   *
   * A hit refreshes `accessedAt`, which is what the sweep measures, so looking
   * at a file again keeps it for another full window. The file is only a hit if
   * it is still on disk and matches its content key: a user who deleted or
   * edited it in their file manager asked for a fresh copy next time.
   */
  lookup(sha256) {
    if (!isSha256(sha256)) return null;
    const entries = this.load();
    const entry = entries[sha256];
    if (!entry) return null;

    const expectedPath = this.pathFor(sha256, entry.name);
    let actualHash = null;
    try {
      if (entry.path === expectedPath) {
        actualHash = sha256File(expectedPath);
      }
    } catch {
      /* missing or unreadable cache files are misses */
    }
    if (actualHash !== sha256) {
      delete entries[sha256];
      this.save();
      return null;
    }

    entry.accessedAt = Date.now();
    this.save();
    return { ...entry, path: expectedPath };
  }

  /**
   * Reserves the target path for a file about to be downloaded.
   *
   * Called before the transfer so the writer has somewhere to put it; the
   * entry only counts as usable once `commit` records it. A file whose hash
   * the phone did not report still gets a home - under a pending key the sweep
   * will clear - so the preview works even on a slow answer.
   */
  reserve(sha256, name) {
    if (sha256 && !isSha256(sha256)) throw new Error('invalid SHA256 cache key');
    const key = sha256 || `_pending-${crypto.randomUUID().slice(0, 8)}`;
    const targetPath = sha256
      ? this.pathFor(key, name)
      : path.join(this.dir, key, safeName(name));
    fs.mkdirSync(path.dirname(targetPath), { recursive: true });
    return { targetPath, key };
  }

  /** Records a finished preview, so the next look is a hit. */
  commit(sha256, { name, size, phonePath, sourcePath }) {
    // A pending key is not content addressed, so there is nothing to look it up
    // by later; the file still lives on disk for this session, and the sweep
    // reclaims the folder.
    if (!sha256) return;
    if (!isSha256(sha256)) throw new Error('invalid SHA256 cache key');
    const targetPath = this.pathFor(sha256, name);
    if (sourcePath && sourcePath !== targetPath) {
      fs.mkdirSync(path.dirname(targetPath), { recursive: true });
      fs.renameSync(sourcePath, targetPath);
    }
    const entries = this.load();
    const now = Date.now();
    entries[sha256] = {
      name: safeName(name),
      size: typeof size === 'number' ? size : 0,
      phonePath: phonePath || '',
      path: targetPath,
      createdAt: entries[sha256]?.createdAt || now,
      accessedAt: now,
    };
    this.save();
  }

  /** Forgets one entry and deletes its file. */
  forget(sha256) {
    const entries = this.load();
    const entry = entries[sha256];
    if (!entry) return false;
    try {
      if (isSha256(sha256)) {
        fs.rmSync(path.join(this.dir, sha256), { recursive: true, force: true });
      }
    } catch (e) {
      console.error(`[preview-cache] could not delete ${sha256}: ${e.message}`);
    }
    delete entries[sha256];
    this.save();
    return true;
  }

  /**
   * Deletes everything not accessed within the window.
   *
   * Returns what it removed so a caller can report it. An entry whose file is
   * already gone is still removed from the index.
   */
  sweep(maxAgeDays = DEFAULT_MAX_AGE_DAYS) {
    const entries = this.load();
    const cutoff = Date.now() - maxAgeDays * 24 * 60 * 60 * 1000;
    const removed = [];

    for (const [sha256, entry] of Object.entries(entries)) {
      const last = Number(entry.accessedAt) || Number(entry.createdAt) || 0;
      if (last > cutoff) continue;
      try {
        if (isSha256(sha256)) {
          fs.rmSync(path.join(this.dir, sha256), { recursive: true, force: true });
        }
      } catch (e) {
        console.error(`[preview-cache] could not delete ${sha256}: ${e.message}`);
      }
      removed.push({ sha256, name: entry.name });
      delete entries[sha256];
    }

    // Whatever else is left in the directory and not in the index is orphaned -
    // a crash between writing a file and recording it. Clear those too, so the
    // folder cannot grow without a record to sweep it.
    try {
      for (const name of fs.readdirSync(this.dir)) {
        if (name === 'index.json' || name.endsWith('.tmp')) continue;
        const full = path.join(this.dir, name);
        if (entries[name]) continue;
        if (!fs.statSync(full).isDirectory()) continue;
        const age = Date.now() - fs.statSync(full).mtimeMs;
        if (age <= maxAgeDays * 24 * 60 * 60 * 1000) continue;
        fs.rmSync(full, { recursive: true, force: true });
        removed.push({ sha256: name, name: '(orphan)' });
      }
    } catch {
      /* the directory may not exist yet; nothing to clear */
    }

    this.save();
    return removed;
  }

  /** How many entries the index holds. */
  size() {
    return Object.keys(this.load()).length;
  }
}

module.exports = { PreviewCache, DEFAULT_DIR, DEFAULT_MAX_AGE_DAYS, safeName, isSha256, sha256File };
