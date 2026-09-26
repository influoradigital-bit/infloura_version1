import { Player, Thumbnail } from '@remotion/player';

import { MeeraIntro } from '@/remotion/intro/MeeraIntro';
import { INTRO_FPS, MEERA_INTRO_DURATION } from '@/remotion/intro/timeline';

/** Frame used for the poster: the Meera symbol, fully drawn, with its name beneath. */
const POSTER_FRAME = 150;

function size(portrait: boolean) {
  return portrait ? { width: 1080, height: 1920 } : { width: 1920, height: 1080 };
}

/**
 * The Meera intro film (`src/remotion/intro/MeeraIntro.tsx`), played live in the page.
 *
 * Lazy-loaded by `pages/meera.tsx` so `remotion` and `@remotion/player` stay out of the
 * marketing site's main chunk. It opens from a click, which counts as the user gesture
 * browsers want before they play sound, so it starts unmuted.
 */
export function MeeraIntroPlayer({ portrait }: { portrait: boolean }) {
  const { width, height } = size(portrait);
  return (
    <Player
      component={MeeraIntro}
      durationInFrames={MEERA_INTRO_DURATION}
      compositionWidth={width}
      compositionHeight={height}
      fps={INTRO_FPS}
      style={{ width: '100%', aspectRatio: `${width} / ${height}`, borderRadius: 20, overflow: 'hidden' }}
      autoPlay
      controls
      showVolumeControls
      clickToPlay
      acknowledgeRemotionLicense
    />
  );
}

/** A still of the film for the hero card; costs one frame render, no playback. */
export function MeeraIntroPoster() {
  return (
    <Thumbnail
      component={MeeraIntro}
      compositionWidth={1920}
      compositionHeight={1080}
      frameToDisplay={POSTER_FRAME}
      durationInFrames={MEERA_INTRO_DURATION}
      fps={INTRO_FPS}
      style={{ width: '100%', aspectRatio: '16 / 9' }}
    />
  );
}
