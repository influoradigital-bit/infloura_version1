import type { CSSProperties, ReactNode } from 'react';
import { useMemo } from 'react';
import { Check, Facebook, Instagram, Sparkles } from 'lucide-react';
import { AbsoluteFill, Easing, interpolate, spring, useCurrentFrame, useVideoConfig } from 'remotion';

import { MeeraMark } from './Genesis';
import { FONT, GRADIENT, INK, LIGHT, pointAt, sampleTrack, trackSlice } from './look';
import type { Beat, BeatId } from './timeline';
import { BEAT_FRAMES } from './timeline';

const CLAMP = { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' } as const;
const OUT = Easing.bezier(0.16, 1, 0.3, 1);

const glass: CSSProperties = {
  background: 'rgba(22,18,52,0.62)',
  border: `1px solid ${INK.glassBorder}`,
  boxShadow: '0 30px 80px rgba(0,0,0,0.45), inset 0 1px 0 rgba(255,255,255,0.12)',
  backdropFilter: 'blur(18px)',
};

/** 0→1 over [a, b] with the house ease-out. */
function ease(frame: number, a: number, b: number) {
  return interpolate(frame, [a, b], [0, 1], { ...CLAMP, easing: OUT });
}

/**
 * One feature beat: a visual on one side, the headline on the other (stacked on portrait).
 * Everything enters over the first ~24 frames and dissolves over the last 14, so beats
 * cross cleanly without a transition wrapper.
 */
export function BeatScene({ beat, index, total }: { beat: Beat; index: number; total: number }) {
  const frame = useCurrentFrame();
  const { width, height } = useVideoConfig();
  const portrait = height > width;
  const u = Math.min(width, height) / 1080;

  const exit = interpolate(frame, [BEAT_FRAMES - 14, BEAT_FRAMES], [1, 0], CLAMP);
  const exitBlur = interpolate(frame, [BEAT_FRAMES - 14, BEAT_FRAMES], [0, 12], CLAMP);
  const vis = ease(frame, 0, 20);
  const head = ease(frame, 6, 24);
  const sub = ease(frame, 14, 32);
  const sweep = interpolate(frame, [0, 18], [0, 1], { ...CLAMP, easing: Easing.bezier(0.3, 0, 0.2, 1) });

  const V = portrait ? width * 0.8 : height * 0.64;
  const visualBox: CSSProperties = portrait
    ? { left: (width - V) / 2, top: height * 0.1, width: V, height: V }
    : { left: width * 0.07, top: (height - V) / 2, width: V, height: V };
  const textBox: CSSProperties = portrait
    ? { left: 90 * u, right: 90 * u, top: height * 0.1 + V + 70 * u }
    : { left: width * 0.53, right: width * 0.07, top: 0, bottom: 0, display: 'flex', flexDirection: 'column', justifyContent: 'center' };
  const lineY = portrait ? height * 0.1 + V + 40 * u : height * 0.5;

  return (
    <AbsoluteFill style={{ opacity: exit, filter: `blur(${exitBlur}px)` }}>
      {/* A thread of light crosses the frame as each beat arrives */}
      <div
        style={{
          position: 'absolute',
          top: lineY,
          left: 0,
          height: 2 * u,
          width: `${sweep * 100}%`,
          background: `linear-gradient(90deg, transparent, ${LIGHT.cyan}, ${LIGHT.violet}, ${LIGHT.white})`,
          opacity: interpolate(frame, [0, 10, 26, 40], [0, 1, 0.8, 0], CLAMP),
          boxShadow: `0 0 ${18 * u}px ${LIGHT.cyan}`,
        }}
      />

      <div style={{ position: 'absolute', ...visualBox, opacity: vis, scale: String(interpolate(vis, [0, 1], [0.9, 1])) }}>
        <BeatVisual id={beat.id} V={V} frame={frame} />
      </div>

      <div style={{ position: 'absolute', ...textBox, fontFamily: FONT, color: INK.text }}>
        <div
          style={{
            fontSize: 26 * u,
            fontWeight: 600,
            letterSpacing: '0.24em',
            color: INK.muted,
            opacity: head,
            display: 'flex',
            alignItems: 'center',
            gap: 18 * u,
          }}
        >
          {String(index + 1).padStart(2, '0')} / {String(total).padStart(2, '0')}
          {beat.comingSoon ? (
            <span
              style={{
                letterSpacing: '0.08em',
                fontSize: 24 * u,
                color: INK.text,
                padding: `${6 * u}px ${18 * u}px`,
                borderRadius: 999,
                border: `1px solid ${LIGHT.pink}`,
                background: `${LIGHT.pink}22`,
              }}
            >
              COMING SOON
            </span>
          ) : null}
        </div>
        <div
          style={{
            marginTop: 22 * u,
            fontSize: (portrait ? 88 : 80) * u,
            lineHeight: 1.06,
            fontWeight: 650,
            letterSpacing: '-0.025em',
            opacity: head,
            translate: `0px ${interpolate(head, [0, 1], [34 * u, 0])}px`,
          }}
        >
          {beat.lead}
          <span style={{ backgroundImage: GRADIENT, WebkitBackgroundClip: 'text', backgroundClip: 'text', color: 'transparent' }}>
            {beat.accent}
          </span>
          {beat.tail}
        </div>
        <div
          style={{
            marginTop: 26 * u,
            fontSize: (portrait ? 44 : 38) * u,
            lineHeight: 1.3,
            color: INK.muted,
            opacity: sub,
            translate: `0px ${interpolate(sub, [0, 1], [20 * u, 0])}px`,
          }}
        >
          {beat.sub}
        </div>
      </div>
    </AbsoluteFill>
  );
}

function BeatVisual({ id, V, frame }: { id: BeatId; V: number; frame: number }) {
  switch (id) {
    case 'connect':
      return <ConnectVisual V={V} frame={frame} />;
    case 'analyse':
      return <AnalyseVisual V={V} frame={frame} />;
    case 'suggest':
      return <SuggestVisual V={V} frame={frame} />;
    case 'grow':
      return <GrowVisual V={V} frame={frame} />;
    case 'script':
      return <ScriptVisual V={V} frame={frame} />;
    case 'brands':
      return <BrandsVisual V={V} frame={frame} />;
    case 'dm':
      return <DmVisual V={V} frame={frame} />;
  }
}

function Card({ V, children, style }: { V: number; children: ReactNode; style?: CSSProperties }) {
  return (
    <div
      style={{
        position: 'absolute',
        borderRadius: V * 0.05,
        padding: V * 0.06,
        fontFamily: FONT,
        color: INK.text,
        ...glass,
        ...style,
      }}
    >
      {children}
    </div>
  );
}

/* ── 1. Connect: Instagram and Facebook wire into Meera ─────────────────────────────── */
function ConnectVisual({ V, frame }: { V: number; frame: number }) {
  const { fps } = useVideoConfig();
  const nodes = [
    { x: 0.18, y: 0.24, Icon: Instagram, color: LIGHT.pink },
    { x: 0.82, y: 0.24, Icon: Facebook, color: LIGHT.blue },
  ];
  const c = { x: 0.5, y: 0.62 };
  const wire = ease(frame, 12, 42);
  return (
    <>
      <svg width={V} height={V} style={{ position: 'absolute', inset: 0, overflow: 'visible' }}>
        {nodes.map((n, i) => {
          // Stop each wire at the rim of Meera's circle (radius 0.17 V) instead of its centre.
          const full = Math.hypot((c.x - n.x) * V, (c.y - n.y) * V);
          const k = (full - 0.17 * V) / full;
          const end = { x: n.x + (c.x - n.x) * k, y: n.y + (c.y - n.y) * k };
          const len = full * k;
          const pulse = ((frame - 40 - i * 12) % 36) / 36;
          return (
            <g key={i}>
              <line
                x1={n.x * V}
                y1={n.y * V}
                x2={end.x * V}
                y2={end.y * V}
                stroke={n.color}
                strokeWidth={V * 0.006}
                strokeDasharray={len}
                strokeDashoffset={len * (1 - wire)}
                opacity={0.8}
              />
              {frame > 40 + i * 12 ? (
                <circle
                  cx={(n.x + (end.x - n.x) * pulse) * V}
                  cy={(n.y + (end.y - n.y) * pulse) * V}
                  r={V * 0.012}
                  fill={LIGHT.white}
                  style={{ filter: `drop-shadow(0 0 ${V * 0.02}px ${n.color})` }}
                />
              ) : null}
            </g>
          );
        })}
      </svg>
      {nodes.map(({ x, y, Icon, color }, i) => {
        const pop = spring({ frame: frame - 4 - i * 6, fps, config: { damping: 13 } });
        const tick = spring({ frame: frame - 44 - i * 10, fps, config: { damping: 12 } });
        return (
          <div
            key={i}
            style={{
              position: 'absolute',
              left: x * V - V * 0.11,
              top: y * V - V * 0.11,
              width: V * 0.22,
              height: V * 0.22,
              borderRadius: '50%',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              scale: String(pop),
              ...glass,
              boxShadow: `0 0 ${V * 0.08}px ${color}55`,
            }}
          >
            <Icon size={V * 0.1} color={INK.text} strokeWidth={1.6} />
            <div
              style={{
                position: 'absolute',
                right: -V * 0.01,
                bottom: -V * 0.01,
                width: V * 0.07,
                height: V * 0.07,
                borderRadius: '50%',
                background: '#2fbf71',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                scale: String(tick),
              }}
            >
              <Check size={V * 0.045} color="#fff" strokeWidth={3} />
            </div>
          </div>
        );
      })}
      <div
        style={{
          position: 'absolute',
          left: c.x * V - V * 0.17,
          top: c.y * V - V * 0.17,
          width: V * 0.34,
          height: V * 0.34,
          borderRadius: '50%',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          ...glass,
          boxShadow: `0 0 ${V * (0.08 + 0.05 * Math.sin(frame / 6))}px ${LIGHT.violet}88`,
        }}
      >
        <MeeraMark size={V * 0.24} id="connect-mark" />
      </div>
    </>
  );
}

/* ── 2. Analyse: a profile card with a scan line and three readings ──────────────────── */
function AnalyseVisual({ V, frame }: { V: number; frame: number }) {
  const scan = interpolate(frame, [10, 70], [0, 1], CLAMP);
  const rows = [
    { label: 'Your audience', fill: 0.82, color: LIGHT.cyan },
    { label: 'Your best posts', fill: 0.66, color: LIGHT.violet },
    { label: 'What’s working', fill: 0.74, color: LIGHT.pink },
  ];
  return (
    <Card V={V} style={{ left: V * 0.06, right: V * 0.06, top: V * 0.14, bottom: V * 0.14, overflow: 'hidden' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: V * 0.04 }}>
        <div style={{ width: V * 0.16, height: V * 0.16, borderRadius: '50%', background: GRADIENT, padding: V * 0.008 }}>
          <div style={{ width: '100%', height: '100%', borderRadius: '50%', background: INK.night }} />
        </div>
        <div>
          <div style={{ fontSize: V * 0.052, fontWeight: 600 }}>@yourhandle</div>
          <div style={{ fontSize: V * 0.036, color: INK.muted, marginTop: V * 0.008 }}>Creator · Instagram</div>
        </div>
      </div>
      <div style={{ marginTop: V * 0.07, display: 'flex', flexDirection: 'column', gap: V * 0.045 }}>
        {rows.map((r, i) => {
          const fill = ease(frame, 24 + i * 10, 60 + i * 10) * r.fill;
          return (
            <div key={r.label}>
              <div style={{ fontSize: V * 0.036, color: INK.muted, marginBottom: V * 0.014 }}>{r.label}</div>
              <div style={{ height: V * 0.022, borderRadius: 999, background: 'rgba(255,255,255,0.08)' }}>
                <div
                  style={{
                    width: `${fill * 100}%`,
                    height: '100%',
                    borderRadius: 999,
                    background: `linear-gradient(90deg, ${r.color}66, ${r.color})`,
                    boxShadow: `0 0 ${V * 0.03}px ${r.color}`,
                  }}
                />
              </div>
            </div>
          );
        })}
      </div>
      <div
        style={{
          position: 'absolute',
          left: 0,
          right: 0,
          top: `${scan * 100}%`,
          height: V * 0.1,
          marginTop: -V * 0.1,
          background: `linear-gradient(180deg, transparent, ${LIGHT.cyan}40)`,
          borderBottom: `2px solid ${LIGHT.cyan}`,
          opacity: interpolate(frame, [10, 20, 62, 72], [0, 1, 1, 0], CLAMP),
        }}
      />
    </Card>
  );
}

/* ── 3. Suggest: the Monday note ─────────────────────────────────────────────────────── */
function SuggestVisual({ V, frame }: { V: number; frame: number }) {
  const { fps } = useVideoConfig();
  const items = [
    { tag: 'POST', text: 'A day-in-my-life Reel', color: LIGHT.cyan },
    { tag: 'FIX', text: 'Say the hook in the first two seconds', color: LIGHT.pink },
  ];
  return (
    <Card V={V} style={{ left: V * 0.04, right: V * 0.04, top: V * 0.16, bottom: V * 0.16 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: V * 0.025, fontSize: V * 0.045, fontWeight: 600 }}>
        <Sparkles size={V * 0.055} color={LIGHT.violet} />
        Your Monday note
      </div>
      <div style={{ marginTop: V * 0.06, display: 'flex', flexDirection: 'column', gap: V * 0.04 }}>
        {items.map((it, i) => {
          const pop = spring({ frame: frame - 18 - i * 14, fps, config: { damping: 14 } });
          return (
            <div
              key={it.tag}
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: V * 0.035,
                padding: `${V * 0.04}px ${V * 0.04}px`,
                borderRadius: V * 0.035,
                background: 'rgba(255,255,255,0.05)',
                border: `1px solid ${it.color}55`,
                opacity: pop,
                translate: `${interpolate(pop, [0, 1], [V * 0.08, 0])}px 0px`,
              }}
            >
              <span
                style={{
                  fontSize: V * 0.03,
                  fontWeight: 700,
                  letterSpacing: '0.12em',
                  color: it.color,
                  minWidth: V * 0.1,
                }}
              >
                {it.tag}
              </span>
              <span style={{ fontSize: V * 0.042 }}>{it.text}</span>
            </div>
          );
        })}
      </div>
    </Card>
  );
}

/* ── 4. Grow: a line that climbs, no invented numbers ───────────────────────────────── */
function GrowVisual({ V, frame }: { V: number; frame: number }) {
  const track = useMemo(
    () =>
      sampleTrack([
        [
          { x: 0.1 * V, y: 0.78 * V },
          { x: 0.32 * V, y: 0.8 * V },
          { x: 0.36 * V, y: 0.6 * V },
          { x: 0.52 * V, y: 0.58 * V },
        ],
        [
          { x: 0.52 * V, y: 0.58 * V },
          { x: 0.68 * V, y: 0.56 * V },
          { x: 0.74 * V, y: 0.3 * V },
          { x: 0.9 * V, y: 0.2 * V },
        ],
      ]),
    [V],
  );
  const drawn = ease(frame, 10, 70) * track.total;
  const tip = pointAt(track, drawn);
  const line = trackSlice(track, 0, drawn);
  const area = line ? `${line} L${tip.x} ${0.86 * V} L${0.1 * V} ${0.86 * V} Z` : '';
  return (
    <Card V={V} style={{ inset: V * 0.04, padding: 0 }}>
      <svg width={V * 0.92} height={V * 0.92} viewBox={`${V * 0.04} ${V * 0.04} ${V * 0.92} ${V * 0.92}`}>
        <defs>
          <linearGradient id="grow-area" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor={LIGHT.violet} stopOpacity="0.45" />
            <stop offset="100%" stopColor={LIGHT.violet} stopOpacity="0" />
          </linearGradient>
          <linearGradient id="grow-line" x1="0" y1="0" x2="1" y2="0">
            <stop offset="0%" stopColor={LIGHT.cyan} />
            <stop offset="100%" stopColor={LIGHT.pink} />
          </linearGradient>
        </defs>
        {[0.3, 0.5, 0.7].map((y) => (
          <line key={y} x1={0.1 * V} x2={0.9 * V} y1={y * V} y2={y * V} stroke="rgba(255,255,255,0.08)" strokeWidth={1} />
        ))}
        <path d={area} fill="url(#grow-area)" />
        <path d={line} fill="none" stroke="url(#grow-line)" strokeWidth={V * 0.012} strokeLinecap="round" />
        {drawn > 0 ? (
          <>
            <circle cx={tip.x} cy={tip.y} r={V * 0.035} fill={LIGHT.pink} opacity={0.35} style={{ filter: 'blur(6px)' }} />
            <circle cx={tip.x} cy={tip.y} r={V * 0.016} fill={LIGHT.white} />
          </>
        ) : null}
      </svg>
    </Card>
  );
}

/* ── 5. Script: a Hinglish Reel script types itself ─────────────────────────────────── */
/** Each line types at ~0.9 frames per character, with a 4-frame beat before the next one. */
const SCRIPT = [
  { tag: 'HOOK', text: 'Ruko! Yeh skip mat karna.', color: LIGHT.pink },
  { tag: 'BODY', text: 'Pehle problem dikhao, phir apna fix.', color: LIGHT.violet },
  { tag: 'CTA', text: 'Save karo, next shoot pe kaam aayega.', color: LIGHT.cyan },
].reduce<Array<{ tag: string; text: string; color: string; start: number; typeFrames: number }>>((acc, line) => {
  const previous = acc[acc.length - 1];
  const start = previous ? previous.start + previous.typeFrames + 4 : 14;
  acc.push({ ...line, start, typeFrames: line.text.length * 0.9 });
  return acc;
}, []);

function ScriptVisual({ V, frame }: { V: number; frame: number }) {
  return (
    <Card V={V} style={{ left: V * 0.02, right: V * 0.02, top: V * 0.12, bottom: V * 0.12 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <span style={{ fontSize: V * 0.045, fontWeight: 600 }}>Reel script</span>
        <span
          style={{
            fontSize: V * 0.03,
            padding: `${V * 0.01}px ${V * 0.028}px`,
            borderRadius: 999,
            border: `1px solid ${INK.glassBorder}`,
            color: INK.muted,
          }}
        >
          Hinglish
        </span>
      </div>
      <div style={{ marginTop: V * 0.05, display: 'flex', flexDirection: 'column', gap: V * 0.04 }}>
        {SCRIPT.map((s) => {
          const { start, typeFrames } = s;
          const chars = Math.floor(interpolate(frame, [start, start + typeFrames], [0, s.text.length], CLAMP));
          const typing = chars > 0 && chars < s.text.length;
          return (
            <div key={s.tag} style={{ opacity: frame >= start ? 1 : 0.25 }}>
              <div style={{ fontSize: V * 0.028, fontWeight: 700, letterSpacing: '0.14em', color: s.color }}>{s.tag}</div>
              <div style={{ fontSize: V * 0.044, marginTop: V * 0.01, minHeight: V * 0.06 }}>
                {s.text.slice(0, chars)}
                {typing ? <span style={{ color: s.color }}>▍</span> : null}
              </div>
            </div>
          );
        })}
      </div>
    </Card>
  );
}

/* ── 6. Brands: categories orbit Meera; the ones that fit light up ──────────────────── */
function BrandsVisual({ V, frame }: { V: number; frame: number }) {
  const cats = [
    { name: 'Skincare', fit: true },
    { name: 'Fitness', fit: false },
    { name: 'Tech', fit: true },
    { name: 'Food', fit: false },
    { name: 'Fashion', fit: true },
  ];
  const turn = frame * 0.55;
  const settle = ease(frame, 50, 72);
  return (
    <>
      <svg width={V} height={V} style={{ position: 'absolute', inset: 0 }}>
        <ellipse cx={V / 2} cy={V / 2} rx={V * 0.42} ry={V * 0.3} fill="none" stroke="rgba(255,255,255,0.12)" strokeDasharray="4 10" />
      </svg>
      <div style={{ position: 'absolute', left: V / 2 - V * 0.11, top: V / 2 - V * 0.11 }}>
        <MeeraMark size={V * 0.22} id="brands-mark" />
      </div>
      {cats.map((c, i) => {
        const a = ((turn + (i * 360) / cats.length) * Math.PI) / 180;
        const x = V / 2 + Math.cos(a) * V * 0.42;
        const y = V / 2 + Math.sin(a) * V * 0.3;
        const lit = c.fit ? settle : 0;
        return (
          <div
            key={c.name}
            style={{
              position: 'absolute',
              left: x,
              top: y,
              translate: '-50% -50%',
              whiteSpace: 'nowrap',
              fontFamily: FONT,
              fontSize: V * 0.042,
              color: INK.text,
              padding: `${V * 0.018}px ${V * 0.035}px`,
              borderRadius: 999,
              ...glass,
              border: `1px solid ${lit > 0.5 ? '#2fbf71' : INK.glassBorder}`,
              boxShadow: `0 0 ${V * 0.06 * lit}px #2fbf71aa`,
              opacity: c.fit ? 1 : interpolate(settle, [0, 1], [1, 0.4]),
              display: 'flex',
              alignItems: 'center',
              gap: V * 0.015,
            }}
          >
            {lit > 0.5 ? <Check size={V * 0.04} color="#2fbf71" strokeWidth={3} /> : null}
            {c.name}
          </div>
        );
      })}
    </>
  );
}

/* ── 7. DM help (coming soon): Meera suggests, the creator replies ──────────────────── */
function DmVisual({ V, frame }: { V: number; frame: number }) {
  const { fps } = useVideoConfig();
  const inbound = spring({ frame: frame - 10, fps, config: { damping: 14 } });
  const hint = spring({ frame: frame - 36, fps, config: { damping: 14 } });
  const dots = frame > 64;
  return (
    <Card V={V} style={{ left: V * 0.08, right: V * 0.08, top: V * 0.08, bottom: V * 0.08 }}>
      <div style={{ fontSize: V * 0.036, color: INK.muted, borderBottom: `1px solid ${INK.glassBorder}`, paddingBottom: V * 0.03 }}>
        Messages
      </div>
      <div
        style={{
          marginTop: V * 0.05,
          maxWidth: '80%',
          padding: `${V * 0.03}px ${V * 0.04}px`,
          borderRadius: `${V * 0.04}px ${V * 0.04}px ${V * 0.04}px ${V * 0.008}px`,
          background: 'rgba(255,255,255,0.1)',
          fontSize: V * 0.04,
          opacity: inbound,
          translate: `0px ${interpolate(inbound, [0, 1], [V * 0.04, 0])}px`,
        }}
      >
        Hi! Loved your Reels. Collab for our launch?
      </div>
      <div
        style={{
          marginTop: V * 0.05,
          padding: V * 0.035,
          borderRadius: V * 0.035,
          border: `1px solid ${LIGHT.violet}88`,
          background: `${LIGHT.violet}1f`,
          opacity: hint,
          scale: String(interpolate(hint, [0, 1], [0.94, 1])),
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: V * 0.02, fontSize: V * 0.03, color: INK.muted, fontWeight: 600 }}>
          <MeeraMark size={V * 0.05} id="dm-mark" glow={false} />
          MEERA SUGGESTS
        </div>
        <div style={{ fontSize: V * 0.04, marginTop: V * 0.015 }}>Ask for the brief and the deliverables first.</div>
      </div>
      <div style={{ marginTop: V * 0.05, display: 'flex', justifyContent: 'flex-end', gap: V * 0.014, opacity: dots ? 1 : 0 }}>
        {[0, 1, 2].map((i) => (
          <div
            key={i}
            style={{
              width: V * 0.02,
              height: V * 0.02,
              borderRadius: '50%',
              background: INK.text,
              opacity: 0.35 + 0.65 * Math.abs(Math.sin((frame - i * 5) / 6)),
            }}
          />
        ))}
      </div>
    </Card>
  );
}
