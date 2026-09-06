import { Composition, Folder } from 'remotion';

import { ChatScene } from './components/ChatScene';
import { IntroScene } from './components/IntroScene';
import { OutroScene } from './components/OutroScene';
import { MEERA_DEMO_DURATION, TIMED_SCENES } from './demo-meta';
import { LANG_ORDER, LOCALES } from './locales';
import { MeeraDemo } from './MeeraDemo';
import { VIDEO } from './theme';
import { introFrames, outroFrames } from './timing';

/**
 * Remotion root: one full demo per language (`MeeraDemo-hi`, `-en`, `-mr`)
 * plus one composition per chapter so each can be previewed and trimmed on
 * its own in Studio (`npx remotion studio`).
 */
export function RemotionRoot() {
  return (
    <>
      {LANG_ORDER.map((lang) => (
        <Composition
          key={lang}
          id={`MeeraDemo-${lang}`}
          component={MeeraDemo}
          defaultProps={{ lang }}
          durationInFrames={MEERA_DEMO_DURATION[lang]}
          fps={VIDEO.fps}
          width={VIDEO.width}
          height={VIDEO.height}
        />
      ))}
      {LANG_ORDER.map((lang) => (
        <Folder key={lang} name={`Scenes-${lang}`}>
          <Composition
            id={`Intro-${lang}`}
            component={IntroScene}
            defaultProps={{ lang }}
            durationInFrames={introFrames(lang)}
            fps={VIDEO.fps}
            width={VIDEO.width}
            height={VIDEO.height}
          />
          {TIMED_SCENES[lang].map((timed) => (
            <Composition
              key={timed.scene.id}
              id={`Scene-${lang}-${timed.scene.id}`}
              component={ChatScene}
              defaultProps={{ timed }}
              durationInFrames={timed.duration}
              fps={VIDEO.fps}
              width={VIDEO.width}
              height={VIDEO.height}
            />
          ))}
          <Composition
            id={`Outro-${lang}`}
            component={OutroScene}
            defaultProps={{ lang }}
            durationInFrames={outroFrames(lang)}
            fps={VIDEO.fps}
            width={VIDEO.width}
            height={VIDEO.height}
          />
        </Folder>
      ))}
    </>
  );
}
