import { Audio, Easing, interpolate, Sequence, staticFile, useCurrentFrame, useVideoConfig } from 'remotion';

import { LOCALES } from '../locales';
import type { LangCode } from '../script';
import { fontFamily, theme } from '../theme';
import { INTRO_VOICE_FROM, voiceFor } from '../timing';

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

export function IntroScene({ lang }: { lang: LangCode }) {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const copy = LOCALES[lang].intro;
  const voice = voiceFor(lang, 'intro');
  return (
    <div
      style={{
        position: 'absolute',
        inset: 0,
        background: `linear-gradient(160deg, ${theme.primary} 0%, ${theme.accentForeground} 100%)`,
        color: '#ffffff',
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
        <Sequence from={INTRO_VOICE_FROM} durationInFrames={Math.ceil(voice.seconds * fps) + 2} layout="none" name="voice">
          <Audio src={staticFile(voice.file)} />
        </Sequence>
      ) : null}
      <div
        style={{
          ...fadeUp(frame, 4),
          fontSize: 32,
          fontWeight: 700,
          letterSpacing: 2,
          textTransform: 'uppercase',
          backgroundColor: 'rgba(255,255,255,0.18)',
          padding: '14px 34px',
          borderRadius: 999,
        }}
      >
        {copy.eyebrow}
      </div>
      <div
        style={{
          ...fadeUp(frame, 14),
          marginTop: 60,
          width: 220,
          height: 220,
          borderRadius: 999,
          backgroundColor: '#ffffff',
          color: theme.primary,
          fontSize: 120,
          fontWeight: 900,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          boxShadow: '0 30px 80px rgba(0,0,0,0.25)',
        }}
      >
        M
      </div>
      <div style={{ ...fadeUp(frame, 26), marginTop: 50, fontSize: 132, fontWeight: 900, letterSpacing: -3 }}>
        {copy.title}
      </div>
      <div style={{ ...fadeUp(frame, 40), marginTop: 20, fontSize: 52, fontWeight: 600, lineHeight: 1.25 }}>
        {copy.sub}
      </div>
      <div style={{ ...fadeUp(frame, 62), marginTop: 44, fontSize: 38, opacity: 0.9, lineHeight: 1.4, maxWidth: 800 }}>
        {copy.line}
      </div>
    </div>
  );
}
