import { CREATOR_NAME, type Locale, type Scene } from './script';

/**
 * English locale of the Meera demo. Same beats, cards and numbers as the
 * Hinglish script (Priya's truth check applies unchanged); every spoken line
 * is a complete sentence, and the voice reads the on-screen text verbatim.
 */

const SCENES: Scene[] = [
  {
    id: 'setup',
    chapter: 'Day 1 · Your rules come first',
    header: 'Meera',
    headerSub: 'Your personal PR manager',
    surface: 'chat',
    beats: [
      {
        kind: 'meera',
        text: `Hello ${CREATOR_NAME}! I am Meera, and I work on your side. Would you like to talk in Hindi, Hinglish, or English?`,
      },
      { kind: 'creator', text: 'English is fine 🙂' },
      {
        kind: 'meera',
        text: 'Done. Let us set your rules first. What is the minimum you would accept for one Reel? Your floor is yours, and a brand will never see it.',
      },
      { kind: 'creator', text: 'Reel ₹3,000, story set ₹1,500' },
      {
        kind: 'card',
        card: {
          type: 'prefs',
          title: 'Your rules',
          sub: 'Only you can see these',
          rows: [
            { label: 'Reel floor', value: '₹3,000' },
            { label: 'Story set floor', value: '₹1,500' },
            { label: 'Language', value: 'English' },
            { label: 'Level 0', value: 'I draft, you send' },
          ],
        },
        hold: 80,
      },
      {
        kind: 'meera',
        text: 'Saved. From now on, whenever a brand messages you, just paste it here and I will read it.',
      },
    ],
    tail: 30,
  },
  {
    id: 'paste',
    chapter: 'Paste the brief · Meera reads it',
    header: 'Meera',
    headerSub: 'Reading the brief…',
    surface: 'chat',
    beats: [
      {
        kind: 'creator',
        text: 'Hi Riya! 1 reel + 1 story set for our Vitamin C serum. Budget ₹5,000. Live by 5 Oct. Category exclusivity 60 days. We can use the content on our channels and ads. Please skip #ad.',
        hold: 80,
      },
      { kind: 'typing', hold: 36 },
      {
        kind: 'meera',
        text: 'It is an eight-line brief with three problems. They want usage forever, sixty days of exclusivity, and they have asked you to drop the ad label.',
      },
      {
        kind: 'card',
        card: {
          type: 'flags',
          title: 'Deal risks',
          flags: [
            { tone: 'red', code: 'USAGE_PERPETUAL', text: 'The brand can use this content forever' },
            { tone: 'amber', code: 'EXCLUSIVITY_LONG', text: 'No other skincare brand for 60 days' },
            { tone: 'amber', code: 'HIDE_DISCLOSURE', text: 'Dropping #ad breaks ASCI rules. I will not write that.' },
          ],
        },
        hold: 105,
      },
      {
        kind: 'card',
        card: {
          type: 'quote',
          title: 'What to ask for',
          lines: [
            { label: '1 Reel', value: '₹4,300' },
            { label: '1 Story set', value: '₹2,150' },
          ],
          total: '₹6,450',
          totalLabel: 'Total',
          floor: 'Your floor total: ₹4,500',
          chip: 'Benchmark, not market data',
          note: 'Their budget is ₹5,000: drop the story set and offer one reel at ₹4,300.',
        },
        hold: 120,
      },
      { kind: 'meera', text: 'I am drafting the reply now. Please read it before anything is sent.' },
    ],
    tail: 24,
  },
  {
    id: 'send',
    chapter: 'You approve · only then it goes',
    header: 'Meera',
    headerSub: 'Draft ready',
    surface: 'chat',
    beats: [
      {
        kind: 'card',
        card: {
          type: 'draft',
          to: 'Reply to the brand',
          body: 'Hi! Thanks for reaching out 🙌 For 1 reel with 60-day exclusivity and ad usage, my rate is ₹4,300. The #ad label stays: it is required. I work through Influora Secure Payments: funds are secured before I start, contract and invoice come automatically. Link below to confirm.',
          footer: 'Drafted with Meera · approved by Riya',
          actions: ['Approve', 'Edit', 'Discard'],
        },
        hold: 130,
      },
      { kind: 'tap', label: 'Approve', hold: 50 },
      {
        kind: 'meera',
        text: 'Sent. Once the brand opens the link and secures the funds, the deal is confirmed.',
      },
      { kind: 'system', text: '2 days later', hold: 40 },
      {
        kind: 'card',
        card: {
          type: 'timeline',
          title: 'Vitamin C serum · 1 reel · ₹4,300',
          steps: [
            { label: 'Funds secured', state: 'done', sub: '₹4,300 secured by the brand' },
            { label: 'Contract', state: 'done', sub: 'Signed by both sides' },
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
    chapter: 'Where the money is · Meera keeps watch',
    header: 'Meera',
    headerSub: 'On WhatsApp · only what matters',
    surface: 'wa',
    beats: [
      {
        kind: 'wa',
        text: 'Your reel is live. I have saved the 24-hour snapshot as proof, and I will send the 72-hour reach to the brand.',
      },
      {
        kind: 'card',
        card: {
          type: 'snapshot',
          title: 'Reel · 24 hours',
          stats: [
            { label: 'Views', value: '12.4K' },
            { label: 'Likes', value: '830' },
            { label: 'Saves', value: '41' },
          ],
          note: 'Source: Instagram Graph API · the brand sees this too',
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
            { label: 'Released', state: 'done', sub: 'Approved by the brand' },
            { label: 'Payout in flight', state: 'now', sub: '1–2 days to your bank' },
            { label: 'Paid', state: 'next' },
          ],
          footer: 'TDS: not recorded on this payout · tax stays with your CA',
        },
        hold: 110,
      },
      {
        kind: 'wa',
        text: 'The money has reached your bank: four thousand three hundred rupees. Your invoice is ready on Influora.',
      },
    ],
    tail: 30,
  },
  {
    id: 'health',
    chapter: 'Before a mistake · and a note every Monday',
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
          title: 'Label is not in the first line',
          body: 'I found “#ad” in the reel caption, but after 125 characters. ASCI wants it at the start.',
          fix: 'Fix: move the label to the first line of the caption',
        },
        hold: 110,
      },
      { kind: 'creator', text: 'Oh, fixing it now' },
      { kind: 'system', text: 'Monday, 9:30 AM', hold: 40 },
      {
        kind: 'card',
        card: {
          type: 'note',
          title: 'Your Monday note',
          when: 'Every week, 9:30 AM',
          items: [
            '₹4,300 arrived on Thursday ✅',
            'Watch: 1 payout pending for 5 days',
            'One post: behind the scenes of the Vitamin C reel',
            'One fix: label in the first line',
          ],
        },
        hold: 120,
      },
    ],
    tail: 24,
  },
  {
    id: 'find',
    chapter: 'Finding new brands · the list is mine, the words are yours',
    header: 'Meera',
    headerSub: 'Searching for brands…',
    surface: 'chat',
    beats: [
      { kind: 'creator', text: 'Meera, find new brands in my niche' },
      { kind: 'typing', hold: 40 },
      {
        kind: 'card',
        card: {
          type: 'brands',
          title: '5 brands found · skincare · Pune, Mumbai',
          brands: [
            { name: 'Nimbu Naturals', warmth: 'W0', tag: 'On Influora', why: 'Already on Influora: pitch directly' },
            { name: 'Dew & Co', warmth: 'W1', tag: 'Lookalike', why: 'Has worked with creators like you' },
            { name: 'Monsoon Skin', warmth: 'W3', tag: 'Cold', why: 'Cold email · at most 2 per run' },
          ],
          footer: 'One pitch per brand every 30 days · email only, never DMs',
        },
        hold: 110,
      },
      {
        kind: 'meera',
        text: 'Write two lines in your own words: why this brand, and why you. Without them, I cannot send anything.',
      },
      {
        kind: 'card',
        card: {
          type: 'hook',
          title: 'Your two lines',
          sub: 'The brand will read these as yours, not mine',
          placeholder: 'Why this brand, why you…',
          value: 'Your rain-proof serum Reel was spot on for the Pune monsoon. I explain skincare to 18K people every day.',
        },
        hold: 100,
      },
      {
        kind: 'card',
        card: {
          type: 'draft',
          to: 'Nimbu Naturals · email',
          subject: 'An idea for your monsoon launch',
          body: 'Hi! Your rain-proof serum Reel was spot on for the Pune monsoon… I am Riya, a skincare creator with 7.4K followers, and I would love to share a small idea.',
          footer: 'Current reply rate: 1 in 4 · that is normal for cold email',
          actions: ['Approve', 'Edit'],
        },
        hold: 110,
      },
      { kind: 'tap', label: 'Approve', hold: 44 },
      {
        kind: 'meera',
        text: 'Sent. If they reply, I will tell you right here. If they do not, that is normal too.',
      },
    ],
    tail: 30,
  },
];

export const EN_LOCALE: Locale = {
  lang: 'en',
  label: 'English',
  tts: 'en-IN',
  scenes: SCENES,
  intro: {
    eyebrow: 'Coming soon',
    title: 'Meera',
    sub: 'Your own PR manager. On your side.',
    line: 'You decide. I write, and I remember.',
    say: 'Your own PR manager, on your side. You decide. I write, and I remember.',
  },
  outro: {
    title: 'Meera for Creators',
    sub: 'In the works. Coming soon.',
    bullets: ['Paste a brief, get a straight answer', 'You approve, only then it goes', 'Money, proof, and a note every Monday'],
    cta: 'Join the waitlist',
    say: 'Meera for Creators. In the works, and coming soon. Paste a brief and get a straight answer. You approve, and only then it goes. Money, proof, and a note every Monday.',
  },
};
