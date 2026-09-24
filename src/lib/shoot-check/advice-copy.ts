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
type AdviceKey =
  | FramingAdvice
  | TiltAdvice
  | LightAdvice
  | FocusAdvice
  | MicAdvice
  | 'unknown'
  | 'tilt-unknown'
  | 'mic-unknown'
  | 'mic-no-voice'
  | 'tidy-background';

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
  // One key per UNKNOWN reason. These were a single `unknown` key, so the tilt row rendered the
  // focus sentence ("Too dark to check focus") whenever a phone had no orientation sensor —
  // found by running the panel against a synthetic camera in a real browser, where tilt is always
  // unknown. A shared key across two readings cannot say why either one is unknown.
  unknown: { 'en-IN': 'Too dark to check focus', 'hi-IN': 'फ़ोकस जाँचने के लिए बहुत अँधेरा है' },
  'tilt-unknown': {
    'en-IN': 'This phone doesn’t report tilt — check the edges of the frame look level',
    'hi-IN': 'यह फ़ोन झुकाव नहीं बताता — फ़्रेम के किनारे देखकर सीधा करें',
  },
  'mic-unknown': {
    'en-IN': 'No microphone reading — check your sound on a test recording',
    'hi-IN': 'माइक की रीडिंग नहीं मिली — एक टेस्ट रिकॉर्डिंग से आवाज़ जाँच लें',
  },
  // A mic IS attached and readable, but nothing is clearly above the room's own noise floor yet —
  // not a fault (there is nothing to judge), so this is deliberately NOT phrased as a problem the
  // way 'reduce-background-noise' is, and useShootCheck never speaks it (see `hasVoiceActivity`).
  'mic-no-voice': {
    'en-IN': 'Say a line to check your sound',
    'hi-IN': 'अपनी आवाज़ जाँचने के लिए एक लाइन बोलें',
  },

  // mic
  'reduce-background-noise': { 'en-IN': 'Room noise is drowning your voice — find a quieter spot', 'hi-IN': 'कमरे का शोर ज़्यादा है' },
  'move-closer-to-mic': { 'en-IN': 'Move a little closer to the mic', 'hi-IN': 'माइक के पास आएँ' },

  ok: { 'en-IN': 'Looking good', 'hi-IN': 'ठीक लग रहा है' },

  // background
  'tidy-background': { 'en-IN': 'Background is busy — tidy up or step away from clutter', 'hi-IN': 'पृष्ठभूमि अस्त-व्यस्त है' },
};

/**
 * "Check my frame" is the ONE thing on this screen that leaves the phone, so the screen has to
 * say so where the creator taps it. The live meter above reads the video in the browser and
 * uploads nothing; this button captures one still and sends it to Influora, which passes it to
 * the AI that writes the advice. A face is personal data, so "nothing is saved" on its own was
 * not enough - it was true (the bytes are never written to disk or the database) but it let a
 * creator assume the photo never left their phone at all.
 */
export const FRAME_CHECK_DISCLOSURE: Record<ShootCheckLang, string> = {
  'en-IN':
    'This sends one photo of your shot to Influora’s AI to check it. It is used for this check only, and never saved.',
  'hi-IN':
    'इससे आपके शॉट की एक फ़ोटो Influora के AI को जाँच के लिए भेजी जाती है। यह सिर्फ़ इसी जाँच के लिए इस्तेमाल होती है, कभी सेव नहीं होती।',
};

/** Falls back to the English copy for a key that somehow isn't in the table, then to the key itself. */
export function adviceText(key: string, lang: ShootCheckLang): string {
  const entry = COPY[key as AdviceKey];
  if (!entry) return key;
  return entry[lang] ?? entry['en-IN'];
}
