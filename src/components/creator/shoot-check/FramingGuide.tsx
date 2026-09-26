import * as React from 'react';

import type { ShootCheckShot } from '@/components/creator/shoot-check/ShootCheckPanel';
import { adviceText, type ShootCheckLang } from '@/lib/shoot-check/advice-copy';
import type { CameraGrid } from '@/lib/shoot-check/guide-prefs';
import { getSafeZones, type SafeZones } from '@/lib/shoot-check/safe-zones';
import {
  PLANE,
  gridGeometry,
  planeRectToBox,
  planeXToBox,
  planeYToBox,
  propZone,
  reelFrame,
  shotBands,
  shotSizeFor,
  textArea,
  type BoxSize,
  type Rect,
} from '@/lib/shoot-check/shot-zones';

/**
 * The shot guide drawn over the live preview (spec v2 Phase 5a + 5b). Bottom to top:
 *   1. the 9:16 "Reel frame", centred, with the area outside it dimmed;
 *   2. the camera grid, only from `grid`: Rule of thirds, Golden grid (lines + 4 crossing dots) or
 *      Off (no lines). No golden spiral;
 *   3. the shot guides, only while `shotGuides` is on: soft bands (not lines) for the eye line,
 *      head top and lower crop of this shot's size, the hook-text area (MCU/MS, or where the
 *      shot card's `text` puts it: top, the side away from the face, lower middle; `none` or `?`
 *      draws no text area), the overhead
 *      surface, and a dashed prop zone on the creator's side;
 *   4. the safe zone, ALWAYS (spec 2.6): red covered areas with text labels, the right-side button
 *      rail, the amber "Short CTA, left side" band and the green safe-area outline. It reads
 *      neither `grid` nor `shotGuides`, so no setting can hide it.
 *
 * There is no match state: green and amber here are zones, never a verdict about the shot. The SVG
 * is `aria-hidden` (the camera sheet's "Set-up for this shot" line is the text version) and
 * `pointer-events-none`, so it never takes a tap from the video underneath.
 *
 * Geometry is worked out on the 1080 x 1920 plane (`shot-zones.ts`) and mapped into the SVG's own
 * CSS pixels, which it measures; `box` overrides the measurement (tests, a fixed-size preview).
 * The safe zone is not mirrored with the preview: the app's buttons sit on the screen's right.
 */
export interface FramingGuideProps {
  shot?: ShootCheckShot | null;
  shotGuides?: boolean;
  grid?: CameraGrid;
  /** The preview is mirrored (front camera): the creator's own right is drawn on screen right. */
  mirrored?: boolean;
  zones?: SafeZones;
  lang?: ShootCheckLang;
  box?: BoxSize;
}

function useMeasuredBox(ref: React.RefObject<SVGSVGElement | null>, override: BoxSize | undefined): BoxSize {
  const [measured, setMeasured] = React.useState<BoxSize | null>(null);
  const measure = !override;
  React.useLayoutEffect(() => {
    if (!measure) return;
    const el = ref.current;
    if (!el) return;
    const read = () => {
      const rect = el.getBoundingClientRect();
      setMeasured((current) =>
        current && current.width === rect.width && current.height === rect.height
          ? current
          : { width: rect.width, height: rect.height }
      );
    };
    read();
    // Rotating the phone resizes the preview; the frame and every zone follow it.
    if (typeof ResizeObserver === 'function') {
      const observer = new ResizeObserver(read);
      observer.observe(el);
      return () => observer.disconnect();
    }
    window.addEventListener('resize', read);
    return () => window.removeEventListener('resize', read);
  }, [ref, measure]);
  if (override) return override;
  // Not laid out yet (or no layout at all): draw on the plane itself, so the guide is never empty.
  return measured && measured.width > 0 && measured.height > 0 ? measured : { width: PLANE.width, height: PLANE.height };
}

function RectShape({ rect, ...rest }: { rect: Rect } & Omit<React.SVGProps<SVGRectElement>, 'r'>) {
  return <rect x={rect.x} y={rect.y} width={Math.max(0, rect.width)} height={Math.max(0, rect.height)} {...rest} />;
}

/** Zone label: white text with a dark outline so it reads on any background. */
function ZoneLabel({
  x,
  y,
  size,
  children,
  anchor = 'start',
  ...rest
}: { x: number; y: number; size: number; children: string; anchor?: 'start' | 'middle' } & React.SVGProps<SVGTextElement>) {
  return (
    <text
      x={x}
      y={y}
      fontSize={size}
      fontWeight={600}
      textAnchor={anchor}
      dominantBaseline="middle"
      fill="white"
      stroke="black"
      strokeOpacity={0.7}
      strokeWidth={size / 5}
      paintOrder="stroke"
      {...rest}
    >
      {children}
    </text>
  );
}

const COVERED_FILL = 'rgb(239 68 68 / 0.30)';
const COVERED_STROKE = 'rgb(248 113 113 / 0.9)';
const CTA_FILL = 'rgb(245 158 11 / 0.28)';
const CTA_STROKE = 'rgb(251 191 36 / 0.95)';
const SAFE_STROKE = 'rgb(34 197 94)';
const BAND_FILL = 'rgb(255 255 255 / 0.16)';
const BAND_FILL_OUTER = 'rgb(255 255 255 / 0.08)';

export function FramingGuide({
  shot = null,
  shotGuides = true,
  grid = 'thirds',
  mirrored = false,
  zones: zonesProp,
  lang = 'en-IN',
  box: boxProp,
}: FramingGuideProps) {
  const svgRef = React.useRef<SVGSVGElement>(null);
  const box = useMeasuredBox(svgRef, boxProp);
  const zones = React.useMemo(() => zonesProp ?? getSafeZones('instagram_reels'), [zonesProp]);
  const frame = reelFrame(box);
  const scale = frame.width / PLANE.width;
  const X = (x: number) => planeXToBox(frame, x);
  const Y = (y: number) => planeYToBox(frame, y);
  const P = (r: Rect) => planeRectToBox(frame, r);
  const labelSize = Math.max(10, 34 * scale);
  const stroke = Math.max(1, 3 * scale);

  // --- safe zone, on the plane (all from the config) ---
  const W = PLANE.width;
  const H = PLANE.height;
  const top = zones.top * H;
  const captionY = zones.captionLine * H;
  const coveredY = zones.coveredFrom * H;
  const sideX0 = zones.side * W;
  const sideX1 = (1 - zones.side) * W;
  const railX = zones.rail.x * W;
  const railY0 = Math.max(top, zones.rail.yFrom * H);
  const railY1 = zones.rail.yTo * H;
  const ctaX1 = Math.min(zones.ctaBand.xTo * W, railX);

  // The green area is the safe rectangle minus the rail's notch (when the rail starts above the
  // caption line, as it does with the interim values).
  const safePath =
    railY0 < captionY
      ? `M${X(sideX0)} ${Y(top)} H${X(sideX1)} V${Y(railY0)} H${X(railX)} V${Y(captionY)} H${X(sideX0)} Z`
      : `M${X(sideX0)} ${Y(top)} H${X(sideX1)} V${Y(captionY)} H${X(sideX0)} Z`;

  const gridLines = gridGeometry(grid);

  const size = shotSizeFor(shot);
  const bands = shotBands(size);
  // Spec v2 Phase 6 (decision 3): with a shot card the text area follows the card's `text`.
  const hookText = textArea({ shot, bands, zones, mirrored });
  const hookArea = hookText?.rect ?? null;
  const prop = shotGuides ? propZone({ shot, size, grid, mirrored, zones }) : null;
  const band = (from: number, to: number): Rect => ({ x: sideX0, y: from, width: sideX1 - sideX0, height: to - from });

  const dimPath = `M0 0 H${box.width} V${box.height} H0 Z M${frame.x} ${frame.y} v${frame.height} h${frame.width} v${-frame.height} Z`;

  return (
    <svg
      ref={svgRef}
      viewBox={`0 0 ${box.width} ${box.height}`}
      preserveAspectRatio="none"
      className="pointer-events-none absolute inset-0 size-full transition-opacity duration-150 motion-reduce:transition-none"
      aria-hidden="true"
      data-testid="framing-guide"
    >
      <path d={dimPath} fill="black" fillOpacity={0.45} fillRule="evenodd" data-testid="reel-frame-dim" />
      <RectShape rect={frame} fill="none" stroke="white" strokeOpacity={0.6} strokeWidth={1} data-testid="reel-frame" />

      {gridLines.vertical.length > 0 ? (
        <g data-testid="camera-grid" data-grid={grid} stroke="white" strokeOpacity={0.55} strokeWidth={1}>
          {gridLines.vertical.map((x) => (
            <line key={`v${x}`} data-axis="x" data-plane={x} x1={X(x)} y1={frame.y} x2={X(x)} y2={frame.y + frame.height} />
          ))}
          {gridLines.horizontal.map((y) => (
            <line key={`h${y}`} data-axis="y" data-plane={y} x1={frame.x} y1={Y(y)} x2={frame.x + frame.width} y2={Y(y)} />
          ))}
          {gridLines.points.map((p) => (
            <circle
              key={`p${p.x}-${p.y}`}
              data-testid="grid-point"
              cx={X(p.x)}
              cy={Y(p.y)}
              r={Math.max(3, 9 * scale)}
              fill="white"
              fillOpacity={0.9}
              stroke="black"
              strokeOpacity={0.8}
              strokeWidth={Math.max(1.5, 4 * scale)}
            />
          ))}
        </g>
      ) : null}

      {shotGuides ? (
        <g data-testid="shot-guides" data-size={bands.drawnAs}>
          {bands.headTop ? (
            <>
              <RectShape rect={P(band(bands.headTop.outer.from, bands.headTop.outer.to))} fill={BAND_FILL_OUTER} data-band="head-top-outer" />
              <RectShape rect={P(band(bands.headTop.inner.from, bands.headTop.inner.to))} fill={BAND_FILL} data-band="head-top" />
            </>
          ) : null}
          {bands.eyeLine ? (
            <RectShape rect={P(band(bands.eyeLine.from, bands.eyeLine.to))} fill={BAND_FILL} data-band="eye-line" />
          ) : null}
          {bands.lowerCrop ? (
            <RectShape
              rect={P(band(bands.lowerCrop.band.from, bands.lowerCrop.band.to))}
              fill={BAND_FILL_OUTER}
              data-band="lower-crop"
              data-part={bands.lowerCrop.part}
            />
          ) : null}
          {bands.overhead ? (
            <>
              <RectShape rect={P(bands.overhead.surface)} fill={BAND_FILL_OUTER} rx={24 * scale} data-band="surface" />
              {bands.overhead.hands.map((hand, i) => (
                <RectShape key={i} rect={P(hand)} fill={BAND_FILL} rx={120 * scale} data-band="hand" />
              ))}
            </>
          ) : null}
          {hookArea ? (
            <>
              <RectShape
                rect={P(hookArea)}
                fill="none"
                stroke="white"
                strokeOpacity={0.8}
                strokeWidth={stroke}
                strokeDasharray={`${12 * scale} ${8 * scale}`}
                data-band="hook-text"
                data-text-kind={hookText?.kind}
              />
              <ZoneLabel x={X(hookArea.x + hookArea.width / 2)} y={Y(hookArea.y + hookArea.height / 2)} size={labelSize} anchor="middle">
                {adviceText(hookText?.kind === 'top' ? 'hook_slot' : 'slot_hook', lang)}
              </ZoneLabel>
            </>
          ) : null}
          {prop ? (
            <RectShape
              rect={P(prop.rect)}
              fill="none"
              stroke="white"
              strokeWidth={stroke}
              strokeDasharray={`${18 * scale} ${12 * scale}`}
              rx={20 * scale}
              data-testid="prop-zone"
              data-side={prop.side}
              data-screen-side={prop.screenSide}
            />
          ) : null}
        </g>
      ) : null}

      <g data-testid="safe-zone">
        <RectShape rect={P({ x: 0, y: 0, width: W, height: top })} fill={COVERED_FILL} data-zone="top" />
        <RectShape rect={P({ x: 0, y: coveredY, width: W, height: H - coveredY })} fill={COVERED_FILL} data-zone="bottom" />
        <RectShape rect={P({ x: 0, y: top, width: sideX0, height: coveredY - top })} fill={COVERED_FILL} data-zone="side-left" />
        <RectShape rect={P({ x: sideX1, y: top, width: W - sideX1, height: coveredY - top })} fill={COVERED_FILL} data-zone="side-right" />
        <RectShape
          rect={P({ x: railX, y: railY0, width: sideX1 - railX, height: railY1 - railY0 })}
          fill={COVERED_FILL}
          stroke={COVERED_STROKE}
          strokeWidth={stroke}
          data-zone="rail"
        />
        <RectShape
          rect={P({ x: ctaX1, y: captionY, width: railX - ctaX1, height: coveredY - captionY })}
          fill={COVERED_FILL}
          data-zone="covered-caption"
        />
        <RectShape
          rect={P({ x: sideX0, y: captionY, width: ctaX1 - sideX0, height: coveredY - captionY })}
          fill={CTA_FILL}
          stroke={CTA_STROKE}
          strokeWidth={stroke}
          data-zone="cta"
        />
        {/* Green outline over a dark line, so it still reads against a green wall. */}
        <path d={safePath} fill="none" stroke="black" strokeOpacity={0.6} strokeWidth={stroke * 2.2} />
        <path d={safePath} fill="none" stroke={SAFE_STROKE} strokeWidth={stroke} data-zone="safe" />

        <ZoneLabel x={X(W / 2)} y={Y(top / 2)} size={labelSize} anchor="middle" data-label="top">
          {adviceText('zone_label_top', lang)}
        </ZoneLabel>
        <ZoneLabel x={X(W / 2)} y={Y((coveredY + H) / 2)} size={labelSize} anchor="middle" data-label="bottom">
          {adviceText('zone_label_bottom', lang)}
        </ZoneLabel>
        <ZoneLabel x={X(sideX0 + 16)} y={Y((captionY + coveredY) / 2)} size={labelSize} data-label="cta">
          {adviceText('zone_label_cta', lang)}
        </ZoneLabel>
      </g>
    </svg>
  );
}
