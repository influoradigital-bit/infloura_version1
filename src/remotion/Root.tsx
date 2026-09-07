import { Composition, Folder } from 'remotion';

import { CampaignDemo } from './campaign/CampaignDemo';
import { CampaignLifecycle } from './campaign/CampaignLifecycle';
import { CREATOR_DURATION } from './campaign/creator-meta';
import { CreatorLifecycle } from './campaign/CreatorLifecycle';
import { LIFECYCLE_DURATION } from './campaign/lifecycle-meta';
import { CAMPAIGN_DURATION } from './campaign/scene-meta';
import { CAMPAIGN_VIDEO } from './campaign/theme';
import { ChatScene } from './components/ChatScene';
import { IntroScene } from './components/IntroScene';
import { OutroScene } from './components/OutroScene';
import { MEERA_DEMO_DURATION, TIMED_SCENES } from './demo-meta';
import { LANG_ORDER } from './locales';
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
      <Composition
        id="CampaignDemo"
        component={CampaignDemo}
        durationInFrames={CAMPAIGN_DURATION}
        fps={CAMPAIGN_VIDEO.fps}
        width={CAMPAIGN_VIDEO.width}
        height={CAMPAIGN_VIDEO.height}
      />
      <Composition
        id="CreatorLifecycle"
        component={CreatorLifecycle}
        durationInFrames={CREATOR_DURATION}
        fps={CAMPAIGN_VIDEO.fps}
        width={CAMPAIGN_VIDEO.width}
        height={CAMPAIGN_VIDEO.height}
      />
      <Composition
        id="CampaignLifecycle"
        component={CampaignLifecycle}
        durationInFrames={LIFECYCLE_DURATION}
        fps={CAMPAIGN_VIDEO.fps}
        width={CAMPAIGN_VIDEO.width}
        height={CAMPAIGN_VIDEO.height}
      />
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
