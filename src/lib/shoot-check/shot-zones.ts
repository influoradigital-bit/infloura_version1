/**
 * The live camera guide's geometry (spec v2 Phase 5a + 5b), pure so every number is testable
 * without a camera.
 *
 * Everything is worked out on the 1080 x 1920 plane of a 9:16 Reel and only mapped into the
 * preview's CSS pixels at the end (`reelFrame` + `planeRectToBox`):
 *   - the 9:16 "Reel frame", centred in whatever box the preview has (portrait or landscape);
 *   - the camera grid: Rule of thirds, Golden grid (lines + their 4 crossings) or Off. No spiral;
 *   - the per-size shot bands (eye line, hook-text area, lower crop, head top), from the spec's
 *     zone table: the talking sizes (CU, MCU, MS) use the approved eye line 640-733, MLS and FS
 *     keep the research values;
 *   - the prop zone, snapped to the grid line on the creator's side and kept clear of the app's
 *     button rail and caption line (both from the safe-zone config, never constants here);
 *   - the set-up chip text rule (20 visible characters, never splitting a Devanagari syllable).
 *
 * These are drawn guides, not readings. Nothing here looks at the video, and nothing here can say
 * a shot "matches" (the 2026-09-26 "no live readings" ruling).
 */
import type { ShootCheckShot } from '@/components/creator/shoot-check/ShootCheckPanel';
import { explicitShotSize, targetForShotSize, type ShotSize } from './beat-to-shot';
import type { CameraGrid } from './guide-prefs';
import type { ShotTarget } from './metrics';
import type { SafeZones } from './safe-zones';

/** The Reel plane every zone below is written in. */
export const PLANE = { width: 1080, height: 1920 } as const;

export interface BoxSize {
  width: number;
  height: number;
}

/** A rectangle; on the plane or in the preview's CSS pixels, as each function says. */
export interface Rect {
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface Point {
  x: number;
  y: number;
}

/** A vertical band on the plane: y from..to. */
export interface Band {
  from: number;
  to: number;
}

// ---------------------------------------------------------------------------
// Reel frame inside the preview box
// ---------------------------------------------------------------------------

/** The largest 9:16 rectangle centred in `box` (CSS pixels). A zero-sized box gives a zero frame. */
export function reelFrame(box: BoxSize): Rect {
  const width = Math.max(0, box.width);
  const height = Math.max(0, box.height);
  if (width === 0 || height === 0) return { x: 0, y: 0, width: 0, height: 0 };
  const ratio = PLANE.width / PLANE.height;
  if (width / height > ratio) {
    const w = height * ratio;
    return { x: (width - w) / 2, y: 0, width: w, height };
  }
  const h = width / ratio;
  return { x: 0, y: (height - h) / 2, width, height: h };
}

export function planeXToBox(frame: Rect, x: number): number {
  return frame.x + (x * frame.width) / PLANE.width;
}

export function planeYToBox(frame: Rect, y: number): number {
  return frame.y + (y * frame.height) / PLANE.height;
}

export function planeRectToBox(frame: Rect, rect: Rect): Rect {
  const scale = frame.width / PLANE.width;
  return {
    x: planeXToBox(frame, rect.x),
    y: planeYToBox(frame, rect.y),
    width: rect.width * scale,
    height: rect.height * scale,
  };
}

// ---------------------------------------------------------------------------
// Camera grid
// ---------------------------------------------------------------------------

export interface GridGeometry {
  /** Plane x of each vertical line. */
  vertical: number[];
  /** Plane y of each horizontal line. */
  horizontal: number[];
  /** Crossing dots (Golden grid only). */
  points: Point[];
}

export const THIRDS_LINES = { vertical: [360, 720], horizontal: [640, 1280] } as const;
/** 38.2% and 61.8% of the plane, as the spec states them (x 412/668, y 733/1187). A set of lines,
 *  nothing more: the app teaches no theory about it (spec 2.7). */
export const GOLDEN_LINES = { vertical: [412, 668], horizontal: [733, 1187] } as const;

export function gridGeometry(grid: CameraGrid): GridGeometry {
  if (grid === 'thirds') {
    return { vertical: [...THIRDS_LINES.vertical], horizontal: [...THIRDS_LINES.horizontal], points: [] };
  }
  if (grid === 'golden') {
    const vertical = [...GOLDEN_LINES.vertical];
    const horizontal = [...GOLDEN_LINES.horizontal];
    const points = horizontal.flatMap((y) => vertical.map((x) => ({ x, y })));
    return { vertical, horizontal, points };
  }
  return { vertical: [], horizontal: [], points: [] };
}

/** The two vertical lines a left/right prop zone snaps to. With the grid Off, the thirds lines. */
function sideLines(grid: CameraGrid): { left: number; right: number } {
  const lines = grid === 'golden' ? GOLDEN_LINES.vertical : THIRDS_LINES.vertical;
  return { left: Math.min(...lines), right: Math.max(...lines) };
}

// ---------------------------------------------------------------------------
// Shot bands (the Phase 5a zone table)
// ---------------------------------------------------------------------------

export type LowerCropPart = 'chest' | 'waist' | 'knees' | 'feet';

export interface ShotBands {
  size: ShotSize;
  /** What is drawn for it: LS is drawn as FS (with a note), ECU as CU. */
  drawnAs: ShotSize;
  eyeLine: Band | null;
  /** MLS and FS only. `inner` is the overlap of the research note's head-top and headroom
   *  ranges, `outer` their union; the outer band is drawn lighter. */
  headTop: { inner: Band; outer: Band } | null;
  /** MCU and MS only: where hook text goes, above the head. */
  hookText: Band | null;
  lowerCrop: { part: LowerCropPart; band: Band } | null;
  /** OVERHEAD only: the work surface and two hand zones coming up from the bottom. No face bands. */
  overhead: { surface: Rect; hands: [Rect, Rect] } | null;
}

/** Approved eye line for the talking sizes: between the top-thirds line and the upper golden line. */
export const TALKING_EYE_LINE: Band = { from: 640, to: 733 };

type DrawnSize = 'CU' | 'MCU' | 'MS' | 'MLS' | 'FS' | 'OVERHEAD';

/** The spec table, 1080 x 1920 plane. Hook-text heights are derived from the eye line, not
 *  measured (Q12); MLS and FS head-top/headroom are the research note's values. */
const BAND_TABLE: Record<DrawnSize, Omit<ShotBands, 'size' | 'drawnAs'>> = {
  CU: { eyeLine: TALKING_EYE_LINE, headTop: null, hookText: null, lowerCrop: null, overhead: null },
  MCU: {
    eyeLine: TALKING_EYE_LINE,
    headTop: null,
    hookText: { from: 269, to: 400 },
    lowerCrop: { part: 'chest', band: { from: 1050, to: 1300 } },
    overhead: null,
  },
  MS: {
    eyeLine: TALKING_EYE_LINE,
    headTop: null,
    hookText: { from: 269, to: 480 },
    lowerCrop: { part: 'waist', band: { from: 1450, to: 1700 } },
    overhead: null,
  },
  MLS: {
    eyeLine: { from: 430, to: 620 },
    // head top 130-260, headroom 100-200.
    headTop: { inner: { from: 130, to: 200 }, outer: { from: 100, to: 260 } },
    hookText: null,
    lowerCrop: { part: 'knees', band: { from: 1600, to: 1830 } },
    overhead: null,
  },
  FS: {
    eyeLine: null,
    // head top 120-250, headroom 100-200.
    headTop: { inner: { from: 120, to: 200 }, outer: { from: 100, to: 250 } },
    hookText: null,
    lowerCrop: { part: 'feet', band: { from: 1680, to: 1840 } },
    overhead: null,
  },
  // Drawn shapes for a top-down shot, not measurements: a work surface in the middle of the frame
  // and two hands coming up from the bottom edge.
  OVERHEAD: {
    eyeLine: null,
    headTop: null,
    hookText: null,
    lowerCrop: null,
    overhead: {
      surface: { x: 130, y: 576, width: 820, height: 806 },
      hands: [
        { x: 150, y: 1190, width: 280, height: 580 },
        { x: 650, y: 1190, width: 280, height: 580 },
      ],
    },
  },
};

function drawnSizeFor(size: ShotSize): DrawnSize {
  if (size === 'ECU') return 'CU';
  if (size === 'LS') return 'FS';
  return size;
}

export function shotBands(size: ShotSize): ShotBands {
  const drawnAs = drawnSizeFor(size);
  const row = BAND_TABLE[drawnAs];
  return {
    size,
    drawnAs,
    eyeLine: row.eyeLine ? { ...row.eyeLine } : null,
    headTop: row.headTop ? { inner: { ...row.headTop.inner }, outer: { ...row.headTop.outer } } : null,
    hookText: row.hookText ? { ...row.hookText } : null,
    lowerCrop: row.lowerCrop ? { part: row.lowerCrop.part, band: { ...row.lowerCrop.band } } : null,
    overhead: row.overhead
      ? { surface: { ...row.overhead.surface }, hands: [{ ...row.overhead.hands[0] }, { ...row.overhead.hands[1] }] }
      : null,
  };
}

/** The hook-text area as a plane rectangle across the safe area's width, never above the top
 *  covered area (so a measured, taller top bar pushes it down with it). */
export function hookTextArea(bands: ShotBands, zones: SafeZones): Rect | null {
  if (!bands.hookText) return null;
  const left = zones.side * PLANE.width;
  const right = (1 - zones.side) * PLANE.width;
  const top = Math.max(bands.hookText.from, zones.top * PLANE.height);
  const bottom = Math.max(top, bands.hookText.to);
  return { x: left, y: top, width: right - left, height: bottom - top };
}

const LABEL_TIMING_RE = /^\s*\d+(?:\.\d+)?-\d+(?:\.\d+)?s\s*·\s*/;

/**
 * The size the guide draws for a camera shot: the size words in its shot text (`context.line`,
 * else the label without its "<from>-<to>s · " prefix), else the shot's own target, else MS.
 * A shot's target decides only when its text names no size, so an explicit "Medium close-up"
 * draws MCU bands even though its checker target is `closeup`.
 */
export function shotSizeFor(shot: ShootCheckShot | null | undefined): ShotSize {
  if (!shot) return 'MS';
  const text = shot.context?.line ?? shot.label.replace(LABEL_TIMING_RE, '');
  const explicit = explicitShotSize(text);
  if (explicit) return explicit;
  switch (shot.target) {
    case 'closeup':
      return 'CU';
    case 'wide':
      return 'FS';
    case 'hands-overhead':
      return 'OVERHEAD';
    default:
      return 'MS';
  }
}

// ---------------------------------------------------------------------------
// Prop zone
// ---------------------------------------------------------------------------

export type PropSide = 'left' | 'centre' | 'right';
export type PropPlace = 'hand' | 'table' | 'surface';

/** v1 section 1.3: the default spot per target when the script names a prop but no position
 *  (every script before Phase 6's shot card). Sides are the CREATOR's own side. Shares of the
 *  frame; `x` is used only for the centre zones (a side zone snaps to its grid line). */
const DEFAULT_PROP: Record<ShotTarget, { side: PropSide; place: PropPlace; x?: [number, number]; y: [number, number] }> = {
  closeup: { side: 'centre', place: 'hand', x: [0.32, 0.68], y: [0.7, 0.86] },
  medium: { side: 'right', place: 'hand', y: [0.55, 0.74] },
  wide: { side: 'left', place: 'table', y: [0.66, 0.84] },
  'hands-overhead': { side: 'centre', place: 'surface', x: [0.36, 0.64], y: [0.4, 0.56] },
};

/** A side prop zone is this wide, centred on its grid line. */
export const PROP_ZONE_WIDTH = 300;
/** Kept this far left of the button rail. */
export const PROP_RAIL_GAP = 16;
/** A zone clipped at the caption line keeps at least this height (it grows upward). */
export const PROP_MIN_HEIGHT = 200;

export interface PropZone {
  /** Plane rectangle, as drawn on the screen (mirroring already applied). */
  rect: Rect;
  /** The creator's own side, for the words. */
  side: PropSide;
  /** The side of the SCREEN it is drawn on. */
  screenSide: PropSide;
  place: PropPlace;
  /** Plane x of the grid line it is centred on; null for a centre zone. */
  snappedTo: number | null;
  /** The prop's text, as the script wrote it. */
  prop: string;
}

/** Trimmed, whitespace collapsed, one pair of wrapping quotes removed; '' for a missing value. */
export function cleanShotText(value: string | undefined): string {
  if (!value) return '';
  let text = value.replace(/\s+/g, ' ').trim();
  const quoted = /^(["'“‘])(.*)(["'”’])$/.exec(text);
  if (quoted) text = quoted[2].trim();
  return text;
}

/**
 * The dashed prop zone, or null when the shot names no prop (we never invent one).
 *
 * `mirrored` is the preview's state: the front camera's preview is a mirror, so the creator's own
 * right is drawn on the screen's right; the rear camera's preview is not, so it flips (spec 2.4).
 */
export function propZone(args: {
  shot: ShootCheckShot | null | undefined;
  size: ShotSize;
  grid: CameraGrid;
  mirrored: boolean;
  zones: SafeZones;
}): PropZone | null {
  const prop = cleanShotText(args.shot?.context?.prop);
  if (!prop) return null;
  const spot = DEFAULT_PROP[targetForShotSize(args.size)];

  let screenSide: PropSide = spot.side;
  if (spot.side !== 'centre' && !args.mirrored) screenSide = spot.side === 'left' ? 'right' : 'left';

  let left: number;
  let right: number;
  let snappedTo: number | null = null;
  if (screenSide === 'centre') {
    const [x0, x1] = spot.x ?? [0.33, 0.67];
    left = x0 * PLANE.width;
    right = x1 * PLANE.width;
  } else {
    const lines = sideLines(args.grid);
    snappedTo = screenSide === 'left' ? lines.left : lines.right;
    left = snappedTo - PROP_ZONE_WIDTH / 2;
    right = snappedTo + PROP_ZONE_WIDTH / 2;
  }
  // Clear of the button rail, and never off the frame.
  right = Math.min(right, args.zones.rail.x * PLANE.width - PROP_RAIL_GAP);
  left = Math.max(0, Math.min(left, right));

  let top = spot.y[0] * PLANE.height;
  let bottom = spot.y[1] * PLANE.height;
  if (spot.place === 'hand' || spot.place === 'table') {
    // A product held or on a table ends above the caption line, where captions do not cover it.
    const captionY = args.zones.captionLine * PLANE.height;
    bottom = Math.min(bottom, captionY);
    top = Math.max(0, Math.min(top, bottom - PROP_MIN_HEIGHT));
  }

  return {
    rect: { x: left, y: top, width: right - left, height: bottom - top },
    side: spot.side,
    screenSide,
    place: spot.place,
    snappedTo,
    prop,
  };
}

// ---------------------------------------------------------------------------
// Set-up chips (v1 section 1.4)
// ---------------------------------------------------------------------------

export const CHIP_MAX_CHARS = 20;

/** User-visible characters: a Devanagari syllable (consonant + matra/virama) is one. */
export function visibleChars(text: string): string[] {
  const Segmenter = (Intl as unknown as { Segmenter?: typeof Intl.Segmenter }).Segmenter;
  if (typeof Segmenter === 'function') {
    return Array.from(new Segmenter(undefined, { granularity: 'grapheme' }).segment(text), (s) => s.segment);
  }
  return Array.from(text);
}

/**
 * At most 20 visible characters. Longer text is cut at the last space before character 19 and
 * gets "…"; with no space it is hard-cut at 19. Counting visible characters means a Devanagari
 * syllable is never split from its matra.
 */
export function chipText(value: string, max: number = CHIP_MAX_CHARS): string {
  const text = cleanShotText(value);
  const chars = visibleChars(text);
  if (chars.length <= max) return text;
  const head = chars.slice(0, max - 1);
  const lastSpace = head.lastIndexOf(' ');
  const kept = lastSpace > 0 ? head.slice(0, lastSpace) : head;
  return `${kept.join('').trimEnd()}…`;
}

export type ChipKind = 'angle' | 'where' | 'light' | 'sit_or_walk';

export interface SetupChip {
  kind: ChipKind;
  /** The cut chip text. */
  text: string;
  /** The whole value, for the text line. */
  full: string;
}

export const MAX_SETUP_CHIPS = 3;

/**
 * Up to 3 chips, from angle, where, light, sit_or_walk in that order (`line`, `action` and
 * `on_camera` never become chips). With no angle the size's own name stands in for it.
 */
export function setupChips(shot: ShootCheckShot | null | undefined, sizeName: string): SetupChip[] {
  const context = shot?.context ?? {};
  const values: Array<[ChipKind, string]> = [
    ['angle', cleanShotText(context.angle) || sizeName],
    ['where', cleanShotText(context.where)],
    ['light', cleanShotText(context.light)],
    ['sit_or_walk', cleanShotText(context.sit_or_walk)],
  ];
  return values
    .filter(([, full]) => full.length > 0)
    .slice(0, MAX_SETUP_CHIPS)
    .map(([kind, full]) => ({ kind, text: chipText(full), full }));
}
