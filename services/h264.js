'use strict';

/**
 * The H.264 codec string WebCodecs wants, read out of the stream itself.
 *
 * `VideoDecoder` will not open without something like `avc1.64001F`: the
 * profile and the level decide which decoder it picks and what the frames are
 * allowed to contain. Guessing one is a trap - the day the phone's encoder
 * picks a different profile, playback stops with an error nobody will connect to
 * this file.
 *
 * So it is parsed from the SPS that travels in front of every keyframe (the
 * encoder is configured to prepend it). Three bytes is all it takes, which is
 * why this is a parser rather than a library.
 */

/** NAL unit type 7 is a sequence parameter set. */
const NAL_SPS = 7;

/**
 * Finds the payload of the first SPS in an Annex-B buffer.
 *
 * Annex-B separates NAL units with 00 00 01 or 00 00 00 01. Scanning for the
 * three-byte form catches both, since the four-byte form contains it.
 */
function findSps(buffer) {
  if (!buffer || buffer.length < 4) return null;

  let i = 0;
  while (i + 3 < buffer.length) {
    if (buffer[i] === 0 && buffer[i + 1] === 0 && buffer[i + 2] === 1) {
      const header = buffer[i + 3];
      const type = header & 0x1f;
      if (type === NAL_SPS) {
        return buffer.subarray(i + 4);
      }
      i += 3;
      continue;
    }
    i += 1;
  }
  return null;
}

/** "avc1" + profile + constraint flags + level, uppercase hex, e.g. avc1.64001F. */
function codecStringFromSps(sps) {
  if (!sps || sps.length < 3) return null;
  const hex = (byte) => byte.toString(16).padStart(2, '0').toUpperCase();
  return `avc1.${hex(sps[0])}${hex(sps[1])}${hex(sps[2])}`;
}

/**
 * The codec string for an Annex-B access unit, or null when it holds no SPS.
 *
 * Null is the caller's cue to use a fallback and keep going: a stream whose
 * first keyframe was dropped must not become a dead player.
 */
function codecStringFromAnnexB(buffer) {
  return codecStringFromSps(findSps(buffer));
}

/**
 * The string used when no SPS could be read: High profile, level 3.1.
 *
 * The desktop's own probe on this machine showed both baseline (42E01E) and
 * high (64001F) decode, so this is a safe default rather than a guess that only
 * works on one machine.
 */
const FALLBACK_CODEC = 'avc1.64001F';

module.exports = { codecStringFromAnnexB, codecStringFromSps, findSps, FALLBACK_CODEC };