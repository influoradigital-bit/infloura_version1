import type { CSSProperties, ReactNode } from 'react';
import { AbsoluteFill, Easing, Img, interpolate, spring, staticFile, useCurrentFrame, useVideoConfig } from 'remotion';

import { CARD_SHADOW, FONT, inr, M, TYPE, WINDOW_SHADOW } from './look';

/**
 * LOOK EXAMPLE — "the brief read", the one moment of a creator's day that Meera already ships.
 *
 * This is a style reference for the product-demo film, not the film itself: one beat, built the
 * way the whole thing should be built.
 *
 *   - Real product surfaces. The three cards are the shipped ones, in order:
 *     "What the brief says" (summary + term chips), "What to watch" (DealRiskCard flags: severity
 *     chip, title, detail, cost, "What to do"), "Suggested package" (lines, add-ons, total, and
 *     the benchmark provenance subtitle that the real card refuses to hide).
 *     See `src/components/creator/meera/CreatorToolResultRenderer.tsx` and
 *     `src/components/shared/deal-risk-card.tsx`.
 *   - Real rule copy. The two flags are BELOW_FLOOR and EXCLUSIVITY_LONG, worded as
 *     `service/risk/rules/BelowFloorRule.java` and `ExclusivityLongRule.java` word them.
 *   - The Meera palette (`example/look.ts`): calm surface, ink text, ONE accent. Red and amber
 *     appear only where the product itself flags money at risk.
 *   - Photography for the human beat, product UI for the proof. Never a drawn icon standing in
 *     for a feature.
 *
 * Every rupee figure below is internally consistent: a ₹18,000 offer against a ₹25,000 floor is
 * ₹7,000 short, 60 days of exclusivity at a ₹8,000/month usual rate is ₹16,000 — ₹23,000 in the
 * closing line.
 */

const CLAMP = { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' } as const;
const EASE = Easing.bezier(0.16, 1, 0.3, 1);

/** Frame marks at 30fps. */
export const BRIEF_READ_DURATION = 510;
const F = {
  photoOut: 95,
  windowIn: 70,
  dmIn: 96,
  cursorClick: 132,
  thinking: 140,
  cardBrief: 172,
  cardRisk: 236,
  pushIn: 262,
  pullOut: 352,
  cardQuote: 358,
  caption: 410,
  endCard: 462,
} as const;

const OFFER = 18000;
const FLOOR = 25000;
const SHORTFALL = FLOOR - OFFER; // 7,000
const EXCLUSIVITY_LOSS = 16000; // ₹8,000/month usual rate × 60 days
const REEL = 20000;
const STORIES = 5000;
const PACKAGE_TOTAL = REEL + STORIES + EXCLUSIVITY_LOSS; // 41,000

function ease(frame: number, a: number, b: number) {
  return interpolate(frame, [a, b], [0, 1], { ...CLAMP, easing: EASE });
}

export function BriefRead() {
  const frame = useCurrentFrame();
  const { width, height } = useVideoConfig();
  const u = height / 1080;

  // Camera: a slow push toward the risk card, then a pull back out. Scale and offset only —
  // no rotation, nothing that reads as a slide transition.
  const push = ease(frame, F.pushIn, F.pushIn + 70) - ease(frame, F.pullOut, F.pullOut + 56);
  const camScale = 1 + push * 0.26;
  const camY = -push * 96 * u;
  const camX = push * 150 * u;

  const endIn = ease(frame, F.endCard, F.endCard + 22);

  return (
    <AbsoluteFill
      style={{
        background: `radial-gradient(120% 95% at 28% 18%, #FFFFFF 0%, ${M.bg} 45%, ${M.bgSubtle} 75%, #E3E8F1 100%)`,
        fontFamily: FONT,
        color: M.text,
      }}
    >
      <PhotoOpen u={u} />

      <AbsoluteFill
        style={{
          opacity: ease(frame, F.windowIn, F.windowIn + 26) * (1 - endIn),
          scale: String(camScale * interpolate(ease(frame, F.windowIn, F.windowIn + 30), [0, 1], [0.965, 1])),
          translate: `${camX}px ${camY + interpolate(ease(frame, F.windowIn, F.windowIn + 30), [0, 1], [34 * u, 0])}px`,
        }}
      >
        <AppWindow u={u} width={width} height={height} />
      </AbsoluteFill>

      <Caption u={u} />
      <EndCard u={u} progress={endIn} />
      <Vignette strength={0.18 + push * 0.22} />
      <Grain />
    </AbsoluteFill>
  );
}

/* ── The human beat: a real photograph, not an illustration ───────────────────────────── */
function PhotoOpen({ u }: { u: number }) {
  const frame = useCurrentFrame();
  const out = interpolate(frame, [F.photoOut - 26, F.photoOut], [1, 0], { ...CLAMP, easing: Easing.bezier(0.4, 0, 0.8, 0.2) });
  if (out <= 0) return null;
  const drift = interpolate(frame, [0, F.photoOut], [1.08, 1.0], CLAMP);
  const textIn = ease(frame, 14, 40);

  return (
    <AbsoluteFill style={{ opacity: out }}>
      <AbsoluteFill style={{ scale: String(drift), overflow: 'hidden' }}>
        <Img
          src={staticFile('stitch-media/creative-video-producer-happily-editing-4k-footage-o-0e52d7.jpg')}
          style={{ width: '100%', height: '100%', objectFit: 'cover' }}
        />
      </AbsoluteFill>
      <AbsoluteFill
        style={{
          background: `linear-gradient(180deg, rgba(11,15,26,0.55) 0%, rgba(11,15,26,0.12) 38%, rgba(11,15,26,0.82) 100%)`,
        }}
      />
      <div
        style={{
          position: 'absolute',
          left: 110 * u,
          bottom: 130 * u,
          opacity: textIn,
          translate: `0px ${interpolate(textIn, [0, 1], [26 * u, 0])}px`,
        }}
      >
        <div
          style={{
            fontSize: TYPE.timestamp * u,
            letterSpacing: '0.22em',
            fontWeight: 600,
            color: 'rgba(255,255,255,0.72)',
          }}
        >
          3:41 PM · MID-EDIT
        </div>
        <div style={{ marginTop: 20 * u, fontSize: TYPE.captionLead * u, fontWeight: 600, letterSpacing: '-0.02em', color: '#fff' }}>
          A brand DM lands.
        </div>
      </div>
    </AbsoluteFill>
  );
}

/* ── The product beat: the real Meera surface in a browser window ─────────────────────── */
function AppWindow({ u, width, height }: { u: number; width: number; height: number }) {
  const w = width * 0.66;
  const h = height * 0.84;
  const chrome = 52 * u;

  return (
    <div
      style={{
        position: 'absolute',
        left: width * 0.055,
        top: (height - h) / 2,
        width: w,
        height: h,
        borderRadius: 18 * u,
        background: M.surface,
        border: `1px solid ${M.border}`,
        boxShadow: WINDOW_SHADOW,
        overflow: 'hidden',
      }}
    >
      {/* Window chrome */}
      <div
        style={{
          height: chrome,
          background: M.surface2,
          borderBottom: `1px solid ${M.border}`,
          display: 'flex',
          alignItems: 'center',
          paddingLeft: 20 * u,
          gap: 9 * u,
        }}
      >
        {['#E0344B', '#E8A317', '#12A150'].map((c) => (
          <span key={c} style={{ width: 11 * u, height: 11 * u, borderRadius: '50%', background: c, opacity: 0.55 }} />
        ))}
        <div
          style={{
            marginLeft: 22 * u,
            height: 30 * u,
            flex: 1,
            marginRight: 20 * u,
            borderRadius: 999,
            background: M.surface,
            border: `1px solid ${M.border}`,
            display: 'flex',
            alignItems: 'center',
            paddingLeft: 16 * u,
            fontSize: TYPE.tiny * u,
            color: M.textMuted,
          }}
        >
          influora.in/creator/copilot
        </div>
      </div>

      <ChatColumn u={u} width={w} height={h - chrome} />
    </div>
  );
}

function ChatColumn({ u, width, height }: { u: number; width: number; height: number }) {
  return (
    <div style={{ position: 'absolute', inset: 0, overflow: 'hidden', background: M.bg }}>
      <PanelHeader u={u} />
      <div
        style={{
          position: 'absolute',
          left: 0,
          right: 0,
          bottom: 116 * u,
          padding: `0 ${34 * u}px`,
          display: 'flex',
          flexDirection: 'column',
          gap: 18 * u,
        }}
      >
        <BrandMessage u={u} />
        <MeeraLine u={u} />
        <BriefSummaryCard u={u} />
        <RiskSection u={u} />
        <QuoteCard u={u} />
      </div>
      <Composer u={u} />
      <Cursor u={u} width={width} height={height} />
    </div>
  );
}

/** The real composer bar: where a creator pastes a brand brief (`components/feature/meera/Composer.tsx`). */
function Composer({ u }: { u: number }) {
  const frame = useCurrentFrame();
  const focused = frame >= F.cursorClick;
  return (
    <div
      style={{
        position: 'absolute',
        left: 0,
        right: 0,
        bottom: 0,
        padding: `${20 * u}px ${34 * u}px`,
        background: M.surface,
        borderTop: `1px solid ${M.border}`,
        display: 'flex',
        alignItems: 'center',
        gap: 14 * u,
      }}
    >
      <div
        style={{
          flex: 1,
          height: 50 * u,
          borderRadius: 11 * u,
          background: M.bg,
          border: `1px solid ${focused ? M.accent : M.border}`,
          boxShadow: focused ? `0 0 0 ${3 * u}px ${M.accent}22` : 'none',
          display: 'flex',
          alignItems: 'center',
          paddingLeft: 16 * u,
          fontSize: TYPE.small * u,
          color: M.textMuted,
        }}
      >
        {frame >= F.dmIn ? 'Brief pasted — 412 characters' : 'Paste a brand brief, or ask Meera anything…'}
      </div>
      <div
        style={{
          height: 50 * u,
          borderRadius: 11 * u,
          background: M.accent,
          color: '#fff',
          fontSize: TYPE.small * u,
          fontWeight: 600,
          display: 'flex',
          alignItems: 'center',
          padding: `0 ${22 * u}px`,
        }}
      >
        Read it
      </div>
    </div>
  );
}

/**
 * The panel header. The floor chip is the product's whole posture in one line: Meera holds the
 * creator's minimum and a brand never sees it (`creator_persona.py` L92-94).
 */
function PanelHeader({ u }: { u: number }) {
  return (
    <div
      style={{
        position: 'absolute',
        left: 0,
        right: 0,
        top: 0,
        padding: `${20 * u}px ${34 * u}px`,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        borderBottom: `1px solid ${M.border}`,
        background: M.surface,
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 14 * u }}>
        <span
          style={{
            width: 34 * u,
            height: 34 * u,
            borderRadius: '50%',
            background: `radial-gradient(circle at 35% 30%, ${M.accent}, ${M.accentPress})`,
          }}
        />
        <span>
          <span style={{ display: 'block', fontSize: TYPE.body * u, fontWeight: 600, lineHeight: 1.2 }}>Meera</span>
          <span style={{ display: 'block', fontSize: TYPE.tiny * u, color: M.textMuted }}>
            Works for you, not the brand
          </span>
        </span>
      </div>
      <span
        style={{
          fontSize: TYPE.tiny * u,
          padding: `${8 * u}px ${14 * u}px`,
          borderRadius: 999,
          background: M.accentSoft,
          border: `1px solid ${M.accent}33`,
          color: M.accentPress,
          fontWeight: 600,
        }}
      >
        Your floor {inr(FLOOR)} · never shown to a brand
      </span>
    </div>
  );
}

function BrandMessage({ u }: { u: number }) {
  const frame = useCurrentFrame();
  const inAnim = ease(frame, F.dmIn, F.dmIn + 20);
  return (
    <div style={{ opacity: inAnim, translate: `0px ${interpolate(inAnim, [0, 1], [16 * u, 0])}px` }}>
      <div style={{ fontSize: TYPE.small * u, color: M.textMuted, marginBottom: 10 * u }}>
        Pasted from Instagram DM · Lumea Skincare
      </div>
      <div
        style={{
          maxWidth: '82%',
          padding: `${18 * u}px ${22 * u}px`,
          borderRadius: `${16 * u}px ${16 * u}px ${16 * u}px ${5 * u}px`,
          background: M.surface2,
          border: `1px solid ${M.border}`,
          fontSize: TYPE.body * u,
          lineHeight: 1.45,
        }}
      >
        “Hi! Loved your reels. We want 1 Reel + 2 Stories for our SPF launch. Budget {inr(OFFER)}. 60-day
        category exclusivity, 6-month usage including paid ads. Can you do it?”
      </div>
    </div>
  );
}

function MeeraLine({ u }: { u: number }) {
  const frame = useCurrentFrame();
  const thinking = frame >= F.thinking && frame < F.cardBrief;
  const inAnim = ease(frame, F.thinking, F.thinking + 16);
  if (frame < F.thinking) return null;
  return (
    <div style={{ opacity: inAnim, display: 'flex', alignItems: 'center', gap: 12 * u }}>
      <span
        style={{
          width: 26 * u,
          height: 26 * u,
          borderRadius: '50%',
          background: `radial-gradient(circle at 35% 30%, ${M.accent}, ${M.accentPress})`,
        }}
      />
      {thinking ? (
        <span style={{ display: 'flex', gap: 6 * u, alignItems: 'center' }}>
          {[0, 1, 2].map((i) => (
            <span
              key={i}
              style={{
                width: 7 * u,
                height: 7 * u,
                borderRadius: '50%',
                background: M.textMuted,
                opacity: 0.3 + 0.7 * Math.abs(Math.sin((frame - i * 4) / 6)),
              }}
            />
          ))}
        </span>
      ) : (
        <span style={{ fontSize: TYPE.body * u, color: M.text }}>
          Read it. Here’s what’s on offer, what’s off, and what I’d ask for.
        </span>
      )}
    </div>
  );
}

function CardShell({
  u,
  title,
  subtitle,
  children,
  appearAt,
  style,
}: {
  u: number;
  title: string;
  subtitle?: ReactNode;
  children: ReactNode;
  appearAt: number;
  style?: CSSProperties;
}) {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const rise = spring({ frame: frame - appearAt, fps, config: { damping: 18, stiffness: 90 } });
  if (frame < appearAt) return null;
  return (
    <div
      style={{
        background: M.surface,
        border: `1px solid ${M.border}`,
        borderRadius: 14 * u,
        padding: `${22 * u}px ${24 * u}px`,
        boxShadow: CARD_SHADOW,
        opacity: rise,
        translate: `0px ${interpolate(rise, [0, 1], [22 * u, 0])}px`,
        ...style,
      }}
    >
      <div style={{ fontSize: TYPE.cardTitle * u, fontWeight: 600, letterSpacing: '-0.01em' }}>{title}</div>
      {subtitle ? <div style={{ marginTop: 6 * u, fontSize: TYPE.small * u, color: M.textMuted, lineHeight: 1.4 }}>{subtitle}</div> : null}
      <div style={{ marginTop: 16 * u }}>{children}</div>
    </div>
  );
}

/* Card 1 — "What the brief says": summary lines, then the term chips. */
function BriefSummaryCard({ u }: { u: number }) {
  const frame = useCurrentFrame();
  const lines = [
    '1 Reel + 2 Stories for an SPF launch.',
    `Budget named: ${inr(OFFER)}.`,
    '60 days category exclusivity, 6 months usage.',
  ];
  const chips = [
    { label: 'Budget', value: inr(OFFER) },
    { label: 'Deliverables', value: '1 Reel, 2 Stories' },
    { label: 'Usage', value: '6 months · paid ads, organic' },
    { label: 'Exclusivity', value: '60 days · Skincare' },
  ];
  return (
    <CardShell u={u} title="What the brief says" appearAt={F.cardBrief}>
      <ul style={{ margin: 0, paddingLeft: 26 * u, fontSize: TYPE.body * u, lineHeight: 1.7 }}>
        {lines.map((line, i) => (
          <li key={line} style={{ opacity: ease(frame, F.cardBrief + 8 + i * 7, F.cardBrief + 26 + i * 7) }}>
            {line}
          </li>
        ))}
      </ul>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 10 * u, marginTop: 18 * u }}>
        {chips.map((chip, i) => {
          const chipIn = ease(frame, F.cardBrief + 26 + i * 6, F.cardBrief + 42 + i * 6);
          return (
            <span
              key={chip.label}
              style={{
                fontSize: TYPE.small * u,
                padding: `${8 * u}px ${13 * u}px`,
                borderRadius: 8 * u,
                background: M.surface2,
                border: `1px solid ${M.border}`,
                opacity: chipIn,
                translate: `0px ${interpolate(chipIn, [0, 1], [8 * u, 0])}px`,
              }}
            >
              <span style={{ color: M.textMuted }}>{chip.label}: </span>
              <span style={{ fontWeight: 600 }}>{chip.value}</span>
            </span>
          );
        })}
      </div>
    </CardShell>
  );
}

/* Card 2 — "What to watch": the shipped DealRiskCard, two real rules. */
const FLAGS = [
  {
    severity: 'Critical',
    tint: M.danger,
    soft: M.dangerSoft,
    title: 'Offer is below your floor',
    detail: `Offer is ${inr(OFFER)} against your floor of ${inr(FLOOR)}.`,
    cost: SHORTFALL,
    costSuffix: 'below your floor',
    action: `Counter at your floor of ${inr(FLOOR)}, unless you are taking this one as a deliberate exception.`,
  },
  {
    severity: 'Warning',
    tint: M.warning,
    soft: M.warningSoft,
    title: 'Long exclusivity window',
    detail: '60 days exclusivity ≈ ₹16,000 at your usual rate.',
    cost: EXCLUSIVITY_LOSS,
    costSuffix: 'of lost income',
    action: 'Price the exclusivity add-on before you agree.',
  },
] as const;

function RiskSection({ u }: { u: number }) {
  const frame = useCurrentFrame();
  return (
    <CardShell u={u} title="What to watch" appearAt={F.cardRisk}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 14 * u }}>
        {FLAGS.map((flag, i) => {
          const at = F.cardRisk + 10 + i * 18;
          const rowIn = ease(frame, at, at + 22);
          // The money figure counts up as it lands — the one number the creator would have missed.
          const counted = Math.round(interpolate(ease(frame, at + 18, at + 48), [0, 1], [0, flag.cost]) / 100) * 100;
          return (
            <div
              key={flag.title}
              style={{
                borderRadius: 11 * u,
                border: `1px solid ${flag.tint}55`,
                background: flag.soft,
                padding: `${16 * u}px ${18 * u}px`,
                opacity: rowIn,
                translate: `${interpolate(rowIn, [0, 1], [14 * u, 0])}px 0px`,
              }}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: 12 * u }}>
                <span
                  style={{
                    fontSize: TYPE.tiny * u,
                    fontWeight: 700,
                    letterSpacing: '0.08em',
                    textTransform: 'uppercase',
                    color: '#fff',
                    background: flag.tint,
                    padding: `${4 * u}px ${10 * u}px`,
                    borderRadius: 6 * u,
                  }}
                >
                  {flag.severity}
                </span>
                <span style={{ fontSize: TYPE.body * u, fontWeight: 600 }}>{flag.title}</span>
              </div>
              <div style={{ marginTop: 10 * u, fontSize: TYPE.small * u, color: M.textMuted }}>{flag.detail}</div>
              <div style={{ marginTop: 8 * u, fontSize: TYPE.money * u, fontWeight: 650, letterSpacing: '-0.02em', color: flag.tint }}>
                ≈ {inr(counted)}{' '}
                <span style={{ fontSize: TYPE.small * u, fontWeight: 500, color: M.textMuted }}>{flag.costSuffix}</span>
              </div>
              <div style={{ marginTop: 10 * u, fontSize: TYPE.small * u, lineHeight: 1.5 }}>
                <span style={{ color: M.textMuted }}>What to do: </span>
                {flag.action}
              </div>
            </div>
          );
        })}
      </div>
    </CardShell>
  );
}

/* Card 3 — "Suggested package", including the provenance line the real card refuses to hide. */
function QuoteCard({ u }: { u: number }) {
  const frame = useCurrentFrame();
  const rows = [
    { label: '1 Reel', value: REEL },
    { label: '2 Stories', value: STORIES },
    { label: '60-day exclusivity add-on', value: EXCLUSIVITY_LOSS, addOn: true },
  ];
  const total = Math.round(interpolate(ease(frame, F.cardQuote + 26, F.cardQuote + 60), [0, 1], [0, PACKAGE_TOTAL]) / 500) * 500;
  return (
    <CardShell
      u={u}
      title="Suggested package"
      appearAt={F.cardQuote}
      subtitle="Benchmark for your tier — this is our estimate, not what creators like you have actually closed at."
    >
      <div style={{ display: 'flex', flexDirection: 'column', gap: 10 * u }}>
        {rows.map((row, i) => {
          const rowIn = ease(frame, F.cardQuote + 12 + i * 6, F.cardQuote + 30 + i * 6);
          return (
            <div
              key={row.label}
              style={{ display: 'flex', justifyContent: 'space-between', fontSize: TYPE.body * u, opacity: rowIn }}
            >
              <span style={{ color: row.addOn ? M.textMuted : M.text }}>{row.label}</span>
              <span style={{ fontWeight: 600 }}>{inr(row.value)}</span>
            </div>
          );
        })}
        <div style={{ height: 1, background: M.border, margin: `${6 * u}px 0` }} />
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
          <span style={{ fontSize: TYPE.body * u, fontWeight: 600 }}>Ask for</span>
          <span style={{ fontSize: TYPE.money * u, fontWeight: 700, letterSpacing: '-0.02em', color: M.accent }}>{inr(total)}</span>
        </div>
        <button
          type="button"
          style={{
            marginTop: 10 * u,
            alignSelf: 'flex-start',
            fontFamily: FONT,
            fontSize: TYPE.small * u,
            fontWeight: 600,
            color: '#fff',
            background: M.accent,
            border: 'none',
            borderRadius: 9 * u,
            padding: `${11 * u}px ${20 * u}px`,
            opacity: ease(frame, F.cardQuote + 40, F.cardQuote + 58),
          }}
        >
          Use in counter
        </button>
      </div>
    </CardShell>
  );
}

/** The pointer that pastes the brief — small, soft-shadowed, and it actually lands on the button. */
function Cursor({ u, width, height }: { u: number; width: number; height: number }) {
  const frame = useCurrentFrame();
  if (frame > F.thinking + 20) return null;
  const travel = ease(frame, F.dmIn + 12, F.cursorClick);
  const x = interpolate(travel, [0, 1], [width * 0.52, width * 0.905]);
  const y = interpolate(travel, [0, 1], [height * 0.62, height - 45 * u]);
  const press = interpolate(frame, [F.cursorClick, F.cursorClick + 5, F.cursorClick + 12], [1, 0.82, 1], CLAMP);
  const ripple = interpolate(frame, [F.cursorClick, F.cursorClick + 22], [0, 1], CLAMP);
  return (
    <>
      {frame >= F.cursorClick ? (
        <div
          style={{
            position: 'absolute',
            left: x,
            top: y,
            width: 90 * u * ripple,
            height: 90 * u * ripple,
            marginLeft: (-90 * u * ripple) / 2,
            marginTop: (-90 * u * ripple) / 2,
            borderRadius: '50%',
            border: `2px solid ${M.accent}`,
            opacity: (1 - ripple) * 0.6,
          }}
        />
      ) : null}
      <svg
        width={30 * u}
        height={30 * u}
        viewBox="0 0 24 24"
        style={{ position: 'absolute', left: x, top: y, scale: String(press), filter: 'drop-shadow(0 3px 6px rgba(14,22,38,0.3))' }}
      >
        <path d="M5 3l14 8-6 1.5L10 19z" fill="#fff" stroke={M.text} strokeWidth="1.4" strokeLinejoin="round" />
      </svg>
    </>
  );
}

/** One line of copy, right of the window: what the creator just avoided. */
function Caption({ u }: { u: number }) {
  const frame = useCurrentFrame();
  const { width } = useVideoConfig();
  const inAnim = ease(frame, F.caption, F.caption + 26);
  const out = interpolate(frame, [F.endCard - 14, F.endCard], [1, 0], CLAMP);
  if (frame < F.caption) return null;
  return (
    <div
      style={{
        position: 'absolute',
        right: width * 0.06,
        top: '50%',
        width: width * 0.26,
        translate: `0px ${interpolate(inAnim, [0, 1], [24 * u, -50])}%`,
        opacity: inAnim * out,
      }}
    >
      <div style={{ fontSize: TYPE.timestamp * u, letterSpacing: '0.2em', fontWeight: 600, color: M.textMuted }}>
        FOUR SECONDS
      </div>
      <div style={{ marginTop: 18 * u, fontSize: TYPE.captionLead * u, fontWeight: 650, letterSpacing: '-0.025em', lineHeight: 1.1 }}>
        {inr(SHORTFALL + EXCLUSIVITY_LOSS)} you’d have said yes to.
      </div>
      <div style={{ marginTop: 18 * u, fontSize: TYPE.caption * u * 0.62, color: M.textMuted, lineHeight: 1.5 }}>
        Meera reads the brief and prices what it costs you. What you send back is yours to write.
      </div>
    </div>
  );
}

function EndCard({ u, progress }: { u: number; progress: number }) {
  if (progress <= 0) return null;
  const frame = useCurrentFrame();
  const lineIn = ease(frame, F.endCard + 14, F.endCard + 38);
  return (
    <AbsoluteFill
      style={{
        background: M.stage,
        opacity: progress,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        flexDirection: 'column',
        textAlign: 'center',
      }}
    >
      <div
        style={{
          width: 74 * u,
          height: 74 * u,
          borderRadius: '50%',
          background: `radial-gradient(circle at 35% 30%, ${M.accent}, ${M.accentPress})`,
          boxShadow: `0 0 ${60 * u}px rgba(109,90,230,0.45)`,
          opacity: lineIn,
        }}
      />
      <div
        style={{
          marginTop: 40 * u,
          fontSize: 76 * u,
          fontWeight: 650,
          letterSpacing: '-0.03em',
          color: '#F7F8FB',
          opacity: lineIn,
          translate: `0px ${interpolate(lineIn, [0, 1], [20 * u, 0])}px`,
        }}
      >
        Someone in your corner.
      </div>
      <div style={{ marginTop: 20 * u, fontSize: 34 * u, color: '#93A0B8', opacity: ease(frame, F.endCard + 30, F.endCard + 52) }}>
        Meera · coming soon on influora.in
      </div>
    </AbsoluteFill>
  );
}

/** Corner falloff — keeps the eye on the window without darkening the surface. */
function Vignette({ strength }: { strength: number }) {
  return (
    <AbsoluteFill
      style={{
        pointerEvents: 'none',
        background: `radial-gradient(120% 90% at 42% 50%, transparent 40%, rgba(14,22,38,${strength}) 100%)`,
      }}
    />
  );
}

/** A little film grain so flat surfaces do not look like vector art. */
function Grain() {
  return (
    <AbsoluteFill style={{ pointerEvents: 'none', opacity: 0.045, mixBlendMode: 'multiply' }}>
      <svg width="100%" height="100%">
        <filter id="grain-noise">
          <feTurbulence type="fractalNoise" baseFrequency="0.85" numOctaves="3" stitchTiles="stitch" />
        </filter>
        <rect width="100%" height="100%" filter="url(#grain-noise)" />
      </svg>
    </AbsoluteFill>
  );
}
