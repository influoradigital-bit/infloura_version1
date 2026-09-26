/**
 * Shot card copy (spec v2 Phase 6): every value of the wire contract has plain words in both
 * languages, anything outside the contract (or `?`) is "not set" and never guessed, and every line
 * passes the house copy rules. The enum lists are read from the parser (`meera-result-cards.ts`),
 * not retyped, so a value added there without words here fails this file.
 */
import { describe, expect, it } from 'vitest';

import {
  CAMERA_HEIGHTS,
  EYE_LINES,
  HEADROOMS,
  LIGHT_KINDS,
  LIGHT_SIDES,
  MOVEMENTS,
  NEGATIVE_SPACES,
  PROP_SIDES,
  PROP_SURFACES,
  SHOT_CARD_KEYS,
  SHOT_SIZES,
  STAND_POSITIONS,
  TEXT_POSITIONS,
} from '@/lib/meera-result-cards';

import {
  SHOT_CARD_COPY,
  SHOT_CARD_FIELDS,
  SHOT_CARD_LABELS,
  allShotCardCopyLines,
  fillShotCardCopy,
  shotCardFieldText,
  type ShotCardField,
  type ShootCheckLang,
} from './advice-copy';

const LANGS: ShootCheckLang[] = ['en-IN', 'hi-IN'];
const DEVANAGARI = /[ऀ-ॿ]/;

const ENUMS: Array<[ShotCardField, readonly string[]]> = [
  ['size', SHOT_SIZES],
  ['height', CAMERA_HEIGHTS],
  ['stand', STAND_POSITIONS],
  ['headroom', HEADROOMS],
  ['eyes', EYE_LINES],
  ['space', NEGATIVE_SPACES],
  ['text', TEXT_POSITIONS],
  ['move', MOVEMENTS],
  ['light', LIGHT_KINDS],
  ['light', LIGHT_KINDS.flatMap((kind) => LIGHT_SIDES.map((side) => `${kind}-${side}`))],
  ['prop', ['none', ...PROP_SIDES.flatMap((side) => PROP_SURFACES.map((surface) => `${side}-${surface}`))]],
];

describe('shot card fields', () => {
  it('lists the 13 keys in the wire order the parser uses', () => {
    expect([...SHOT_CARD_FIELDS]).toEqual([...SHOT_CARD_KEYS]);
    for (const field of SHOT_CARD_FIELDS) {
      for (const lang of LANGS) expect(SHOT_CARD_LABELS[field][lang].trim()).not.toBe('');
    }
  });

  it('has plain words for every enum value of the contract, in both languages', () => {
    const missing: string[] = [];
    for (const [field, values] of ENUMS) {
      for (const value of values) {
        for (const lang of LANGS) {
          const text = shotCardFieldText(field, value, lang);
          if (!text) missing.push(`${field}=${value} (${lang})`);
          else if (lang === 'hi-IN' && !DEVANAGARI.test(text)) missing.push(`${field}=${value} not Devanagari`);
        }
      }
    }
    expect(missing).toEqual([]);
  });

  it('writes the owner examples in plain words, sides always the creator\'s own', () => {
    expect(shotCardFieldText('height', 'eye', 'en-IN')).toBe('eye level');
    expect(shotCardFieldText('light', 'window-left', 'en-IN')).toBe('window, your left');
    expect(shotCardFieldText('light', 'ring_light', 'en-IN')).toBe('ring light');
    expect(shotCardFieldText('prop', 'right-hand', 'en-IN')).toBe('in your right hand');
    expect(shotCardFieldText('prop', 'centre-table', 'en-IN')).toBe('on the table, in the middle');
    expect(shotCardFieldText('size', 'MCU', 'en-IN')).toBe('Medium close-up (chest up)');
    expect(shotCardFieldText('light', 'window-left', 'hi-IN')).toBe('खिड़की, आपकी बाईं ओर');
    expect(shotCardFieldText('prop', 'right-hand', 'hi-IN')).toBe('आपके दाएँ हाथ में');
  });

  it('treats ?, blank, missing and out-of-contract values as not set (never guessed)', () => {
    const notSet: Array<[ShotCardField, string | undefined | null]> = [
      ['size', '?'],
      ['size', ' ? '],
      ['size', ''],
      ['size', undefined],
      ['size', null],
      ['size', 'mcu'], // enum values are exact, as the contract spells them
      ['size', 'CLOSE'],
      ['height', 'waist'],
      ['light', 'candle'],
      ['light', 'window-up'],
      ['light', 'window-'],
      ['light', '-left'],
      ['prop', 'right-shelf'],
      ['prop', 'right'],
      ['prop', 'hand'],
      ['move', 'run'],
      ['space', 'bottom'],
      ['text', 'bottom'],
      ['distance', '?'],
      ['distance', 'x'.repeat(21)],
      ['place', 'y'.repeat(41)],
      ['background', 'z'.repeat(41)],
      ['size', 'toString'], // an Object.prototype key is not a value
    ];
    for (const [field, value] of notSet) {
      expect({ field, value, text: shotCardFieldText(field, value, 'en-IN') }).toEqual({ field, value, text: undefined });
    }
  });

  it('shows free text as written, up to its limit in code points', () => {
    expect(shotCardFieldText('distance', '0.8-1 m', 'en-IN')).toBe('0.8-1 m');
    expect(shotCardFieldText('distance', 'x'.repeat(20), 'en-IN')).toBe('x'.repeat(20));
    expect(shotCardFieldText('place', 'बेडरूम की टेबल', 'hi-IN')).toBe('बेडरूम की टेबल');
    expect(shotCardFieldText('background', 'p'.repeat(40), 'hi-IN')).toBe('p'.repeat(40));
  });

  it('fills the prefill template', () => {
    expect(
      fillShotCardCopy(SHOT_CARD_COPY.ask_prefill['en-IN'], { n: 2, time: '3-8s', fields: 'movement' }),
    ).toBe('Shot card for beat 2 (3-8s): ask me what you need to fill in movement.');
  });
});

/** House rules (spec 2.5 / 2.7). Case-insensitive. */
const BANNED: RegExp[] = [
  /escrow/i,
  /co-?pilot/i,
  /\bviral\b/i,
  /\breach\b/i,
  /engagement/i,
  /more views/i,
  /algorithm/i,
  /guarantee/i,
];

describe('shot card copy scan', () => {
  const lines = allShotCardCopyLines();

  it('scans real strings (not an empty table)', () => {
    // 6 copy lines + 13 labels + 7 light kinds + 4 sides + 10 props + 36 enum words, x2 languages.
    expect(lines.length).toBe((6 + 13 + 7 + 4 + 10 + 36) * 2);
    for (const [, text] of lines) expect(text.trim().length).toBeGreaterThan(0);
  });

  it('has no @ handle and none of the banned words', () => {
    const hits = lines.flatMap(([id, text]) => [
      ...(/@\w/.test(text) ? [`${id} ~ @`] : []),
      ...BANNED.filter((re) => re.test(text)).map((re) => `${id} ~ ${re}`),
    ]);
    expect(hits).toEqual([]);
  });

  it('writes every hi-IN line in Devanagari', () => {
    const latinOnly = lines.filter(([id, text]) => id.endsWith('.hi-IN') && !DEVANAGARI.test(text)).map(([id]) => id);
    expect(latinOnly).toEqual([]);
  });

  it('says left and right only as the creator\'s own ("your left")', () => {
    const bare = lines
      .filter(([id, text]) => id.endsWith('.en-IN') && /\b(left|right)\b/i.test(text) && !/\byour\b/i.test(text))
      .map(([id]) => id);
    expect(bare).toEqual([]);
  });
});
