/**
 * Owner decision C (2026-09-26): the photo-check body's optional, code-written `setup_seen`
 * ({light, light_side, place, phone_height, product_side}) is read into `result.setupSeen`. Words
 * only; `product_side` is the creator's own left/centre/right or null. An older body without it
 * parses exactly as before (no `setupSeen` key).
 */
import { describe, expect, it } from 'vitest';

import { parseShootCheckFrameBody, photoCheckFromHistoryCard } from './meera-api';

const BASE = {
  what_i_see: 'You at a desk, window on the left.',
  steps: [{ kind: 'move_you', text: 'Turn to face the window.', note: 'window_light', label: 'Window light' }],
  ok: [],
  cant_tell: [],
  ask: null,
  lang: 'en',
  retake: false,
};

const SEEN = {
  light: 'window',
  light_side: 'your_left',
  place: 'desk',
  phone_height: 'eye_level',
  product_side: 'right',
};

describe('parseShootCheckFrameBody: setup_seen', () => {
  it('an older body without it parses exactly as before (no setupSeen key)', () => {
    const result = parseShootCheckFrameBody(BASE);
    expect('setupSeen' in result).toBe(false);
  });

  it('reads every field', () => {
    expect(parseShootCheckFrameBody({ ...BASE, setup_seen: SEEN }).setupSeen).toEqual({
      light: 'window',
      lightSide: 'your_left',
      place: 'desk',
      phoneHeight: 'eye_level',
      productSide: 'right',
    });
  });

  it('null fields stay null (the photo does not show them)', () => {
    const none = { light: null, light_side: null, place: null, phone_height: null, product_side: null };
    expect(parseShootCheckFrameBody({ ...BASE, setup_seen: none }).setupSeen).toEqual({
      light: null,
      lightSide: null,
      place: null,
      phoneHeight: null,
      productSide: null,
    });
  });

  it('a product side outside left/centre/right is null, never guessed; case is folded', () => {
    for (const bad of ['center', 'viewer_left', 'middle', 42, '']) {
      expect(parseShootCheckFrameBody({ ...BASE, setup_seen: { ...SEEN, product_side: bad } }).setupSeen?.productSide).toBeNull();
    }
    expect(parseShootCheckFrameBody({ ...BASE, setup_seen: { ...SEEN, product_side: 'Centre' } }).setupSeen?.productSide).toBe('centre');
  });

  it('a non-string or over-long word is null for that field only', () => {
    const seen = parseShootCheckFrameBody({
      ...BASE,
      setup_seen: { ...SEEN, light: { x: 0.2 }, place: 'p'.repeat(41), phone_height: '  ' },
    }).setupSeen!;
    expect(seen.light).toBeNull();
    expect(seen.place).toBeNull();
    expect(seen.phoneHeight).toBeNull();
    expect(seen.lightSide).toBe('your_left');
    expect(seen.productSide).toBe('right');
  });

  it('a setup_seen that is not an object is ignored (no setupSeen key)', () => {
    for (const bad of ['window, left', ['window'], null, 3]) {
      expect('setupSeen' in parseShootCheckFrameBody({ ...BASE, setup_seen: bad })).toBe(false);
    }
  });

  it('a card rebuilt from history keeps setup_seen (it is words, not geometry)', () => {
    const rebuilt = photoCheckFromHistoryCard({ kind: 'photo_check', v: 1, result: { ...BASE, setup_seen: SEEN } });
    expect(rebuilt?.result.setupSeen?.productSide).toBe('right');
  });
});
