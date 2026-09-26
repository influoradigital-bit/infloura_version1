/**
 * reel-layout.ts (spec Phase 4 steps 1 to 7): the 9:16 crop, the zones from the config, and the
 * deterministic placement of hook text, captions, logo and sticker.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { describe, expect, it } from 'vitest';

import type { MeeraGeomBox, MeeraShootCheckLayout } from '@/lib/meera-api';

import {
  EDGE_GAP,
  FACE_PAD,
  PLANE_H,
  PLANE_W,
  PRODUCT_PAD,
  SLOT_GAP,
  SLOT_ORDER,
  cropTo916,
  padRect,
  planReelLayout,
  reelZones,
  type Rect,
} from './reel-layout';
import { INTERIM_SAFE_ZONES, parseSafeZonesConfig, type SafeZones } from './safe-zones';
import bundled from './safe-zones.json';

const PORTRAIT = { width: 1080, height: 1920 };

/** The crop cases influora-ai's `reel_crop` also reads (tests/shoot/test_checklist.py). */
const CROP_CASES: Array<{
  name: string;
  photo: { width: number; height: number };
  layout: MeeraShootCheckLayout;
  crop: MeeraGeomBox;
}> = JSON.parse(
  readFileSync(join(process.cwd(), 'src', 'lib', '__fixtures__', 'reel-crop-cases.json'), 'utf-8'),
).cases;

/** A plane-pixel box on a 1080 x 1920 photo, as the photo shares the wire carries. */
function share(x: number, y: number, w: number, h: number): MeeraGeomBox {
  return { x: x / PLANE_W, y: y / PLANE_H, w: w / PLANE_W, h: h / PLANE_H };
}

function overlaps(a: Rect, b: Rect): boolean {
  return a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;
}

function inside(a: Rect, area: Rect): boolean {
  return a.x >= area.x && a.y >= area.y && a.x + a.w <= area.x + area.w && a.y + a.h <= area.y + area.h;
}

/** Deterministic PRNG (mulberry32), so the property test is repeatable. */
function rng(seed: number) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

describe('crop to 9:16', () => {
  it('an exact 9:16 photo is used whole', () => {
    expect(cropTo916(PORTRAIT)).toEqual({ x: 0, y: 0, w: 1, h: 1 });
    expect(cropTo916({ width: 720, height: 1280 }, { faces: [share(100, 100, 200, 200)], product: null })).toEqual({
      x: 0,
      y: 0,
      w: 1,
      h: 1,
    });
  });

  it('a wide photo with no boxes takes the most central 1%-step window', () => {
    const crop = cropTo916({ width: 1920, height: 1080 });
    expect(crop.w).toBeCloseTo((1080 * 9) / 16 / 1920, 10);
    expect(crop.h).toBe(1);
    expect(crop.y).toBe(0);
    // (1 - w) / 2 = 0.3418; the 1% grid's nearest is 0.34.
    expect(crop.x).toBeCloseTo(0.34, 10);
  });

  it('a wide photo keeps the face inside, preferring the more central window among those that do', () => {
    const face = { x: 0.1, y: 0.3, w: 0.1, h: 0.2 };
    const crop = cropTo916({ width: 1920, height: 1080 }, { faces: [face], product: null });
    expect(crop.x).toBeLessThanOrEqual(face.x);
    expect(crop.x + crop.w).toBeGreaterThanOrEqual(face.x + face.w);
    expect(crop.x).toBeCloseTo(0.1, 10);
  });

  it('the product counts double: it wins over one face when both cannot fit', () => {
    const face = { x: 0.02, y: 0.3, w: 0.08, h: 0.2 };
    const product = { x: 0.85, y: 0.5, w: 0.1, h: 0.2 };
    const crop = cropTo916({ width: 1920, height: 1080 }, { faces: [face], product });
    expect(crop.x).toBeLessThanOrEqual(product.x);
    expect(crop.x + crop.w).toBeGreaterThanOrEqual(product.x + product.w - 1e-9);
    // Two faces outweigh nothing, but one product (2) beats one face (1).
    const crop2 = cropTo916({ width: 1920, height: 1080 }, { faces: [face, { ...face, x: 0.11 }], product });
    expect(crop2.x + crop2.w).toBeGreaterThanOrEqual(product.x + product.w - 1e-9);
  });

  it('a tall photo slides vertically, putting the largest face’s eye line nearest 0.36 of the crop', () => {
    const face = { x: 0.4, y: 0.3, w: 0.2, h: 0.1 }; // eye line 0.34 of the photo
    const crop = cropTo916({ width: 1080, height: 2400 }, { faces: [face], product: null });
    expect(crop.x).toBe(0);
    expect(crop.w).toBe(1);
    expect(crop.h).toBeCloseTo(0.8, 10);
    // (0.34 - y) / 0.8 nearest 0.36 on the 1% grid: y = 0.05.
    expect(crop.y).toBeCloseTo(0.05, 10);
  });

  it('matches the shared crop fixture, window for window (the server judges its checks on the same crop)', () => {
    expect(CROP_CASES.length).toBeGreaterThanOrEqual(12);
    for (const c of CROP_CASES) {
      const crop = cropTo916(c.photo, c.layout);
      for (const k of ['x', 'y', 'w', 'h'] as const) {
        expect(Math.abs(crop[k] - c.crop[k]), `${c.name}: ${k}`).toBeLessThanOrEqual(1e-9);
      }
    }
  });

  it('boxes less than half inside the crop are dropped; the rest are clipped', () => {
    // Neither face can fit a 0.316-wide window, so every window scores 0 and the central one
    // (x 0.34 to 0.656) wins. Face A is 79% inside it: kept, clipped to the crop's full width.
    // Face B is 6% inside: dropped.
    const plan = planReelLayout(
      { width: 1920, height: 1080 },
      { faces: [{ x: 0.3, y: 0.3, w: 0.4, h: 0.3 }, { x: 0, y: 0.3, w: 0.36, h: 0.3 }], product: null },
      INTERIM_SAFE_ZONES,
    );
    expect(plan.crop.x).toBeCloseTo(0.34, 10);
    expect(plan.faces).toEqual([{ x: 0, y: Math.round(0.3 * 1920), w: 1080, h: Math.round(0.6 * 1920) - Math.round(0.3 * 1920) }]);
  });

  it('a box mostly outside a forced crop is dropped, a box mostly inside is clipped to the edge', () => {
    // Tall photo, face low down; a second small face at the very top ends up outside the chosen window.
    const plan = planReelLayout(
      { width: 1080, height: 3840 },
      { faces: [{ x: 0.4, y: 0.6, w: 0.2, h: 0.05 }, { x: 0.1, y: 0.0, w: 0.1, h: 0.03 }], product: null },
      INTERIM_SAFE_ZONES,
    );
    expect(plan.faces).toHaveLength(1);
  });
});

describe('zones come from the config', () => {
  it('the interim config gives the spec 2.6 pixel table', () => {
    const z = reelZones(INTERIM_SAFE_ZONES);
    expect(z.safe).toEqual({ x: 43, y: 269, w: 1037 - 43, h: 1248 - 269 });
    expect(z.rail).toEqual({ x: 929, y: 960, w: 1037 - 929, h: 1498 - 960 });
    expect(z.ctaBand).toEqual({ x: 43, y: 1248, w: 594 - 43, h: 1498 - 1248 });
    expect(z.coveredTop).toEqual({ x: 0, y: 0, w: 1080, h: 269 });
    expect(z.coveredBottom).toEqual({ x: 0, y: 1498, w: 1080, h: 1920 - 1498 });
    expect(z.coveredCaption).toEqual({ x: 594, y: 1248, w: 929 - 594, h: 1498 - 1248 });
    expect(z.captionLineY).toBe(1248);
    // "x 43-1037, y 269-1248, minus the rail": the notch at the rail's top-left corner.
    expect(z.safeOutline).toEqual([
      { x: 43, y: 269 },
      { x: 1037, y: 269 },
      { x: 1037, y: 960 },
      { x: 929, y: 960 },
      { x: 929, y: 1248 },
      { x: 43, y: 1248 },
    ]);
  });

  /** Every plane point is green, covered, CTA or rail: nothing is left unmarked (spec Phase 4 step 2). */
  function unmarked(zones: SafeZones): Array<[number, number]> {
    const z = reelZones(zones);
    const inRect = (x: number, y: number, r: Rect) => x >= r.x && x < r.x + r.w && y >= r.y && y < r.y + r.h;
    // Point-in-polygon for the axis-aligned green outline (even-odd, on half-pixel centres).
    const inSafe = (x: number, y: number) => {
      let inside = false;
      const pts = z.safeOutline;
      for (let i = 0, j = pts.length - 1; i < pts.length; j = i++) {
        const a = pts[i];
        const b = pts[j];
        if (a.y > y !== b.y > y && x < ((b.x - a.x) * (y - a.y)) / (b.y - a.y) + a.x) inside = !inside;
      }
      return inside;
    };
    const covered = [z.coveredTop, z.coveredBottom, z.sideLeft, z.sideRight, z.rail, z.ctaBand, z.coveredCaption];
    const out: Array<[number, number]> = [];
    for (let y = 0.5; y < PLANE_H; y += 4) {
      for (let x = 0.5; x < PLANE_W; x += 4) {
        if (!inSafe(x, y) && !covered.some((r) => inRect(x, y, r))) out.push([x, y]);
      }
    }
    return out;
  }

  it('leaves no part of the plane unmarked: the band right of the short CTA is covered', () => {
    expect(unmarked(INTERIM_SAFE_ZONES)).toEqual([]);
    const z = reelZones(INTERIM_SAFE_ZONES);
    const inRect = (x: number, y: number, r: Rect) => x >= r.x && x < r.x + r.w && y >= r.y && y < r.y + r.h;
    // The checker's probe point, where the app's caption sits.
    expect(inRect(760, 1370, z.coveredCaption)).toBe(true);
    // Below the caption line and above covered_from, every point is CTA, covered caption, rail or side.
    for (let y = z.captionLineY; y < z.coveredBottom.y; y += 5) {
      for (let x = 0; x < PLANE_W; x += 5) {
        const marked = [z.ctaBand, z.coveredCaption, z.rail, z.sideLeft, z.sideRight].filter((r) => inRect(x, y, r));
        expect(marked.length, `${x},${y}`).toBeGreaterThanOrEqual(1);
      }
    }
  });

  it('a rail that stops above covered_from, or starts below the caption line, still leaves nothing unmarked', () => {
    const short = { ...INTERIM_SAFE_ZONES, rail: { x: 0.86, yFrom: 0.5, yTo: 0.7 } };
    expect(unmarked(short)).toEqual([]);
    expect(reelZones(short).coveredCaption.x + reelZones(short).coveredCaption.w).toBe(1037);
    const low = { ...INTERIM_SAFE_ZONES, rail: { x: 0.86, yFrom: 0.7, yTo: 0.78 } };
    expect(unmarked(low)).toEqual([]);
    // No notch: the green outline is the plain rectangle again.
    expect(reelZones(low).safeOutline).toHaveLength(4);
  });

  it('change caption_line in a test config and the caption box moves with it', () => {
    const face = [share(440, 560, 200, 220)];
    const base = planReelLayout(PORTRAIT, { faces: face, product: null }, INTERIM_SAFE_ZONES);
    const edited = JSON.parse(JSON.stringify(bundled));
    edited.platforms.instagram_reels.default.caption_line = 0.6;
    const zones: SafeZones = parseSafeZonesConfig(edited);
    const moved = planReelLayout(PORTRAIT, { faces: face, product: null }, zones);
    expect(base.slots.captions).toMatchObject({ y: 1248 - EDGE_GAP - 200 });
    expect(moved.slots.captions).toMatchObject({ y: Math.round(0.6 * 1920) - EDGE_GAP - 200 });
    expect(moved.zones.ctaBand.y).toBe(Math.round(0.6 * 1920));
  });
});

describe('placement', () => {
  it('matches mockup 10: one face on the left thirds line, the product on the right one', () => {
    const plan = planReelLayout(
      PORTRAIT,
      { faces: [share(260, 540, 200, 220)], product: share(620, 830, 200, 160) },
      INTERIM_SAFE_ZONES,
    );
    expect(plan.slots.hook).toEqual({ x: 59, y: 285, w: 700, h: 180 });
    expect(plan.slots.logo).toEqual({ x: 821, y: 285, w: 200, h: 90 });
    expect(plan.slots.captions).toEqual({ x: 59, y: 1032, w: 840, h: 200 });
    expect(plan.slots.sticker).toEqual({ x: 741, y: 509, w: 280, h: 280 });
    expect(plan.dropped).toEqual([]);
  });

  it('no face and no product: the zones and the default slots on the plain crop', () => {
    const plan = planReelLayout(PORTRAIT, { faces: [], product: null }, INTERIM_SAFE_ZONES);
    expect(plan.slots.hook).toEqual({ x: 59, y: 285, w: 700, h: 180 });
    expect(plan.slots.captions).toEqual({ x: 59, y: 1032, w: 840, h: 200 });
    expect(plan.slots.logo).toEqual({ x: 821, y: 285, w: 200, h: 90 });
    expect(plan.slots.sticker).not.toBeNull();
    expect(plan.dropped).toEqual([]);
    // An absent layout plans the same.
    expect(planReelLayout(PORTRAIT, undefined, INTERIM_SAFE_ZONES)).toEqual(plan);
  });

  it('a face high in the frame leaves no room above the head: the hook is dropped, not put over it', () => {
    const plan = planReelLayout(PORTRAIT, { faces: [share(300, 300, 400, 400)], product: null }, INTERIM_SAFE_ZONES);
    expect(plan.slots.hook).toBeNull();
    expect(plan.dropped).toContain('hook');
  });

  it('no space anywhere: every slot is dropped (the legend then gives each its line)', () => {
    const plan = planReelLayout(PORTRAIT, { faces: [share(40, 250, 1000, 1000)], product: null }, INTERIM_SAFE_ZONES);
    expect(plan.dropped).toEqual(['hook', 'captions', 'logo', 'sticker']);
    for (const name of SLOT_ORDER) expect(plan.slots[name]).toBeNull();
  });

  it('is repeatable: the same input always gives the same layout', () => {
    const layout: MeeraShootCheckLayout = {
      faces: [share(500, 700, 180, 200), share(200, 900, 120, 140)],
      product: share(700, 400, 150, 150),
    };
    const a = planReelLayout({ width: 1600, height: 1200 }, layout, INTERIM_SAFE_ZONES);
    const b = planReelLayout({ width: 1600, height: 1200 }, JSON.parse(JSON.stringify(layout)), INTERIM_SAFE_ZONES);
    expect(b).toEqual(a);
  });

  it('property (500 random layouts): slots stay in the green area, clear of padded faces, the product, the rail, the CTA band and each other; the hook sits above the largest face or is dropped', () => {
    const random = rng(20260926);
    const zones = reelZones(INTERIM_SAFE_ZONES);
    const room: Rect = {
      x: zones.safe.x + EDGE_GAP,
      y: zones.safe.y + EDGE_GAP,
      w: zones.safe.w - 2 * EDGE_GAP,
      h: zones.safe.h - 2 * EDGE_GAP,
    };
    const sizes = [
      { width: 1080, height: 1920 },
      { width: 1920, height: 1080 },
      { width: 1200, height: 1600 },
      { width: 1080, height: 2400 },
      { width: 800, height: 800 },
    ];
    const randomBox = (maxShare: number): MeeraGeomBox => {
      const w = 0.03 + random() * maxShare;
      const h = 0.03 + random() * maxShare;
      return { x: random() * (1 - w), y: random() * (1 - h), w, h };
    };
    let placed = 0;
    let droppedHooks = 0;
    for (let i = 0; i < 500; i++) {
      const faces = Array.from({ length: Math.floor(random() * 4) }, () => randomBox(0.3));
      const product = random() < 0.6 ? randomBox(0.35) : null;
      const plan = planReelLayout(sizes[i % sizes.length], { faces, product }, INTERIM_SAFE_ZONES);

      const keepOut = [
        ...plan.faces.map((f) => padRect(f, FACE_PAD)),
        ...(plan.product ? [padRect(plan.product, PRODUCT_PAD)] : []),
      ];
      const slots = SLOT_ORDER.map((n) => plan.slots[n]).filter((s): s is Rect => s !== null);
      for (const slot of slots) {
        placed++;
        expect(inside(slot, room)).toBe(true);
        expect(inside(slot, zones.safe)).toBe(true);
        for (const k of keepOut) expect(overlaps(slot, k)).toBe(false);
        expect(overlaps(slot, padRect(zones.rail, EDGE_GAP))).toBe(false);
        expect(overlaps(slot, zones.ctaBand)).toBe(false);
        expect(overlaps(slot, zones.coveredTop)).toBe(false);
        expect(overlaps(slot, zones.coveredBottom)).toBe(false);
      }
      for (let a = 0; a < slots.length; a++) {
        for (let b = a + 1; b < slots.length; b++) expect(overlaps(padRect(slots[a], SLOT_GAP), slots[b])).toBe(false);
      }
      const hook = plan.slots.hook;
      if (plan.faces.length > 0) {
        const biggest = plan.faces.reduce((m, f) => (f.w * f.h > m.w * m.h ? f : m));
        if (hook) expect(hook.y + hook.h).toBeLessThanOrEqual(biggest.y - FACE_PAD);
      }
      if (!hook) {
        droppedHooks++;
        expect(plan.dropped).toContain('hook');
      }
      expect(plan.dropped).toEqual(SLOT_ORDER.filter((n) => plan.slots[n] === null));
    }
    // The generator really exercises both outcomes.
    expect(placed).toBeGreaterThan(500);
    expect(droppedHooks).toBeGreaterThan(0);
  });
});
