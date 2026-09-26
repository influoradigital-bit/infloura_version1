/**
 * Frame timeline for the Meera intro film (`MeeraIntro.tsx`), 30 fps, 40 s.
 *
 *   0 ─ GENESIS ─ 180 ─ 7 × BEAT (114 each) ─ 978 ─ FINALE ─ 1200
 *
 * `scripts/meera-intro-audio.py` places its chord changes and the finale swell
 * on these same seconds (6.0 s, +3.8 s per beat, 32.6 s). Change both together.
 */
export const INTRO_FPS = 30;

export const GENESIS_FRAMES = 180;
export const BEAT_FRAMES = 114;
export const FINALE_FRAMES = 222;

/** Frame inside GENESIS at which the light finishes drawing the symbol (chime). */
export const SYMBOL_DONE_FRAME = 104;

export type BeatId = 'connect' | 'analyse' | 'suggest' | 'grow' | 'script' | 'brands' | 'dm';

export type Beat = {
  id: BeatId;
  /** Headline; the part in `accent` is painted with the Meera gradient. */
  lead: string;
  accent: string;
  tail: string;
  sub: string;
  comingSoon?: boolean;
};

/**
 * On-screen copy, in order. Every line has to hold up against what the product does:
 * Meera advises, the creator decides (U-4, see `pages/meera-for-creators.claims.test.tsx`).
 * The DM beat is labelled "Coming soon" and promises help, not sending.
 */
export const BEATS: readonly Beat[] = [
  { id: 'connect', lead: 'Connect your ', accent: 'Instagram', tail: '.', sub: 'One tap, through Meta’s own login.' },
  { id: 'analyse', lead: 'Meera ', accent: 'reads', tail: ' your profile.', sub: 'Your audience, your best posts, what’s working.' },
  { id: 'suggest', lead: 'One clear ', accent: 'next step', tail: '.', sub: 'Every Monday: one thing to post, one thing to fix.' },
  { id: 'grow', lead: 'Grow with a ', accent: 'plan', tail: ', not a guess.', sub: 'Small weekly moves that add up.' },
  { id: 'script', lead: 'Scripts and hooks, ', accent: 'in your language', tail: '.', sub: 'Hinglish, English or Marathi.' },
  { id: 'brands', lead: 'Brands that ', accent: 'fit you', tail: '.', sub: 'Meera brings the list. You choose.' },
  { id: 'dm', lead: '', accent: 'DM help', tail: ' for your inbox.', sub: 'Suggestions for every brand message.', comingSoon: true },
];

export const BEATS_START = GENESIS_FRAMES;
export const FINALE_START = BEATS_START + BEATS.length * BEAT_FRAMES;
export const MEERA_INTRO_DURATION = FINALE_START + FINALE_FRAMES;

export function beatStart(index: number): number {
  return BEATS_START + index * BEAT_FRAMES;
}
