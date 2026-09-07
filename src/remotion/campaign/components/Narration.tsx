import { Audio, Sequence, staticFile, useVideoConfig } from 'remotion';

import { VOICE_FROM, voiceFor } from '../scene-meta';

/**
 * The narration for one scene, offset by `VOICE_FROM` so the voice starts just
 * after the scene has faded in. Renders nothing when a clip has not been
 * generated yet, so the composition still previews without audio.
 */
export function Narration({ scene }: { scene: string }) {
  const { fps } = useVideoConfig();
  const voice = voiceFor(scene);
  if (!voice) return null;
  return (
    <Sequence
      name="Narration"
      from={VOICE_FROM}
      durationInFrames={Math.ceil(voice.seconds * fps) + 2}
      layout="none"
    >
      <Audio src={staticFile(voice.file)} />
    </Sequence>
  );
}
