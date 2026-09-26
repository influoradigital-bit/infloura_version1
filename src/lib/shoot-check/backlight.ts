/**
 * "Bright window behind the face" (spec Phase 4, code-side checks): compares the average luma of
 * the face box with a ring around it, on the still the chat is holding in memory.
 *
 * ON THE PHONE ONLY. influora-ai has no image library, so this runs here, on the newest card while
 * its photo is in memory, and its line is shown but never stored or sent anywhere.
 *
 * `backlightReading`/`isBacklit` are pure (a pixel buffer and a box in, numbers out), so they are
 * tested with synthetic buffers; `readStillPixels` is the one browser-facing helper.
 */
import type { MeeraGeomBox } from '@/lib/meera-api';

import { averageLuma } from './metrics';

/** An RGBA buffer, 4 bytes per pixel, row by row (the shape `ImageData` has). */
export interface PixelBuffer {
  data: Uint8ClampedArray;
  width: number;
  height: number;
}

/** The ring must be at least this bright (0..100 luma)... */
export const BACKLIGHT_RING_MIN = 75;
/** ...and this much brighter than the face. */
export const BACKLIGHT_GAP_MIN = 25;
/** The ring reaches this share of the face's width out from every side of the face box. */
export const BACKLIGHT_RING_FACTOR = 0.5;

/** Width the still is downscaled to before reading: plenty for two averages, cheap on any phone. */
export const BACKLIGHT_SAMPLE_WIDTH = 160;

/** Average luma of the pixels in [x0, x1) x [y0, y1), leaving out `hole`; null when none are left. */
function regionLuma(
  buf: PixelBuffer,
  x0: number,
  y0: number,
  x1: number,
  y1: number,
  hole?: { x0: number; y0: number; x1: number; y1: number },
): number | null {
  const picked: number[] = [];
  for (let y = y0; y < y1; y++) {
    for (let x = x0; x < x1; x++) {
      if (hole && x >= hole.x0 && x < hole.x1 && y >= hole.y0 && y < hole.y1) continue;
      const i = (y * buf.width + x) * 4;
      picked.push(buf.data[i], buf.data[i + 1], buf.data[i + 2], buf.data[i + 3]);
    }
  }
  if (picked.length === 0) return null;
  return averageLuma(Uint8ClampedArray.from(picked));
}

/**
 * The face's and the ring's average luma (0..100), or null when the buffer or the box cannot give
 * both. `face` is in shares of the photo as sent (spec 2.2), the same photo the buffer holds.
 */
export function backlightReading(buf: PixelBuffer, face: MeeraGeomBox): { face: number; ring: number } | null {
  const { width, height, data } = buf;
  if (!(width > 0 && height > 0) || data.length < width * height * 4) return null;
  const clampX = (v: number) => Math.min(width, Math.max(0, Math.round(v)));
  const clampY = (v: number) => Math.min(height, Math.max(0, Math.round(v)));
  const hole = {
    x0: clampX(face.x * width),
    y0: clampY(face.y * height),
    x1: clampX((face.x + face.w) * width),
    y1: clampY((face.y + face.h) * height),
  };
  if (hole.x1 <= hole.x0 || hole.y1 <= hole.y0) return null;
  const reach = BACKLIGHT_RING_FACTOR * face.w * width;
  const faceLuma = regionLuma(buf, hole.x0, hole.y0, hole.x1, hole.y1);
  const ringLuma = regionLuma(
    buf,
    clampX(hole.x0 - reach),
    clampY(hole.y0 - reach),
    clampX(hole.x1 + reach),
    clampY(hole.y1 + reach),
    hole,
  );
  if (faceLuma === null || ringLuma === null) return null;
  return { face: faceLuma, ring: ringLuma };
}

/** Ring at least `BACKLIGHT_RING_MIN` and at least `BACKLIGHT_GAP_MIN` brighter than the face. */
export function isBacklit(buf: PixelBuffer, face: MeeraGeomBox): boolean {
  const reading = backlightReading(buf, face);
  if (!reading) return false;
  return reading.ring >= BACKLIGHT_RING_MIN && reading.ring - reading.face >= BACKLIGHT_GAP_MIN;
}

/** The in-memory still as a small RGBA buffer, or null wherever the browser cannot decode or read it
 *  (no `createImageBitmap`, no 2D canvas, a tainted canvas). Never throws. */
export async function readStillPixels(blob: Blob, maxWidth: number = BACKLIGHT_SAMPLE_WIDTH): Promise<PixelBuffer | null> {
  try {
    if (typeof createImageBitmap !== 'function') return null;
    const bitmap = await createImageBitmap(blob);
    try {
      if (!bitmap.width || !bitmap.height) return null;
      const scale = Math.min(1, maxWidth / bitmap.width);
      const width = Math.max(1, Math.round(bitmap.width * scale));
      const height = Math.max(1, Math.round(bitmap.height * scale));
      const canvas = document.createElement('canvas');
      canvas.width = width;
      canvas.height = height;
      const ctx = canvas.getContext('2d');
      if (!ctx) return null;
      ctx.drawImage(bitmap, 0, 0, width, height);
      const image = ctx.getImageData(0, 0, width, height);
      return { data: image.data, width, height };
    } finally {
      bitmap.close?.();
    }
  } catch {
    return null;
  }
}
