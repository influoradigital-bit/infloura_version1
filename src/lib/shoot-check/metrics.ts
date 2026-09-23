/**
 * Shoot Check — pure metrics.
 *
 * Everything in this file is a PURE function: no DOM, no camera, no `window`. Every input is
 * plain data (a pixel buffer, a couple of numbers, a box) so it can be exercised with synthetic
 * fixtures in `metrics.test.ts` without a real device. `useShootCheck` (the camera-facing hook)
 * is the only caller — it samples the live preview into a small offscreen canvas ~4x/second and
 * feeds the resulting `Uint8ClampedArray` through these functions.
 *
 * Coordinate convention: `Box` and `Size` are always in the SAME pixel space as each other for
 * any one call — e.g. a face box from `FaceDetector` paired with `{width: video.videoWidth,
 * height: video.videoHeight}`, or a subject box already rescaled into the 160x90 analysis
 * canvas paired with that canvas's `Size`. Nothing here assumes a particular resolution.
 */

// ---------------------------------------------------------------------------
// Shared types
// ---------------------------------------------------------------------------

/** Axis-aligned box in pixel space, top-left origin — same convention as `DOMRectReadOnly`. */
export interface Box {
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface Size {
  width: number;
  height: number;
}

/**
 * What the current shot is trying to be. A close-up wants the subject's face filling most of
 * the frame; a hands-overhead shot (e.g. a top-down product/craft demo) has NO face in it at
 * all by design — `framingVerdict` treats that as correct, not as a failure to detect a face.
 */
export type ShotTarget = 'closeup' | 'medium' | 'wide' | 'hands-overhead';

// ---------------------------------------------------------------------------
// THRESHOLDS
// ---------------------------------------------------------------------------

/**
 * Every numeric cutoff this module uses, in one place.
 *
 * These are CRAFT HEURISTICS — chosen so the synthetic test fixtures in `metrics.test.ts`
 * produce sane, self-consistent verdicts. They are NOT calibrated against real footage from our
 * own creators yet. Expect to retune every one of these once we have field data from Level 2
 * (`/ai/shoot-check/frame`) and real shoot sessions; nothing here is a promise about accuracy on
 * an actual phone.
 */
export const THRESHOLDS = {
  // --- light (0..100 luma) ---
  /** Below this, the frame is too dark to trust ANYTHING derived from pixel content — including
   * focus, since sensor grain in a dark frame reads as high-frequency detail. */
  DARK_LUMA: 25,
  /** Below this (but at/above DARK_LUMA) the frame is dim but focus can still be judged. */
  DIM_LUMA: 35,
  /** Above this the frame is blown out / overexposed. */
  BRIGHT_LUMA: 90,

  // --- focus (Laplacian variance of a downscaled grayscale frame) ---
  /** Below this, the frame reads as out of focus. Deliberately low-resolution math (160x90
   * analysis canvas) so this is a coarse "is it obviously blurry" signal, not a lens-grade MTF
   * measurement. */
  SHARP_MIN: 40,

  // --- background clutter (0..100, edge-pixel density outside the subject box) ---
  /** Per-pixel gradient magnitude (0..510: sum of horizontal + vertical abs delta on a 0..255
   * grayscale channel) above which a pixel counts as an "edge" for clutter purposes. */
  CLUTTER_EDGE_PIXEL_DELTA: 40,
  /** Edge-pixel density above which the background reads as busy. */
  CLUTTER_BUSY: 55,

  // --- tilt (absolute degrees off level) ---
  TILT_OK_DEG: 3,
  TILT_WARN_DEG: 8,

  // --- mic (dB GAP between voice level and the room's noise floor, never raw volume alone —
  // a loud room and a quiet voice can read the same raw dB as a quiet room and a normal voice) ---
  MIC_GOOD_GAP_DB: 12,
  MIC_LOW_GAP_DB: 6,
  /** Below this gap there is no voice activity to judge at all — a silent room reads at (or just
   * above) its own noise floor, and running THAT through `micVerdict` scores it as
   * `MIC_LOW_GAP_DB`-or-worse ("room noise is drowning your voice") even though nobody is
   * talking. Deliberately smaller than `MIC_LOW_GAP_DB`: this is a "is anyone speaking at all"
   * gate, not a quality judgment — a real, poorly-separated voice still clears it and gets
   * judged by `micVerdict` as before. */
  MIC_VOICE_ACTIVE_GAP_DB: 4,

  // --- framing: face-height as a fraction of frame height, per shot target ---
  FACE_CLOSEUP_MIN_FRACTION: 0.35,
  FACE_MEDIUM_MIN_FRACTION: 0.15,
  FACE_MEDIUM_MAX_FRACTION: 0.45,
  FACE_WIDE_MAX_FRACTION: 0.2,
  // --- framing: headroom (face top / frame height) ---
  HEADROOM_MIN: 0.05,
  HEADROOM_MAX: 0.22,
  // --- framing: how far the face center may drift from the horizontal midline before we say
  // something, as a fraction of frame width ---
  CENTER_TOLERANCE: 0.12,
} as const;

// ---------------------------------------------------------------------------
// Light
// ---------------------------------------------------------------------------

/** Perceptual luma (ITU-R BT.601), averaged over every pixel, scaled to 0..100. */
export function averageLuma(data: Uint8ClampedArray): number {
  if (data.length < 4) return 0;
  let sum = 0;
  let count = 0;
  for (let i = 0; i + 2 < data.length; i += 4) {
    sum += 0.299 * data[i] + 0.587 * data[i + 1] + 0.114 * data[i + 2];
    count++;
  }
  if (count === 0) return 0;
  return clamp((sum / count / 255) * 100, 0, 100);
}

export type LightStatus = 'dark' | 'dim' | 'ok' | 'bright';
export type LightAdvice = 'add-light' | 'brighten' | 'reduce-light' | 'ok';

export function lightVerdict(luma: number): { status: LightStatus; advice: LightAdvice } {
  if (luma < THRESHOLDS.DARK_LUMA) return { status: 'dark', advice: 'add-light' };
  if (luma < THRESHOLDS.DIM_LUMA) return { status: 'dim', advice: 'brighten' };
  if (luma > THRESHOLDS.BRIGHT_LUMA) return { status: 'bright', advice: 'reduce-light' };
  return { status: 'ok', advice: 'ok' };
}

// ---------------------------------------------------------------------------
// Grayscale helper (shared by sharpness + clutter)
// ---------------------------------------------------------------------------

function toGrayscale(data: Uint8ClampedArray, w: number, h: number): Float64Array {
  const gray = new Float64Array(w * h);
  for (let i = 0, p = 0; p < w * h && i + 2 < data.length; i += 4, p++) {
    gray[p] = 0.299 * data[i] + 0.587 * data[i + 1] + 0.114 * data[i + 2];
  }
  return gray;
}

// ---------------------------------------------------------------------------
// Sharpness (Laplacian variance)
// ---------------------------------------------------------------------------

/**
 * Laplacian-variance sharpness estimate. Higher is sharper; a flat/blurred image has near-zero
 * variance because every neighbor is close to its center pixel, while a sharp, high-contrast
 * image has large swings at every edge.
 */
export function sharpness(data: Uint8ClampedArray, w: number, h: number): number {
  if (w < 3 || h < 3 || data.length < w * h * 4) return 0;
  const gray = toGrayscale(data, w, h);

  const samples: number[] = [];
  for (let y = 1; y < h - 1; y++) {
    for (let x = 1; x < w - 1; x++) {
      const idx = y * w + x;
      const lap = gray[idx - w] + gray[idx + w] + gray[idx - 1] + gray[idx + 1] - 4 * gray[idx];
      samples.push(lap);
    }
  }
  if (samples.length === 0) return 0;

  const mean = samples.reduce((a, b) => a + b, 0) / samples.length;
  const variance = samples.reduce((a, b) => a + (b - mean) * (b - mean), 0) / samples.length;
  // Scaled down so THRESHOLDS.SHARP_MIN sits in a friendly tens-range instead of the
  // hundred-thousands a raw 0..255 Laplacian variance produces on high-contrast input.
  return variance / 20;
}

export type FocusStatus = 'unknown' | 'blurry' | 'ok';
export type FocusAdvice = 'unknown' | 'hold-steady-or-clean-lens' | 'ok';

/**
 * Focus must NOT be judged in the dark — sensor grain in a near-black frame produces high
 * pixel-to-pixel variance that reads as "sharp" even when the shot is unusable. Below
 * `THRESHOLDS.DARK_LUMA`, this returns `unknown` instead of trusting the Laplacian number.
 */
export function focusVerdict(sharpnessValue: number, luma: number): { status: FocusStatus; advice: FocusAdvice } {
  if (luma < THRESHOLDS.DARK_LUMA) return { status: 'unknown', advice: 'unknown' };
  if (sharpnessValue < THRESHOLDS.SHARP_MIN) return { status: 'blurry', advice: 'hold-steady-or-clean-lens' };
  return { status: 'ok', advice: 'ok' };
}

// ---------------------------------------------------------------------------
// Clutter (background busyness)
// ---------------------------------------------------------------------------

function toPixelBounds(box: Box, w: number, h: number) {
  return {
    x0: clamp(Math.round(box.x), 0, w),
    y0: clamp(Math.round(box.y), 0, h),
    x1: clamp(Math.round(box.x + box.width), 0, w),
    y1: clamp(Math.round(box.y + box.height), 0, h),
  };
}

function isInsideBounds(x: number, y: number, b: { x0: number; y0: number; x1: number; y1: number }): boolean {
  return x >= b.x0 && x < b.x1 && y >= b.y0 && y < b.y1;
}

/**
 * Edge-pixel density (0..100) OUTSIDE the subject box — how busy the background is, ignoring
 * whatever the subject itself is doing. With no subject box, the whole frame counts.
 */
export function clutter(data: Uint8ClampedArray, w: number, h: number, subject?: Box): number {
  if (w < 2 || h < 2 || data.length < w * h * 4) return 0;
  const gray = toGrayscale(data, w, h);
  const bounds = subject ? toPixelBounds(subject, w, h) : null;

  let edgeCount = 0;
  let total = 0;
  for (let y = 0; y < h - 1; y++) {
    for (let x = 0; x < w - 1; x++) {
      if (bounds && isInsideBounds(x, y, bounds)) continue;
      const idx = y * w + x;
      const gx = Math.abs(gray[idx + 1] - gray[idx]);
      const gy = Math.abs(gray[idx + w] - gray[idx]);
      total++;
      if (gx + gy > THRESHOLDS.CLUTTER_EDGE_PIXEL_DELTA) edgeCount++;
    }
  }
  if (total === 0) return 0;
  return clamp((edgeCount / total) * 100, 0, 100);
}

// ---------------------------------------------------------------------------
// Framing
// ---------------------------------------------------------------------------

export type FramingStatus = 'ok' | 'adjust' | 'unknown';
export type FramingAdvice =
  | 'lift-phone'
  | 'lower-phone'
  | 'come-closer'
  | 'step-back'
  | 'move-left'
  | 'move-right'
  | 'point-at-hands'
  | 'no-face'
  | 'ok';

export interface FramingVerdict {
  status: FramingStatus;
  advice: FramingAdvice;
}

function faceSizeRangeFor(target: ShotTarget): { min: number; max: number } {
  switch (target) {
    case 'closeup':
      return { min: THRESHOLDS.FACE_CLOSEUP_MIN_FRACTION, max: Number.POSITIVE_INFINITY };
    case 'medium':
      return { min: THRESHOLDS.FACE_MEDIUM_MIN_FRACTION, max: THRESHOLDS.FACE_MEDIUM_MAX_FRACTION };
    case 'wide':
      return { min: 0, max: THRESHOLDS.FACE_WIDE_MAX_FRACTION };
    case 'hands-overhead':
      // Unreachable — hands-overhead short-circuits in framingVerdict before this is consulted.
      return { min: 0, max: Number.POSITIVE_INFINITY };
  }
}

/**
 * Turns a detected face box (or its absence) into one piece of framing advice.
 *
 * `hands-overhead` is the special case the rest of this function must never reach: it expects
 * NO face (the camera is pointed down at hands/a surface), so a missing face there is the
 * CORRECT state, not a failure — and if a face IS seen, that means the phone is pointed at the
 * creator's face instead of their hands, which is never described as "lift the phone" (there is
 * no eye-line concept for an overhead shot at all).
 */
export function framingVerdict(face: Box | null, frame: Size, target: ShotTarget): FramingVerdict {
  if (target === 'hands-overhead') {
    return face ? { status: 'adjust', advice: 'point-at-hands' } : { status: 'ok', advice: 'ok' };
  }

  if (!face || frame.width <= 0 || frame.height <= 0) {
    return { status: 'unknown', advice: 'no-face' };
  }

  const { min, max } = faceSizeRangeFor(target);
  const faceHeightFraction = face.height / frame.height;
  if (faceHeightFraction < min) return { status: 'adjust', advice: 'come-closer' };
  if (faceHeightFraction > max) return { status: 'adjust', advice: 'step-back' };

  const headroomFraction = face.y / frame.height;
  if (headroomFraction > THRESHOLDS.HEADROOM_MAX) return { status: 'adjust', advice: 'lift-phone' };
  if (headroomFraction < THRESHOLDS.HEADROOM_MIN) return { status: 'adjust', advice: 'lower-phone' };

  const faceCenterXFraction = (face.x + face.width / 2) / frame.width;
  if (faceCenterXFraction < 0.5 - THRESHOLDS.CENTER_TOLERANCE) return { status: 'adjust', advice: 'move-right' };
  if (faceCenterXFraction > 0.5 + THRESHOLDS.CENTER_TOLERANCE) return { status: 'adjust', advice: 'move-left' };

  return { status: 'ok', advice: 'ok' };
}

// ---------------------------------------------------------------------------
// Tilt
// ---------------------------------------------------------------------------

export type TiltStatus = 'ok' | 'warn' | 'bad';
export type TiltAdvice = 'level-it' | 'ok';

/**
 * Classifies an absolute tilt reading in degrees. Callers whose device never reports orientation
 * represent that as a separate `'unknown'` state at the reading level (see `useShootCheck`) —
 * this function only ever receives a real number, never invents 0 for "no sensor".
 */
export function tiltVerdict(degrees: number): { status: TiltStatus; advice: TiltAdvice } {
  const abs = Math.abs(degrees);
  if (abs <= THRESHOLDS.TILT_OK_DEG) return { status: 'ok', advice: 'ok' };
  if (abs <= THRESHOLDS.TILT_WARN_DEG) return { status: 'warn', advice: 'level-it' };
  return { status: 'bad', advice: 'level-it' };
}

// ---------------------------------------------------------------------------
// Mic
// ---------------------------------------------------------------------------

export type MicStatus = 'poor-separation' | 'weak-separation' | 'ok';
export type MicAdvice = 'reduce-background-noise' | 'move-closer-to-mic' | 'ok';

/**
 * Judges the GAP (dB) between the voice level and the room's noise floor — never raw volume on
 * its own. A loud room with a loud voice can have the same raw `levelDb` as a quiet room with a
 * quiet voice; only the gap tells them apart, and only the gap tells us whether the audio will
 * actually be intelligible once background noise is present.
 */
export function micVerdict(levelDb: number, noiseFloorDb: number): { status: MicStatus; advice: MicAdvice } {
  const gap = levelDb - noiseFloorDb;
  if (gap < THRESHOLDS.MIC_LOW_GAP_DB) return { status: 'poor-separation', advice: 'reduce-background-noise' };
  if (gap < THRESHOLDS.MIC_GOOD_GAP_DB) return { status: 'weak-separation', advice: 'move-closer-to-mic' };
  return { status: 'ok', advice: 'ok' };
}

/**
 * True when the current level is clearly above the (slow-moving) noise floor — i.e. there is an
 * actual voice signal to judge. A silent room has `levelDb` sitting AT its own noise floor (the
 * floor estimate drops instantly toward a quieter reading), so `levelDb - noiseFloorDb` is near
 * zero there — well under `MIC_LOW_GAP_DB` — and feeding that straight into `micVerdict` reads as
 * "poor-separation" / "Room noise is drowning your voice" with nobody talking. Callers MUST gate
 * `micVerdict` on this: only judge the gap once it's this function that says someone is actually
 * making sound.
 */
export function hasVoiceActivity(levelDb: number, noiseFloorDb: number): boolean {
  return levelDb - noiseFloorDb >= THRESHOLDS.MIC_VOICE_ACTIVE_GAP_DB;
}

// ---------------------------------------------------------------------------
// Shared util
// ---------------------------------------------------------------------------

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}
