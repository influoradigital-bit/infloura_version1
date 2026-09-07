import type { ReactNode } from 'react';
import {
  AbsoluteFill,
  Audio,
  Easing,
  interpolate,
  Sequence,
  staticFile,
  useCurrentFrame,
  useVideoConfig,
} from 'remotion';

import { theme } from '../theme';
import { CreatorContractScene, CreatorHook, CreatorOutro } from './components/CreatorBookends';
import {
  ApplyScene,
  BrowseScene,
  CreatorDeliverScene,
  NegotiateScene,
  PaidScene,
  SecuredScene,
  ShortlistScene,
} from './components/CreatorScenes';
import { CREATOR_FRAMES, CREATOR_START, CREATOR_VOICE_FROM, creatorVoiceFor } from './creator-meta';

function Stage({ scene, children }: { scene: string; children: ReactNode }) {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const voice = creatorVoiceFor(scene);
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
      {voice ? (
        <Sequence
          name="Narration"
          from={CREATOR_VOICE_FROM}
          durationInFrames={Math.ceil(voice.seconds * fps) + 2}
          layout="none"
        >
          <Audio src={staticFile(voice.file)} />
        </Sequence>
      ) : null}
      {children}
    </AbsoluteFill>
  );
}

/**
 * "Know when you get paid" — the campaign lifecycle from the creator's side.
 *
 * Same deal as `CampaignLifecycle`, other chair: Ritika applying to Bloomveda's
 * Diwali campaign. Wallet vocabulary is the product's own Available Balance /
 * Secured / Pending Payouts; see `creator-script.ts` for per-scene accuracy notes.
 */
export function CreatorLifecycle() {
  return (
    <AbsoluteFill style={{ backgroundColor: theme.background }}>
      <Sequence name="Hook" from={CREATOR_START.hook} durationInFrames={CREATOR_FRAMES.hook}>
        <Stage scene="hook">
          <CreatorHook />
        </Stage>
      </Sequence>
      <Sequence name="1 — Find work" from={CREATOR_START.browse} durationInFrames={CREATOR_FRAMES.browse}>
        <Stage scene="browse">
          <BrowseScene />
        </Stage>
      </Sequence>
      <Sequence name="2 — Apply" from={CREATOR_START.apply} durationInFrames={CREATOR_FRAMES.apply}>
        <Stage scene="apply">
          <ApplyScene />
        </Stage>
      </Sequence>
      <Sequence name="3 — Get picked" from={CREATOR_START.shortlist} durationInFrames={CREATOR_FRAMES.shortlist}>
        <Stage scene="shortlist">
          <ShortlistScene />
        </Stage>
      </Sequence>
      <Sequence name="4 — Agree the rate" from={CREATOR_START.negotiate} durationInFrames={CREATOR_FRAMES.negotiate}>
        <Stage scene="negotiate">
          <NegotiateScene />
        </Stage>
      </Sequence>
      <Sequence name="5 — Contract" from={CREATOR_START.contract} durationInFrames={CREATOR_FRAMES.contract}>
        <Stage scene="contract">
          <CreatorContractScene />
        </Stage>
      </Sequence>
      <Sequence name="6 — Money secured" from={CREATOR_START.secured} durationInFrames={CREATOR_FRAMES.secured}>
        <Stage scene="secured">
          <SecuredScene />
        </Stage>
      </Sequence>
      <Sequence name="7 — Deliver" from={CREATOR_START.deliver} durationInFrames={CREATOR_FRAMES.deliver}>
        <Stage scene="deliver">
          <CreatorDeliverScene />
        </Stage>
      </Sequence>
      <Sequence name="8 — Get paid" from={CREATOR_START.paid} durationInFrames={CREATOR_FRAMES.paid}>
        <Stage scene="paid">
          <PaidScene />
        </Stage>
      </Sequence>
      <Sequence name="Outro" from={CREATOR_START.outro} durationInFrames={CREATOR_FRAMES.outro}>
        <Stage scene="outro">
          <CreatorOutro />
        </Stage>
      </Sequence>
    </AbsoluteFill>
  );
}
