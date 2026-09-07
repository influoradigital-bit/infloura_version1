import { LIFECYCLE_SCRIPT } from './lifecycle-script';
import { LIFECYCLE_VOICE } from './lifecycle-voice-manifest';
import { CAMPAIGN_VIDEO } from './theme';

/** Frames into a scene before its narration starts. */
export const LIFECYCLE_VOICE_FROM = 10;
/** Frames a scene holds after its narration ends, so cuts do not clip the tail. */
const VOICE_TAIL = 20;

export function lifecycleVoiceFor(id: string) {
  return LIFECYCLE_VOICE[id];
}

/**
 * Same rule as the creation film: the authored `seconds` is a floor and the
 * scene stretches to fit its narration, never the other way round.
 */
export const LIFECYCLE_FRAMES: Record<string, number> = Object.fromEntries(
  LIFECYCLE_SCRIPT.map((scene) => {
    const authored = Math.round(scene.seconds * CAMPAIGN_VIDEO.fps);
    const voice = LIFECYCLE_VOICE[scene.id];
    const spoken = voice
      ? LIFECYCLE_VOICE_FROM + Math.ceil(voice.seconds * CAMPAIGN_VIDEO.fps) + VOICE_TAIL
      : 0;
    return [scene.id, Math.max(authored, spoken)];
  }),
);

export const LIFECYCLE_START: Record<string, number> = (() => {
  const starts: Record<string, number> = {};
  let cursor = 0;
  for (const scene of LIFECYCLE_SCRIPT) {
    starts[scene.id] = cursor;
    cursor += LIFECYCLE_FRAMES[scene.id];
  }
  return starts;
})();

export const LIFECYCLE_DURATION = LIFECYCLE_SCRIPT.reduce(
  (total, scene) => total + LIFECYCLE_FRAMES[scene.id],
  0,
);
