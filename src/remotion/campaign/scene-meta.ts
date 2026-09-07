import { CAMPAIGN_SCRIPT } from './script';
import { CAMPAIGN_VIDEO } from './theme';
import { CAMPAIGN_VOICE } from './voice-manifest';

/** Frames into a scene before its narration starts. */
export const VOICE_FROM = 10;
/** Frames a scene holds after its narration ends, so cuts do not clip the tail. */
const VOICE_TAIL = 18;

export function voiceFor(id: string) {
  return CAMPAIGN_VOICE[id];
}

/**
 * Frames each scene occupies.
 *
 * The authored `seconds` in `script.ts` is a floor, not the answer: Sarvam's
 * Indian-English voice reads slower than the 150 wpm the copy was estimated at,
 * so a scene stretches to whatever its narration actually needs. Without this a
 * line gets cut off mid-word at the scene boundary.
 */
export const SCENE_FRAMES: Record<string, number> = Object.fromEntries(
  CAMPAIGN_SCRIPT.map((scene) => {
    const authored = Math.round(scene.seconds * CAMPAIGN_VIDEO.fps);
    const voice = CAMPAIGN_VOICE[scene.id];
    const spoken = voice ? VOICE_FROM + Math.ceil(voice.seconds * CAMPAIGN_VIDEO.fps) + VOICE_TAIL : 0;
    return [scene.id, Math.max(authored, spoken)];
  }),
);

/** Absolute start frame of each scene in the full demo. */
export const SCENE_START: Record<string, number> = (() => {
  const starts: Record<string, number> = {};
  let cursor = 0;
  for (const scene of CAMPAIGN_SCRIPT) {
    starts[scene.id] = cursor;
    cursor += SCENE_FRAMES[scene.id];
  }
  return starts;
})();

export const CAMPAIGN_DURATION = CAMPAIGN_SCRIPT.reduce(
  (total, scene) => total + SCENE_FRAMES[scene.id],
  0,
);

export function sceneById(id: string) {
  const found = CAMPAIGN_SCRIPT.find((scene) => scene.id === id);
  if (!found) throw new Error(`Unknown campaign scene: ${id}`);
  return found;
}
