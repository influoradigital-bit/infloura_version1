import { AbsoluteFill, Audio, Easing, interpolate, Sequence, spring, staticFile, useCurrentFrame, useVideoConfig } from 'remotion';

import { BeatScene } from './Beats';
import { Cosmos } from './Cosmos';
import { Genesis, MeeraMark } from './Genesis';
import { FONT, GRADIENT, INK, LIGHT } from './look';
import { BEAT_FRAMES, beatStart, BEATS, FINALE_FRAMES, FINALE_START, GENESIS_FRAMES, SYMBOL_DONE_FRAME } from './timeline';

const CLAMP = { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' } as const;

/**
 * The Meera intro film: a line of light writes the Meera symbol, seven beats show what
 * Meera does for a creator, and everything folds back into the symbol for the close.
 *
 * Landscape (1920×1080) and portrait (1080×1920) are the same component; every scene
 * lays itself out from `useVideoConfig()`. Audio is synthesised in-house by
 * `scripts/meera-intro-audio.py` into `public/meera-intro/`.
 */
export function MeeraIntro() {
  return (
    <AbsoluteFill style={{ backgroundColor: INK.deep }}>
      <Cosmos />

      <Sequence name="Genesis" durationInFrames={GENESIS_FRAMES}>
        <Genesis />
      </Sequence>

      <CornerMark />

      {BEATS.map((beat, i) => (
        <Sequence key={beat.id} name={`Beat ${beat.id}`} from={beatStart(i)} durationInFrames={BEAT_FRAMES}>
          <BeatScene beat={beat} index={i} total={BEATS.length} />
        </Sequence>
      ))}

      <ProgressThread />

      <Sequence name="Finale" from={FINALE_START} durationInFrames={FINALE_FRAMES}>
        <Finale />
      </Sequence>

      {/* Sound */}
      <Audio src={staticFile('meera-intro/music.mp3')} volume={0.6} />
      <Sequence name="Chime" from={SYMBOL_DONE_FRAME - 2} durationInFrames={50} layout="none">
        <Audio src={staticFile('meera-intro/chime.mp3')} volume={0.5} />
      </Sequence>
      {BEATS.map((beat, i) => (
        <Sequence key={beat.id} name={`Whoosh ${beat.id}`} from={beatStart(i) - 4} durationInFrames={36} layout="none">
          <Audio src={staticFile('meera-intro/whoosh.mp3')} volume={0.35} />
        </Sequence>
      ))}
      <Sequence name="Riser" from={FINALE_START - 60} durationInFrames={72} layout="none">
        <Audio src={staticFile('meera-intro/riser.mp3')} volume={0.4} />
      </Sequence>
    </AbsoluteFill>
  );
}

/** The small Meera mark that sits in the corner while the beats play. */
function CornerMark() {
  const frame = useCurrentFrame();
  const { width, height } = useVideoConfig();
  const u = Math.min(width, height) / 1080;
  const show = interpolate(frame, [GENESIS_FRAMES - 6, GENESIS_FRAMES + 14, FINALE_START - 10, FINALE_START + 6], [0, 1, 1, 0], CLAMP);
  return (
    <div
      style={{
        position: 'absolute',
        left: 64 * u,
        top: 56 * u,
        display: 'flex',
        alignItems: 'center',
        gap: 16 * u,
        opacity: show,
        fontFamily: FONT,
        color: INK.text,
        fontSize: 34 * u,
        fontWeight: 600,
        letterSpacing: '0.04em',
      }}
    >
      <MeeraMark size={58 * u} id="corner-mark" />
      Meera
    </div>
  );
}

/** Seven small lights along the bottom edge that fill in as the beats go by. */
function ProgressThread() {
  const frame = useCurrentFrame();
  const { width, height } = useVideoConfig();
  const u = Math.min(width, height) / 1080;
  const show = interpolate(frame, [GENESIS_FRAMES, GENESIS_FRAMES + 14, FINALE_START - 10, FINALE_START + 6], [0, 1, 1, 0], CLAMP);
  const seg = 70 * u;
  const gap = 14 * u;
  return (
    <div
      style={{
        position: 'absolute',
        bottom: 60 * u,
        left: 0,
        right: 0,
        display: 'flex',
        justifyContent: 'center',
        gap,
        opacity: show,
      }}
    >
      {BEATS.map((b, i) => {
        const fill = interpolate(frame, [beatStart(i), beatStart(i) + BEAT_FRAMES], [0, 1], CLAMP);
        return (
          <div key={b.id} style={{ width: seg, height: 4 * u, borderRadius: 999, background: 'rgba(255,255,255,0.14)', overflow: 'hidden' }}>
            <div style={{ width: `${fill * 100}%`, height: '100%', background: GRADIENT, boxShadow: `0 0 ${10 * u}px ${LIGHT.cyan}` }} />
          </div>
        );
      })}
    </div>
  );
}

/** Close: the symbol returns, then "One tool. Everything changes." */
function Finale() {
  const frame = useCurrentFrame();
  const { width, height, fps } = useVideoConfig();
  const portrait = height > width;
  const u = Math.min(width, height) / 1080;

  const mark = spring({ frame, fps, config: { damping: 16, stiffness: 90 } });
  const halo = 0.5 + 0.5 * Math.sin(frame / 10);
  const one = interpolate(frame, [30, 54], [0, 1], { ...CLAMP, easing: Easing.bezier(0.16, 1, 0.3, 1) });
  const every = interpolate(frame, [58, 84], [0, 1], { ...CLAMP, easing: Easing.bezier(0.16, 1, 0.3, 1) });
  const foot = interpolate(frame, [104, 128], [0, 1], CLAMP);
  const out = interpolate(frame, [FINALE_FRAMES - 18, FINALE_FRAMES], [1, 0], CLAMP);

  const markSize = Math.min(width, height) * (portrait ? 0.36 : 0.28);
  const headline = (portrait ? 112 : 104) * u;

  return (
    <AbsoluteFill
      style={{
        opacity: out,
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        fontFamily: FONT,
        color: INK.text,
        textAlign: 'center',
        padding: `0 ${90 * u}px`,
      }}
    >
      <div style={{ position: 'relative', scale: String(mark), opacity: mark }}>
        <div
          style={{
            position: 'absolute',
            inset: -markSize * 0.35,
            borderRadius: '50%',
            background: `radial-gradient(circle, ${LIGHT.violet}66 0%, transparent 65%)`,
            opacity: 0.6 + 0.4 * halo,
            filter: 'blur(20px)',
          }}
        />
        <MeeraMark size={markSize} id="finale-mark" />
      </div>
      <div style={{ marginTop: 70 * u, fontSize: headline, fontWeight: 650, letterSpacing: '-0.03em', lineHeight: 1.05 }}>
        <span style={{ opacity: one, display: 'inline-block', translate: `0px ${interpolate(one, [0, 1], [30 * u, 0])}px` }}>
          One tool.
        </span>{' '}
        {portrait ? <br /> : null}
        <span
          style={{
            opacity: every,
            display: 'inline-block',
            translate: `0px ${interpolate(every, [0, 1], [30 * u, 0])}px`,
            backgroundImage: GRADIENT,
            WebkitBackgroundClip: 'text',
            backgroundClip: 'text',
            color: 'transparent',
          }}
        >
          Everything changes.
        </span>
      </div>
      <div style={{ marginTop: 44 * u, fontSize: 36 * u, color: INK.muted, opacity: foot }}>
        Meera · coming soon on <span style={{ color: INK.text, fontWeight: 600 }}>influora.in</span>
      </div>
    </AbsoluteFill>
  );
}
