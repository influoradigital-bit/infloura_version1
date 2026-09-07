import { CREATOR_SCRIPT } from './creator-script';
import { CREATOR_VOICE } from './creator-voice-manifest';
import { CAMPAIGN_VIDEO } from './theme';

/** Frames into a scene before its narration starts. */
export const CREATOR_VOICE_FROM = 10;
/** Frames a scene holds after its narration ends, so cuts do not clip the tail. */
const VOICE_TAIL = 20;

export function creatorVoiceFor(id: string) {
  return CREATOR_VOICE[id];
}

/** Authored seconds are a floor; a scene stretches to fit its narration. */
export const CREATOR_FRAMES: Record<string, number> = Object.fromEntries(
  CREATOR_SCRIPT.map((scene) => {
    const authored = Math.round(scene.seconds * CAMPAIGN_VIDEO.fps);
    const voice = CREATOR_VOICE[scene.id];
    const spoken = voice
      ? CREATOR_VOICE_FROM + Math.ceil(voice.seconds * CAMPAIGN_VIDEO.fps) + VOICE_TAIL
      : 0;
    return [scene.id, Math.max(authored, spoken)];
  }),
);

export const CREATOR_START: Record<string, number> = (() => {
  const starts: Record<string, number> = {};
  let cursor = 0;
  for (const scene of CREATOR_SCRIPT) {
    starts[scene.id] = cursor;
    cursor += CREATOR_FRAMES[scene.id];
  }
  return starts;
})();

export const CREATOR_DURATION = CREATOR_SCRIPT.reduce(
  (total, scene) => total + CREATOR_FRAMES[scene.id],
  0,
);
