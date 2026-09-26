import { useMemo } from 'react';
import { AbsoluteFill, interpolate, random, useCurrentFrame, useVideoConfig } from 'remotion';

import { INK, LIGHT } from './look';

const STAR_COUNT = 190;

/**
 * The backdrop for the whole film: a deep night sky with slow-drifting nebula light in the
 * Meera orb colours and a deterministic, twinkling starfield (seeded, so every render and
 * every Player frame shows the same sky).
 */
export function Cosmos() {
  const frame = useCurrentFrame();
  const { width, height, durationInFrames } = useVideoConfig();

  const stars = useMemo(
    () =>
      Array.from({ length: STAR_COUNT }, (_, i) => ({
        x: random(`x-${i}`),
        y: random(`y-${i}`),
        r: 0.6 + random(`r-${i}`) ** 3 * 2.4,
        phase: random(`p-${i}`) * Math.PI * 2,
        speed: 0.03 + random(`s-${i}`) * 0.07,
        depth: 0.3 + random(`d-${i}`) * 0.7,
      })),
    [],
  );

  const drift = frame / durationInFrames;
  const skyIn = interpolate(frame, [0, 24], [0, 1], { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' });

  return (
    <AbsoluteFill style={{ background: `radial-gradient(ellipse at 50% 40%, ${INK.night} 0%, ${INK.deep} 70%)` }}>
      <AbsoluteFill
        style={{
          opacity: 0.55 * skyIn,
          background: [
            `radial-gradient(circle at ${18 + drift * 10}% ${30 - drift * 8}%, ${LIGHT.violet}55 0%, transparent 38%)`,
            `radial-gradient(circle at ${82 - drift * 12}% ${70 + drift * 6}%, ${LIGHT.pink}33 0%, transparent 34%)`,
            `radial-gradient(circle at ${60 + drift * 6}% ${18 + drift * 10}%, ${LIGHT.cyan}26 0%, transparent 30%)`,
          ].join(','),
          filter: 'blur(40px)',
        }}
      />
      <svg width={width} height={height} style={{ position: 'absolute', inset: 0, opacity: skyIn }}>
        {stars.map((s, i) => {
          const twinkle = 0.45 + 0.55 * Math.abs(Math.sin(s.phase + frame * s.speed));
          const x = ((s.x * width - frame * s.depth * 0.35) % width + width) % width;
          return <circle key={i} cx={x} cy={s.y * height} r={s.r} fill="#ffffff" opacity={twinkle * s.depth} />;
        })}
      </svg>
    </AbsoluteFill>
  );
}
