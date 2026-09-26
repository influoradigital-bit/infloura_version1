import { useMemo } from 'react';
import { AbsoluteFill, Easing, interpolate, spring, useCurrentFrame, useVideoConfig } from 'remotion';

import {
  FONT,
  fourPointStar,
  GRADIENT,
  INK,
  LIGHT,
  M_STROKE,
  placeSymbol,
  pointAt,
  RING_RADIUS,
  sampleTrack,
  SPARK_CENTER,
  trackSlice,
} from './look';
import { SYMBOL_DONE_FRAME } from './timeline';

const CLAMP = { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' } as const;

/** Shared SVG <defs>: the Meera stroke gradient and a soft glow. `id` keeps copies apart. */
export function SymbolDefs({ id }: { id: string }) {
  return (
    <defs>
      <linearGradient id={`${id}-stroke`} x1="0" y1="0" x2="1" y2="1">
        <stop offset="0%" stopColor={LIGHT.cyan} />
        <stop offset="50%" stopColor={LIGHT.violet} />
        <stop offset="100%" stopColor={LIGHT.pink} />
      </linearGradient>
      <filter id={`${id}-glow`} x="-50%" y="-50%" width="200%" height="200%">
        <feGaussianBlur stdDeviation="3.5" result="blur" />
        <feMerge>
          <feMergeNode in="blur" />
          <feMergeNode in="blur" />
          <feMergeNode in="SourceGraphic" />
        </feMerge>
      </filter>
    </defs>
  );
}

const M_D = [
  `M${M_STROKE[0][0].x} ${M_STROKE[0][0].y}`,
  ...M_STROKE.map(([, b, c, d]) => `C${b.x} ${b.y} ${c.x} ${c.y} ${d.x} ${d.y}`),
].join(' ');

/** The finished Meera symbol, static, at any size. Used as the corner mark and inside beats. */
export function MeeraMark({ size, id, glow = true }: { size: number; id: string; glow?: boolean }) {
  return (
    <svg width={size} height={size} viewBox="0 0 200 200" style={{ overflow: 'visible' }}>
      <SymbolDefs id={id} />
      <g filter={glow ? `url(#${id}-glow)` : undefined}>
        <circle cx="100" cy="100" r={RING_RADIUS} fill="none" stroke={`url(#${id}-stroke)`} strokeWidth="3" opacity="0.7" />
        <path d={M_D} fill="none" stroke={`url(#${id}-stroke)`} strokeWidth="9" strokeLinecap="round" strokeLinejoin="round" />
        <path d={fourPointStar(SPARK_CENTER.x, SPARK_CENTER.y, 16)} fill={LIGHT.white} />
      </g>
    </svg>
  );
}

/**
 * Opening shot: a comet of light streaks in from the edge of the sky, sweeps across the frame
 * and, without lifting, writes the Meera "M". The ring closes around it, the spark ignites
 * (chime at SYMBOL_DONE_FRAME), and the name fades up beneath.
 */
export function Genesis() {
  const frame = useCurrentFrame();
  const { width, height, fps } = useVideoConfig();
  const portrait = height > width;

  const size = Math.min(width, height) * (portrait ? 0.5 : 0.4);
  const cx = width / 2;
  const cy = height * (portrait ? 0.42 : 0.4);
  const at = (p: { x: number; y: number }) => placeSymbol(p, cx, cy, size);

  const { track, mStartLength } = useMemo(() => {
    const start = at(M_STROKE[0][0]);
    const leadIn: [typeof start, typeof start, typeof start, typeof start] = [
      { x: -0.08 * width, y: 0.82 * height },
      { x: 0.3 * width, y: 0.02 * height },
      { x: 0.02 * width, y: 1.02 * height },
      start,
    ];
    const mCurves = M_STROKE.map((c) => c.map(at) as typeof leadIn);
    const lead = sampleTrack([leadIn], 90);
    return { track: sampleTrack([leadIn, ...mCurves], 60), mStartLength: lead.total };
    // `at` is derived from width/height only.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [width, height]);

  const head = interpolate(frame, [12, SYMBOL_DONE_FRAME], [0, track.total], {
    ...CLAMP,
    easing: Easing.bezier(0.5, 0, 0.25, 1),
  });
  const headPt = pointAt(track, head);
  const trail = Math.min(width, height) * 0.55;
  const comet = interpolate(frame, [SYMBOL_DONE_FRAME - 4, SYMBOL_DONE_FRAME + 10], [1, 0], CLAMP);

  const ring = interpolate(frame, [SYMBOL_DONE_FRAME - 18, SYMBOL_DONE_FRAME + 26], [0, 1], {
    ...CLAMP,
    easing: Easing.bezier(0.16, 1, 0.3, 1),
  });
  const spark = spring({ frame: frame - SYMBOL_DONE_FRAME, fps, config: { damping: 11, stiffness: 140 } });
  const burst = interpolate(frame, [SYMBOL_DONE_FRAME, SYMBOL_DONE_FRAME + 30], [0, 1], CLAMP);

  const name = interpolate(frame, [SYMBOL_DONE_FRAME + 12, SYMBOL_DONE_FRAME + 36], [0, 1], {
    ...CLAMP,
    easing: Easing.bezier(0.16, 1, 0.3, 1),
  });
  const tagline = interpolate(frame, [SYMBOL_DONE_FRAME + 28, SYMBOL_DONE_FRAME + 50], [0, 1], CLAMP);
  const exit = interpolate(frame, [158, 180], [1, 0], { ...CLAMP, easing: Easing.bezier(0.7, 0, 0.84, 0) });

  const ringR = (RING_RADIUS * size) / 200;
  const ringLen = 2 * Math.PI * ringR;
  const sparkPt = at(SPARK_CENTER);
  const stroke = (size / 200) * 9;

  return (
    <AbsoluteFill style={{ opacity: exit, scale: String(interpolate(exit, [0, 1], [0.92, 1])) }}>
      <svg width={width} height={height} style={{ position: 'absolute', inset: 0, overflow: 'visible' }}>
        <SymbolDefs id="genesis" />
        {/* The light's burst when the spark ignites */}
        <circle
          cx={cx}
          cy={cy}
          r={size * (0.3 + burst * 0.9)}
          fill={LIGHT.violet}
          opacity={(1 - burst) * 0.35 * (frame >= SYMBOL_DONE_FRAME ? 1 : 0)}
          style={{ filter: 'blur(30px)' }}
        />
        <g filter="url(#genesis-glow)">
          {/* Comet tail: three stacked windows behind the head, brighter toward it */}
          {[1, 0.55, 0.25].map((f, i) => (
            <path
              key={i}
              d={trackSlice(track, head - trail * f, head)}
              fill="none"
              stroke={i === 2 ? LIGHT.white : `url(#genesis-stroke)`}
              strokeWidth={stroke * (0.35 + i * 0.3)}
              strokeLinecap="round"
              opacity={comet * (0.25 + i * 0.3)}
            />
          ))}
          {/* What the light leaves behind: the M */}
          <path
            d={trackSlice(track, mStartLength, head)}
            fill="none"
            stroke="url(#genesis-stroke)"
            strokeWidth={stroke}
            strokeLinecap="round"
            strokeLinejoin="round"
          />
          <circle
            cx={cx}
            cy={cy}
            r={ringR}
            fill="none"
            stroke="url(#genesis-stroke)"
            strokeWidth={stroke / 3}
            strokeDasharray={ringLen}
            strokeDashoffset={ringLen * (1 - ring)}
            transform={`rotate(-90 ${cx} ${cy})`}
            opacity={0.75}
          />
          <path
            d={fourPointStar(sparkPt.x, sparkPt.y, (size / 200) * 16 * spark)}
            fill={LIGHT.white}
            opacity={frame >= SYMBOL_DONE_FRAME ? 1 : 0}
          />
        </g>
        {/* Comet head */}
        <circle cx={headPt.x} cy={headPt.y} r={stroke * 1.1} fill={LIGHT.white} opacity={comet} style={{ filter: 'blur(1px)' }} />
        <circle cx={headPt.x} cy={headPt.y} r={stroke * 3.2} fill={LIGHT.cyan} opacity={comet * 0.35} style={{ filter: 'blur(10px)' }} />
      </svg>

      <div
        style={{
          position: 'absolute',
          left: 0,
          right: 0,
          top: cy + size * 0.62,
          textAlign: 'center',
          fontFamily: FONT,
          color: INK.text,
        }}
      >
        <div
          style={{
            fontSize: Math.min(width, height) * 0.12,
            fontWeight: 600,
            letterSpacing: `${interpolate(name, [0, 1], [0.4, 0.06])}em`,
            opacity: name,
            translate: `0px ${interpolate(name, [0, 1], [24, 0])}px`,
            paddingLeft: '0.06em',
          }}
        >
          Meera
        </div>
        <div
          style={{
            marginTop: Math.min(width, height) * 0.015,
            fontSize: Math.min(width, height) * 0.04,
            fontWeight: 500,
            display: 'inline-block',
            opacity: tagline,
            backgroundImage: GRADIENT,
            backgroundClip: 'text',
            WebkitBackgroundClip: 'text',
            color: 'transparent',
          }}
        >
          Your AI for the creator life
        </div>
      </div>
    </AbsoluteFill>
  );
}
