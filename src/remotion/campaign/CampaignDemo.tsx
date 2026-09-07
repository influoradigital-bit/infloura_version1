import type { ReactNode } from 'react';
import { AbsoluteFill, Easing, interpolate, Sequence, useCurrentFrame } from 'remotion';

import { theme } from '../theme';
import { BasicsBrandScene, BasicsTitleScene, BudgetScene, ContentScene, RequirementsScene } from './components/FormScenes';
import { Narration } from './components/Narration';
import { PublishScene, ReviewScene } from './components/ReviewScenes';
import { HookScene, OutroScene } from './components/TitleScenes';
import { TypePickerScene } from './components/TypePickerScene';
import { SCENE_FRAMES, SCENE_START } from './scene-meta';

/**
 * Cross-scene fade. Each scene animates its own contents in; this only softens
 * the cut between them so the browser window does not pop.
 */
function Fade({ scene, children }: { scene: string; children: ReactNode }) {
  const frame = useCurrentFrame();
  return (
    <AbsoluteFill
      style={{
        backgroundColor: theme.background,
        opacity: interpolate(frame, [0, 8], [0, 1], {
          extrapolateLeft: 'clamp',
          extrapolateRight: 'clamp',
          easing: Easing.bezier(0.16, 1, 0.3, 1),
        }),
      }}
    >
      <Narration scene={scene} />
      {children}
    </AbsoluteFill>
  );
}

/**
 * "How to create a campaign" — the brand-side product demo.
 *
 * Scene order and durations come from `script.ts`; the on-screen form mirrors
 * the real five-step form in `src/components/brand/campaigns/campaign-form.tsx`.
 */
export function CampaignDemo() {
  return (
    <AbsoluteFill style={{ backgroundColor: theme.background }}>
      <Sequence name="Hook" from={SCENE_START.hook} durationInFrames={SCENE_FRAMES.hook}>
        <Fade scene="hook">
          <HookScene />
        </Fade>
      </Sequence>
      <Sequence name="Campaign type" from={SCENE_START.type} durationInFrames={SCENE_FRAMES.type}>
        <Fade scene="type">
          <TypePickerScene />
        </Fade>
      </Sequence>
      <Sequence name="Basics — title" from={SCENE_START['basics-a']} durationInFrames={SCENE_FRAMES['basics-a']}>
        <Fade scene="basics-a">
          <BasicsTitleScene />
        </Fade>
      </Sequence>
      <Sequence name="Basics — brand" from={SCENE_START['basics-b']} durationInFrames={SCENE_FRAMES['basics-b']}>
        <Fade scene="basics-b">
          <BasicsBrandScene />
        </Fade>
      </Sequence>
      <Sequence name="Content" from={SCENE_START.content} durationInFrames={SCENE_FRAMES.content}>
        <Fade scene="content">
          <ContentScene />
        </Fade>
      </Sequence>
      <Sequence name="Budget" from={SCENE_START.budget} durationInFrames={SCENE_FRAMES.budget}>
        <Fade scene="budget">
          <BudgetScene />
        </Fade>
      </Sequence>
      <Sequence name="Requirements" from={SCENE_START.requirements} durationInFrames={SCENE_FRAMES.requirements}>
        <Fade scene="requirements">
          <RequirementsScene />
        </Fade>
      </Sequence>
      <Sequence name="Review" from={SCENE_START.review} durationInFrames={SCENE_FRAMES.review}>
        <Fade scene="review">
          <ReviewScene />
        </Fade>
      </Sequence>
      <Sequence name="Publish" from={SCENE_START.publish} durationInFrames={SCENE_FRAMES.publish}>
        <Fade scene="publish">
          <PublishScene />
        </Fade>
      </Sequence>
      <Sequence name="Outro" from={SCENE_START.outro} durationInFrames={SCENE_FRAMES.outro}>
        <Fade scene="outro">
          <OutroScene />
        </Fade>
      </Sequence>
    </AbsoluteFill>
  );
}
