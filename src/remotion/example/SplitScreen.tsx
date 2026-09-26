import type { CSSProperties, ReactNode } from 'react';
import { AbsoluteFill, Easing, Img, interpolate, spring, staticFile, useCurrentFrame, useVideoConfig } from 'remotion';

import { FONT, M, TYPE } from './look';

/**
 * LOOK EXAMPLE 2 — the split screen: Meera working on the left, the creator reacting on the
 * right, a seam of light between them. Storyboard for "Seven Days with Meera".
 *
 * HONESTY MAP (2026-09-23). Do not publish this anywhere public until the SPEC-ONLY beats are
 * either built or relabelled:
 *   SHIPPED   — connect (Meta login), profile read (`get_my_metrics`), script/hooks (creator
 *               persona), the "works for you, not the brand" posture (`creator_persona.py` L58)
 *   OFF TODAY — demand/trends. Ingest is disabled (T-TSOFF-0920); the endpoint answers
 *               404 TRENDS_DISABLED. The beat here shows BRIEF DEMAND from our own platform,
 *               which needs no external trend licence — that is the proposal, not the state.
 *   NOT BUILT — peer benchmarks, the 7-day challenge, closed-rate figures. Sample values below
 *               are placeholders for features we intend to build, not readings from live data.
 *
 * Copy rules still apply: Meera advises, the creator decides. "PR manager" / "personal manager"
 * framing stays banned (`pages/meera-for-creators.claims.test.tsx`), hence "your own strategist".
 */

const CLAMP = { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' } as const;
const EASE = Easing.bezier(0.16, 1, 0.3, 1);

type BeatId = 'intro' | 'connect' | 'promise' | 'profile' | 'peers' | 'demand' | 'script' | 'challenge' | 'end';

type Beat = {
  id: BeatId;
  from: number;
  duration: number;
  /** The left panel's status line, as a working AI would report it. */
  status: string;
  /** The line that sits under the seam, bottom left. */
  line: string;
  /** What the creator's side says in its chip. */
  chip?: string;
  photo?: string;
  /** objectPosition for the crop — keeps fabricated screens in the stock photos out of frame. */
  focus?: string;
};

const PHOTO = {
  editing: 'stitch-media/creative-video-producer-happily-editing-4k-footage-o-0e52d7.jpg',
  rooftop: 'stitch-media/successful-indian-creator-reviewing-approved-brand-c-caa021.jpg',
  daylight: 'stitch-media/warm-natural-daylight-streaming-into-an-indian-creat-65bc72.jpg',
  shoot: 'stitch-media/authentic-candid-lifestyle-photography-of-an-indian--dd479b.jpg',
} as const;

export const BEATS: readonly Beat[] = [
  { id: 'intro', from: 0, duration: 120, status: 'Waking up', line: 'Meera', photo: PHOTO.editing, focus: '38% 30%' },
  {
    id: 'connect',
    from: 120,
    duration: 150,
    status: 'Connecting your account',
    line: 'Connect your account.',
    chip: 'One tap. Nothing else to fill in.',
    photo: PHOTO.editing,
    focus: '38% 30%',
  },
  {
    id: 'promise',
    from: 270,
    duration: 120,
    status: 'Ready',
    line: 'Your own strategist. Ready before you are.',
    photo: PHOTO.rooftop,
    focus: '24% 28%',
  },
  {
    id: 'profile',
    from: 390,
    duration: 180,
    status: 'Reading 47 posts',
    line: 'Analysing your profile.',
    chip: 'She knows my numbers better than I do.',
    photo: PHOTO.rooftop,
    focus: '24% 28%',
  },
  {
    id: 'peers',
    from: 570,
    duration: 180,
    status: 'Comparing creators like you',
    line: 'Checking creators like you.',
    chip: 'So that is what they charge.',
    photo: PHOTO.daylight,
    focus: '42% 32%',
  },
  {
    id: 'demand',
    from: 750,
    duration: 180,
    status: 'Reading this week’s briefs',
    line: 'What brands want now.',
    photo: PHOTO.daylight,
    focus: '42% 32%',
  },
  {
    id: 'script',
    from: 930,
    duration: 210,
    status: 'Writing from your top 3 Reels',
    line: 'A script, in your voice.',
    chip: 'That is my voice.',
    photo: PHOTO.shoot,
    focus: '56% 30%',
  },
  {
    id: 'challenge',
    from: 1140,
    duration: 210,
    status: 'Planning your week',
    line: 'Take the 7-day challenge.',
    chip: 'Seven days. One thing a day.',
    photo: PHOTO.shoot,
    focus: '56% 30%',
  },
  { id: 'end', from: 1350, duration: 150, status: '', line: 'Someone in your corner.' },
];

export const SPLIT_SCREEN_DURATION = 1500;
const SEAM = 0.58;

function ease(frame: number, a: number, b: number) {
  return interpolate(frame, [a, b], [0, 1], { ...CLAMP, easing: EASE });
}

function beatAt(frame: number): { beat: Beat; local: number; index: number } {
  let index = 0;
  for (let i = 0; i < BEATS.length; i++) {
    if (frame >= BEATS[i].from) index = i;
  }
  return { beat: BEATS[index], local: frame - BEATS[index].from, index };
}

export function SplitScreen() {
  const frame = useCurrentFrame();
  const { width, height } = useVideoConfig();
  const u = height / 1080;
  const { beat, local, index } = beatAt(frame);
  const seamX = width * SEAM;

  const endIn = beat.id === 'end' ? ease(local, 0, 26) : 0;
  const open = ease(frame, 8, 46); // the two panels part from the seam

  return (
    <AbsoluteFill style={{ backgroundColor: M.stage, fontFamily: FONT, color: '#EAEEF6' }}>
      {/* RIGHT — the creator */}
      <CreatorSide u={u} width={width} height={height} seamX={seamX} beat={beat} local={local} open={open} />

      {/* LEFT — Meera working */}
      <div
        style={{
          position: 'absolute',
          left: 0,
          top: 0,
          width: seamX,
          height,
          background: `linear-gradient(160deg, ${M.stage2} 0%, ${M.stage} 60%)`,
          overflow: 'hidden',
          translate: `${interpolate(open, [0, 1], [-40 * u, 0])}px 0px`,
        }}
      >
        <AmbientField u={u} width={seamX} height={height} />
        <MeeraPanel u={u} width={seamX} height={height} beat={beat} local={local} index={index} />
      </div>

      <Seam u={u} x={seamX} height={height} frame={frame} local={local} />
      <EndCard u={u} progress={endIn} local={local} />
      <AbsoluteFill
        style={{
          pointerEvents: 'none',
          background: 'radial-gradient(130% 100% at 50% 50%, transparent 55%, rgba(5,8,16,0.45) 100%)',
        }}
      />
    </AbsoluteFill>
  );
}

/* ── The seam: one line of light, and the only bridge between the two sides ───────────── */
function Seam({ u, x, height, frame, local }: { u: number; x: number; height: number; frame: number; local: number }) {
  const draw = ease(frame, 0, 40);
  // A pulse crosses to the creator's side whenever Meera hands something over.
  const handoff = interpolate(local, [26, 60], [0, 1], CLAMP);
  const pulseY = height * (0.2 + handoff * 0.55);
  return (
    <>
      <div
        style={{
          position: 'absolute',
          left: x - 1,
          top: height * (1 - draw) * 0.5,
          width: 2,
          height: height * draw,
          background: `linear-gradient(180deg, transparent, ${M.accent}, #A9A0F5, ${M.accent}, transparent)`,
          boxShadow: `0 0 ${26 * u}px ${M.accent}, 0 0 ${70 * u}px rgba(109,90,230,0.45)`,
        }}
      />
      {handoff > 0 && handoff < 1 ? (
        <div
          style={{
            position: 'absolute',
            left: x - 5 * u,
            top: pulseY,
            width: 10 * u,
            height: 10 * u,
            borderRadius: '50%',
            background: '#fff',
            boxShadow: `0 0 ${30 * u}px ${M.accent}`,
            opacity: Math.sin(handoff * Math.PI),
          }}
        />
      ) : null}
    </>
  );
}

/* ── Meera's side ─────────────────────────────────────────────────────────────────────── */
function MeeraPanel({
  u,
  width,
  height,
  beat,
  local,
  index,
}: {
  u: number;
  width: number;
  height: number;
  beat: Beat;
  local: number;
  index: number;
}) {
  const fade = ease(local, 0, 18) * (1 - interpolate(local, [beat.duration - 14, beat.duration], [0, 1], CLAMP));
  return (
    <>
      <PanelChrome u={u} width={width} beat={beat} index={index} local={local} />

      <div
        style={{
          position: 'absolute',
          left: 84 * u,
          right: 84 * u,
          top: height * 0.25,
          opacity: fade,
          translate: `0px ${interpolate(fade, [0, 1], [26 * u, 0])}px`,
        }}
      >
        <BeatVisual u={u} beat={beat} local={local} />
      </div>

      {/* The line, bottom left, where a subtitle would sit */}
      <div
        style={{
          position: 'absolute',
          left: 84 * u,
          right: 60 * u,
          bottom: 96 * u,
          fontSize: (beat.id === 'promise' ? 76 : 58) * u,
          fontWeight: 650,
          letterSpacing: '-0.025em',
          lineHeight: 1.1,
          opacity: ease(local, 8, 30),
          translate: `0px ${interpolate(ease(local, 8, 30), [0, 1], [18 * u, 0])}px`,
        }}
      >
        {beat.line}
      </div>
    </>
  );
}

function PanelChrome({ u, width, beat, index, local }: { u: number; width: number; beat: Beat; index: number; local: number }) {
  const frame = useCurrentFrame();
  const working = beat.id !== 'end';
  return (
    <>
      <div style={{ position: 'absolute', left: 84 * u, top: 74 * u, display: 'flex', alignItems: 'center', gap: 16 * u }}>
        <Orb u={u} size={40} />
        <span style={{ fontSize: 30 * u, fontWeight: 600, letterSpacing: '0.04em' }}>Meera</span>
        {working ? (
          <>
            <span style={{ width: 1, height: 26 * u, background: 'rgba(255,255,255,0.18)' }} />
            <span style={{ display: 'flex', alignItems: 'center', gap: 10 * u, fontSize: 24 * u, color: '#93A0B8' }}>
              <span
                style={{
                  width: 9 * u,
                  height: 9 * u,
                  borderRadius: '50%',
                  background: M.accent,
                  opacity: 0.35 + 0.65 * Math.abs(Math.sin(frame / 7)),
                }}
              />
              {beat.status}
            </span>
          </>
        ) : null}
      </div>
      {working ? (
        <div
          style={{
            position: 'absolute',
            left: 84 * u,
            bottom: 52 * u,
            fontSize: 22 * u,
            letterSpacing: '0.24em',
            color: '#5D6A82',
            opacity: ease(local, 6, 24),
          }}
        >
          {String(index).padStart(2, '0')} / 07
        </div>
      ) : null}
    </>
  );
}

function Orb({ u, size }: { u: number; size: number }) {
  const frame = useCurrentFrame();
  const breathe = 1 + Math.sin(frame / 22) * 0.04;
  return (
    <span
      style={{
        width: size * u,
        height: size * u,
        borderRadius: '50%',
        background: `radial-gradient(circle at 34% 28%, #9C8BFF, ${M.accent} 55%, ${M.accentPress})`,
        boxShadow: `0 0 ${size * 0.9 * u}px rgba(109,90,230,0.55)`,
        scale: String(breathe),
        display: 'inline-block',
      }}
    />
  );
}

/** Faint moving data behind Meera's panel — presence, not decoration. */
function AmbientField({ u, width, height }: { u: number; width: number; height: number }) {
  const frame = useCurrentFrame();
  const rows = 26;
  return (
    <AbsoluteFill style={{ opacity: 0.1 }}>
      <svg width={width} height={height}>
        {Array.from({ length: rows }, (_, i) => {
          const y = (i / rows) * height;
          const w = (0.2 + ((i * 37) % 60) / 100) * width;
          const shift = ((frame * (0.4 + (i % 5) * 0.12)) % (width * 1.4)) - width * 0.2;
          return <rect key={i} x={shift} y={y} width={w * 0.45} height={2 * u} rx={1} fill={i % 3 === 0 ? M.accent : '#5D6A82'} />;
        })}
      </svg>
    </AbsoluteFill>
  );
}

/* ── The work itself, one panel per beat ──────────────────────────────────────────────── */
function BeatVisual({ u, beat, local }: { u: number; beat: Beat; local: number }) {
  switch (beat.id) {
    case 'intro':
      return <IntroVisual u={u} local={local} />;
    case 'connect':
      return <ConnectVisual u={u} local={local} />;
    case 'promise':
      return null;
    case 'profile':
      return <ProfileVisual u={u} local={local} />;
    case 'peers':
      return <PeersVisual u={u} local={local} />;
    case 'demand':
      return <DemandVisual u={u} local={local} />;
    case 'script':
      return <ScriptVisual u={u} local={local} />;
    case 'challenge':
      return <ChallengeVisual u={u} local={local} />;
    default:
      return null;
  }
}

function Panel({ u, children, style }: { u: number; children: ReactNode; style?: CSSProperties }) {
  return (
    <div
      style={{
        background: 'rgba(21,28,44,0.78)',
        border: '1px solid #25304A',
        borderRadius: 16 * u,
        padding: `${26 * u}px ${28 * u}px`,
        boxShadow: '0 30px 70px rgba(0,0,0,0.45)',
        ...style,
      }}
    >
      {children}
    </div>
  );
}

function Label({ u, children }: { u: number; children: ReactNode }) {
  return (
    <div style={{ fontSize: 22 * u, letterSpacing: '0.16em', color: '#5D6A82', fontWeight: 600, marginBottom: 16 * u }}>
      {children}
    </div>
  );
}

function IntroVisual({ u, local }: { u: number; local: number }) {
  const halo = ease(local, 10, 60);
  return (
    <div style={{ display: 'flex', justifyContent: 'center', paddingTop: 60 * u, opacity: halo }}>
      <div style={{ position: 'relative' }}>
        <div
          style={{
            position: 'absolute',
            inset: -90 * u,
            borderRadius: '50%',
            background: `radial-gradient(circle, rgba(109,90,230,${0.35 * halo}) 0%, transparent 65%)`,
            filter: 'blur(24px)',
          }}
        />
        <Orb u={u} size={150} />
      </div>
    </div>
  );
}

function ConnectVisual({ u, local }: { u: number; local: number }) {
  const { fps } = useVideoConfig();
  const tick = spring({ frame: local - 46, fps, config: { damping: 13 } });
  const scan = ease(local, 58, 120);
  return (
    <Panel u={u}>
      <Label u={u}>ACCOUNT</Label>
      <div style={{ display: 'flex', alignItems: 'center', gap: 18 * u }}>
        <div
          style={{
            width: 62 * u,
            height: 62 * u,
            borderRadius: 16 * u,
            background: 'linear-gradient(135deg,#F9CE34,#EE2A7B 55%,#6228D7)',
          }}
        />
        <div style={{ flex: 1 }}>
          <div style={{ fontSize: 32 * u, fontWeight: 600 }}>@yourhandle</div>
          <div style={{ fontSize: 24 * u, color: '#93A0B8', marginTop: 4 * u }}>Instagram · Business account</div>
        </div>
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 10 * u,
            fontSize: 24 * u,
            color: M.escrow,
            fontWeight: 600,
            scale: String(tick),
          }}
        >
          <span
            style={{
              width: 30 * u,
              height: 30 * u,
              borderRadius: '50%',
              background: M.escrow,
              color: '#fff',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              fontSize: 20 * u,
            }}
          >
            ✓
          </span>
          Connected
        </div>
      </div>
      <div style={{ marginTop: 26 * u }}>
        <div style={{ fontSize: 22 * u, color: '#93A0B8', marginBottom: 10 * u }}>
          Reading {Math.round(interpolate(scan, [0, 1], [0, 47]))} of 47 posts
        </div>
        <div style={{ height: 6 * u, background: 'rgba(255,255,255,0.08)', borderRadius: 999 }}>
          <div
            style={{
              width: `${scan * 100}%`,
              height: '100%',
              borderRadius: 999,
              background: `linear-gradient(90deg, ${M.accent}, #A9A0F5)`,
              boxShadow: `0 0 ${14 * u}px ${M.accent}`,
            }}
          />
        </div>
      </div>
    </Panel>
  );
}

const PROFILE_ROWS = [
  { label: 'Reach', fill: 0.78 },
  { label: 'Saves', fill: 0.62 },
  { label: 'Watch-through', fill: 0.84 },
];

function ProfileVisual({ u, local }: { u: number; local: number }) {
  return (
    <Panel u={u}>
      <Label u={u}>YOUR LAST 47 POSTS</Label>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 20 * u }}>
        {PROFILE_ROWS.map((row, i) => {
          const fill = ease(local, 14 + i * 10, 64 + i * 10) * row.fill;
          return (
            <div key={row.label}>
              <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 24 * u, color: '#93A0B8', marginBottom: 9 * u }}>
                <span>{row.label}</span>
              </div>
              <div style={{ height: 12 * u, background: 'rgba(255,255,255,0.07)', borderRadius: 999 }}>
                <div
                  style={{
                    width: `${fill * 100}%`,
                    height: '100%',
                    borderRadius: 999,
                    background: `linear-gradient(90deg, ${M.accent}66, ${M.accent})`,
                  }}
                />
              </div>
            </div>
          );
        })}
      </div>
      <div style={{ display: 'flex', gap: 12 * u, marginTop: 26 * u, flexWrap: 'wrap' }}>
        {['Top format: Reels, 21–34s', 'Best window: Tue 7–9 PM'].map((chip, i) => (
          <span
            key={chip}
            style={{
              fontSize: 23 * u,
              padding: `${10 * u}px ${16 * u}px`,
              borderRadius: 10 * u,
              background: 'rgba(109,90,230,0.14)',
              border: `1px solid ${M.accent}55`,
              opacity: ease(local, 70 + i * 10, 96 + i * 10),
            }}
          >
            {chip}
          </span>
        ))}
      </div>
    </Panel>
  );
}

const PEERS = [
  { label: 'Creators like you', posts: 4, fill: 1, highlight: false },
  { label: 'You', posts: 2, fill: 0.5, highlight: true },
];

function PeersVisual({ u, local }: { u: number; local: number }) {
  return (
    <Panel u={u}>
      <Label u={u}>CREATORS YOUR SIZE · SKINCARE</Label>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 22 * u }}>
        {PEERS.map((row, i) => {
          const fill = ease(local, 12 + i * 14, 58 + i * 14) * row.fill;
          return (
            <div key={row.label} style={{ display: 'flex', alignItems: 'center', gap: 18 * u }}>
              <span style={{ width: 300 * u, fontSize: 25 * u, color: row.highlight ? '#EAEEF6' : '#93A0B8' }}>{row.label}</span>
              <span style={{ flex: 1, height: 26 * u, background: 'rgba(255,255,255,0.06)', borderRadius: 8 * u }}>
                <span
                  style={{
                    display: 'block',
                    width: `${fill * 100}%`,
                    height: '100%',
                    borderRadius: 8 * u,
                    background: row.highlight ? `linear-gradient(90deg, ${M.warning}, #F4C56A)` : `linear-gradient(90deg, ${M.accent}, #A9A0F5)`,
                  }}
                />
              </span>
              <span style={{ width: 150 * u, fontSize: 25 * u, textAlign: 'right' }}>{row.posts} Reels / wk</span>
            </div>
          );
        })}
      </div>
      <div
        style={{
          marginTop: 28 * u,
          padding: `${16 * u}px ${20 * u}px`,
          borderRadius: 12 * u,
          background: 'rgba(18,161,80,0.12)',
          border: `1px solid ${M.escrow}55`,
          fontSize: 26 * u,
          opacity: ease(local, 72, 100),
        }}
      >
        They closed skincare Reels at <strong>₹22,000–₹28,000</strong> this quarter.
      </div>
    </Panel>
  );
}

const DEMAND = [
  { label: 'Skincare · festive gifting', note: 'briefs up this week' },
  { label: 'Before / after formats', note: 'asked for in 3 of 5 briefs' },
  { label: 'Hindi voiceover', note: 'new ask' },
];

function DemandVisual({ u, local }: { u: number; local: number }) {
  const { fps } = useVideoConfig();
  return (
    <Panel u={u}>
      <Label u={u}>WHAT BRANDS ARE BRIEFING FOR</Label>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 16 * u }}>
        {DEMAND.map((row, i) => {
          const rise = spring({ frame: local - 14 - i * 12, fps, config: { damping: 16 } });
          return (
            <div
              key={row.label}
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                padding: `${18 * u}px ${20 * u}px`,
                borderRadius: 12 * u,
                background: 'rgba(255,255,255,0.04)',
                border: '1px solid #25304A',
                opacity: rise,
                translate: `0px ${interpolate(rise, [0, 1], [28 * u, 0])}px`,
              }}
            >
              <span style={{ fontSize: 27 * u }}>{row.label}</span>
              <span style={{ fontSize: 22 * u, color: M.escrow, display: 'flex', alignItems: 'center', gap: 8 * u }}>
                ↑ {row.note}
              </span>
            </div>
          );
        })}
      </div>
    </Panel>
  );
}

const SCRIPT_LINES = [
  { tag: 'HOOK', text: 'Ruko — SPF lagane ka sahi tareeka.' },
  { tag: 'BODY', text: 'Pehle problem, phir do lines mein fix.' },
  { tag: 'CTA', text: 'Save karo, kal ke shoot mein kaam aayega.' },
];

function ScriptVisual({ u, local }: { u: number; local: number }) {
  let clock = 20;
  const starts = SCRIPT_LINES.map((line) => {
    const start = clock;
    clock += line.text.length * 0.8 + 8;
    return start;
  });
  return (
    <Panel u={u}>
      <Label u={u}>BUILT FROM YOUR TOP 3 REELS</Label>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 22 * u }}>
        {SCRIPT_LINES.map((line, i) => {
          const start = starts[i];
          const chars = Math.floor(interpolate(local, [start, start + line.text.length * 0.8], [0, line.text.length], CLAMP));
          const typing = chars > 0 && chars < line.text.length;
          return (
            <div key={line.tag} style={{ opacity: local >= start ? 1 : 0.28 }}>
              <div style={{ fontSize: 21 * u, fontWeight: 700, letterSpacing: '0.14em', color: M.accent }}>{line.tag}</div>
              <div style={{ fontSize: 30 * u, marginTop: 8 * u, minHeight: 40 * u }}>
                {line.text.slice(0, chars)}
                {typing ? <span style={{ color: M.accent }}>▍</span> : null}
              </div>
            </div>
          );
        })}
      </div>
    </Panel>
  );
}

function ChallengeVisual({ u, local }: { u: number; local: number }) {
  const { fps } = useVideoConfig();
  const days = ['Post the hook', 'Reply in the first hour', 'Same time, same format', 'Ask one question', 'Repost the best', 'Rest', 'Review with Meera'];
  return (
    <Panel u={u}>
      <Label u={u}>YOUR NEXT SEVEN DAYS</Label>
      <div style={{ display: 'flex', gap: 12 * u, marginBottom: 26 * u }}>
        {days.map((_, i) => {
          const pop = spring({ frame: local - 12 - i * 7, fps, config: { damping: 14 } });
          const lit = i === 0;
          return (
            <span
              key={i}
              style={{
                width: 54 * u,
                height: 54 * u,
                borderRadius: '50%',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                fontSize: 24 * u,
                fontWeight: 600,
                color: lit ? '#fff' : '#93A0B8',
                background: lit ? M.accent : 'rgba(255,255,255,0.05)',
                border: `1px solid ${lit ? M.accent : '#25304A'}`,
                boxShadow: lit ? `0 0 ${24 * u}px ${M.accent}88` : 'none',
                scale: String(pop),
              }}
            >
              {i + 1}
            </span>
          );
        })}
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 12 * u }}>
        {days.slice(0, 3).map((day, i) => (
          <div
            key={day}
            style={{
              display: 'flex',
              gap: 16 * u,
              fontSize: 27 * u,
              opacity: ease(local, 60 + i * 12, 86 + i * 12),
              color: i === 0 ? '#EAEEF6' : '#93A0B8',
            }}
          >
            <span style={{ color: '#5D6A82', width: 90 * u }}>Day {i + 1}</span>
            {day}
          </div>
        ))}
      </div>
    </Panel>
  );
}

/* ── The creator's side ───────────────────────────────────────────────────────────────── */
function CreatorSide({
  u,
  width,
  height,
  seamX,
  beat,
  local,
  open,
}: {
  u: number;
  width: number;
  height: number;
  seamX: number;
  beat: Beat;
  local: number;
  open: number;
}) {
  const { fps } = useVideoConfig();
  const w = width - seamX;
  const drift = interpolate(local, [0, beat.duration], [1.06, 1.0], CLAMP);
  const chipIn = beat.chip ? spring({ frame: local - 34, fps, config: { damping: 15 } }) : 0;

  return (
    <div
      style={{
        position: 'absolute',
        left: seamX,
        top: 0,
        width: w,
        height,
        overflow: 'hidden',
        translate: `${interpolate(open, [0, 1], [40 * u, 0])}px 0px`,
      }}
    >
      {beat.photo ? (
        <AbsoluteFill style={{ scale: String(drift) }}>
          <Img
            src={staticFile(beat.photo)}
            style={{ width: '100%', height: '100%', objectFit: 'cover', objectPosition: beat.focus ?? '50% 35%' }}
          />
        </AbsoluteFill>
      ) : (
        <AbsoluteFill style={{ background: M.stage2 }} />
      )}
      {/* Keep the creator's side warm but let the left stay the brighter surface */}
      <AbsoluteFill
        style={{
          background: `linear-gradient(200deg, rgba(11,15,26,0.28) 0%, rgba(11,15,26,0.05) 40%, rgba(11,15,26,0.78) 100%)`,
        }}
      />
      {beat.chip ? (
        <div
          style={{
            position: 'absolute',
            left: 48 * u,
            right: 48 * u,
            bottom: 96 * u,
            padding: `${20 * u}px ${24 * u}px`,
            borderRadius: 14 * u,
            background: 'rgba(11,15,26,0.72)',
            border: '1px solid rgba(255,255,255,0.14)',
            backdropFilter: 'blur(14px)',
            fontSize: 30 * u,
            lineHeight: 1.3,
            opacity: chipIn,
            translate: `0px ${interpolate(chipIn, [0, 1], [26 * u, 0])}px`,
          }}
        >
          “{beat.chip}”
        </div>
      ) : null}
    </div>
  );
}

function EndCard({ u, progress, local }: { u: number; progress: number; local: number }) {
  if (progress <= 0) return null;
  return (
    <AbsoluteFill
      style={{
        background: M.stage,
        opacity: progress,
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
      }}
    >
      <Orb u={u} size={92} />
      <div
        style={{
          marginTop: 46 * u,
          fontSize: 82 * u,
          fontWeight: 650,
          letterSpacing: '-0.03em',
          opacity: ease(local, 14, 40),
        }}
      >
        Someone in your corner.
      </div>
      <div style={{ marginTop: 22 * u, fontSize: 34 * u, color: '#93A0B8', opacity: ease(local, 30, 56) }}>
        Meera · coming soon on influora.in
      </div>
    </AbsoluteFill>
  );
}
