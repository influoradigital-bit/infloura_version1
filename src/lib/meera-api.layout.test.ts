/**
 * The photo-check body's `layout` and `checks` (spec 2.2 + Phase 4): the `normalizePoint` /
 * `normalizeBox` twin of influora-ai's validator drops a bad item on its own and keeps the rest of
 * the reply; an older body without the keys parses exactly as before.
 */
import { describe, expect, it } from 'vitest';

import { SHOOT_CHECK_MAX_FACES, normalizeBox, normalizePoint, parseShootCheckFrameBody } from './meera-api';

const BASE = {
  what_i_see: 'You at a desk, window on the left.',
  steps: [{ kind: 'move_you', text: 'Turn to face the window.', note: 'window_light', label: 'Window light' }],
  ok: [],
  cant_tell: [],
  ask: null,
  lang: 'en',
  retake: false,
};

describe('normalizeBox / normalizePoint (spec 2.2 twin)', () => {
  it('keeps a valid box and rounds to 3 decimals', () => {
    expect(normalizeBox({ x: 0.12345, y: 0.2, w: 0.30049, h: 0.4 })).toEqual({ x: 0.123, y: 0.2, w: 0.3, h: 0.4 });
    expect(normalizeBox({ x: 0, y: 0, w: 1, h: 1 })).toEqual({ x: 0, y: 0, w: 1, h: 1 });
    expect(normalizeBox({ x: 0.5, y: 0.5, w: 0.5005, h: 0.5 })).not.toBeNull(); // x + w = 1.0005 <= 1.001
  });

  it.each([
    ['null', null],
    ['an array', [0.1, 0.1, 0.2, 0.2]],
    ['a string', 'x'],
    ['a missing key', { x: 0.1, y: 0.1, w: 0.2 }],
    ['an extra key', { x: 0.1, y: 0.1, w: 0.2, h: 0.2, label: 'face' }],
    ['NaN', { x: Number.NaN, y: 0.1, w: 0.2, h: 0.2 }],
    ['Infinity', { x: 0.1, y: 0.1, w: Number.POSITIVE_INFINITY, h: 0.2 }],
    ['a string number', { x: '0.1', y: 0.1, w: 0.2, h: 0.2 }],
    ['a boolean', { x: true, y: 0.1, w: 0.2, h: 0.2 }],
    ['a null value', { x: null, y: 0.1, w: 0.2, h: 0.2 }],
    ['x above 1', { x: 1.4, y: 0.1, w: 0.2, h: 0.2 }],
    ['y below 0', { x: 0.1, y: -0.01, w: 0.2, h: 0.2 }],
    ['w at 0.02', { x: 0.1, y: 0.1, w: 0.02, h: 0.2 }],
    ['h above 1', { x: 0, y: 0, w: 0.2, h: 1.01 }],
    ['x + w past 1.001', { x: 0.5, y: 0.1, w: 0.502, h: 0.2 }],
    ['y + h past 1.001', { x: 0.1, y: 0.9, w: 0.2, h: 0.2 }],
  ])('drops a box with %s', (_name, value) => {
    expect(normalizeBox(value)).toBeNull();
  });

  it('JSON Infinity (1e999) is rejected, as json.loads Infinity is in Python', () => {
    const parsed = JSON.parse('{"x": 1e999, "y": 0.1, "w": 0.2, "h": 0.2}');
    expect(normalizeBox(parsed)).toBeNull();
  });

  it('points: x and y only, finite, in [0, 1]', () => {
    expect(normalizePoint({ x: 0.3333, y: 0.6667 })).toEqual({ x: 0.333, y: 0.667 });
    expect(normalizePoint({ x: 0.3, y: 1.4 })).toBeNull();
    expect(normalizePoint({ x: 0.3, y: Number.NaN })).toBeNull();
    expect(normalizePoint({ x: 0.3, y: '0.2' })).toBeNull();
    expect(normalizePoint({ x: 0.3, y: 0.2, w: 0.1 })).toBeNull();
    expect(normalizePoint(null)).toBeNull();
  });
});

describe('parseShootCheckFrameBody: layout and checks', () => {
  it('an older body without the keys parses exactly as before (no layout, no checks keys)', () => {
    const result = parseShootCheckFrameBody(BASE);
    expect('layout' in result).toBe(false);
    expect('checks' in result).toBe(false);
    expect(result.steps).toHaveLength(1);
  });

  it('keeps valid faces and the product, drops only the bad items, ignores unknown keys', () => {
    const result = parseShootCheckFrameBody({
      ...BASE,
      layout: {
        faces: [{ x: 0.1, y: 0.1, w: 0.2, h: 0.2 }, { x: Number.NaN, y: 0, w: 0.1, h: 0.1 }, 'face', { x: 0.5, y: 0.2, w: 0.1, h: 0.1 }],
        product: { x: 0.6, y: 0.5, w: 0.2, h: 0.2 },
        people: 3,
      },
    });
    expect(result.layout).toEqual({
      faces: [
        { x: 0.1, y: 0.1, w: 0.2, h: 0.2 },
        { x: 0.5, y: 0.2, w: 0.1, h: 0.1 },
      ],
      product: { x: 0.6, y: 0.5, w: 0.2, h: 0.2 },
    });
    // The text is untouched by the geometry.
    expect(result.whatISee).toBe(BASE.what_i_see);
    expect(result.steps[0].text).toBe('Turn to face the window.');
  });

  it(`keeps at most ${SHOOT_CHECK_MAX_FACES} faces, whole items in order`, () => {
    const faces = Array.from({ length: 11 }, (_, i) => ({ x: i * 0.05, y: 0.1, w: 0.04, h: 0.1 }));
    const result = parseShootCheckFrameBody({ ...BASE, layout: { faces, product: null } });
    expect(result.layout?.faces).toHaveLength(SHOOT_CHECK_MAX_FACES);
    expect(result.layout?.faces[7]).toEqual({ x: 0.35, y: 0.1, w: 0.04, h: 0.1 });
  });

  it('a bad product reads as none; entirely invalid geometry still returns all the text', () => {
    const result = parseShootCheckFrameBody({
      ...BASE,
      layout: { faces: [{ x: 2, y: 0, w: 0.1, h: 0.1 }], product: [0.1, 0.1, 0.2, 0.2] },
    });
    expect(result.layout).toEqual({ faces: [], product: null });
    expect(result.whatISee).toBe(BASE.what_i_see);
    expect(parseShootCheckFrameBody({ ...BASE, layout: 'faces' }).layout).toBeUndefined();
    expect(parseShootCheckFrameBody({ ...BASE, layout: [] }).layout).toBeUndefined();
  });

  it('checks: trimmed strings only', () => {
    const result = parseShootCheckFrameBody({
      ...BASE,
      checks: ['  Your head is at the top edge. ', 4, '', null, 'The product is very close to the lens.'],
    });
    expect(result.checks).toEqual(['Your head is at the top edge.', 'The product is very close to the lens.']);
    expect('checks' in parseShootCheckFrameBody({ ...BASE, checks: 'nope' })).toBe(false);
  });
});
