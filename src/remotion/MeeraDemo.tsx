import type { ReactNode } from 'react';
import { linearTiming, TransitionSeries } from '@remotion/transitions';
import { fade } from '@remotion/transitions/fade';

import { ChatScene } from './components/ChatScene';
import { IntroScene } from './components/IntroScene';
import { OutroScene } from './components/OutroScene';
import { TIMED_SCENES } from './demo-meta';
import { LOCALES } from './locales';
import type { LangCode } from './script';
import { introFrames, outroFrames, TRANSITION_FRAMES } from './timing';

// A type alias, not an interface: Remotion's <Composition component> wants props assignable to Record<string, unknown>.
export type MeeraDemoProps = { lang: LangCode };

function crossfade(key: string) {
  return (
    <TransitionSeries.Transition
      key={key}
      presentation={fade()}
      timing={linearTiming({ durationInFrames: TRANSITION_FRAMES })}
    />
  );
}

/**
 * The full demo in one language: intro → six chapters → outro, crossfaded.
 * Portrait 1080×1920 at 30 fps; see `theme.ts` VIDEO.
 *
 * TransitionSeries requires Sequence and Transition elements as direct
 * children, so the list is built flat rather than nested in fragments.
 */
export function MeeraDemo({ lang }: MeeraDemoProps) {
  const locale = LOCALES[lang];
  const children: ReactNode[] = [
    <TransitionSeries.Sequence key="intro" durationInFrames={introFrames(lang)} name="Intro">
      <IntroScene lang={lang} />
    </TransitionSeries.Sequence>,
  ];
  for (const timed of TIMED_SCENES[lang]) {
    children.push(crossfade(`t-${timed.scene.id}`));
    children.push(
      <TransitionSeries.Sequence key={timed.scene.id} durationInFrames={timed.duration} name={timed.scene.chapter}>
        <ChatScene timed={timed} />
      </TransitionSeries.Sequence>,
    );
  }
  children.push(crossfade('t-outro'));
  children.push(
    <TransitionSeries.Sequence key="outro" durationInFrames={outroFrames(lang)} name={`Outro ${locale.label}`}>
      <OutroScene lang={lang} />
    </TransitionSeries.Sequence>,
  );
  return <TransitionSeries>{children}</TransitionSeries>;
}
