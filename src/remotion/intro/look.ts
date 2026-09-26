/**
 * Colours, type and the Meera symbol geometry for the intro film.
 *
 * The four light colours are the Meera orb's blobs (`components/feature/meera/MeeraOrb.tsx`)
 * so the film and the in-app orb read as the same being. Remotion renders outside Tailwind,
 * so everything here is a literal value.
 */
export const INK = {
  deep: '#05040f',
  night: '#0d0a24',
  text: '#f5f3ff',
  muted: '#aaa4cc',
  glass: 'rgba(255,255,255,0.06)',
  glassBorder: 'rgba(255,255,255,0.14)',
} as const;

export const LIGHT = {
  violet: '#6D5AE6',
  blue: '#2C7BE5',
  cyan: '#22D3EE',
  pink: '#FF5C9E',
  white: '#ffffff',
} as const;

export const GRADIENT = `linear-gradient(100deg, ${LIGHT.cyan} 0%, ${LIGHT.violet} 45%, ${LIGHT.pink} 100%)`;

export const FONT = '"Inter", "Segoe UI", Roboto, "Noto Sans", "Helvetica Neue", Arial, sans-serif';

type Pt = { x: number; y: number };
type Cubic = [Pt, Pt, Pt, Pt];

/**
 * The Meera symbol in a 200×200 box: one unbroken stroke that writes a flowing "M",
 * a four-point spark above its middle, and a ring around both.
 */
export const M_STROKE: Cubic[] = [
  [{ x: 36, y: 146 }, { x: 44, y: 110 }, { x: 50, y: 62 }, { x: 64, y: 60 }],
  [{ x: 64, y: 60 }, { x: 78, y: 58 }, { x: 88, y: 122 }, { x: 100, y: 122 }],
  [{ x: 100, y: 122 }, { x: 112, y: 122 }, { x: 122, y: 58 }, { x: 136, y: 60 }],
  [{ x: 136, y: 60 }, { x: 150, y: 62 }, { x: 156, y: 110 }, { x: 164, y: 146 }],
];
export const SPARK_CENTER: Pt = { x: 100, y: 40 };
export const RING_RADIUS = 92;

function cubicAt([a, b, c, d]: Cubic, t: number): Pt {
  const u = 1 - t;
  return {
    x: u * u * u * a.x + 3 * u * u * t * b.x + 3 * u * t * t * c.x + t * t * t * d.x,
    y: u * u * u * a.y + 3 * u * u * t * b.y + 3 * u * t * t * c.y + t * t * t * d.y,
  };
}

/** A sampled polyline with cumulative lengths, so the comet can ride it at constant speed. */
export type Track = { points: Pt[]; lengths: number[]; total: number };

export function sampleTrack(curves: Cubic[], stepsPerCurve = 48): Track {
  const points: Pt[] = [curves[0][0]];
  for (const curve of curves) {
    for (let i = 1; i <= stepsPerCurve; i++) points.push(cubicAt(curve, i / stepsPerCurve));
  }
  const lengths = [0];
  for (let i = 1; i < points.length; i++) {
    lengths.push(lengths[i - 1] + Math.hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y));
  }
  return { points, lengths, total: lengths[lengths.length - 1] };
}

/** The part of the track between two arc lengths, as an SVG path `d`. */
export function trackSlice(track: Track, from: number, to: number): string {
  const a = Math.max(0, from);
  const b = Math.min(track.total, to);
  if (b <= a) return '';
  const pts: Pt[] = [];
  for (let i = 0; i < track.points.length; i++) {
    const l = track.lengths[i];
    if (l >= a && l <= b) pts.push(track.points[i]);
  }
  pts.unshift(pointAt(track, a));
  pts.push(pointAt(track, b));
  return pts.map((p, i) => `${i === 0 ? 'M' : 'L'}${p.x.toFixed(2)} ${p.y.toFixed(2)}`).join(' ');
}

export function pointAt(track: Track, length: number): Pt {
  const l = Math.min(Math.max(length, 0), track.total);
  let i = 1;
  while (i < track.lengths.length - 1 && track.lengths[i] < l) i++;
  const l0 = track.lengths[i - 1];
  const l1 = track.lengths[i];
  const t = l1 === l0 ? 0 : (l - l0) / (l1 - l0);
  const p0 = track.points[i - 1];
  const p1 = track.points[i];
  return { x: p0.x + (p1.x - p0.x) * t, y: p0.y + (p1.y - p0.y) * t };
}

/** Maps a point from the 200×200 symbol box into frame pixels. */
export function placeSymbol(p: Pt, cx: number, cy: number, size: number): Pt {
  return { x: cx + ((p.x - 100) * size) / 200, y: cy + ((p.y - 100) * size) / 200 };
}

export function fourPointStar(cx: number, cy: number, r: number): string {
  const k = r * 0.22;
  return [
    `M${cx} ${cy - r}`,
    `Q${cx + k} ${cy - k} ${cx + r} ${cy}`,
    `Q${cx + k} ${cy + k} ${cx} ${cy + r}`,
    `Q${cx - k} ${cy + k} ${cx - r} ${cy}`,
    `Q${cx - k} ${cy - k} ${cx} ${cy - r}`,
    'Z',
  ].join(' ');
}
