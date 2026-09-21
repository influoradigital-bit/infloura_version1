/**
 * T-CREATOR-CREDITS-V2 (SPEC.md §9.1, F1) — creator-credits copy, bilingual (en / hi-IN).
 *
 * Every string here is Tejas's corrected wording (SPEC.md §1, C-rows on Tejas's original draft):
 * no "escrow" (brand rule, see `project_escrow_word_banned_in_user_copy`), no "upgrade" (this is
 * a top-up, not a tier change), and no manufactured urgency. The server's own refusal templates
 * (`CreatorCreditService.refusal`, Java) use the SAME wording as `exhausted`/`cap` below — if this
 * file's copy for those two keys ever changes, the Java template must change with it or the
 * client-rendered fallback and the server-sent message will visibly disagree.
 *
 * `language` throughout this module is the BCP-47-ish `creator_language` value the rest of the
 * creator surface already keys Hindi off of (`MeeraCopilotChat`'s `language` prop,
 * `PasteBriefCard`'s `askMeeraCopy`) — `"hi-IN"` (or anything starting `"hi"`) selects Hindi,
 * everything else (including `undefined`) selects English.
 */

export type CreatorCreditsLanguage = 'en' | 'hi';

/** Every possible `{placeholder}` any copy key below uses. Callers pass only what a given key needs. */
export interface CreatorCreditsCopyVars {
  n?: number;
  total?: number;
  free?: number;
  paid?: number;
  count?: number;
  date?: string;
}

interface CopyEntry {
  en: string;
  hi: string;
}

/**
 * SPEC.md §9.1 table, verbatim. Keys match the spec's key column exactly so a reviewer can diff
 * this object against the spec table directly.
 */
export const CREATOR_CREDITS_COPY = {
  'pill.normal': { en: '{n} credits', hi: '{n} क्रेडिट्स' },
  'pill.low': { en: '{n} left', hi: '{n} बचे हैं' },
  'pill.zero': { en: '0 credits', hi: '0 क्रेडिट्स' },
  'pill.cap': { en: '30/30 today', hi: 'आज 30/30' },
  exhausted: {
    en: "You're out of credits, so I can't answer this one yet. Buy 60 credits to keep chatting.",
    hi: 'आपके क्रेडिट्स खत्म हो गए हैं, इसलिए मैं अभी इसका जवाब नहीं दे सकती। चैट जारी रखने के लिए 60 क्रेडिट्स लें।',
  },
  cap: {
    en: "You've used today's 30 credits. Your limit resets at midnight (IST). Credits you buy now stay in your balance for tomorrow.",
    hi: 'आपने आज के 30 क्रेडिट्स इस्तेमाल कर लिए हैं। आपकी लिमिट आधी रात (IST) को रीसेट होगी। अभी लिए गए क्रेडिट्स कल के लिए आपके बैलेंस में रहेंगे।',
  },
  'cap.voice': {
    en: 'A voice reply needs 2 credits and you have 1 left today. Turn off voice replies to send this as text.',
    hi: 'वॉइस रिप्लाई में 2 क्रेडिट्स लगते हैं और आज आपका 1 बचा है। इसे टेक्स्ट में भेजने के लिए वॉइस रिप्लाई बंद करें।',
  },
  'buy.cta': { en: 'Buy 60 credits — ₹249', hi: '60 क्रेडिट्स खरीदें — ₹249' },
  'sheet.title': { en: 'Top up your credits', hi: 'टॉप-अप करें' },
  'sheet.pack': {
    en: '60 credits — ₹249 (includes 18% GST)',
    hi: '60 क्रेडिट्स — ₹249 (18% GST शामिल)',
  },
  'sheet.bullets': {
    en: '60 text messages with Meera · Voice replies cost 2 credits each · Brief analysis costs 3 credits · Valid for 90 days · Up to 30 credits a day',
    hi: 'Meera के साथ 60 टेक्स्ट मैसेज · वॉइस रिप्लाई में 2 क्रेडिट्स लगते हैं · ब्रीफ़ एनालिसिस में 3 क्रेडिट्स · 90 दिनों तक वैध · दिन में ज़्यादा से ज़्यादा 30 क्रेडिट्स',
  },
  'sheet.balance': {
    en: 'Current balance: {total} credits ({free} free, {paid} paid)',
    hi: 'वर्तमान बैलेंस: {total} क्रेडिट्स ({free} फ्री, {paid} पेड)',
  },
  'sheet.freeFirst': { en: 'Free credits are used first.', hi: 'फ्री क्रेडिट्स पहले इस्तेमाल होते हैं।' },
  'sheet.provider': { en: 'Payment by Razorpay (secure)', hi: 'भुगतान Razorpay द्वारा (सुरक्षित)' },
  'pay.success': {
    en: '60 credits added! Your balance: {total} credits. Valid for 90 days.',
    hi: '60 क्रेडिट्स जोड़े गए! आपका बैलेंस: {total} क्रेडिट्स। 90 दिनों तक वैध।',
  },
  'pay.pending': {
    en: 'Payment received. Your credits will appear in a minute.',
    hi: 'भुगतान मिल गया। आपके क्रेडिट्स एक मिनट में दिखेंगे।',
  },
  'pay.failed': {
    en: "Payment didn't go through. Try again, or contact support if this keeps happening.",
    hi: 'भुगतान नहीं हो पाया। दोबारा कोशिश करें, या अगर यह बार-बार हो तो सपोर्ट से संपर्क करें।',
  },
  welcome: {
    en: "Welcome to Meera! You've got 40 free credits for connecting Instagram. · 1 text message = 1 credit · Voice reply = 2 credits · Brief analysis = 3 credits · From next month, you get 15 free credits on the 1st of every month. [Start chatting]",
    hi: 'Meera में आपका स्वागत है! Instagram कनेक्ट करने के लिए आपको 40 फ्री क्रेडिट्स मिले हैं। · 1 टेक्स्ट मैसेज = 1 क्रेडिट · वॉइस रिप्लाई = 2 क्रेडिट्स · ब्रीफ़ एनालिसिस = 3 क्रेडिट्स · अगले महीने से, हर महीने की 1 तारीख को आपको 15 फ्री क्रेडिट्स मिलेंगे। [चैट शुरू करें]',
  },
  monthly: {
    en: "Your monthly credits are here! 15 free credits added. Unused monthly credits don't carry over.",
    hi: 'आपके मासिक क्रेडिट्स आ गए हैं! 15 फ्री क्रेडिट्स जोड़े गए। बचे हुए मासिक क्रेडिट्स अगले महीने नहीं जुड़ते।',
  },
  expiring: {
    en: 'Reminder: {count} paid credits expire on {date}.',
    hi: 'रिमाइंडर: {count} पेड क्रेडिट्स {date} को एक्सपायर होंगे।',
  },
  'cost.voice': { en: 'Voice replies cost 2 credits', hi: 'वॉइस रिप्लाई में 2 क्रेडिट्स लगते हैं' },
  'cost.brief': {
    en: 'Analysing this brief costs 3 credits',
    hi: 'इस ब्रीफ़ के एनालिसिस में 3 क्रेडिट्स लगेंगे',
  },
  zeroBanner: {
    en: 'Out of credits? Top up to keep chatting. [Buy — ₹249]',
    hi: 'क्रेडिट्स खत्म हो गए? चैट जारी रखने के लिए टॉप-अप करें। [खरीदें — ₹249]',
  },
} as const satisfies Record<string, CopyEntry>;

export type CreatorCreditsCopyKey = keyof typeof CREATOR_CREDITS_COPY;

/** `creator_language` (e.g. `"hi-IN"`, `"en-IN"`, `undefined`) → the two-way switch every key uses. */
export function resolveCreatorCreditsLanguage(language: string | null | undefined): CreatorCreditsLanguage {
  return (language ?? '').toLowerCase().startsWith('hi') ? 'hi' : 'en';
}

function interpolate(text: string, vars?: CreatorCreditsCopyVars): string {
  if (!vars) return text;
  let out = text;
  (Object.keys(vars) as Array<keyof CreatorCreditsCopyVars>).forEach((key) => {
    const value = vars[key];
    if (value === undefined) return;
    out = out.split(`{${key}}`).join(String(value));
  });
  return out;
}

/** The raw, interpolated copy string for `key` in the creator's language. */
export function creditsCopy(
  key: CreatorCreditsCopyKey,
  language: string | null | undefined,
  vars?: CreatorCreditsCopyVars,
): string {
  const lang = resolveCreatorCreditsLanguage(language);
  return interpolate(CREATOR_CREDITS_COPY[key][lang], vars);
}

/**
 * A handful of §9.1 keys (`welcome`, `zeroBanner`) end with a trailing `[Button label]` — the
 * spec's way of saying "this copy ends in a real CTA", not literal bracket text to render. This
 * splits that trailing bracket off so a component can render the sentence as a message and the
 * bracketed text as an actual `<Button>`, while this file still stores Tejas's copy exactly as
 * specified (word-for-word, brackets included) for the completeness test (A51) to check against.
 */
export function creditsCopyWithCta(
  key: CreatorCreditsCopyKey,
  language: string | null | undefined,
  vars?: CreatorCreditsCopyVars,
): { message: string; cta: string | null } {
  const raw = creditsCopy(key, language, vars);
  const match = raw.match(/^([\s\S]*?)\s*\[([^[\]]+)]\s*$/);
  if (!match) return { message: raw, cta: null };
  return { message: match[1].trim(), cta: match[2].trim() };
}
