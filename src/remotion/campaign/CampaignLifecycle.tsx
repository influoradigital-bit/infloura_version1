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
import { CreateMontageScene, LifecycleHook, LifecycleOutro } from './components/LifecycleBookends';
import {
  AcceptScene,
  AnalyticsScene,
  ApproveScene,
  BidsScene,
  ContractScene,
  DeliverScene,
  SecureFundsScene,
} from './components/LifecycleScenes';
import { LIFECYCLE_FRAMES, LIFECYCLE_START, LIFECYCLE_VOICE_FROM, lifecycleVoiceFor } from './lifecycle-meta';

function Stage({ scene, children }: { scene: string; children: ReactNode }) {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const voice = lifecycleVoiceFor(scene);
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
          from={LIFECYCLE_VOICE_FROM}
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
 * "How a campaign works" — the full brand lifecycle, brief to results.
 *
 * Companion to `CampaignDemo`, which is the deep-dive on creation alone. The
 * stage spine is the Deal Room's own five phases (Negotiate → Contract →
 * Secure funds → Deliver → Pay); see `lifecycle-script.ts` for the per-scene
 * accuracy notes on what is real versus what the copy deliberately does not claim.
 */
export function CampaignLifecycle() {
  return (
    <AbsoluteFill style={{ backgroundColor: theme.background }}>
      <Sequence name="Hook" from={LIFECYCLE_START.hook} durationInFrames={LIFECYCLE_FRAMES.hook}>
        <Stage scene="hook">
          <LifecycleHook />
        </Stage>
      </Sequence>
      <Sequence name="1 — Create" from={LIFECYCLE_START.create} durationInFrames={LIFECYCLE_FRAMES.create}>
        <Stage scene="create">
          <CreateMontageScene />
        </Stage>
      </Sequence>
      <Sequence name="2 — Applications" from={LIFECYCLE_START.bids} durationInFrames={LIFECYCLE_FRAMES.bids}>
        <Stage scene="bids">
          <BidsScene />
        </Stage>
      </Sequence>
      <Sequence name="3 — Negotiate" from={LIFECYCLE_START.accept} durationInFrames={LIFECYCLE_FRAMES.accept}>
        <Stage scene="accept">
          <AcceptScene />
        </Stage>
      </Sequence>
      <Sequence name="4 — Contract" from={LIFECYCLE_START.contract} durationInFrames={LIFECYCLE_FRAMES.contract}>
        <Stage scene="contract">
          <ContractScene />
        </Stage>
      </Sequence>
      <Sequence
        name="5 — Secure funds"
        from={LIFECYCLE_START.securefunds}
        durationInFrames={LIFECYCLE_FRAMES.securefunds}
      >
        <Stage scene="securefunds">
          <SecureFundsScene />
        </Stage>
      </Sequence>
      <Sequence name="6 — Deliver" from={LIFECYCLE_START.deliver} durationInFrames={LIFECYCLE_FRAMES.deliver}>
        <Stage scene="deliver">
          <DeliverScene />
        </Stage>
      </Sequence>
      <Sequence name="7 — Approve" from={LIFECYCLE_START.approve} durationInFrames={LIFECYCLE_FRAMES.approve}>
        <Stage scene="approve">
          <ApproveScene />
        </Stage>
      </Sequence>
      <Sequence name="8 — Results" from={LIFECYCLE_START.analytics} durationInFrames={LIFECYCLE_FRAMES.analytics}>
        <Stage scene="analytics">
          <AnalyticsScene />
        </Stage>
      </Sequence>
      <Sequence name="Outro" from={LIFECYCLE_START.outro} durationInFrames={LIFECYCLE_FRAMES.outro}>
        <Stage scene="outro">
          <LifecycleOutro />
        </Stage>
      </Sequence>
    </AbsoluteFill>
  );
}
