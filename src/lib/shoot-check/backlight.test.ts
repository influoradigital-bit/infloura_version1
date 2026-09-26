/**
 * backlight.ts (spec Phase 4, "Bright window behind the face"): `metrics.averageLuma` of the face box
 * against a ring 0.5 x face-width around it; ring >= 75 and ring - face >= 25 flags it. Synthetic
 * buffers only.
 */
import { describe, expect, it } from 'vitest';

import { BACKLIGHT_GAP_MIN, BACKLIGHT_RING_MIN, backlightReading, isBacklit, type PixelBuffer } from './backlight';

const FACE = { x: 0.4, y: 0.3, w: 0.2, h: 0.2 };

/** A grey buffer: `ring` everywhere, `face` inside the face box (shares of a W x H frame). */
function buffer(face: number, ring: number, width = 100, height = 100, box = FACE, outside = ring): PixelBuffer {
  const data = new Uint8ClampedArray(width * height * 4);
  // Whole-pixel edges (0.4 * 100 is 40.000000000000004 in floating point).
  const x0 = Math.round(box.x * width);
  const x1 = Math.round((box.x + box.w) * width);
  const y0 = Math.round(box.y * height);
  const y1 = Math.round((box.y + box.h) * height);
  const reach = Math.round(0.5 * box.w * width);
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const inFace = x >= x0 && x < x1 && y >= y0 && y < y1;
      const inRing = x >= x0 - reach && x < x1 + reach && y >= y0 - reach && y < y1 + reach;
      const v = inFace ? face : inRing ? ring : outside;
      data.set([v, v, v, 255], (y * width + x) * 4);
    }
  }
  return { data, width, height };
}

/** Grey level v as 0..100 luma (BT.601 weights sum to 1 on a grey pixel). */
const luma = (v: number) => (v / 255) * 100;

describe('backlight check', () => {
  it('a bright window behind a dark face is flagged', () => {
    const reading = backlightReading(buffer(60, 240), FACE);
    expect(reading?.face).toBeCloseTo(luma(60), 5);
    expect(reading?.ring).toBeCloseTo(luma(240), 5);
    expect(isBacklit(buffer(60, 240), FACE)).toBe(true);
  });

  it('only the ring counts: a bright frame far from the face does not flag', () => {
    // Ring as dark as the face; the rest of the frame blown out.
    expect(isBacklit(buffer(60, 60, 100, 100, FACE, 255), FACE)).toBe(false);
  });

  it('an evenly bright scene is not flagged (no gap)', () => {
    expect(isBacklit(buffer(230, 240), FACE)).toBe(false);
  });

  it(`a gap under ${BACKLIGHT_GAP_MIN} is not flagged, just over it is`, () => {
    // ring 230 (90.2); face 170 (66.7): gap 23.5 -> no. face 160 (62.7): gap 27.5 -> yes.
    expect(isBacklit(buffer(170, 230), FACE)).toBe(false);
    expect(isBacklit(buffer(160, 230), FACE)).toBe(true);
  });

  it(`a ring under ${BACKLIGHT_RING_MIN} is not flagged even with a big gap, at or over it is`, () => {
    // ring 185 (72.5) with face 20: gap 64.7 but the ring is not bright -> no. ring 195 (76.5) -> yes.
    expect(isBacklit(buffer(20, 185), FACE)).toBe(false);
    expect(isBacklit(buffer(20, 195), FACE)).toBe(true);
  });

  it('a face at the frame edge still reads its clipped ring', () => {
    const edge = { x: 0, y: 0, w: 0.2, h: 0.2 };
    expect(isBacklit(buffer(40, 250, 100, 100, edge), edge)).toBe(true);
  });

  it('an unusable buffer or box gives no reading and no flag', () => {
    expect(backlightReading({ data: new Uint8ClampedArray(0), width: 0, height: 0 }, FACE)).toBeNull();
    expect(backlightReading({ data: new Uint8ClampedArray(16), width: 100, height: 100 }, FACE)).toBeNull();
    expect(isBacklit(buffer(60, 240), { x: 0.5, y: 0.5, w: 0, h: 0 })).toBe(false);
  });
});
