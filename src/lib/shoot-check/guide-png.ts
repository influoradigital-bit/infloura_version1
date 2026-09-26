/**
 * The CapCut guide PNG (spec Phase 4): a TRANSPARENT 1080 x 1920 image made only of outlines, for
 * the creator to lay over their video in an editor, line things up, and delete before export.
 *
 * What it draws: the safe zone from the config (covered top and bottom, the edges, the right-side
 * button rail, the short-CTA band and the covered caption area beside it, the green safe area minus
 * the rail's notch), face "keep clear" boxes, the placed slots
 * with their labels, and "GUIDE - DELETE BEFORE EXPORT" at the top. What it never draws: a photo
 * pixel, a name, or any text beyond those labels. It takes zones and slots as INPUT (never a photo),
 * so the after-launch marketing overlay (spec section 15) can reuse it with no faces and no slots.
 *
 * jsdom has no real canvas, so drawing goes through `GuideCanvasContext` (the few 2D-context calls
 * this file makes) and the canvas comes from an injectable factory; the tests rasterise those calls
 * into a real pixel buffer.
 */
import { PLANE_H, PLANE_W, type Rect, type ReelZones, type SlotName } from './reel-layout';

/** The 2D-context calls the guide makes. A real `CanvasRenderingContext2D` satisfies it. */
export type GuideCanvasContext = Pick<
  CanvasRenderingContext2D,
  | 'clearRect'
  | 'strokeRect'
  | 'beginPath'
  | 'moveTo'
  | 'lineTo'
  | 'closePath'
  | 'stroke'
  | 'fillText'
  | 'strokeText'
  | 'setLineDash'
  | 'save'
  | 'restore'
  | 'lineWidth'
  | 'strokeStyle'
  | 'fillStyle'
  | 'font'
  | 'textAlign'
  | 'textBaseline'
  | 'lineJoin'
>;

export interface GuideCanvas {
  width: number;
  height: number;
  getContext(contextId: '2d'): GuideCanvasContext | null;
  toBlob(callback: (blob: Blob | null) => void, type?: string): void;
}

export type CreateGuideCanvas = () => GuideCanvas;

export interface GuidePngInput {
  zones: ReelZones;
  /** Face boxes on the 1080 x 1920 plane, drawn as "keep clear" outlines. May be empty. */
  faces: readonly Rect[];
  /** Placed slots on the plane; a null or missing slot is not drawn. */
  slots: Partial<Record<SlotName, Rect | null>>;
  labels: {
    banner: string;
    cta: string;
    slots: Record<SlotName, string>;
  };
}

/** Every colour the guide paints with, all fully opaque. Nothing else ever lands in the PNG. */
export const GUIDE_COLOURS = {
  covered: '#ff3b30',
  cta: '#ffb020',
  safe: '#34c759',
  face: '#ffffff',
  slot: '#ffffff',
  halo: '#000000',
} as const;

export const GUIDE_FILENAME = 'influora-reel-guide.png';

/** Draws with a dark under-stroke first, so every outline reads on light and dark video alike. */
function outline(ctx: GuideCanvasContext, r: Rect, colour: string, width: number, dash: number[] = []) {
  if (r.w <= 0 || r.h <= 0) return;
  ctx.setLineDash(dash);
  ctx.strokeStyle = GUIDE_COLOURS.halo;
  ctx.lineWidth = width + 4;
  ctx.strokeRect(r.x, r.y, r.w, r.h);
  ctx.strokeStyle = colour;
  ctx.lineWidth = width;
  ctx.strokeRect(r.x, r.y, r.w, r.h);
}

/** A closed outline through `points` (the notched green area), with the same dark under-stroke. */
function outlinePolygon(
  ctx: GuideCanvasContext,
  points: ReadonlyArray<{ x: number; y: number }>,
  colour: string,
  width: number,
) {
  if (points.length < 3) return;
  const trace = () => {
    ctx.beginPath();
    ctx.moveTo(points[0].x, points[0].y);
    for (const p of points.slice(1)) ctx.lineTo(p.x, p.y);
    ctx.closePath();
    ctx.stroke();
  };
  ctx.setLineDash([]);
  ctx.strokeStyle = GUIDE_COLOURS.halo;
  ctx.lineWidth = width + 4;
  trace();
  ctx.strokeStyle = colour;
  ctx.lineWidth = width;
  trace();
}

function label(ctx: GuideCanvasContext, text: string, x: number, y: number, size: number, colour: string) {
  ctx.setLineDash([]);
  ctx.font = `bold ${size}px sans-serif`;
  ctx.textBaseline = 'top';
  ctx.textAlign = 'left';
  ctx.lineJoin = 'round';
  ctx.strokeStyle = GUIDE_COLOURS.halo;
  ctx.lineWidth = 6;
  ctx.strokeText(text, x, y);
  ctx.fillStyle = colour;
  ctx.fillText(text, x, y);
}

/** Draws the whole guide on a 1080 x 1920 context, starting from fully transparent. */
export function drawGuide(ctx: GuideCanvasContext, input: GuidePngInput): void {
  const { zones, faces, slots, labels } = input;
  ctx.save();
  ctx.clearRect(0, 0, PLANE_W, PLANE_H);

  outline(ctx, zones.coveredTop, GUIDE_COLOURS.covered, 6);
  outline(ctx, zones.coveredBottom, GUIDE_COLOURS.covered, 6);
  outline(ctx, zones.sideLeft, GUIDE_COLOURS.covered, 4);
  outline(ctx, zones.sideRight, GUIDE_COLOURS.covered, 4);
  outline(ctx, zones.rail, GUIDE_COLOURS.covered, 6, [18, 12]);
  outline(ctx, zones.coveredCaption, GUIDE_COLOURS.covered, 4);
  outline(ctx, zones.ctaBand, GUIDE_COLOURS.cta, 6, [18, 12]);
  outlinePolygon(ctx, zones.safeOutline, GUIDE_COLOURS.safe, 6);
  if (zones.ctaBand.w > 0 && zones.ctaBand.h > 0) {
    label(ctx, labels.cta, zones.ctaBand.x + 16, zones.ctaBand.y + 16, 32, GUIDE_COLOURS.cta);
  }

  for (const face of faces) outline(ctx, face, GUIDE_COLOURS.face, 4, [14, 10]);

  for (const name of Object.keys(labels.slots) as SlotName[]) {
    const slot = slots[name];
    if (!slot) continue;
    outline(ctx, slot, GUIDE_COLOURS.slot, 4, [22, 12]);
    label(ctx, labels.slots[name], slot.x + 12, slot.y + 12, 30, GUIDE_COLOURS.slot);
  }

  // Inside the covered top bar: it is a guide layer, never content, so it sits where text never goes.
  label(ctx, labels.banner, 48, 40, 44, GUIDE_COLOURS.slot);
  ctx.restore();
}

function defaultCreateCanvas(): GuideCanvas {
  const canvas = document.createElement('canvas');
  return {
    get width() {
      return canvas.width;
    },
    set width(value: number) {
      canvas.width = value;
    },
    get height() {
      return canvas.height;
    },
    set height(value: number) {
      canvas.height = value;
    },
    getContext: () => canvas.getContext('2d'),
    toBlob: (callback, type) => canvas.toBlob(callback, type),
  };
}

/** The guide as a PNG Blob, or null where the browser cannot draw or encode it. Never throws. */
export async function renderGuidePng(
  input: GuidePngInput,
  createCanvas: CreateGuideCanvas = defaultCreateCanvas,
): Promise<Blob | null> {
  try {
    const canvas = createCanvas();
    canvas.width = PLANE_W;
    canvas.height = PLANE_H;
    const ctx = canvas.getContext('2d');
    if (!ctx) return null;
    drawGuide(ctx, input);
    return await new Promise<Blob | null>((resolve) => {
      try {
        canvas.toBlob((blob) => resolve(blob), 'image/png');
      } catch {
        resolve(null);
      }
    });
  } catch {
    return null;
  }
}

/** How long the Blob URL lives after the click: iOS Safari starts the save asynchronously, and a URL
 *  revoked in the same tick can fail it. */
export const GUIDE_URL_REVOKE_MS = 1000;

/** Saves `blob` through an `<a download>` Blob URL, then revokes the URL. */
export function downloadGuidePng(blob: Blob, filename: string = GUIDE_FILENAME): void {
  const url = URL.createObjectURL(blob);
  try {
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    a.rel = 'noopener';
    a.style.display = 'none';
    document.body.appendChild(a);
    a.click();
    a.remove();
  } finally {
    setTimeout(() => URL.revokeObjectURL(url), GUIDE_URL_REVOKE_MS);
  }
}
