import { Audio, Easing, interpolate, Sequence, staticFile, useCurrentFrame, useVideoConfig } from 'remotion';

import { PHONE } from '../theme';
import { CHAT_VIEWPORT, heightAt, type TimedScene } from '../timing';
import { Bubble, SystemLine, TapButton, TypingDots } from './Bubble';
import { CardView } from './Cards';
import { PhoneFrame } from './PhoneFrame';

const SCROLL_FRAMES = 16;

/**
 * Renders one chapter: the phone, and its beats appearing on their start
 * frames. Auto-scroll is computed from estimated beat heights (no DOM
 * measurement) so the newest beat always stays in view.
 */
export function ChatScene({ timed }: { timed: TimedScene }) {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const { scene, beats } = timed;

  // Content height now, and content height just before the most recent beat
  // arrived, so the scroll can ease between the two.
  let latestStart = 0;
  let contentNow = 0;
  for (const b of beats) {
    contentNow += heightAt(b, frame);
    if (b.start <= frame && b.start > latestStart) latestStart = b.start;
  }
  let contentBefore = 0;
  for (const b of beats) {
    contentBefore += heightAt(b, latestStart - 1);
  }
  const target = Math.max(0, contentNow + 30 - CHAT_VIEWPORT);
  const previous = Math.max(0, contentBefore + 30 - CHAT_VIEWPORT);
  const scrollY = interpolate(frame, [latestStart, latestStart + SCROLL_FRAMES], [previous, target], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
    easing: Easing.bezier(0.16, 1, 0.3, 1),
  });

  return (
    <PhoneFrame chapter={scene.chapter} header={scene.header} headerSub={scene.headerSub} surface={scene.surface}>
      {beats.map((b, i) =>
        b.voice ? (
          <Sequence
            key={`${scene.id}-voice-${i}`}
            from={b.start + 4}
            durationInFrames={Math.ceil(b.voice.seconds * fps) + 2}
            layout="none"
            name={`voice ${scene.id}-${i}`}
          >
            <Audio src={staticFile(b.voice.file)} />
          </Sequence>
        ) : null,
      )}
      <div
        style={{
          position: 'absolute',
          top: PHONE.header,
          left: 0,
          right: 0,
          bottom: 0,
          overflow: 'hidden',
        }}
      >
        <div
          style={{
            padding: `24px ${PHONE.pad}px 0`,
            translate: `0px ${-scrollY}px`,
          }}
        >
          {beats.map((b, i) => {
            if (frame < b.start) return null;
            const key = `${scene.id}-${i}`;
            switch (b.beat.kind) {
              case 'meera':
                return (
                  <Bubble key={key} start={b.start} text={b.beat.text} side="left" typewriter revealFrames={b.revealFrames} />
                );
              case 'wa':
                return (
                  <Bubble key={key} start={b.start} text={b.beat.text} side="left" typewriter revealFrames={b.revealFrames} wa />
                );
              case 'creator':
                return <Bubble key={key} start={b.start} text={b.beat.text} side="right" />;
              case 'typing':
                return <TypingDots key={key} start={b.start} hold={b.hold} />;
              case 'card':
                return <CardView key={key} start={b.start} card={b.beat.card} />;
              case 'tap':
                return <TapButton key={key} start={b.start} label={b.beat.label} />;
              case 'system':
                return <SystemLine key={key} start={b.start} text={b.beat.text} />;
              default:
                return null;
            }
          })}
        </div>
      </div>
    </PhoneFrame>
  );
}
