import { useState } from 'react';
import { Player } from '@remotion/player';

import { DEFAULT_LANG, MEERA_DEMO_DURATION } from '@/remotion/demo-meta';
import { LANG_ORDER, LOCALES } from '@/remotion/locales';
import { MeeraDemo } from '@/remotion/MeeraDemo';
import type { LangCode } from '@/remotion/script';
import { VIDEO } from '@/remotion/theme';

/**
 * The scripted Meera demo, played live in a phone-shaped frame, in the
 * language the visitor picks (Hinglish, English, Marathi). Each language is
 * its own composition length because the voice clips differ.
 *
 * Lazy-loaded by `pages/meera-for-creators.tsx` so `remotion` and
 * `@remotion/player` stay out of the marketing site's main chunk. The
 * composition itself is portrait 1080×1920; the Player scales it to the
 * container width and keeps the aspect ratio.
 */
export function MeeraDemoPlayer() {
  const [lang, setLang] = useState<LangCode>(DEFAULT_LANG);

  return (
    <div className="mx-auto w-full max-w-[360px]">
      <div
        className="mb-3 flex justify-center gap-1 rounded-full border border-border/60 bg-card/50 p-1"
        role="tablist"
        aria-label="Demo language"
      >
        {LANG_ORDER.map((code) => {
          const active = code === lang;
          return (
            <button
              key={code}
              type="button"
              role="tab"
              aria-selected={active}
              onClick={() => setLang(code)}
              className={`rounded-full px-3 py-1 text-sm font-medium transition-colors ${
                active ? 'bg-accent-foreground text-white' : 'text-muted-foreground hover:text-foreground'
              }`}
            >
              {LOCALES[code].label}
            </button>
          );
        })}
      </div>
      <Player
        key={lang}
        component={MeeraDemo}
        inputProps={{ lang }}
        durationInFrames={MEERA_DEMO_DURATION[lang]}
        compositionWidth={VIDEO.width}
        compositionHeight={VIDEO.height}
        fps={VIDEO.fps}
        style={{ width: '100%', aspectRatio: `${VIDEO.width} / ${VIDEO.height}`, borderRadius: 24 }}
        autoPlay
        loop
        controls
        initiallyMuted
        showVolumeControls
        clickToPlay
        acknowledgeRemotionLicense
      />
      <p className="mt-3 text-center text-xs text-muted-foreground">Unmute to hear Meera 🔊</p>
    </div>
  );
}
