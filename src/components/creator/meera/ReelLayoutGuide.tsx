import * as React from 'react';
import { Download } from 'lucide-react';

import { Button } from '@/components/ui/button';
import type { MeeraShootCheckLayout } from '@/lib/meera-api';
import { adviceText, type GuideCopyKey, type ShootCheckLang } from '@/lib/shoot-check/advice-copy';
import {
  GUIDE_COLOURS,
  downloadGuidePng,
  renderGuidePng,
  type CreateGuideCanvas,
} from '@/lib/shoot-check/guide-png';
import { PLANE_H, PLANE_W, planReelLayout, type Rect, type SlotName } from '@/lib/shoot-check/reel-layout';
import { getSafeZones } from '@/lib/shoot-check/safe-zones';
import { cn } from '@/lib/utils';

/**
 * Influora's Reel layout guide (spec Phase 4): the newest photo check's photo, cropped to 9:16, with
 * the always-on safe zone, the faces boxed as "keep clear", the product boxed, and the four slots our
 * code placed (`reel-layout.ts`). A numbered text legend repeats everything the picture shows; the
 * picture itself is `aria-hidden`, so a screen reader reads the legend, never the overlay.
 *
 * Shown only while the chat holds the photo in memory (the caller passes `photo` to the newest card
 * only), and only when the reply had at least one face or product box: with no boxes at all (for
 * example an older server) there is nothing to lay out, and the card stays text only.
 *
 * The photo is shown from an object URL that is revoked on unmount; it is never uploaded or saved,
 * and the CapCut PNG never contains it (`guide-png.ts` is given zones and boxes, not the photo).
 */
export interface ReelLayoutGuideProps {
  photo: Blob;
  layout: MeeraShootCheckLayout | undefined;
  lang: ShootCheckLang;
  className?: string;
  /** Test seam: the canvas the PNG is drawn on (jsdom has none). */
  createCanvas?: CreateGuideCanvas;
}

const SLOT_COPY: Record<SlotName, GuideCopyKey> = {
  hook: 'slot_hook',
  captions: 'slot_captions',
  logo: 'slot_logo',
  sticker: 'slot_sticker',
};

/** The placement line for a slot that found room. A sticker needs no instruction beyond its box. */
const PLACED_LINE: Partial<Record<SlotName, GuideCopyKey>> = {
  hook: 'hook_slot',
  captions: 'captions_line',
  logo: 'logo_line',
};

const NO_SPACE_LINE: Record<SlotName, GuideCopyKey> = {
  hook: 'no_space_hook',
  captions: 'no_space_captions',
  logo: 'no_space_logo',
  sticker: 'no_space_sticker',
};

/** Has the reply given anything to lay out? */
function hasLayoutBoxes(layout: MeeraShootCheckLayout | undefined): layout is MeeraShootCheckLayout {
  return Boolean(layout && (layout.faces.length > 0 || layout.product));
}

interface LegendItem {
  n: number;
  text: string;
  /** Where the number badge sits on the plane. */
  at: { x: number; y: number };
}

function badgeAt(r: Rect): { x: number; y: number } {
  return { x: r.x + 40, y: r.y + 40 };
}

export function ReelLayoutGuide({ photo, layout, lang, className, createCanvas }: ReelLayoutGuideProps) {
  const [url, setUrl] = React.useState<string | null>(null);
  // Keyed by the URL it was read from, so a new photo never reuses the last photo's size.
  const [loaded, setLoaded] = React.useState<{ src: string; width: number; height: number } | null>(null);
  const photoWidth = loaded && loaded.src === url ? loaded.width : 0;
  const photoHeight = loaded && loaded.src === url ? loaded.height : 0;
  const [pngFailed, setPngFailed] = React.useState(false);
  const [saving, setSaving] = React.useState(false);
  const titleId = React.useId();
  const show = hasLayoutBoxes(layout);

  React.useEffect(() => {
    if (!show) return undefined;
    const next = URL.createObjectURL(photo);
    setUrl(next);
    return () => URL.revokeObjectURL(next);
  }, [photo, show]);

  const plan = React.useMemo(
    () =>
      show && photoWidth > 0 && photoHeight > 0
        ? planReelLayout({ width: photoWidth, height: photoHeight }, layout, getSafeZones())
        : null,
    [show, photoWidth, photoHeight, layout],
  );

  if (!show || !url) return null;
  const t = (key: GuideCopyKey) => adviceText(key, lang);

  const legend: LegendItem[] = [];
  if (plan) {
    const add = (text: string, at: { x: number; y: number }) => legend.push({ n: legend.length + 1, text, at });
    const { zones } = plan;
    add(t('zone_label_safe'), { x: zones.safe.x + zones.safe.w - 60, y: zones.safe.y + zones.safe.h - 60 });
    add(t('zone_label_top'), { x: PLANE_W - 80, y: zones.coveredTop.h / 2 });
    add(t('zone_label_bottom'), { x: PLANE_W - 80, y: zones.coveredBottom.y + zones.coveredBottom.h / 2 });
    add(t('zone_label_rail'), { x: zones.rail.x + zones.rail.w / 2, y: zones.rail.y + 50 });
    add(t('zone_label_cta'), { x: zones.ctaBand.x + zones.ctaBand.w - 50, y: zones.ctaBand.y + zones.ctaBand.h / 2 });
    if (plan.faces.length > 0) add(t('face_keep_clear'), badgeAt(plan.faces[0]));
    if (plan.product) add(t('product_label'), badgeAt(plan.product));
    for (const name of Object.keys(SLOT_COPY) as SlotName[]) {
      const slot = plan.slots[name];
      if (slot) add(t(SLOT_COPY[name]), badgeAt(slot));
    }
  }

  const placementLines = plan
    ? (Object.keys(SLOT_COPY) as SlotName[])
        .map((name) => {
          if (!plan.slots[name]) return t(NO_SPACE_LINE[name]);
          const placed = PLACED_LINE[name];
          return placed ? t(placed) : null;
        })
        .filter((line): line is string => line !== null)
    : [];

  const onDownload = async () => {
    if (!plan) return;
    setSaving(true);
    setPngFailed(false);
    // Labels drawn INTO the PNG are English (see `png_banner`): the file is a working layer.
    const en = (key: GuideCopyKey) => adviceText(key, 'en-IN').toUpperCase();
    const blob = await renderGuidePng(
      {
        zones: plan.zones,
        faces: plan.faces,
        slots: plan.slots,
        labels: {
          banner: adviceText('png_banner', 'en-IN'),
          cta: en('zone_label_cta'),
          slots: { hook: en('slot_hook'), captions: en('slot_captions'), logo: en('slot_logo'), sticker: en('slot_sticker') },
        },
      },
      createCanvas,
    );
    setSaving(false);
    if (!blob) {
      setPngFailed(true);
      return;
    }
    downloadGuidePng(blob);
  };

  const crop = plan?.crop;
  return (
    <section
      data-testid="reel-layout-guide"
      aria-labelledby={titleId}
      className={cn('flex flex-col gap-3 border-t border-border pt-3', className)}
    >
      <h3 id={titleId} className="text-sm font-semibold text-foreground">
        {t('reel_layout_title')}
      </h3>

      <div
        data-testid="reel-layout-visual"
        aria-hidden="true"
        className="relative mx-auto aspect-[9/16] w-full max-w-[260px] overflow-hidden rounded-lg bg-black"
      >
        <img
          src={url}
          alt=""
          onLoad={(e) => {
            const img = e.currentTarget;
            if (img.naturalWidth > 0 && img.naturalHeight > 0) {
              setLoaded({ src: url, width: img.naturalWidth, height: img.naturalHeight });
            }
          }}
          className="absolute max-w-none select-none"
          style={
            crop
              ? {
                  width: `${100 / crop.w}%`,
                  height: `${100 / crop.h}%`,
                  left: `${(-crop.x / crop.w) * 100}%`,
                  top: `${(-crop.y / crop.h) * 100}%`,
                }
              : { width: '100%', height: '100%', objectFit: 'cover' }
          }
          draggable={false}
        />
        {plan ? (
          <svg
            data-testid="reel-layout-overlay"
            viewBox={`0 0 ${PLANE_W} ${PLANE_H}`}
            className="absolute inset-0 h-full w-full"
            aria-hidden="true"
            focusable="false"
          >
            <Zone name="top" r={plan.zones.coveredTop} fill={GUIDE_COLOURS.covered} />
            <Zone name="bottom" r={plan.zones.coveredBottom} fill={GUIDE_COLOURS.covered} />
            <Zone name="side-left" r={plan.zones.sideLeft} fill={GUIDE_COLOURS.covered} />
            <Zone name="side-right" r={plan.zones.sideRight} fill={GUIDE_COLOURS.covered} />
            <Zone name="covered-caption" r={plan.zones.coveredCaption} fill={GUIDE_COLOURS.covered} />
            <Zone name="rail" r={plan.zones.rail} fill={GUIDE_COLOURS.covered} dashed />
            <Zone name="cta" r={plan.zones.ctaBand} fill={GUIDE_COLOURS.cta} dashed />
            {/* The green area minus the rail's notch, as the live camera draws it. */}
            <path
              data-zone="safe"
              d={outlinePath(plan.zones.safeOutline)}
              fill="none"
              stroke={GUIDE_COLOURS.safe}
              strokeWidth={8}
            />
            {plan.faces.map((f, i) => (
              <Box key={`f${i}`} r={f} colour={GUIDE_COLOURS.face} kind="face" />
            ))}
            {plan.product ? <Box r={plan.product} colour={GUIDE_COLOURS.cta} kind="product" /> : null}
            {(Object.keys(SLOT_COPY) as SlotName[]).map((name) => {
              const slot = plan.slots[name];
              return slot ? <Box key={name} r={slot} colour={GUIDE_COLOURS.slot} kind={name} /> : null;
            })}
            {legend.map((item) => (
              <g key={item.n}>
                <circle cx={item.at.x} cy={item.at.y} r={34} fill={GUIDE_COLOURS.halo} stroke="#ffffff" strokeWidth={4} />
                <text
                  x={item.at.x}
                  y={item.at.y}
                  fill="#ffffff"
                  fontSize={40}
                  fontWeight={700}
                  textAnchor="middle"
                  dominantBaseline="central"
                >
                  {item.n}
                </text>
              </g>
            ))}
          </svg>
        ) : null}
      </div>

      {plan ? (
        <>
          <ol data-testid="reel-layout-legend" className="flex flex-col gap-1 text-sm text-foreground">
            {legend.map((item) => (
              <li key={item.n} className="flex items-start gap-2">
                <span className="w-5 shrink-0 text-right font-semibold tabular-nums">{item.n}.</span>
                <span className="min-w-0 break-words">{item.text}</span>
              </li>
            ))}
          </ol>
          <ul data-testid="reel-layout-lines" className="flex flex-col gap-1 text-sm text-foreground">
            {placementLines.map((line) => (
              <li key={line} className="break-words">
                {line}
              </li>
            ))}
          </ul>
          <p data-testid="reel-layout-safe-zone-note" className="text-xs text-muted-foreground">
            {t('safe_zone_note')}
          </p>
          <div className="flex flex-col gap-1.5">
            <Button
              type="button"
              variant="outline"
              className="min-h-11 self-start"
              onClick={onDownload}
              disabled={saving}
              data-testid="reel-layout-download"
            >
              <Download className="size-4" aria-hidden="true" />
              {t('download_png')}
            </Button>
            {pngFailed ? (
              <p role="status" data-testid="reel-layout-png-failed" className="text-xs text-foreground">
                {t('png_failed')}
              </p>
            ) : null}
            <p className="text-xs text-muted-foreground">{t('png_note')}</p>
            <p data-testid="reel-layout-device-note" className="text-xs text-muted-foreground">
              {t('device_note')}
            </p>
          </div>
        </>
      ) : null}
    </section>
  );
}

/** An SVG path through `points`, closed. */
function outlinePath(points: ReadonlyArray<{ x: number; y: number }>): string {
  return `${points.map((p, i) => `${i === 0 ? 'M' : 'L'}${p.x} ${p.y}`).join(' ')} Z`;
}

function Zone({ name, r, fill, dashed }: { name: string; r: Rect; fill: string; dashed?: boolean }) {
  if (r.w <= 0 || r.h <= 0) return null;
  return (
    <rect
      data-zone={name}
      x={r.x}
      y={r.y}
      width={r.w}
      height={r.h}
      fill={fill}
      fillOpacity={0.35}
      stroke={fill}
      strokeWidth={6}
      strokeDasharray={dashed ? '18 12' : undefined}
    />
  );
}

function Box({ r, colour, kind }: { r: Rect; colour: string; kind: string }) {
  return (
    <g data-box={kind}>
      <rect x={r.x} y={r.y} width={r.w} height={r.h} fill="none" stroke={GUIDE_COLOURS.halo} strokeWidth={12} />
      <rect x={r.x} y={r.y} width={r.w} height={r.h} fill="none" stroke={colour} strokeWidth={6} strokeDasharray="22 12" />
    </g>
  );
}
