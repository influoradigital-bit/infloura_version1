import { type Locale, type Scene } from './script';

/**
 * Marathi locale of the Meera demo. Devanagari on screen and for the voice
 * (`mr-IN`); product words stay in Latin. Same beats, cards and numbers as
 * the Hinglish script, so Priya's truth check applies unchanged.
 */

const SCENES: Scene[] = [
  {
    id: 'setup',
    chapter: 'दिवस १ · आधी तुमचे नियम',
    header: 'Meera',
    headerSub: 'तुमची personal PR manager',
    surface: 'chat',
    beats: [
      {
        kind: 'meera',
        text: 'नमस्कार रिया! मी मीरा. मी तुमच्या बाजूने काम करते. आपण हिंदी, मराठी की English मध्ये बोलूया?',
      },
      { kind: 'creator', text: 'मराठी चालेल 🙂' },
      {
        kind: 'meera',
        text: 'ठीक आहे. आधी तुमचे नियम ठरवूया. एका Reel साठी किमान किती घ्यायचं? हा floor तुमचा आहे, brand ला तो कधीच दिसणार नाही.',
      },
      { kind: 'creator', text: 'Reel ₹3,000, story set ₹1,500' },
      {
        kind: 'card',
        card: {
          type: 'prefs',
          title: 'तुमचे नियम',
          sub: 'फक्त तुम्हालाच दिसतात',
          rows: [
            { label: 'Reel floor', value: '₹3,000' },
            { label: 'Story set floor', value: '₹1,500' },
            { label: 'भाषा', value: 'मराठी' },
            { label: 'Level 0', value: 'मी लिहिते, तुम्ही पाठवता' },
          ],
        },
        hold: 80,
      },
      {
        kind: 'meera',
        text: 'Save झालं. आता जेव्हा कोणताही brand message करेल, तो इथे paste करा, मी वाचेन.',
      },
    ],
    tail: 30,
  },
  {
    id: 'paste',
    chapter: 'Brief paste करा · Meera वाचते',
    header: 'Meera',
    headerSub: 'Brief वाचते आहे…',
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
        text: 'आठ ओळींचा brief आहे, आणि तीन अडचणी आहेत. कायमचा usage, साठ दिवसांची exclusivity, आणि hashtag ad काढायला सांगितलं आहे.',
      },
      {
        kind: 'card',
        card: {
          type: 'flags',
          title: 'Deal मधले धोके',
          flags: [
            { tone: 'red', code: 'USAGE_PERPETUAL', text: 'Brand हा content कायमचा वापरू शकतो' },
            { tone: 'amber', code: 'EXCLUSIVITY_LONG', text: '६० दिवस दुसरा कोणताही skincare brand नाही' },
            { tone: 'amber', code: 'HIDE_DISCLOSURE', text: '#ad काढणं ASCI नियमांच्या विरोधात आहे. ते मी लिहिणार नाही.' },
          ],
        },
        hold: 105,
      },
      {
        kind: 'card',
        card: {
          type: 'quote',
          title: 'किती मागायचं',
          lines: [
            { label: '1 Reel', value: '₹4,300' },
            { label: '1 Story set', value: '₹2,150' },
          ],
          total: '₹6,450',
          totalLabel: 'एकूण',
          floor: 'तुमचा floor एकूण: ₹4,500',
          chip: 'Benchmark, market data नाही',
          note: 'Budget ₹5,000 आहे: story set वगळा आणि एक reel ₹4,300 ला offer करा.',
        },
        hold: 120,
      },
      { kind: 'meera', text: 'मी reply draft करते आहे. पाठवण्याआधी तुम्ही नक्की वाचा.' },
    ],
    tail: 24,
  },
  {
    id: 'send',
    chapter: 'तुम्ही approve करता · तेव्हाच जातं',
    header: 'Meera',
    headerSub: 'Draft तयार',
    surface: 'chat',
    beats: [
      {
        kind: 'card',
        card: {
          type: 'draft',
          to: 'Brand ला reply',
          body: 'Hi! Thanks for reaching out 🙌 For 1 reel with 60-day exclusivity and ad usage, my rate is ₹4,300. The #ad label stays: it is required. I work through Influora Secure Payments: funds are secured before I start, contract and invoice come automatically. Link below to confirm.',
          footer: 'Drafted with Meera · approved by Riya',
          actions: ['Approve', 'Edit', 'Discard'],
        },
        hold: 130,
      },
      { kind: 'tap', label: 'Approve', hold: 50 },
      {
        kind: 'meera',
        text: 'पाठवलं. Brand ने link उघडून funds secure केले, की deal पक्की.',
      },
      { kind: 'system', text: '२ दिवसांनी', hold: 40 },
      {
        kind: 'card',
        card: {
          type: 'timeline',
          title: 'Vitamin C serum · 1 reel · ₹4,300',
          steps: [
            { label: 'Funds secured', state: 'done', sub: '₹4,300 · brand ने secure केले' },
            { label: 'Contract', state: 'done', sub: 'दोघांनी sign केलं' },
            { label: 'Delivery', state: 'now', sub: '५ Oct पर्यंत live' },
          ],
        },
        hold: 90,
      },
    ],
    tail: 24,
  },
  {
    id: 'money',
    chapter: 'पैसे कुठे आहेत · Meera लक्ष ठेवते',
    header: 'Meera',
    headerSub: 'WhatsApp वर · फक्त महत्त्वाचं',
    surface: 'wa',
    beats: [
      {
        kind: 'wa',
        text: 'तुमचा reel live आहे. चोवीस तासांचा snapshot proof म्हणून save केला आहे, आणि बहात्तर तासांचा reach मी brand ला पाठवेन.',
      },
      {
        kind: 'card',
        card: {
          type: 'snapshot',
          title: 'Reel · २४ तास',
          stats: [
            { label: 'Views', value: '12.4K' },
            { label: 'Likes', value: '830' },
            { label: 'Saves', value: '41' },
          ],
          note: 'Source: Instagram Graph API · brand लाही दिसतं',
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
            { label: 'Released', state: 'done', sub: 'Brand ने approve केलं' },
            { label: 'Payout in flight', state: 'now', sub: 'Bank पर्यंत १–२ दिवस' },
            { label: 'Paid', state: 'next' },
          ],
          footer: 'TDS: या payout वर नोंद नाही · tax तुमच्या CA कडे',
        },
        hold: 110,
      },
      {
        kind: 'wa',
        text: 'पैसे तुमच्या bank मध्ये पोहोचले: चार हजार तीनशे रुपये. Invoice Influora वर तयार आहे.',
      },
    ],
    tail: 30,
  },
  {
    id: 'health',
    chapter: 'चूक होण्याआधी · आणि दर सोमवारी एक note',
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
          title: 'Label पहिल्या ओळीत नाही',
          body: 'Reel च्या caption मध्ये “#ad” सापडलं, पण १२५ अक्षरांनंतर. ASCI नुसार ते सुरुवातीला हवं.',
          fix: 'Fix: label caption च्या पहिल्या ओळीत आणा',
        },
        hold: 110,
      },
      { kind: 'creator', text: 'अरे, आत्ता बदलते' },
      { kind: 'system', text: 'सोमवार, ९:३० AM', hold: 40 },
      {
        kind: 'card',
        card: {
          type: 'note',
          title: 'सोमवारची note',
          when: 'दर आठवड्याला, ९:३० AM',
          items: [
            '₹4,300 गुरुवारी आले ✅',
            'लक्ष द्या: १ payout ५ दिवसांपासून pending',
            'एक post: Vitamin C reel चं behind-the-scenes',
            'एक fix: label पहिल्या ओळीत',
          ],
        },
        hold: 120,
      },
    ],
    tail: 24,
  },
  {
    id: 'find',
    chapter: 'नवीन brands शोधणं · यादी Meera ची, शब्द तुमचे',
    header: 'Meera',
    headerSub: 'Brands शोधते आहे…',
    surface: 'chat',
    beats: [
      { kind: 'creator', text: 'Meera, माझ्या niche मधले नवीन brands शोध' },
      { kind: 'typing', hold: 40 },
      {
        kind: 'card',
        card: {
          type: 'brands',
          title: '५ brands सापडले · skincare · पुणे, मुंबई',
          brands: [
            { name: 'Nimbu Naturals', warmth: 'W0', tag: 'Influora वर', why: 'Influora वर आहे: थेट pitch' },
            { name: 'Dew & Co', warmth: 'W1', tag: 'Lookalike', why: 'तुमच्यासारख्या creators सोबत काम केलं' },
            { name: 'Monsoon Skin', warmth: 'W3', tag: 'Cold', why: 'Cold email · एका run मध्ये जास्तीत जास्त २' },
          ],
          footer: 'एका brand ला ३० दिवसांत एकदाच · फक्त email, DM कधीच नाही',
        },
        hold: 110,
      },
      {
        kind: 'meera',
        text: 'तुमच्या शब्दांत दोन ओळी लिहा: हा brand का, आणि तुम्ही का. त्याशिवाय मी काहीच पाठवू शकत नाही.',
      },
      {
        kind: 'card',
        card: {
          type: 'hook',
          title: 'तुमच्या दोन ओळी',
          sub: 'Brand ला या ओळी तुमच्या वाटतील, Meera च्या नाही',
          placeholder: 'हा brand का, तुम्ही का…',
          value: 'तुमचा rain-proof serum चा Reel पुण्याच्या पावसात अगदी योग्य होता. मी रोज १८ हजार लोकांना skincare समजावते.',
        },
        hold: 100,
      },
      {
        kind: 'card',
        card: {
          type: 'draft',
          to: 'Nimbu Naturals · email',
          subject: 'तुमच्या monsoon launch साठी एक कल्पना',
          body: 'Hi! तुमचा rain-proof serum चा Reel पुण्याच्या पावसात अगदी योग्य होता… मी रिया, skincare creator, 7.4K followers. एक छोटी कल्पना share करायची आहे.',
          footer: 'सध्याचा reply rate: ४ पैकी १ · cold email मध्ये हे normal आहे',
          actions: ['Approve', 'Edit'],
        },
        hold: 110,
      },
      { kind: 'tap', label: 'Approve', hold: 44 },
      {
        kind: 'meera',
        text: 'पाठवलं. Reply आला तर इथेच सांगेन. नाही आला तरी ते normal आहे.',
      },
    ],
    tail: 30,
  },
];

export const MR_LOCALE: Locale = {
  lang: 'mr',
  label: 'मराठी',
  tts: 'mr-IN',
  scenes: SCENES,
  intro: {
    eyebrow: 'लवकरच येत आहे',
    title: 'Meera',
    sub: 'तुमची स्वतःची PR manager. तुमच्या बाजूने.',
    line: 'निर्णय तुमचा. मी लिहिते, आणि लक्षात ठेवते.',
    say: 'तुमची स्वतःची PR manager, तुमच्या बाजूने. निर्णय तुमचा. मी लिहिते, आणि लक्षात ठेवते.',
  },
  outro: {
    title: 'Meera for Creators',
    sub: 'सध्या तयार होते आहे. लवकरच येत आहे.',
    bullets: ['Brief paste करा, थेट उत्तर मिळवा', 'तुम्ही approve करता, तेव्हाच जातं', 'पैसे, proof, आणि दर सोमवारी एक note'],
    cta: 'Waitlist मध्ये नाव नोंदवा',
    say: 'Meera for Creators. सध्या तयार होते आहे, लवकरच येत आहे. Brief paste करा आणि थेट उत्तर मिळवा. तुम्ही approve करता, तेव्हाच जातं. पैसे, proof, आणि दर सोमवारी एक note.',
  },
};
