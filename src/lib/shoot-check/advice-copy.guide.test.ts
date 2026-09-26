/**
 * Spec 2.7 copy scan: every Reel layout / camera guide string (`GUIDE_COPY`, both languages) is free
 * of `@` handles, third-party creator names and the banned unproven claims, and uses the approved
 * spec 2.8 lines word for word. The contract keys the camera builder reads all exist.
 */
import { describe, expect, it } from 'vitest';

import { GUIDE_COPY, adviceText } from './advice-copy';

const ALL_LINES: Array<[string, string]> = Object.entries(GUIDE_COPY).flatMap(([key, entry]) =>
  Object.entries(entry).map(([lang, text]) => [`${key}.${lang}`, text] as [string, string]),
);

/** Unproven claims and house-banned words (spec 2.7 / 2.5). Case-insensitive. */
const BANNED: RegExp[] = [
  /found in nature/i,
  /divine proportion/i,
  /golden ratio/i,
  /fibonacci/i,
  /\b10\s*[x×]/i,
  /0\.5 seconds?/i,
  /\bkills?\b/i,
  /\bviral\b/i,
  /\breach\b/i,
  /engagement/i,
  /more views/i,
  /algorithm/i,
  /guarantee/i,
  /escrow/i,
  /co-?pilot/i,
  /alessiolr|mansourmelouli|editorsinventory/i,
];

describe('guide copy scan (spec 2.7)', () => {
  it('scans real strings (not an empty table)', () => {
    expect(ALL_LINES.length).toBeGreaterThan(80);
    for (const [, text] of ALL_LINES) expect(text.trim().length).toBeGreaterThan(0);
  });

  it('has no @ handle in any line', () => {
    const hits = ALL_LINES.filter(([, text]) => /@\w/.test(text)).map(([id]) => id);
    expect(hits).toEqual([]);
  });

  it('has none of the banned claims or words', () => {
    const hits = ALL_LINES.flatMap(([id, text]) => BANNED.filter((re) => re.test(text)).map((re) => `${id} ~ ${re}`));
    expect(hits).toEqual([]);
  });

  it('uses the approved spec 2.8 English word for word', () => {
    expect(adviceText('safe_zone_note', 'en-IN')).toBe(
      'The green area keeps your text visible. Instagram’s buttons and captions cover the red areas — check your preview before posting.',
    );
    expect(adviceText('hook_slot', 'en-IN')).toBe('Put your hook text here, above your head.');
    expect(adviceText('device_note', 'en-IN')).toBe(
      'Every phone and app version is a little different — always check your Reel preview before you post.',
    );
    expect(adviceText('grid_setting_label', 'en-IN')).toBe('Camera grid');
    expect(adviceText('grid_option_thirds', 'en-IN')).toBe('Rule of thirds');
    expect(adviceText('grid_option_golden', 'en-IN')).toBe('Golden grid');
    expect(adviceText('grid_option_off', 'en-IN')).toBe('Off');
    expect(adviceText('zone_label_top', 'en-IN')).toBe('Covered: app top bar');
    expect(adviceText('zone_label_bottom', 'en-IN')).toBe('Covered: username, caption, music');
    expect(adviceText('zone_label_cta', 'en-IN')).toBe('Short CTA, left side');
    expect(adviceText('reel_layout_title', 'en-IN')).toBe('Influora’s Reel layout guide');
  });

  it('has every contract key the camera guide reads, in both languages, Hindi in Devanagari', () => {
    const contract = [
      'grid_setting_label',
      'grid_option_thirds',
      'grid_option_golden',
      'grid_option_off',
      'grid_default_tag',
      'safe_zone_note',
      'hook_slot',
      'device_note',
      'safe_zone_always_on',
      'guides_button',
      'shot_guides_label',
      'guide_remembered',
      'zone_label_top',
      'zone_label_bottom',
      'zone_label_cta',
      'got_it',
      'setup_line_prefix',
      'stand_other_line',
    ];
    for (const key of contract) {
      expect(adviceText(key, 'en-IN'), key).not.toBe(key);
      const hi = adviceText(key, 'hi-IN');
      expect(hi, key).not.toBe(key);
      expect(/[ऀ-ॿ]/.test(hi), `${key} hi-IN is Devanagari`).toBe(true);
    }
    expect(adviceText('guide_remembered', 'en-IN')).toBe('Your choice is remembered on this phone.');
    expect(adviceText('safe_zone_always_on', 'en-IN')).toBe('Safe zone: Always on');
    expect(adviceText('setup_line_prefix', 'en-IN')).toBe('Set-up for this shot:');
    expect(adviceText('stand_other_line', 'en-IN')).toBe('Stand on the other line.');
  });

  it('existing advice keys still resolve, and an unknown key falls back to itself', () => {
    expect(adviceText('ok', 'en-IN')).toBe('Looking good');
    expect(adviceText('no-such-key', 'hi-IN')).toBe('no-such-key');
  });
});
