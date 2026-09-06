import { Audio, Easing, interpolate, Sequence, staticFile, useCurrentFrame, useVideoConfig } from 'remotion';

import { LOCALES } from '../locales';
import type { LangCode } from '../script';
import { fontFamily, theme } from '../theme';
import { OUTRO_VOICE_FROM, voiceFor } from '../timing';

function fadeUp(frame: number, at: number) {
  return {
    opacity: interpolate(frame, [at, at + 18], [0, 1], {
      extrapolateLeft: 'clamp',
      extrapolateRight: 'clamp',
      easing: Easing.bezier(0.16, 1, 0.3, 1),
    }),
    translate: interpolate(frame, [at, at + 22], ['0px 30px', '0px 0px'], {
      extrapolateLeft: 'clamp',
      extrapolateRight: 'clamp',
      easing: Easing.bezier(0.16, 1, 0.3, 1),
    }),
  };
}

export function OutroScene({ lang }: { lang: LangCode }) {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const copy = LOCALES[lang].outro;
  const voice = voiceFor(lang, 'outro');
  return (
    <div
      style={{
        position: 'absolute',
        inset: 0,
        backgroundColor: theme.background,
        color: theme.foreground,
        fontFamily,
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        textAlign: 'center',
        padding: '0 100px',
      }}
    >
      {voice ? (
        <Sequence from={OUTRO_VOICE_FROM} durationInFrames={Math.ceil(voice.seconds * fps) + 2} layout="none" name="voice">
          <Audio src={staticFile(voice.file)} />
        </Sequence>
      ) : null}
      <div style={{ ...fadeUp(frame, 4), fontSize: 96, fontWeight: 900, letterSpacing: -2, lineHeight: 1.05 }}>
        {copy.title}
      </div>
      <div style={{ ...fadeUp(frame, 16), marginTop: 22, fontSize: 44, color: theme.mutedForeground }}>
        {copy.sub}
      </div>
      <div style={{ marginTop: 70, display: 'flex', flexDirection: 'column', gap: 26, width: 820 }}>
        {copy.bullets.map((b, i) => (
          <div
            key={b}
            style={{
              ...fadeUp(frame, 34 + i * 14),
              display: 'flex',
              alignItems: 'center',
              gap: 22,
              backgroundColor: theme.card,
              border: `2px solid ${theme.border}`,
              borderRadius: 24,
              padding: '24px 30px',
              fontSize: 38,
              fontWeight: 600,
              textAlign: 'left',
            }}
          >
            <span
              style={{
                width: 48,
                height: 48,
                borderRadius: 999,
                backgroundColor: theme.success,
                color: theme.successForeground,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                fontSize: 30,
                fontWeight: 800,
                flexShrink: 0,
              }}
            >
              ✓
            </span>
            {b}
          </div>
        ))}
      </div>
      <div
        style={{
          ...fadeUp(frame, 96),
          marginTop: 70,
          fontSize: 44,
          fontWeight: 800,
          color: '#ffffff',
          backgroundColor: theme.accentForeground,
          padding: '28px 64px',
          borderRadius: 999,
          boxShadow: '0 20px 50px rgba(76, 59, 194, 0.35)',
        }}
      >
        {copy.cta}
      </div>
    </div>
  );
}
