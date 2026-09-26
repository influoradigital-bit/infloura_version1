/**
 * Influora's Reel layout guide: the pure, deterministic crop and placement behind the "Reel layout"
 * view and the CapCut guide PNG (spec Phase 4, steps 1 to 7).
 *
 * Input: the photo's pixel size, the validated `layout` boxes (shares 0..1 of the photo AS SENT,
 * spec 2.2) and the safe zones. Output, on the 1080 x 1920 plane of a 9:16 Reel: the crop, the
 * boxes mapped into it, the zones, and the four slots our code places (hook text, captions, logo,
 * sticker). No DOM, no randomness, no clock: the same input always gives the same layout.
 *
 * Zone numbers come ONLY from `SafeZones` (safe-zones.ts, i.e. the shared JSON config). The pixel
 * paddings and slot sizes below are the spec's placement rules, not zone positions.
 */
import type { MeeraGeomBox, MeeraShootCheckLayout } from '@/lib/meera-api';

import { getSafeZones, type SafeZones } from './safe-zones';

export const PLANE_W = 1080;
export const PLANE_H = 1920;
export const REEL_ASPECT = 9 / 16;

/** A photo this close to 9:16 is used whole. */
const ASPECT_TOLERANCE = 0.005;
/** Crop windows slide in 1% steps of the photo. */
const CROP_STEP = 0.01;
/** The approved eye line's middle (y 640 to 733 on the plane), as a share of the crop height. */
const TARGET_EYE_LINE = 0.36;
/** Estimated eye line inside a face box: its top plus 0.4 of its height. */
const EYE_IN_FACE = 0.4;

/** Keep-out padding around a face: 4% of the frame width (43 px). */
export const FACE_PAD = Math.round(0.04 * PLANE_W);
export const PRODUCT_PAD = 32;
/** Every slot keeps this far from the safe area's edges (and the rail), and from other slots. */
export const EDGE_GAP = 16;
export const SLOT_GAP = 16;
const STEP = 16;
const ROW = 32;

export type SlotName = 'hook' | 'captions' | 'logo' | 'sticker';
export const SLOT_ORDER: readonly SlotName[] = ['hook', 'captions', 'logo', 'sticker'];
export const SLOT_SIZES: Record<SlotName, { w: number; h: number }> = {
  hook: { w: 700, h: 180 },
  captions: { w: 840, h: 200 },
  logo: { w: 200, h: 90 },
  sticker: { w: 280, h: 280 },
};

/** A rectangle on the 1080 x 1920 plane, in pixels, (x, y) top-left. */
export interface Rect {
  x: number;
  y: number;
  w: number;
  h: number;
}

export interface ReelZones {
  /** The green safe area for text: its bounding rectangle. The rail is cut out of it. */
  safe: Rect;
  /** The green safe area's outline: `safe` minus the rail's notch (spec 2.6 "minus the rail"), as
   *  the corners of a closed path, clockwise from the top-left. What the guide and the PNG draw. */
  safeOutline: Array<{ x: number; y: number }>;
  rail: Rect;
  /** "Short CTA, left side": from the caption line to the covered bottom, left of the rail. */
  ctaBand: Rect;
  /** The rest of that band, right of the short CTA up to the rail (where the app's caption sits):
   *  covered, like the live camera's `covered-caption`. Up to the right edge when the rail does not
   *  cover the whole band. */
  coveredCaption: Rect;
  coveredTop: Rect;
  coveredBottom: Rect;
  sideLeft: Rect;
  sideRight: Rect;
  captionLineY: number;
}

export interface ReelLayoutPlan {
  /** The 9:16 window, in shares of the photo. */
  crop: MeeraGeomBox;
  /** Boxes at least half inside the crop, clipped to it, on the plane. */
  faces: Rect[];
  product: Rect | null;
  zones: ReelZones;
  slots: Record<SlotName, Rect | null>;
  /** Slots that found no clear space, in placement order; the legend says why for each. */
  dropped: SlotName[];
}

export interface PhotoSize {
  width: number;
  height: number;
}

// ---------------------------------------------------------------------------
// Geometry helpers
// ---------------------------------------------------------------------------

function overlaps(a: Rect, b: Rect): boolean {
  return a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;
}

function inside(a: Rect, area: Rect): boolean {
  return a.x >= area.x && a.y >= area.y && a.x + a.w <= area.x + area.w && a.y + a.h <= area.y + area.h;
}

export function padRect(r: Rect, pad: number): Rect {
  return { x: r.x - pad, y: r.y - pad, w: r.w + 2 * pad, h: r.h + 2 * pad };
}

function area(r: { w: number; h: number }): number {
  return r.w * r.h;
}

/** `start`, `start + step`, ... up to `end`, plus `end` itself when the steps miss it. */
function positions(start: number, end: number, step: number): number[] {
  if (end < start) return [];
  const out: number[] = [];
  for (let p = start; p <= end; p += step) out.push(p);
  if (out[out.length - 1] !== end) out.push(end);
  return out;
}

/** The largest face (first one on a tie), or null. */
function largest<T extends { w: number; h: number }>(boxes: readonly T[]): T | null {
  let best: T | null = null;
  for (const box of boxes) if (!best || area(box) > area(best)) best = box;
  return best;
}

// ---------------------------------------------------------------------------
// Step 1: crop to 9:16
// ---------------------------------------------------------------------------

const EPS = 1e-9;

function fullyInside(box: MeeraGeomBox, crop: MeeraGeomBox): boolean {
  return (
    box.x >= crop.x - EPS &&
    box.y >= crop.y - EPS &&
    box.x + box.w <= crop.x + crop.w + EPS &&
    box.y + box.h <= crop.y + crop.h + EPS
  );
}

function cropScore(crop: MeeraGeomBox, layout: MeeraShootCheckLayout | null | undefined): number {
  if (!layout) return 0;
  let score = 0;
  for (const face of layout.faces) if (fullyInside(face, crop)) score += 1;
  if (layout.product && fullyInside(layout.product, crop)) score += 2;
  return score;
}

/** Window starts from 0 to `max` in 1% steps, plus `max` itself. Rounded to kill float drift. */
function windowStarts(max: number): number[] {
  const out: number[] = [];
  for (let k = 0; k * CROP_STEP <= max + EPS; k++) out.push(Math.min(Math.round(k * CROP_STEP * 1e6) / 1e6, max));
  if (out.length === 0 || Math.abs(out[out.length - 1] - max) > EPS) out.push(max);
  return out;
}

/**
 * The 9:16 window over the photo. Wider than 9:16: slide horizontally, +1 per face fully inside,
 * +2 for the product fully inside, ties to the more central window. Taller: slide vertically with
 * the same score, ties to the window that puts the largest face's eye line nearest 0.36 of the
 * crop, then the more central one. Within 0.5% of 9:16 (or an unusable size): the whole photo.
 */
export function cropTo916(photo: PhotoSize, layout?: MeeraShootCheckLayout | null): MeeraGeomBox {
  const full = { x: 0, y: 0, w: 1, h: 1 };
  const { width, height } = photo;
  if (!(width > 0 && height > 0 && Number.isFinite(width) && Number.isFinite(height))) return full;
  const aspect = width / height;
  if (Math.abs(aspect - REEL_ASPECT) <= ASPECT_TOLERANCE) return full;

  const wide = aspect > REEL_ASPECT;
  const size = wide ? (height * REEL_ASPECT) / width : width / REEL_ASPECT / height;
  const bigFace = layout ? largest(layout.faces) : null;

  let best: { crop: MeeraGeomBox; score: number; tie: number; central: number } | null = null;
  for (const start of windowStarts(1 - size)) {
    const crop = wide ? { x: start, y: 0, w: size, h: 1 } : { x: 0, y: start, w: 1, h: size };
    const score = cropScore(crop, layout);
    const central = Math.abs(start + size / 2 - 0.5);
    const tie =
      !wide && bigFace ? Math.abs((bigFace.y + EYE_IN_FACE * bigFace.h - start) / size - TARGET_EYE_LINE) : central;
    const better =
      !best ||
      score > best.score ||
      (score === best.score && tie < best.tie - EPS) ||
      (score === best.score && Math.abs(tie - best.tie) <= EPS && central < best.central - EPS);
    if (better) best = { crop, score, tie, central };
  }
  return best ? best.crop : full;
}

/** A photo box on the plane: dropped when less than half of it is inside the crop, else clipped. */
function toPlane(box: MeeraGeomBox, crop: MeeraGeomBox): Rect | null {
  const x0 = Math.max(box.x, crop.x);
  const y0 = Math.max(box.y, crop.y);
  const x1 = Math.min(box.x + box.w, crop.x + crop.w);
  const y1 = Math.min(box.y + box.h, crop.y + crop.h);
  if (x1 <= x0 || y1 <= y0) return null;
  if ((x1 - x0) * (y1 - y0) < 0.5 * box.w * box.h) return null;
  const sx = PLANE_W / crop.w;
  const sy = PLANE_H / crop.h;
  const x = Math.round((x0 - crop.x) * sx);
  const y = Math.round((y0 - crop.y) * sy);
  return { x, y, w: Math.round((x1 - crop.x) * sx) - x, h: Math.round((y1 - crop.y) * sy) - y };
}

// ---------------------------------------------------------------------------
// Step 2: zones from the config
// ---------------------------------------------------------------------------

/** The safe-zone config on the plane, rounded to whole pixels (interim: green x 43-1037, y 269-1248
 *  minus the rail; rail x 929-1037, y 960-1498; short CTA y 1248-1498 left of x 594; covered caption
 *  x 594-929 in that band). Everything outside green is covered, CTA or rail (spec Phase 4 step 2). */
export function reelZones(zones: SafeZones): ReelZones {
  const left = Math.round(zones.side * PLANE_W);
  const right = PLANE_W - left;
  const top = Math.round(zones.top * PLANE_H);
  const captionLineY = Math.round(zones.captionLine * PLANE_H);
  const coveredFrom = Math.round(zones.coveredFrom * PLANE_H);
  const railX = Math.round(zones.rail.x * PLANE_W);
  const railY0 = Math.round(zones.rail.yFrom * PLANE_H);
  const railY1 = Math.round(zones.rail.yTo * PLANE_H);
  const ctaRight = Math.min(Math.round(zones.ctaBand.xTo * PLANE_W), railX, right);
  const railW = Math.max(0, right - railX);
  // The rail's notch in the green area: from where the rail starts (never above the top area) down
  // to the caption line. A rail that starts at or below the caption line leaves no notch.
  const notchY = Math.max(railY0, top);
  const safeOutline =
    railW > 0 && notchY < captionLineY
      ? [
          { x: left, y: top },
          { x: right, y: top },
          { x: right, y: notchY },
          { x: railX, y: notchY },
          { x: railX, y: captionLineY },
          { x: left, y: captionLineY },
        ]
      : [
          { x: left, y: top },
          { x: right, y: top },
          { x: right, y: captionLineY },
          { x: left, y: captionLineY },
        ];
  const railCoversBand = railW > 0 && railY0 <= captionLineY && railY1 >= coveredFrom;
  const captionRight = railCoversBand ? railX : right;
  return {
    safe: { x: left, y: top, w: right - left, h: captionLineY - top },
    safeOutline,
    rail: { x: railX, y: railY0, w: railW, h: railY1 - railY0 },
    ctaBand: { x: left, y: captionLineY, w: Math.max(0, ctaRight - left), h: coveredFrom - captionLineY },
    coveredCaption: {
      x: ctaRight,
      y: captionLineY,
      w: Math.max(0, captionRight - ctaRight),
      h: coveredFrom - captionLineY,
    },
    coveredTop: { x: 0, y: 0, w: PLANE_W, h: top },
    coveredBottom: { x: 0, y: coveredFrom, w: PLANE_W, h: PLANE_H - coveredFrom },
    sideLeft: { x: 0, y: top, w: left, h: coveredFrom - top },
    sideRight: { x: right, y: top, w: PLANE_W - right, h: coveredFrom - top },
    captionLineY,
  };
}

// ---------------------------------------------------------------------------
// Steps 3 to 5: keep-outs and placement
// ---------------------------------------------------------------------------

/**
 * Crops the photo, maps the boxes, and places the four slots in the fixed order hook, captions,
 * logo, sticker (spec Phase 4 step 4). `layout` null or empty gives the zones and default slots on
 * the plain crop (step 7); whether to SHOW anything then is the component's call.
 */
export function planReelLayout(
  photo: PhotoSize,
  layout?: MeeraShootCheckLayout | null,
  safeZones: SafeZones = getSafeZones(),
): ReelLayoutPlan {
  const crop = cropTo916(photo, layout);
  const faces = (layout?.faces ?? []).map((f) => toPlane(f, crop)).filter((r): r is Rect => r !== null);
  const product = layout?.product ? toPlane(layout.product, crop) : null;
  const zones = reelZones(safeZones);

  // Where a slot may sit: the safe area, EDGE_GAP in from every edge. The rail is cut out below.
  const room: Rect = {
    x: zones.safe.x + EDGE_GAP,
    y: zones.safe.y + EDGE_GAP,
    w: zones.safe.w - 2 * EDGE_GAP,
    h: zones.safe.h - 2 * EDGE_GAP,
  };
  const keepOut: Rect[] = [
    ...faces.map((f) => padRect(f, FACE_PAD)),
    ...(product ? [padRect(product, PRODUCT_PAD)] : []),
    ...(zones.rail.w > 0 && zones.rail.h > 0 ? [padRect(zones.rail, EDGE_GAP)] : []),
  ];
  const fits = (r: Rect): boolean => inside(r, room) && !keepOut.some((k) => overlaps(r, k));
  const roomRight = room.x + room.w;
  const roomBottom = room.y + room.h;

  const slots: Record<SlotName, Rect | null> = { hook: null, captions: null, logo: null, sticker: null };
  const place = (name: SlotName, rect: Rect | null) => {
    slots[name] = rect;
    if (rect) keepOut.push(padRect(rect, SLOT_GAP));
  };

  // Hook text: from the safe area's top-left, down in 16 px steps while its bottom stays above the
  // largest face's padded top; then the same right-aligned; otherwise dropped.
  {
    const { w, h } = SLOT_SIZES.hook;
    const face = largest(faces);
    const limit = face ? Math.min(face.y - FACE_PAD, roomBottom) : roomBottom;
    let found: Rect | null = null;
    for (const x of [room.x, roomRight - w]) {
      if (x < room.x) continue;
      for (let y = room.y; y + h <= limit && !found; y += STEP) {
        const r = { x, y, w, h };
        if (fits(r)) found = r;
      }
      if (found) break;
    }
    place('hook', found);
  }

  // Captions: bottom 16 px above the caption line at the left edge, then up in 16 px steps, then
  // sideways in 16 px steps. `room` ends above the caption line, so it never enters the CTA band.
  {
    const { w, h } = SLOT_SIZES.captions;
    let found: Rect | null = null;
    for (const x of positions(room.x, roomRight - w, STEP)) {
      for (let y = roomBottom - h; y >= room.y && !found; y -= STEP) {
        const r = { x, y, w, h };
        if (fits(r)) found = r;
      }
      if (found) break;
    }
    place('captions', found);
  }

  // Logo: top-right of the safe area, then top-left, then rows of 32 px.
  {
    const { w, h } = SLOT_SIZES.logo;
    const tries: Rect[] = [
      { x: roomRight - w, y: room.y, w, h },
      { x: room.x, y: room.y, w, h },
    ];
    let found = tries.find(fits) ?? null;
    for (const y of positions(room.y, roomBottom - h, ROW)) {
      if (found) break;
      for (const x of positions(room.x, roomRight - w, STEP)) {
        const r = { x, y, w, h };
        if (fits(r)) {
          found = r;
          break;
        }
      }
    }
    place('logo', found);
  }

  // Sticker: rows of 32 px from the top; among the free spots, the one farthest from the nearest
  // face (the first free spot when there is no face). Strict ">" keeps the scan order on a tie.
  {
    const { w, h } = SLOT_SIZES.sticker;
    let found: Rect | null = null;
    let bestDistance = -1;
    for (const y of positions(room.y, roomBottom - h, ROW)) {
      for (const x of positions(room.x, roomRight - w, STEP)) {
        const r = { x, y, w, h };
        if (!fits(r)) continue;
        const d = faces.length === 0 ? 0 : Math.min(...faces.map((f) => centreDistance(r, f)));
        if (!found || d > bestDistance) {
          found = r;
          bestDistance = d;
        }
        if (faces.length === 0) break;
      }
      if (found && faces.length === 0) break;
    }
    place('sticker', found);
  }

  return {
    crop,
    faces,
    product,
    zones,
    slots,
    dropped: SLOT_ORDER.filter((name) => slots[name] === null),
  };
}

function centreDistance(a: Rect, b: Rect): number {
  return Math.hypot(a.x + a.w / 2 - (b.x + b.w / 2), a.y + a.h / 2 - (b.y + b.h / 2));
}
