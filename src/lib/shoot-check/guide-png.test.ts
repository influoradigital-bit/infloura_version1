/**
 * guide-png.ts (spec Phase 4): the CapCut guide is a transparent 1080 x 1920 PNG of outlines only,
 * never photo pixels, and its download URL is revoked.
 *
 * jsdom has no canvas, so `RasterContext` below rasterises the calls `drawGuide` makes into a real
 * RGBA buffer (rectangles and text as solid bands; dashes drawn solid, which only adds pixels). Any
 * `drawImage`/`putImageData` would paint the source's colour into that buffer, so a guide that drew
 * the photo would leave the photo's unique colour behind.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  GUIDE_COLOURS,
  GUIDE_FILENAME,
  GUIDE_URL_REVOKE_MS,
  downloadGuidePng,
  drawGuide,
  renderGuidePng,
  type GuideCanvas,
  type GuideCanvasContext,
  type GuidePngInput,
} from './guide-png';
import { PLANE_H, PLANE_W, planReelLayout } from './reel-layout';
import { INTERIM_SAFE_ZONES } from './safe-zones';

type RGBA = [number, number, number, number];

function parseColour(css: unknown): RGBA {
  const m = /^#([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(String(css));
  if (!m) throw new Error(`unexpected colour ${String(css)}`);
  return [parseInt(m[1], 16), parseInt(m[2], 16), parseInt(m[3], 16), 255];
}

/** A photo stand-in whose every pixel is one colour no guide colour uses. */
const PHOTO_COLOUR: RGBA = [1, 254, 3, 255];
const PHOTO = { width: 1080, height: 1920, colour: PHOTO_COLOUR };

class RasterContext {
  data = new Uint8ClampedArray(PLANE_W * PLANE_H * 4);
  lineWidth = 1;
  strokeStyle: unknown = '#000000';
  fillStyle: unknown = '#000000';
  font = '10px sans-serif';
  textAlign = 'left';
  textBaseline = 'top';
  lineJoin = 'miter';
  imageDraws = 0;

  constructor() {
    // Start from garbage, so only clearRect can make the canvas transparent.
    this.data.fill(77);
  }

  private paint(x: number, y: number, w: number, h: number, c: RGBA) {
    const x0 = Math.max(0, Math.floor(x));
    const y0 = Math.max(0, Math.floor(y));
    const x1 = Math.min(PLANE_W, Math.ceil(x + w));
    const y1 = Math.min(PLANE_H, Math.ceil(y + h));
    for (let yy = y0; yy < y1; yy++) {
      for (let xx = x0; xx < x1; xx++) this.data.set(c, (yy * PLANE_W + xx) * 4);
    }
  }

  private fontSize() {
    return Number(/(\d+)px/.exec(this.font)?.[1] ?? 10);
  }

  save() {}
  restore() {}
  setLineDash() {}
  clearRect(x: number, y: number, w: number, h: number) {
    this.paint(x, y, w, h, [0, 0, 0, 0]);
  }
  strokes: Array<{ x: number; y: number; w: number; h: number; colour: string }> = [];
  strokeRect(x: number, y: number, w: number, h: number) {
    this.strokes.push({ x, y, w, h, colour: String(this.strokeStyle) });
    const c = parseColour(this.strokeStyle);
    const half = this.lineWidth / 2;
    this.paint(x - half, y - half, w + this.lineWidth, this.lineWidth, c);
    this.paint(x - half, y + h - half, w + this.lineWidth, this.lineWidth, c);
    this.paint(x - half, y - half, this.lineWidth, h + this.lineWidth, c);
    this.paint(x + w - half, y - half, this.lineWidth, h + this.lineWidth, c);
  }
  // Paths: the guide only ever strokes axis-aligned polygons (the notched green area), so each
  // segment is painted as a band `lineWidth` wide, like a strokeRect side.
  private path: Array<{ x: number; y: number }> = [];
  private closed = false;
  beginPath() {
    this.path = [];
    this.closed = false;
  }
  moveTo(x: number, y: number) {
    this.path = [{ x, y }];
  }
  lineTo(x: number, y: number) {
    this.path.push({ x, y });
  }
  closePath() {
    this.closed = true;
  }
  stroke() {
    const c = parseColour(this.strokeStyle);
    const half = this.lineWidth / 2;
    const pts = this.closed && this.path.length > 0 ? [...this.path, this.path[0]] : this.path;
    for (let i = 1; i < pts.length; i++) {
      const a = pts[i - 1];
      const b = pts[i];
      if (a.x !== b.x && a.y !== b.y) throw new Error('the guide strokes axis-aligned segments only');
      const x0 = Math.min(a.x, b.x);
      const y0 = Math.min(a.y, b.y);
      this.paint(x0 - half, y0 - half, Math.abs(b.x - a.x) + this.lineWidth, Math.abs(b.y - a.y) + this.lineWidth, c);
    }
  }
  fillText(text: string, x: number, y: number) {
    this.paint(x, y, text.length * this.fontSize() * 0.55, this.fontSize(), parseColour(this.fillStyle));
  }
  strokeText(text: string, x: number, y: number) {
    const pad = this.lineWidth / 2;
    this.paint(x - pad, y - pad, text.length * this.fontSize() * 0.55 + 2 * pad, this.fontSize() + 2 * pad, parseColour(this.strokeStyle));
  }
  drawImage(source: { colour?: RGBA }) {
    this.imageDraws++;
    this.paint(0, 0, PLANE_W, PLANE_H, source.colour ?? [9, 9, 9, 255]);
  }
  putImageData() {
    this.imageDraws++;
    this.paint(0, 0, PLANE_W, PLANE_H, PHOTO_COLOUR);
  }
}

function fakeCanvas(ctx: RasterContext | null) {
  const toBlob = vi.fn((cb: (b: Blob | null) => void, type?: string) => cb(new Blob(['png'], { type: type ?? '' })));
  const canvas = {
    width: 300,
    height: 150,
    getContext: vi.fn(() => ctx as unknown as GuideCanvasContext | null),
    toBlob,
  } satisfies GuideCanvas;
  return canvas;
}

function guideInput(): GuidePngInput {
  const plan = planReelLayout(
    { width: 1080, height: 1920 },
    {
      faces: [{ x: 260 / 1080, y: 540 / 1920, w: 200 / 1080, h: 220 / 1920 }],
      product: { x: 620 / 1080, y: 830 / 1920, w: 200 / 1080, h: 160 / 1920 },
    },
    INTERIM_SAFE_ZONES,
  );
  return {
    zones: plan.zones,
    faces: plan.faces,
    slots: plan.slots,
    labels: {
      banner: 'GUIDE - DELETE BEFORE EXPORT',
      cta: 'SHORT CTA, LEFT SIDE',
      slots: { hook: 'HOOK TEXT', captions: 'CAPTIONS', logo: 'LOGO', sticker: 'STICKER' },
    },
  };
}

afterEach(() => {
  vi.restoreAllMocks();
  vi.useRealTimers();
});

describe('CapCut guide PNG', () => {
  it('is a 1080 x 1920 PNG', async () => {
    const ctx = new RasterContext();
    const canvas = fakeCanvas(ctx);
    const blob = await renderGuidePng(guideInput(), () => canvas);
    expect(canvas.width).toBe(1080);
    expect(canvas.height).toBe(1920);
    expect(canvas.getContext).toHaveBeenCalledWith('2d');
    expect(canvas.toBlob).toHaveBeenCalledWith(expect.any(Function), 'image/png');
    expect(blob?.type).toBe('image/png');
  });

  it('is mostly transparent, and every painted pixel is an opaque guide colour: outlines only, no photo pixels', () => {
    const ctx = new RasterContext();
    // The photo rides along as an extra key, the way a careless caller might pass it; the guide
    // must never draw it.
    drawGuide(ctx as unknown as GuideCanvasContext, { ...guideInput(), photo: PHOTO } as GuidePngInput);

    const allowed = new Set(Object.values(GUIDE_COLOURS).map((c) => parseColour(c).join(',')));
    let transparent = 0;
    let photoPixels = 0;
    const strays = new Set<string>();
    for (let i = 0; i < ctx.data.length; i += 4) {
      const a = ctx.data[i + 3];
      if (a === 0) {
        transparent++;
        continue;
      }
      const key = `${ctx.data[i]},${ctx.data[i + 1]},${ctx.data[i + 2]},${a}`;
      if (key === PHOTO_COLOUR.join(',')) photoPixels++;
      else if (!allowed.has(key)) strays.add(key);
    }
    expect(ctx.imageDraws).toBe(0);
    expect(photoPixels).toBe(0);
    expect([...strays]).toEqual([]);
    // Outlines only: the frame is overwhelmingly see-through.
    expect(transparent / (PLANE_W * PLANE_H)).toBeGreaterThan(0.75);
    // Inside a face box and inside the green area, between outlines: transparent.
    const alphaAt = (x: number, y: number) => ctx.data[(y * PLANE_W + x) * 4 + 3];
    expect(alphaAt(360, 650)).toBe(0);
    expect(alphaAt(540, 880)).toBe(0);
  });

  it('draws the banner at the top and an outline on the safe area’s edge', () => {
    const ctx = new RasterContext();
    drawGuide(ctx as unknown as GuideCanvasContext, guideInput());
    const at = (x: number, y: number) => Array.from(ctx.data.slice((y * PLANE_W + x) * 4, (y * PLANE_W + x) * 4 + 4));
    expect(at(540, 269)).toEqual(parseColour(GUIDE_COLOURS.safe));
    // Banner text box starts at (48, 40), 44px type.
    expect(at(60, 60)[3]).toBe(255);
  });

  it('outlines the green area minus the rail notch, and the covered caption area beside the short CTA', () => {
    const ctx = new RasterContext();
    const input = guideInput();
    drawGuide(ctx as unknown as GuideCanvasContext, input);
    const at = (x: number, y: number) => Array.from(ctx.data.slice((y * PLANE_W + x) * 4, (y * PLANE_W + x) * 4 + 4));
    const { safe, rail, coveredCaption, captionLineY } = input.zones;
    // The notch: green runs along the rail's left side (x 929) from its top (y 960) to the caption
    // line, and along the rail's top; the safe rectangle's right edge stops at the rail's top.
    expect(at(rail.x, Math.round((rail.y + captionLineY) / 2))).toEqual(parseColour(GUIDE_COLOURS.safe));
    expect(at(Math.round((rail.x + safe.x + safe.w) / 2), rail.y)).toEqual(parseColour(GUIDE_COLOURS.safe));
    expect(at(safe.x + safe.w, Math.round((rail.y + captionLineY) / 2))).not.toEqual(parseColour(GUIDE_COLOURS.safe));
    // The covered caption area (x 594-929, y 1248-1498) is outlined in red. Its edges coincide with
    // the CTA band, the green line, the rail and the covered bottom, so the call is what is checked.
    expect(coveredCaption).toEqual({ x: 594, y: 1248, w: 929 - 594, h: 1498 - 1248 });
    expect(ctx.strokes).toContainEqual({ ...coveredCaption, colour: GUIDE_COLOURS.covered });
    // The safe area is never drawn as the plain rectangle (that would run through the rail).
    expect(ctx.strokes.some((r) => r.colour === GUIDE_COLOURS.safe)).toBe(false);
  });

  it('a phone with no 2D canvas gives null, never a throw', async () => {
    await expect(renderGuidePng(guideInput(), () => fakeCanvas(null))).resolves.toBeNull();
    await expect(
      renderGuidePng(guideInput(), () => {
        throw new Error('no canvas');
      }),
    ).resolves.toBeNull();
  });

  it('downloads through an <a download> Blob URL and revokes the URL afterwards', () => {
    vi.useFakeTimers();
    const create = vi.fn(() => 'blob:guide-1');
    const revoke = vi.fn();
    Object.defineProperty(URL, 'createObjectURL', { value: create, configurable: true, writable: true });
    Object.defineProperty(URL, 'revokeObjectURL', { value: revoke, configurable: true, writable: true });
    const clicks: HTMLAnchorElement[] = [];
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      clicks.push(this);
    });

    const blob = new Blob(['png'], { type: 'image/png' });
    downloadGuidePng(blob);

    expect(create).toHaveBeenCalledWith(blob);
    expect(clicks).toHaveLength(1);
    expect(clicks[0].download).toBe(GUIDE_FILENAME);
    expect(clicks[0].getAttribute('href')).toBe('blob:guide-1');
    expect(clicks[0].isConnected).toBe(false);
    expect(revoke).not.toHaveBeenCalled();
    vi.advanceTimersByTime(GUIDE_URL_REVOKE_MS);
    expect(revoke).toHaveBeenCalledWith('blob:guide-1');
  });
});
