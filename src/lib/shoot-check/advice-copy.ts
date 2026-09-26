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
  // Owner ruling 2026-09-26: the guide shows the photo the way viewers see the posted Reel (never
  // mirrored, even for a front-camera still), because the safe zones and quick checks are about the
  // posted video.
  viewer_view_note: {
    'en-IN': 'Shown the way your viewers will see your Reel.',
    'hi-IN': 'जैसे आपके दर्शक आपकी Reel देखेंगे, वैसे ही दिखाया गया है।',
  },
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

// ---------------------------------------------------------------------------
// Shot card (spec v2 Phase 6): the 13 fields of a script beat's card, in plain words
// ---------------------------------------------------------------------------

/**
 * The 13 shot-card keys, in the wire format's fixed order (`S<n>: size=…; height=…; …; move=…`,
 * the "Shot cards:" block `parseMeeraScript` reads). The card shows its rows in this order.
 */
export const SHOT_CARD_FIELDS = [
  'size',
  'height',
  'distance',
  'place',
  'light',
  'stand',
  'headroom',
  'eyes',
  'background',
  'space',
  'text',
  'prop',
  'move',
] as const;

export type ShotCardField = (typeof SHOT_CARD_FIELDS)[number];

type CopyPair = Record<ShootCheckLang, string>;

/**
 * Shot card copy. en-IN follows the spec's mockup 12 (`12-shot-card.svg`); left and right are
 * always the creator's own ("your left"), never the screen's (spec 2.4).
 *
 * hi-IN: Devanagari Hindi written at build time, PENDING REVIEW (same review as `GUIDE_COPY`). Do
 * not treat any hi-IN line here as approved until that review lands.
 *
 * House rules (scanned by `advice-copy.shot-card.test.ts`): no `@` handle, no reach or engagement
 * promise, never the word "escrow", and the assistant is only ever "Meera".
 */
export const SHOT_CARD_COPY = {
  title: { 'en-IN': 'Shot card', 'hi-IN': 'शॉट कार्ड' },
  not_set: { 'en-IN': 'Not set yet', 'hi-IN': 'अभी तय नहीं' },
  /** `{n}` is the number of fields still "Not set yet". */
  missing_count: { 'en-IN': '{n} not set yet', 'hi-IN': '{n} अभी तय नहीं' },
  from_answers_note: {
    'en-IN': 'Meera fills these from your answers, never by guessing.',
    'hi-IN': 'Meera इन्हें आपके जवाबों से भरती है, अंदाज़े से नहीं।',
  },
  ask_meera: { 'en-IN': 'Ask Meera', 'hi-IN': 'Meera से पूछें' },
  /** Prefilled into the message box by the card's Ask button (never sent on its own). `{n}` is the
   *  beat number, `{time}` its timing ("3-8s"), `{fields}` the missing field names. */
  ask_prefill: {
    'en-IN': 'Shot card for beat {n} ({time}): ask me what you need to fill in {fields}.',
    'hi-IN': 'बीट {n} ({time}) का शॉट कार्ड: {fields} भरने के लिए मुझसे जो जानना है, पूछें।',
  },
} as const satisfies Record<string, CopyPair>;

/** Row labels, one per field. */
export const SHOT_CARD_LABELS: Record<ShotCardField, CopyPair> = {
  size: { 'en-IN': 'Size', 'hi-IN': 'शॉट साइज़' },
  height: { 'en-IN': 'Camera height', 'hi-IN': 'कैमरे की ऊँचाई' },
  distance: { 'en-IN': 'Distance', 'hi-IN': 'दूरी' },
  place: { 'en-IN': 'Where', 'hi-IN': 'जगह' },
  light: { 'en-IN': 'Light', 'hi-IN': 'रोशनी' },
  stand: { 'en-IN': 'You in frame', 'hi-IN': 'फ़्रेम में आप' },
  headroom: { 'en-IN': 'Space above your head', 'hi-IN': 'सिर के ऊपर जगह' },
  eyes: { 'en-IN': 'Eyes', 'hi-IN': 'नज़र' },
  background: { 'en-IN': 'Background', 'hi-IN': 'बैकग्राउंड' },
  space: { 'en-IN': 'Empty side', 'hi-IN': 'खाली जगह' },
  text: { 'en-IN': 'Text on screen', 'hi-IN': 'स्क्रीन पर टेक्स्ट' },
  prop: { 'en-IN': 'Prop', 'hi-IN': 'प्रॉप' },
  move: { 'en-IN': 'Movement', 'hi-IN': 'मूवमेंट' },
};

/** Plain words for every enum value of the wire contract. `light` and `prop` are composed below. */
const SHOT_CARD_ENUM_WORDS = {
  size: {
    ECU: { 'en-IN': 'Extreme close-up (eyes or a detail)', 'hi-IN': 'एक्सट्रीम क्लोज़-अप (आँखें या कोई डिटेल)' },
    CU: { 'en-IN': 'Close-up (face)', 'hi-IN': 'क्लोज़-अप (चेहरा)' },
    MCU: { 'en-IN': 'Medium close-up (chest up)', 'hi-IN': 'मीडियम क्लोज़-अप (छाती से ऊपर)' },
    MS: { 'en-IN': 'Medium shot (waist up)', 'hi-IN': 'मीडियम शॉट (कमर से ऊपर)' },
    MLS: { 'en-IN': 'Medium long shot (knees up)', 'hi-IN': 'मीडियम लॉन्ग शॉट (घुटनों से ऊपर)' },
    FS: { 'en-IN': 'Full shot (head to toe)', 'hi-IN': 'फ़ुल शॉट (सिर से पैर तक)' },
    LS: { 'en-IN': 'Wide shot (you and the place)', 'hi-IN': 'वाइड शॉट (आप और पूरी जगह)' },
    OVERHEAD: { 'en-IN': 'Overhead (looking down at your hands)', 'hi-IN': 'ऊपर से शॉट (नीचे हाथों की ओर)' },
  },
  height: {
    eye: { 'en-IN': 'eye level', 'hi-IN': 'आँखों के लेवल पर' },
    chest: { 'en-IN': 'chest height', 'hi-IN': 'छाती की ऊँचाई पर' },
    above: { 'en-IN': 'above you, tilted down a little', 'hi-IN': 'आपसे ऊपर, थोड़ा नीचे की ओर' },
    below: { 'en-IN': 'below you, tilted up a little', 'hi-IN': 'आपसे नीचे, थोड़ा ऊपर की ओर' },
    overhead: { 'en-IN': 'straight above, looking down', 'hi-IN': 'ठीक ऊपर, सीधे नीचे की ओर' },
  },
  stand: {
    left: { 'en-IN': 'to your left', 'hi-IN': 'आपकी बाईं ओर' },
    centre: { 'en-IN': 'centre', 'hi-IN': 'बीच में' },
    right: { 'en-IN': 'to your right', 'hi-IN': 'आपकी दाईं ओर' },
  },
  headroom: {
    cropped: { 'en-IN': 'none, top of your head just out of frame', 'hi-IN': 'नहीं, सिर का ऊपरी हिस्सा फ़्रेम से बाहर' },
    small: { 'en-IN': 'a little', 'hi-IN': 'थोड़ी' },
    medium: { 'en-IN': 'some', 'hi-IN': 'मध्यम' },
  },
  eyes: {
    lens: { 'en-IN': 'to the lens', 'hi-IN': 'लेंस की ओर' },
    product: { 'en-IN': 'on the product', 'hi-IN': 'प्रोडक्ट पर' },
    off_lens: { 'en-IN': 'away from the lens', 'hi-IN': 'लेंस से हटकर' },
  },
  space: {
    left: { 'en-IN': 'your left', 'hi-IN': 'आपकी बाईं ओर' },
    right: { 'en-IN': 'your right', 'hi-IN': 'आपकी दाईं ओर' },
    top: { 'en-IN': 'above you', 'hi-IN': 'आपके ऊपर' },
    none: { 'en-IN': 'none', 'hi-IN': 'कोई नहीं' },
  },
  text: {
    top: { 'en-IN': 'at the top', 'hi-IN': 'ऊपर' },
    opposite_face: { 'en-IN': 'on the side away from your face', 'hi-IN': 'चेहरे की दूसरी ओर' },
    lower_middle: { 'en-IN': 'lower middle', 'hi-IN': 'नीचे बीच में' },
    none: { 'en-IN': 'no text', 'hi-IN': 'कोई टेक्स्ट नहीं' },
  },
  move: {
    still: { 'en-IN': 'stay still', 'hi-IN': 'स्थिर रहें' },
    sit: { 'en-IN': 'sitting', 'hi-IN': 'बैठकर' },
    stand: { 'en-IN': 'standing', 'hi-IN': 'खड़े होकर' },
    walk: { 'en-IN': 'walking', 'hi-IN': 'चलते हुए' },
    pan: { 'en-IN': 'phone turns slowly across the scene', 'hi-IN': 'फ़ोन धीरे से एक ओर घूमे' },
    push: { 'en-IN': 'phone moves slowly closer', 'hi-IN': 'फ़ोन धीरे से पास आए' },
  },
} as const satisfies Record<string, Record<string, CopyPair>>;

const SHOT_CARD_LIGHT_KINDS = {
  window: { 'en-IN': 'window', 'hi-IN': 'खिड़की' },
  sun: { 'en-IN': 'sunlight', 'hi-IN': 'धूप' },
  shade: { 'en-IN': 'shade', 'hi-IN': 'छाँव' },
  lamp: { 'en-IN': 'lamp', 'hi-IN': 'लैंप' },
  ring_light: { 'en-IN': 'ring light', 'hi-IN': 'रिंग लाइट' },
  tube_light: { 'en-IN': 'tube light', 'hi-IN': 'ट्यूबलाइट' },
  mixed: { 'en-IN': 'mixed light', 'hi-IN': 'मिली-जुली रोशनी' },
} as const satisfies Record<string, CopyPair>;

/** The light's side, the creator's own as they face the phone. */
const SHOT_CARD_LIGHT_SIDES = {
  left: { 'en-IN': 'your left', 'hi-IN': 'आपकी बाईं ओर' },
  right: { 'en-IN': 'your right', 'hi-IN': 'आपकी दाईं ओर' },
  front: { 'en-IN': 'in front of you', 'hi-IN': 'आपके सामने' },
  behind: { 'en-IN': 'behind you', 'hi-IN': 'आपके पीछे' },
} as const satisfies Record<string, CopyPair>;

/** `prop=<side>-<surface>`, written out whole (no word-by-word joining, so the Hindi reads right). */
const SHOT_CARD_PROP_WORDS = {
  none: { 'en-IN': 'no prop', 'hi-IN': 'कोई प्रॉप नहीं' },
  'left-hand': { 'en-IN': 'in your left hand', 'hi-IN': 'आपके बाएँ हाथ में' },
  'centre-hand': { 'en-IN': 'in both hands, in the middle', 'hi-IN': 'दोनों हाथों में, बीच में' },
  'right-hand': { 'en-IN': 'in your right hand', 'hi-IN': 'आपके दाएँ हाथ में' },
  'left-table': { 'en-IN': 'on the table, to your left', 'hi-IN': 'टेबल पर, आपकी बाईं ओर' },
  'centre-table': { 'en-IN': 'on the table, in the middle', 'hi-IN': 'टेबल पर, बीच में' },
  'right-table': { 'en-IN': 'on the table, to your right', 'hi-IN': 'टेबल पर, आपकी दाईं ओर' },
  'left-floor': { 'en-IN': 'on the floor, to your left', 'hi-IN': 'ज़मीन पर, आपकी बाईं ओर' },
  'centre-floor': { 'en-IN': 'on the floor, in the middle', 'hi-IN': 'ज़मीन पर, बीच में' },
  'right-floor': { 'en-IN': 'on the floor, to your right', 'hi-IN': 'ज़मीन पर, आपकी दाईं ओर' },
} as const satisfies Record<string, CopyPair>;

/** Free-text fields and their wire-contract length limits. A longer value counts as not set. */
const SHOT_CARD_FREE_TEXT_MAX: Partial<Record<ShotCardField, number>> = {
  distance: 20,
  place: 40,
  background: 40,
};

function lookup(table: Record<string, CopyPair>, value: string, lang: ShootCheckLang): string | undefined {
  if (!Object.prototype.hasOwnProperty.call(table, value)) return undefined;
  return table[value][lang];
}

/**
 * One shot-card field's value in plain words, in the creator's language, or `undefined` when the
 * field is not set: missing, blank, `?`, an enum value outside the wire contract, or free text
 * longer than its limit. The caller shows `SHOT_CARD_COPY.not_set` for `undefined` and never
 * guesses a value.
 *
 * Free text (`distance`, `place`, `background`) is Meera's own words in the creator's language and
 * is shown as written. Pure; shared by the script card and the camera sheet's chips.
 */
export function shotCardFieldText(
  field: ShotCardField,
  value: string | null | undefined,
  lang: ShootCheckLang,
): string | undefined {
  if (typeof value !== 'string') return undefined;
  const clean = value.trim().replace(/\s+/g, ' ');
  if (!clean || clean === '?') return undefined;

  const freeTextMax = SHOT_CARD_FREE_TEXT_MAX[field];
  // Counted in code points, like the parsers (`SHOT_CARD_TEXT_MAX`, Python's `len`).
  if (freeTextMax !== undefined) return [...clean].length <= freeTextMax ? clean : undefined;

  if (field === 'light') {
    const dash = clean.indexOf('-');
    const kind = dash === -1 ? clean : clean.slice(0, dash);
    const kindText = lookup(SHOT_CARD_LIGHT_KINDS, kind, lang);
    if (!kindText) return undefined;
    if (dash === -1) return kindText;
    const sideText = lookup(SHOT_CARD_LIGHT_SIDES, clean.slice(dash + 1), lang);
    return sideText ? `${kindText}, ${sideText}` : undefined;
  }
  if (field === 'prop') return lookup(SHOT_CARD_PROP_WORDS, clean, lang);

  const table = (SHOT_CARD_ENUM_WORDS as Record<string, Record<string, CopyPair>>)[field];
  return table ? lookup(table, clean, lang) : undefined;
}

/** Fills `{name}` placeholders in a copy line. */
export function fillShotCardCopy(template: string, values: Record<string, string | number>): string {
  return template.replace(/\{(\w+)\}/g, (whole, name: string) =>
    Object.prototype.hasOwnProperty.call(values, name) ? String(values[name]) : whole,
  );
}

/** Every shot-card line in both languages, for the house-rule copy scan. */
export function allShotCardCopyLines(): Array<[string, string]> {
  const tables: Array<[string, Record<string, CopyPair>]> = [
    ['copy', SHOT_CARD_COPY],
    ['label', SHOT_CARD_LABELS],
    ['light_kind', SHOT_CARD_LIGHT_KINDS],
    ['light_side', SHOT_CARD_LIGHT_SIDES],
    ['prop', SHOT_CARD_PROP_WORDS],
    ...Object.entries(SHOT_CARD_ENUM_WORDS).map(
      ([field, table]) => [`enum.${field}`, table as Record<string, CopyPair>] as [string, Record<string, CopyPair>],
    ),
  ];
  return tables.flatMap(([prefix, table]) =>
    Object.entries(table).flatMap(([key, pair]) =>
      (Object.entries(pair) as Array<[string, string]>).map(
        ([lang, text]) => [`${prefix}.${key}.${lang}`, text] as [string, string],
      ),
    ),
  );
}
