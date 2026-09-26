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

/**
 * Reel layout guide (Phase 4) and live-camera guide (Phase 5) copy. The en-IN lines marked in spec
 * 2.8 (`safe_zone_note`, `hook_slot`, `device_note`, the grid labels, the three zone labels) are the
 * owner-approved text word for word; the rest follow the spec's mockups 10, 11, 13 and 14.
 *
 * hi-IN: Devanagari Hindi written at build time, PENDING REVIEW (spec 2.8: "reviewed like the 5.3
 * notices"). Do not treat any hi-IN line here as approved until that review lands.
 *
 * House rules for every line (spec 2.7, scanned by `advice-copy.guide.test.ts`): no `@` handle and
 * no third-party names, no golden-ratio theory, no reach or engagement promise, never the word
 * "escrow", and the assistant is only ever "Meera".
 */
export const GUIDE_COPY = {
  // Camera grid setting (Phase 5a/5b, the Guides sheet)
  grid_setting_label: { 'en-IN': 'Camera grid', 'hi-IN': 'कैमरा ग्रिड' },
  grid_option_thirds: { 'en-IN': 'Rule of thirds', 'hi-IN': 'रूल ऑफ़ थर्ड्स' },
  grid_option_golden: { 'en-IN': 'Golden grid', 'hi-IN': 'गोल्डन ग्रिड' },
  grid_option_off: { 'en-IN': 'Off', 'hi-IN': 'बंद' },
  grid_default_tag: { 'en-IN': 'Default', 'hi-IN': 'डिफ़ॉल्ट' },
  guides_button: { 'en-IN': 'Guides', 'hi-IN': 'गाइड' },
  shot_guides_label: { 'en-IN': 'Shot guides', 'hi-IN': 'शॉट गाइड' },
  safe_zone_always_on: { 'en-IN': 'Safe zone: Always on', 'hi-IN': 'सेफ़ ज़ोन: हमेशा चालू' },
  guide_remembered: {
    'en-IN': 'Your choice is remembered on this phone.',
    'hi-IN': 'आपकी पसंद इस फ़ोन पर याद रखी जाती है।',
  },
  got_it: { 'en-IN': 'Got it', 'hi-IN': 'ठीक है' },
  setup_line_prefix: { 'en-IN': 'Set-up for this shot:', 'hi-IN': 'इस शॉट का सेट-अप:' },
  stand_other_line: { 'en-IN': 'Stand on the other line.', 'hi-IN': 'दूसरी लाइन पर खड़े हों।' },

  // Safe zone (spec 2.6 / 2.8): always on, in the layout guide, the PNG and the live camera
  safe_zone_note: {
    'en-IN':
      'The green area keeps your text visible. Instagram’s buttons and captions cover the red areas — check your preview before posting.',
    'hi-IN':
      'हरे हिस्से में रखा टेक्स्ट दिखता रहता है। लाल हिस्सों को Instagram के बटन और कैप्शन ढक लेते हैं — पोस्ट करने से पहले प्रीव्यू ज़रूर देखें।',
  },
  device_note: {
    'en-IN': 'Every phone and app version is a little different — always check your Reel preview before you post.',
    'hi-IN': 'हर फ़ोन और ऐप वर्ज़न थोड़ा अलग होता है — पोस्ट करने से पहले अपनी Reel का प्रीव्यू हमेशा देखें।',
  },
  zone_label_top: { 'en-IN': 'Covered: app top bar', 'hi-IN': 'ढका हुआ: ऐप की ऊपरी पट्टी' },
  zone_label_bottom: { 'en-IN': 'Covered: username, caption, music', 'hi-IN': 'ढका हुआ: यूज़रनेम, कैप्शन, म्यूज़िक' },
  zone_label_cta: { 'en-IN': 'Short CTA, left side', 'hi-IN': 'छोटा CTA, बाईं ओर' },
  zone_label_rail: { 'en-IN': 'Right-side buttons', 'hi-IN': 'दाईं ओर के बटन' },
  zone_label_sides: { 'en-IN': 'Covered: the edges', 'hi-IN': 'ढका हुआ: किनारे' },
  zone_label_safe: { 'en-IN': 'Green: safe for text', 'hi-IN': 'हरा: टेक्स्ट के लिए सुरक्षित' },

  // Reel layout guide (Phase 4)
  reel_layout_tab: { 'en-IN': 'Reel layout', 'hi-IN': 'Reel लेआउट' },
  reel_layout_title: { 'en-IN': 'Influora’s Reel layout guide', 'hi-IN': 'Influora की Reel लेआउट गाइड' },
  face_keep_clear: { 'en-IN': 'Face: keep clear', 'hi-IN': 'चेहरा: इस पर कुछ न रखें' },
  product_label: { 'en-IN': 'Your product', 'hi-IN': 'आपका प्रोडक्ट' },
  slot_hook: { 'en-IN': 'Hook text', 'hi-IN': 'हुक टेक्स्ट' },
  slot_captions: { 'en-IN': 'Captions / CapCut text', 'hi-IN': 'कैप्शन / CapCut टेक्स्ट' },
  slot_logo: { 'en-IN': 'Logo / brand tag', 'hi-IN': 'लोगो / ब्रांड टैग' },
  slot_sticker: { 'en-IN': 'Sticker / product image', 'hi-IN': 'स्टिकर / प्रोडक्ट इमेज' },
  hook_slot: { 'en-IN': 'Put your hook text here, above your head.', 'hi-IN': 'अपना हुक टेक्स्ट यहाँ, अपने सिर के ऊपर रखें।' },
  captions_line: {
    'en-IN': 'Captions: 2 short lines on a dark backing, just above the bottom area.',
    'hi-IN': 'कैप्शन: गहरे बैकग्राउंड पर 2 छोटी लाइनें, नीचे वाले हिस्से के ठीक ऊपर।',
  },
  logo_line: {
    'en-IN': 'Logo: small, never over your face or the product.',
    'hi-IN': 'लोगो: छोटा, कभी भी आपके चेहरे या प्रोडक्ट के ऊपर नहीं।',
  },
  no_space_hook: {
    'en-IN':
      'No clear space above your head for hook text. Leave a little more room above your head, or put it in your captions.',
    'hi-IN': 'हुक टेक्स्ट के लिए सिर के ऊपर खाली जगह नहीं है। सिर के ऊपर थोड़ी और जगह छोड़ें, या इसे कैप्शन में डालें।',
  },
  no_space_captions: {
    'en-IN': 'No clear space for captions: use a dark text backing or a cutaway.',
    'hi-IN': 'कैप्शन के लिए खाली जगह नहीं है: टेक्स्ट के पीछे गहरा बैकग्राउंड लगाएँ या कटअवे शॉट लें।',
  },
  no_space_logo: {
    'en-IN': 'No clear space for your logo: use a dark text backing or a cutaway.',
    'hi-IN': 'लोगो के लिए खाली जगह नहीं है: गहरा बैकग्राउंड लगाएँ या कटअवे शॉट लें।',
  },
  no_space_sticker: {
    'en-IN': 'No clear space for a sticker: use a dark text backing or a cutaway.',
    'hi-IN': 'स्टिकर के लिए खाली जगह नहीं है: गहरा बैकग्राउंड लगाएँ या कटअवे शॉट लें।',
  },
  download_png: { 'en-IN': 'Download CapCut guide (PNG)', 'hi-IN': 'CapCut गाइड डाउनलोड करें (PNG)' },
  png_note: {
    'en-IN': 'The guide has no photo in it. Delete it before you export.',
    'hi-IN': 'इस गाइड में कोई फ़ोटो नहीं है। एक्सपोर्ट करने से पहले इसे हटा दें।',
  },
  png_failed: {
    'en-IN': 'Couldn’t make the guide on this phone. The layout above still works.',
    'hi-IN': 'इस फ़ोन पर गाइड नहीं बन पाई। ऊपर का लेआउट फिर भी काम करता है।',
  },
  // Drawn INTO the PNG: English only, so the file reads the same in every editor's font.
  png_banner: { 'en-IN': 'GUIDE - DELETE BEFORE EXPORT', 'hi-IN': 'GUIDE - DELETE BEFORE EXPORT' },

  // Code-side checks shown on the card (Phase 4). hi-IN here is Hinglish in LATIN script, not
  // Devanagari: the list title and the phone-side backlight line sit in one list with the server's
  // lines (influora-ai checklist.py CHECK_LINES "hi"), which are Hinglish like the rest of the
  // photo-check card, so one list never mixes scripts. Part of the same PENDING REVIEW: the reviewer
  // decides the card's one script (the Reel layout guide under it uses this table's Devanagari).
  quick_checks_label: { 'en-IN': 'Quick checks', 'hi-IN': 'Quick checks' },
  check_backlight: {
    'en-IN': 'Bright light behind your face will make it dark.',
    'hi-IN': 'Aapke peeche tez roshni hai, isse aapka face dark dikhega.',
  },
} as const satisfies Record<string, Record<ShootCheckLang, string>>;

export type GuideCopyKey = keyof typeof GUIDE_COPY;

/** Falls back to the English copy for a key that somehow isn't in the table, then to the key itself. */
export function adviceText(key: string, lang: ShootCheckLang): string {
  const entry: Record<ShootCheckLang, string> | undefined =
    COPY[key as AdviceKey] ?? (GUIDE_COPY as Record<string, Record<ShootCheckLang, string>>)[key];
  if (!entry) return key;
  return entry[lang] ?? entry['en-IN'];
}
