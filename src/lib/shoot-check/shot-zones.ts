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
 *     button rail and caption line (both from the safe-zone config, never constants here). With a
 *     shot card (Phase 6) it comes from the card's `prop` (side x hand/table/floor, v1's 3 x 3
 *     table); a card's `none` or `?` draws no zone;
 *   - the text area: the size table's hook-text area, or with a shot card the card's `text`
 *     (top / opposite the face / lower middle); `none` or `?` draws none;
 *   - the set-up chip text rule (20 visible characters, never splitting a Devanagari syllable).
 *
 * These are drawn guides, not readings. Nothing here looks at the video, and nothing here can say
 * a shot "matches" (the 2026-09-26 "no live readings" ruling).
 */
import type { ShootCheckShot } from '@/components/creator/shoot-check/ShootCheckPanel';
import {
  CARD_UNKNOWN,
  cardShotSize,
  explicitShotSize,
  parseCardProp,
  targetForShotSize,
  type ShotCard,
  type ShotSize,
} from './beat-to-shot';
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

/** A band as a plane rectangle across the safe area's width, never above the top covered area. */
function topTextRect(band: Band, zones: SafeZones): Rect {
  const left = zones.side * PLANE.width;
  const right = (1 - zones.side) * PLANE.width;
  const top = Math.max(band.from, zones.top * PLANE.height);
  const bottom = Math.max(top, band.to);
  return { x: left, y: top, width: right - left, height: bottom - top };
}

/**
 * The hook-text area as a plane rectangle across the safe area's width, never above the top
 * covered area (so a measured, taller top bar pushes it down with it).
 *
 * With a `shot` that has a shot card, the card's `text` decides instead (`textArea`): pass the shot
 * and the preview's mirror state to get it. Without them this is the Phase 5a size-table area.
 */
export function hookTextArea(
  bands: ShotBands,
  zones: SafeZones,
  shot?: ShootCheckShot | null,
  mirrored: boolean = false
): Rect | null {
  if (shot?.card) return textArea({ shot, bands, zones, mirrored })?.rect ?? null;
  if (!bands.hookText) return null;
  return topTextRect(bands.hookText, zones);
}

export type TextAreaKind = 'top' | 'opposite_face' | 'lower_middle';

export interface TextArea {
  /** Plane rectangle, as drawn on the screen (mirroring already applied). */
  rect: Rect;
  kind: TextAreaKind;
  /** The SCREEN side of an `opposite_face` area; null for the full-width kinds. */
  screenSide: 'left' | 'right' | null;
  /** From the shot card, or the size table's hook-text area. */
  fromCard: boolean;
}

/** The card's `top` for a size with no hook-text band of its own (CU, MLS, FS, OVERHEAD): one
 *  line, as MCU's, right under the top covered area. */
export const CARD_TOP_TEXT: Band = { from: 269, to: 400 };
/** Kept this far from the rail, the caption line and the frame's centre line. */
export const TEXT_AREA_GAP = 16;
/** An `opposite_face` area reaches this far above and below the eye-line band. */
export const OPPOSITE_FACE_PAD = 90;
/** A `lower_middle` area is this tall, ending just above the caption line. */
export const LOWER_MIDDLE_HEIGHT = 240;

/**
 * Where on-screen text goes for this shot.
 *   - No shot card: the size table's hook-text area (MCU, MS), else none.
 *   - A card's `top`: the size's hook-text area, or `CARD_TOP_TEXT` for a size without one.
 *   - `opposite_face`: beside the face on the other side from where the creator stands (`stand`,
 *     the creator's own side; mirrored like the prop zone), level with the eye-line band. Nothing
 *     when `stand` is centre or `?`, or the size has no eye line (FS, LS, OVERHEAD).
 *   - `lower_middle`: a band ending just above the caption line, left of the rail.
 *   - `none` or `?`: nothing. We never place text the card does not settle.
 */
export function textArea(args: {
  shot: ShootCheckShot | null | undefined;
  bands: ShotBands;
  zones: SafeZones;
  mirrored: boolean;
}): TextArea | null {
  const { bands, zones } = args;
  const card = args.shot?.card;
  if (!card) {
    return bands.hookText ? { rect: topTextRect(bands.hookText, zones), kind: 'top', screenSide: null, fromCard: false } : null;
  }
  const sideX0 = zones.side * PLANE.width;
  const sideX1 = (1 - zones.side) * PLANE.width;
  const rightLimit = Math.min(sideX1, zones.rail.x * PLANE.width - TEXT_AREA_GAP);
  switch (card.text) {
    case 'top':
      return { rect: topTextRect(bands.hookText ?? CARD_TOP_TEXT, zones), kind: 'top', screenSide: null, fromCard: true };
    case 'lower_middle': {
      const bottom = zones.captionLine * PLANE.height - TEXT_AREA_GAP;
      const top = Math.max(zones.top * PLANE.height, bottom - LOWER_MIDDLE_HEIGHT);
      return {
        rect: { x: sideX0, y: top, width: Math.max(0, rightLimit - sideX0), height: Math.max(0, bottom - top) },
        kind: 'lower_middle',
        screenSide: null,
        fromCard: true,
      };
    }
    case 'opposite_face': {
      if (card.stand !== 'left' && card.stand !== 'right') return null;
      if (!bands.eyeLine) return null;
      // The creator's own side is on the same screen side in a mirrored (front) preview.
      const faceScreenSide = args.mirrored ? card.stand : card.stand === 'left' ? 'right' : 'left';
      const screenSide = faceScreenSide === 'left' ? 'right' : 'left';
      const mid = PLANE.width / 2;
      const x0 = screenSide === 'left' ? sideX0 : mid + TEXT_AREA_GAP;
      const x1 = screenSide === 'left' ? mid - TEXT_AREA_GAP : rightLimit;
      const top = Math.max(zones.top * PLANE.height, bands.eyeLine.from - OPPOSITE_FACE_PAD);
      const bottom = Math.min(zones.captionLine * PLANE.height, bands.eyeLine.to + OPPOSITE_FACE_PAD);
      return {
        rect: { x: x0, y: top, width: Math.max(0, x1 - x0), height: Math.max(0, bottom - top) },
        kind: 'opposite_face',
        screenSide,
        fromCard: true,
      };
    }
    default:
      return null;
  }
}

const LABEL_TIMING_RE = /^\s*\d+(?:\.\d+)?-\d+(?:\.\d+)?s\s*·\s*/;

/**
 * The size the guide draws for a camera shot: the shot card's `size` when it has a known one
 * (spec v2 Phase 6: the card before the shot words), else the size words in its shot text
 * (`context.line`, else the label without its "<from>-<to>s · " prefix), else the shot's own
 * target, else MS. A shot's target decides only when its text names no size, so an explicit
 * "Medium close-up" draws MCU bands even though its checker target is `closeup`.
 */
export function shotSizeFor(shot: ShootCheckShot | null | undefined): ShotSize {
  if (!shot) return 'MS';
  const fromCard = cardShotSize(shot.card);
  if (fromCard) return fromCard;
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
export type PropPlace = 'hand' | 'table' | 'floor' | 'surface';

type PropSpot = { side: PropSide; place: PropPlace; x?: [number, number]; y: [number, number]; edge?: boolean };

/** v1 section 1.3: the default spot per target when the script names a prop but no position
 *  (a shot with no shot card). Sides are the CREATOR's own side. Shares of the frame; `x` is used
 *  only for the centre zones (a side zone snaps to its grid line). */
const DEFAULT_PROP: Record<ShotTarget, PropSpot> = {
  closeup: { side: 'centre', place: 'hand', x: [0.32, 0.68], y: [0.7, 0.86] },
  medium: { side: 'right', place: 'hand', y: [0.55, 0.74] },
  wide: { side: 'left', place: 'table', y: [0.66, 0.84] },
  'hands-overhead': { side: 'centre', place: 'surface', x: [0.36, 0.64], y: [0.4, 0.56] },
};

/** v1 section 1.3's 3 x 3 table for a card's `prop` (side x surface): the y band per target and
 *  surface, as shares of the frame. A close-up cannot show a table or the floor, so those are an
 *  edge marker at the frame's bottom edge on that side. Overhead is one surface plane. */
export const CARD_PROP_Y: Record<ShotTarget, Record<'hand' | 'table' | 'floor', [number, number] | 'edge'>> = {
  closeup: { hand: [0.7, 0.86], table: 'edge', floor: 'edge' },
  medium: { hand: [0.55, 0.74], table: [0.72, 0.88], floor: [0.86, 0.98] },
  wide: { hand: [0.4, 0.52], table: [0.62, 0.8], floor: [0.82, 0.95] },
  'hands-overhead': { hand: [0.4, 0.56], table: [0.4, 0.56], floor: [0.4, 0.56] },
};
/** The edge marker's band, at the frame's bottom edge. */
export const EDGE_MARKER_Y: [number, number] = [0.92, 0.98];
/** v1's centre x band for a card position. */
const CARD_CENTRE_X: [number, number] = [0.33, 0.67];

/** The spot a shot's prop goes: from its card when it has one (a card without a real position
 *  gives none, even if the context names a prop), else v1's default when the context names one. */
function propSpot(shot: ShootCheckShot | null | undefined, target: ShotTarget, propText: string): PropSpot | null {
  const card: ShotCard | undefined = shot?.card;
  if (card) {
    const position = parseCardProp(card.prop);
    if (!position) return null;
    const y = CARD_PROP_Y[target][position.surface];
    return {
      side: position.side,
      place: position.surface,
      ...(position.side === 'centre' ? { x: CARD_CENTRE_X } : {}),
      y: y === 'edge' ? EDGE_MARKER_Y : y,
      edge: y === 'edge',
    };
  }
  return propText ? DEFAULT_PROP[target] : null;
}

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
  /** The prop's text, as the script wrote it ('' when only the card places it). */
  prop: string;
  /** From the shot card's `prop` (a planned spot), or v1's default (a suggested spot). */
  fromCard: boolean;
  /** A close-up's table or floor prop: a marker at the frame's bottom edge, not a zone in frame. */
  edgeMarker: boolean;
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
 * With a shot card, the card's `prop` places it (side x hand/table/floor); the card's `none` or `?`
 * gives no zone. Without a card, v1's default spot for the target when the context names a prop.
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
  const spot = propSpot(args.shot, targetForShotSize(args.size), prop);
  if (!spot) return null;

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
  if (!spot.edge && (spot.place === 'hand' || spot.place === 'table')) {
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
    fromCard: Boolean(args.shot?.card),
    edgeMarker: Boolean(spot.edge),
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
 * At most `max` visible characters. Longer text is cut at the last space before character
 * `max - 1` and gets "…"; with no space it is hard-cut at `max - 1`. Counting visible characters
 * means a Devanagari syllable is never split from its matra. Also used for the camera sheet's Say
 * and On-screen lines, with a larger `max`.
 */
export function clipVisible(value: string, max: number): string {
  const text = cleanShotText(value);
  const chars = visibleChars(text);
  if (chars.length <= max) return text;
  const head = chars.slice(0, max - 1);
  const lastSpace = head.lastIndexOf(' ');
  const kept = lastSpace > 0 ? head.slice(0, lastSpace) : head;
  return `${kept.join('').trimEnd()}…`;
}

/** At most 20 visible characters (`clipVisible` with the chip limit). */
export function chipText(value: string, max: number = CHIP_MAX_CHARS): string {
  return clipVisible(value, max);
}

/**
 * `size` is the chip that used to be called "angle": it always shows the shot size's name
 * (decision 3, 2026-09-26: the old chip was really the shot size word). `height` and `light` from a
 * shot card are our own fixed words; `where` is free text and is cut to 20 visible characters.
 */
export type ChipKind = 'size' | 'height' | 'light' | 'where' | 'sit_or_walk';

export interface SetupChip {
  kind: ChipKind;
  /** The cut chip text. */
  text: string;
  /** The whole value, for the text line. */
  full: string;
}

export const MAX_SETUP_CHIPS = 3;
/** With a shot card: size, camera height, light and place. Our fixed words are short, and the
 *  bottom panel wraps them to at most 2 rows on a 360 px phone. */
export const MAX_CARD_CHIPS = 4;
/** Our own fixed chip words (size, height, light) are never cut below this. */
export const FIXED_CHIP_MAX_CHARS = 24;

/** The card chips' words in the chat's language, worked out by the sheet from its own copy; null
 *  for a field the card does not know (`?` draws nothing). */
export interface CardChipWords {
  height: string | null;
  light: string | null;
}

/**
 * The set-up chips.
 *   - With a shot card: the size, then the card's camera height, light and place, each only when
 *     the card knows it (a `?` field gets no chip, and nothing is filled in from elsewhere).
 *   - Without one: up to 3, from the size, where, light, sit_or_walk in that order (`line`,
 *     `action`, `angle` and `on_camera` never become chips; `angle` stays in the text line).
 */
export function setupChips(
  shot: ShootCheckShot | null | undefined,
  sizeName: string,
  cardWords?: CardChipWords
): SetupChip[] {
  const size: SetupChip = { kind: 'size', text: chipText(sizeName, FIXED_CHIP_MAX_CHARS), full: sizeName };
  const card = shot?.card;
  if (card) {
    const chips: SetupChip[] = [size];
    for (const [kind, word] of [
      ['height', cardWords?.height],
      ['light', cardWords?.light],
    ] as const) {
      const full = cleanShotText(word ?? '');
      if (full) chips.push({ kind, text: chipText(full, FIXED_CHIP_MAX_CHARS), full });
    }
    const place = card.place === CARD_UNKNOWN ? '' : cleanShotText(card.place);
    if (place) chips.push({ kind: 'where', text: chipText(place), full: place });
    return chips.slice(0, MAX_CARD_CHIPS);
  }
  const context = shot?.context ?? {};
  const values: Array<[ChipKind, string]> = [
    ['where', cleanShotText(context.where)],
    ['light', cleanShotText(context.light)],
    ['sit_or_walk', cleanShotText(context.sit_or_walk)],
  ];
  const rest = values
    .filter(([, full]) => full.length > 0)
    .map(([kind, full]) => ({ kind, text: chipText(full), full }));
  return [size, ...rest].slice(0, MAX_SETUP_CHIPS);
}
