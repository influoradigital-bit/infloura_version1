/**
 * The Meera demo script — every word Meera "says" in the marketing demo is
 * predefined here. Nothing calls the real AI.
 *
 * Roman Hinglish throughout. Each scene is one chapter of the creator journey
 * as it will look once phases A–E ship. The UI elements, flag codes, money
 * states and numbers follow Priya's storyboard truth check (2026-09-05):
 * quote is benchmark-mode with a provenance chip (Phase B §4.3, §14.1); no TDS
 * or GST figure is ever shown (Phase C §1); the creator types their own hook
 * before any pitch (Phase E §15.2a); nothing sends without a visible tap.
 *
 * Copy rules: no "escrow" (Secure Payments / funds secured), no real brand
 * names, no "guaranteed", future tense on the page ("aa raha hai").
 */

export type FlagTone = 'red' | 'amber' | 'yellow' | 'green' | 'info';

export type Card =
  | { type: 'prefs'; title: string; sub: string; rows: { label: string; value: string }[] }
  | { type: 'flags'; title: string; flags: { tone: FlagTone; code: string; text: string }[] }
  | {
      type: 'quote';
      title: string;
      lines: { label: string; value: string }[];
      total: string;
      totalLabel?: string;
      floor: string;
      chip: string;
      note: string;
    }
  | { type: 'draft'; to: string; subject?: string; body: string; footer: string; actions: string[] }
  | {
      type: 'timeline';
      title: string;
      steps: { label: string; state: 'done' | 'now' | 'next'; sub?: string }[];
      footer?: string;
    }
  | { type: 'snapshot'; title: string; stats: { label: string; value: string }[]; note: string }
  | { type: 'health'; tone: FlagTone; code: string; title: string; body: string; fix: string }
  | { type: 'note'; title: string; when: string; items: string[] }
  | {
      type: 'brands';
      title: string;
      brands: { name: string; warmth: 'W0' | 'W1' | 'W2' | 'W3'; tag: string; why: string }[];
      footer: string;
    }
  | { type: 'hook'; title: string; sub: string; placeholder: string; value: string };

/**
 * `say` is what the voice actually reads (Devanagari with product words in
 * Latin), because Sarvam's Hindi voice is far more natural on native script
 * than on Roman Hinglish. `text` stays Roman for the screen.
 */
export type Beat =
  | { kind: 'meera'; text: string; say?: string; hold?: number }
  | { kind: 'creator'; text: string; hold?: number }
  | { kind: 'typing'; hold?: number }
  | { kind: 'card'; card: Card; hold?: number }
  | { kind: 'tap'; label: string; hold?: number }
  | { kind: 'system'; text: string; hold?: number }
  | { kind: 'wa'; text: string; say?: string; hold?: number };

export interface Scene {
  id: string;
  /** Chapter chip shown above the phone. */
  chapter: string;
  /** Header title inside the phone. */
  header: string;
  headerSub: string;
  /** Visual style of the screen: chat = Meera thread, wa = WhatsApp-like. */
  surface: 'chat' | 'wa';
  beats: Beat[];
  /** Extra frames to hold the finished scene before the transition. */
  tail?: number;
}

export type LangCode = 'hi' | 'en' | 'mr';

export interface Locale {
  lang: LangCode;
  /** Label on the page's language switch. */
  label: string;
  /** Sarvam `target_language_code`. */
  tts: string;
  scenes: Scene[];
  intro: { eyebrow: string; title: string; sub: string; line: string; say?: string };
  outro: { title: string; sub: string; bullets: string[]; cta: string; say?: string };
}

export const CREATOR_NAME = 'Riya';

export const SCENES: Scene[] = [
  {
    id: 'setup',
    chapter: 'Din 1 · Pehle aapke rules',
    header: 'Meera',
    headerSub: 'Aapki personal PR manager',
    surface: 'chat',
    beats: [
      {
        kind: 'meera',
        text: `Namaste ${CREATOR_NAME}! Main Meera hoon. Aapki side pe kaam karti hoon. Hindi, Hinglish ya English?`,
        say: 'नमस्ते रिया! मैं मीरा हूँ। आपकी side पे काम करती हूँ। हिंदी, हिंग्लिश, या English?',
      },
      { kind: 'creator', text: 'Hinglish chalega 🙂' },
      {
        kind: 'meera',
        text: 'Done. Pehle aapke rules. Ek Reel ke liye minimum kitna? Floor aapka hai, brand ko kabhi nahi dikhega.',
        say: 'Done. पहले आपके rules। एक Reel के लिए minimum कितना? Floor आपका है, brand को कभी नहीं दिखेगा।',
      },
      { kind: 'creator', text: 'Reel ₹3,000, story set ₹1,500' },
      {
        kind: 'card',
        card: {
          type: 'prefs',
          title: 'Aapke rules',
          sub: 'Sirf aap dekh sakte ho',
          rows: [
            { label: 'Reel floor', value: '₹3,000' },
            { label: 'Story set floor', value: '₹1,500' },
            { label: 'Bhasha', value: 'Hinglish' },
            { label: 'Level 0', value: 'Main likhungi, bhejenge aap' },
          ],
        },
        hold: 80,
      },
      {
        kind: 'meera',
        text: 'Save ho gaya. Ab jab bhi koi brand message kare, mujhe paste kar dena.',
        say: 'Save हो गया। अब जब भी कोई brand message करे, मुझे paste कर देना।',
      },
    ],
    tail: 30,
  },
  {
    id: 'paste',
    chapter: 'Brief paste karo · Meera padhti hai',
    header: 'Meera',
    headerSub: 'Brief padh rahi hoon…',
    surface: 'chat',
    beats: [
      {
        kind: 'creator',
        text: 'Hi Riya! 1 reel + 1 story set for our Vitamin C serum. Budget ₹5,000. Live by 5 Oct. Category exclusivity 60 days. We can use the content on our channels and ads. Please skip #ad.',
        hold: 80,
      },
      { kind: 'typing', hold: 36 },
      { kind: 'meera', text: '8 line ka brief, 3 dikkat. Forever usage, 60 din exclusivity, aur #ad hatane ko bola hai.',
        say: 'आठ line का brief, तीन दिक्कत। Forever usage, साठ दिन exclusivity, और hashtag ad हटाने को बोला है।' },
      {
        kind: 'card',
        card: {
          type: 'flags',
          title: 'Deal risks',
          flags: [
            { tone: 'red', code: 'USAGE_PERPETUAL', text: 'Brand yeh content hamesha use kar sakta hai' },
            { tone: 'amber', code: 'EXCLUSIVITY_LONG', text: '60 din tak koi aur skincare brand nahi' },
            { tone: 'amber', code: 'HIDE_DISCLOSURE', text: '#ad hatana ASCI ke against hai. Woh main nahi likhungi.' },
          ],
        },
        hold: 105,
      },
      {
        kind: 'card',
        card: {
          type: 'quote',
          title: 'Kitna maangna chahiye',
          lines: [
            { label: '1 Reel', value: '₹4,300' },
            { label: '1 Story set', value: '₹2,150' },
          ],
          total: '₹6,450',
          floor: 'Aapka floor total: ₹4,500',
          chip: 'Benchmark, market data nahi',
          note: 'Budget ₹5,000 hai: story set hata ke 1 reel ₹4,300 offer karo.',
        },
        hold: 120,
      },
      { kind: 'meera', text: 'Reply draft kar rahi hoon. Aap dekh ke hi bhejna.',
        say: 'Reply draft कर रही हूँ। आप देख के ही भेजना।' },
    ],
    tail: 24,
  },
  {
    id: 'send',
    chapter: 'Aap approve karo · tab hi jaata hai',
    header: 'Meera',
    headerSub: 'Draft taiyaar',
    surface: 'chat',
    beats: [
      {
        kind: 'card',
        card: {
          type: 'draft',
          to: 'Brand ko reply',
          body: 'Hi! Thanks for reaching out 🙌 For 1 reel with 60-day exclusivity and ad usage, my rate is ₹4,300. The #ad label stays: it is required. I work through Influora Secure Payments: funds are secured before I start, contract and invoice come automatically. Link below to confirm.',
          footer: 'Drafted with Meera · approved by Riya',
          actions: ['Approve', 'Edit', 'Discard'],
        },
        hold: 130,
      },
      { kind: 'tap', label: 'Approve', hold: 50 },
      { kind: 'meera', text: 'Bhej diya ✅ Brand link kholega, funds secure karega, tab deal pakki.',
        say: 'भेज दिया! Brand link खोलेगा, funds secure करेगा, तब deal पक्की।' },
      { kind: 'system', text: '2 din baad', hold: 40 },
      {
        kind: 'card',
        card: {
          type: 'timeline',
          title: 'Vitamin C serum · 1 reel · ₹4,300',
          steps: [
            { label: 'Funds secured', state: 'done', sub: '₹4,300 · brand ne secure kiya' },
            { label: 'Contract', state: 'done', sub: 'Dono ne sign kiya' },
            { label: 'Delivery', state: 'now', sub: 'Live by 5 Oct' },
          ],
        },
        hold: 90,
      },
    ],
    tail: 24,
  },
  {
    id: 'money',
    chapter: 'Paisa kahan atka hai · Meera nazar rakhti hai',
    header: 'Meera',
    headerSub: 'WhatsApp par · sirf zaroori updates',
    surface: 'wa',
    beats: [
      { kind: 'wa', text: 'Reel live hai. 24 ghante ka snapshot save kar liya, proof ke liye. 72 ghante ka reach brand ko bhej dungi.',
        say: 'Reel live है। चौबीस घंटे का snapshot save कर लिया, proof के लिए। बहत्तर घंटे का reach brand को भेज दूँगी।' },
      {
        kind: 'card',
        card: {
          type: 'snapshot',
          title: 'Reel · 24 ghante',
          stats: [
            { label: 'Views', value: '12.4K' },
            { label: 'Likes', value: '830' },
            { label: 'Saves', value: '41' },
          ],
          note: 'Source: Instagram Graph API · brand ko bhi dikhta hai',
        },
        hold: 90,
      },
      {
        kind: 'card',
        card: {
          type: 'timeline',
          title: 'Payout · ₹4,300',
          steps: [
            { label: 'Secured', state: 'done' },
            { label: 'Released', state: 'done', sub: 'Brand ne approve kiya' },
            { label: 'Payout in flight', state: 'now', sub: 'Bank tak 1–2 din' },
            { label: 'Paid', state: 'next' },
          ],
          footer: 'TDS: is payout par record nahi hua · tax aapke CA ka kaam',
        },
        hold: 110,
      },
      { kind: 'wa', text: 'Paisa bank pahunch gaya: ₹4,300 ✅ Invoice Influora par ready hai.',
        say: 'पैसा bank पहुँच गया, चार हज़ार तीन सौ रुपये। Invoice Influora पर ready है।' },
    ],
    tail: 30,
  },
  {
    id: 'health',
    chapter: 'Galti se pehle · aur har Somvaar ek note',
    header: 'Meera',
    headerSub: 'Account health',
    surface: 'chat',
    beats: [
      {
        kind: 'card',
        card: {
          type: 'health',
          tone: 'amber',
          code: 'LABELLED_LATE',
          title: 'Label pehli line mein nahi',
          body: 'Reel ke caption mein “#ad” mila, par 125 characters ke baad. ASCI ke hisaab se shuru mein hona chahiye.',
          fix: 'Fix: label ko caption ki pehli line mein le aao',
        },
        hold: 110,
      },
      { kind: 'creator', text: 'Oh, abhi theek karti hoon' },
      { kind: 'system', text: 'Somvaar, 9:30 AM', hold: 40 },
      {
        kind: 'card',
        card: {
          type: 'note',
          title: 'Somvaar ka note',
          when: 'Har hafte, 9:30 AM',
          items: [
            '₹4,300 Thursday ko aaya ✅',
            'Dhyan do: 1 payout 5 din se pending',
            'Ek post: Vitamin C wali reel ka behind-the-scenes',
            'Ek fix: label pehli line mein',
          ],
        },
        hold: 120,
      },
    ],
    tail: 24,
  },
  {
    id: 'find',
    chapter: 'Naye brand dhoondhna · list Meera ki, shabd aapke',
    header: 'Meera',
    headerSub: 'Brands dhundh rahi hoon…',
    surface: 'chat',
    beats: [
      { kind: 'creator', text: 'Meera, mere niche ke naye brands dhundho' },
      { kind: 'typing', hold: 40 },
      {
        kind: 'card',
        card: {
          type: 'brands',
          title: '5 brand mile · skincare · Pune, Mumbai',
          brands: [
            { name: 'Nimbu Naturals', warmth: 'W0', tag: 'Influora par', why: 'Influora par hai: direct pitch' },
            { name: 'Dew & Co', warmth: 'W1', tag: 'Lookalike', why: 'Aap jaise creators ke saath kaam kiya' },
            { name: 'Monsoon Skin', warmth: 'W3', tag: 'Cold', why: 'Cold email · max 2 per run' },
          ],
          footer: 'Ek brand ko 30 din mein sirf ek baar · email only, DM kabhi nahi',
        },
        hold: 110,
      },
      { kind: 'meera', text: 'Do line apne shabdon mein likho: yeh brand kyun, aap kyun. Uske bina main bhej nahi sakti.',
        say: 'दो line अपने शब्दों में लिखो: ये brand क्यों, आप क्यों। उसके बिना मैं भेज नहीं सकती।' },
      {
        kind: 'card',
        card: {
          type: 'hook',
          title: 'Aapki do lines',
          sub: 'Ye line brand ko aapki lagegi, Meera ki nahi',
          placeholder: 'Yeh brand kyun, aap kyun…',
          value: 'Aapki rain-proof serum wali Reel Pune ki baarish mein bilkul sahi baithi. Main roz 18K logon ko skincare samjhaati hoon.',
        },
        hold: 100,
      },
      {
        kind: 'card',
        card: {
          type: 'draft',
          to: 'Nimbu Naturals · email',
          subject: 'Aapke monsoon launch ke liye ek idea',
          body: 'Hi! Aapki rain-proof serum wali Reel Pune ki baarish mein bilkul sahi baithi… Main Riya hoon, skincare creator, 7.4K followers. Ek chhota sa idea share karna chahti hoon.',
          footer: 'Reply rate abhi: 4 mein se 1 · cold email mein yeh normal hai',
          actions: ['Approve', 'Edit'],
        },
        hold: 110,
      },
      { kind: 'tap', label: 'Approve', hold: 44 },
      { kind: 'meera', text: 'Bhej diya. Reply aaya to yahin bataungi. Na aaye to bhi normal hai.',
        say: 'भेज दिया। Reply आया तो यहीं बताऊँगी। ना आए तो भी normal है।' },
    ],
    tail: 30,
  },
];

export const INTRO = {
  eyebrow: 'Jald aa rahi hai',
  title: 'Meera',
  sub: 'Aapka apna PR manager. Aapki taraf se.',
  line: 'Aap decide karte ho. Main likhti aur yaad rakhti hoon.',
  say: 'आपका अपना PR manager। आपकी तरफ़ से। आप decide करते हो। मैं लिखती हूँ, और याद रखती हूँ।',
};

export const OUTRO = {
  title: 'Meera for Creators',
  sub: 'Abhi ban rahi hai. Jald aa rahi hai.',
  bullets: [
    'Brief paste, seedha jawab',
    'Aap approve karo, tab hi jaata hai',
    'Paisa, proof, aur har Somvaar ek note',
  ],
  cta: 'Waitlist me naam likhwao',
  say: 'Meera for Creators। अभी बन रही है, जल्द आ रही है। Brief paste, सीधा जवाब। आप approve करो, तब ही जाता है। पैसा, proof, और हर सोमवार एक note।',
};

/** Hinglish: Roman on screen, Devanagari for the voice. */
export const HI_LOCALE: Locale = {
  lang: 'hi',
  label: 'Hinglish',
  tts: 'hi-IN',
  scenes: SCENES,
  intro: INTRO,
  outro: OUTRO,
};
