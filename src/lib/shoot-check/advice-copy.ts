/**
 * Shoot Check — human-readable copy for every advice key `metrics.ts` can produce.
 *
 * One lookup table, two consumers: `ShootCheckPanel` renders `.text` as the one-line fix next to
 * each reading, and `useShootCheck` reads the same `.text` (in the creator's language) for the
 * spoken cue — so the on-screen line and the spoken line never drift apart.
 *
 * Keys are the literal advice strings the six verdict functions in `metrics.ts` return. `'ok'` is
 * intentionally included — a reading that's fine still needs display text ("Looking good").
 */
import type { FocusAdvice, FramingAdvice, LightAdvice, MicAdvice, TiltAdvice } from './metrics';

export type ShootCheckLang = 'en-IN' | 'hi-IN';

/**
 * `'tidy-background'` has no corresponding verdict function in `metrics.ts` — `clutter()` only
 * returns a 0..100 number, and `useShootCheck` classifies it against `THRESHOLDS.CLUTTER_BUSY`
 * itself (there's no per-target nuance the way framing has, so a standalone function would be a
 * single `if`). The copy still belongs in this shared table so the on-screen text and any future
 * spoken cue for it stay in lockstep like every other reading.
 */
type AdviceKey = FramingAdvice | TiltAdvice | LightAdvice | FocusAdvice | MicAdvice | 'unknown' | 'tidy-background';

const COPY: Record<AdviceKey, Record<ShootCheckLang, string>> = {
  // framing
  'lift-phone': { 'en-IN': 'Lift the phone a little to raise your eyes in frame', 'hi-IN': 'फ़ोन को थोड़ा ऊपर उठाएँ' },
  'lower-phone': { 'en-IN': 'Lower the phone slightly — you need more headroom', 'hi-IN': 'फ़ोन थोड़ा नीचे करें' },
  'come-closer': { 'en-IN': 'Come a little closer to fill the frame', 'hi-IN': 'थोड़ा पास आएँ' },
  'step-back': { 'en-IN': 'Step back a little — you’re too close', 'hi-IN': 'थोड़ा पीछे हटें' },
  'move-left': { 'en-IN': 'Move left to center yourself', 'hi-IN': 'थोड़ा बाईं ओर जाएँ' },
  'move-right': { 'en-IN': 'Move right to center yourself', 'hi-IN': 'थोड़ा दाईं ओर जाएँ' },
  'point-at-hands': { 'en-IN': 'Point the camera down at your hands, not your face', 'hi-IN': 'कैमरे को अपने हाथों की ओर करें' },
  'no-face': { 'en-IN': 'Can’t find your face — step into frame', 'hi-IN': 'चेहरा नहीं दिख रहा — फ़्रेम में आएँ' },

  // tilt
  'level-it': { 'en-IN': 'Level the phone — it’s tilted', 'hi-IN': 'फ़ोन सीधा करें' },

  // light
  'add-light': { 'en-IN': 'Too dark — add a light or face a window', 'hi-IN': 'रोशनी बहुत कम है' },
  brighten: { 'en-IN': 'A bit dim — brighten the room if you can', 'hi-IN': 'थोड़ी और रोशनी चाहिए' },
  'reduce-light': { 'en-IN': 'Too bright — move out of direct light', 'hi-IN': 'रोशनी बहुत तेज़ है' },

  // focus
  'hold-steady-or-clean-lens': {
    'en-IN': 'Looks blurry — hold steady or wipe the lens',
    'hi-IN': 'धुंधला लग रहा है — कैमरा स्थिर रखें',
  },
  unknown: { 'en-IN': 'Too dark to check focus', 'hi-IN': 'फ़ोकस जाँचने के लिए बहुत अँधेरा है' },

  // mic
  'reduce-background-noise': { 'en-IN': 'Room noise is drowning your voice — find a quieter spot', 'hi-IN': 'कमरे का शोर ज़्यादा है' },
  'move-closer-to-mic': { 'en-IN': 'Move a little closer to the mic', 'hi-IN': 'माइक के पास आएँ' },

  ok: { 'en-IN': 'Looking good', 'hi-IN': 'ठीक लग रहा है' },

  // background
  'tidy-background': { 'en-IN': 'Background is busy — tidy up or step away from clutter', 'hi-IN': 'पृष्ठभूमि अस्त-व्यस्त है' },
};

/** Falls back to the English copy for a key that somehow isn't in the table, then to the key itself. */
export function adviceText(key: string, lang: ShootCheckLang): string {
  const entry = COPY[key as AdviceKey];
  if (!entry) return key;
  return entry[lang] ?? entry['en-IN'];
}
