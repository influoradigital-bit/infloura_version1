import {
  BadgeCheck,
  Boxes,
  Camera,
  CircleDollarSign,
  Clapperboard,
  FileSignature,
  Gamepad2,
  Gift,
  Handshake,
  LineChart,
  Link2,
  PackageCheck,
  PlugZap,
  Radio,
  ShieldCheck,
  Sparkles,
  Ticket,
  Truck,
  Users,
  Utensils,
  type LucideIcon,
} from 'lucide-react';

/**
 * Every piece of copy on /festival-box, in one place (T-FESTIVALBOX-0905).
 *
 * WHY IT EXISTS: the page renders these arrays AND derives its JSON-LD from them, exactly the way
 * {@link ../content/how-it-works-steps} feeds both the rendered steps and the HowTo schema on
 * /how-it-works/brands. When the two were allowed to drift on that page, the structured data that
 * AI answer engines quote said something the visitor never saw. Mapping instead of re-typing makes
 * that impossible here too.
 *
 * SOURCE: Influora_Festival_Box_Proposal10.pptx (22 slides, Mumbai Festive Edition 2026). Numbers
 * that appear here — tier prices, reach ranges, the roster split — are the deck's, not invented.
 * When Edition 02 reprices, this file is the only thing that changes; nothing about the price lives
 * in the backend (see FestivalTier).
 *
 * ---------------------------------------------------------------------------------------------
 * THIS COPY DELIBERATELY DIVERGES FROM THE DECK IN ONE PLACE. DO NOT "CORRECT" IT BACK.
 *
 * The deck sells a "live sales scoreboard" that updates from the first post. There is no such
 * screen: no Festival Box sponsor dashboard, and no brand-facing view of coupon redemptions at all
 * (`CreatorCouponController` serves a CREATOR their own codes; nothing serves a brand the
 * redemptions against theirs). Edition 01 reports those numbers by hand. So every claim here is
 * written to the outcome we actually deliver — sales tracked per creator and reported — rather than
 * to the live dashboard the deck implies. "Overpromising the platform" is named as this product's
 * biggest risk in `wiki/decisions/FESTIVAL-BOX-CMO-ASSESSMENT.md` (Tejas, 2026-09-02), and
 * "fully automated" is on its do-not-say list.
 *
 * CORRECTION (Priya gate review, 2026-09-05) — an earlier version of this comment also claimed
 * there is "no order-webhook listener wired to a sponsor's store", citing that assessment's §5.
 * THAT CLAIM IS FALSE and the assessment is wrong on it; §5 states it was written "based on memory
 * context", not from the code. The listener exists and is production-grade:
 *   - `ShopifyWebhookController` — `orders/paid` + `orders/create`, HMAC-verified against
 *     `X-Shopify-Hmac-Sha256` before parsing, `order.discount_codes[0].code` → `RedemptionService#redeem`
 *   - `WooCommerceWebhookController` — `order.created` / `order.updated`, same shape
 *   - plus `CouponCode`, `CouponRedemption`, `ConversionTrackingService`, `UtmCampaign`,
 *     `GET /track/click/{id}`, and signed `POST /webhooks/{redemption,conversion}`
 * All workspace-scoped, idempotent, and rate-limited. Do not fund rebuilding any of it.
 *
 * The real gap for a Festival sponsor is MODELLING, not infrastructure: `CouponCode` requires a
 * non-null `workspace_id` + `campaign_id` + `creator_id`, so a sponsor needs a workspace, a
 * campaign, per-creator codes, and a store OAuth connection. Days of onboarding work on top of
 * plumbing that already runs — which is why the copy above still says "reported" and not "live".
 *
 * If the sponsor-facing dashboard is ever built, this copy can go back to the deck's wording — but
 * only then.
 * ---------------------------------------------------------------------------------------------
 */

/** Identifies the edition an enquiry belongs to. Must match the backend's DEFAULT_EDITION. */
export const FESTIVAL_EDITION = 'MUMBAI_FESTIVE_2026';

/** Human label for the edition, used in the hero and the form's confirmation copy. */
export const FESTIVAL_EDITION_LABEL = 'Mumbai · Festive Edition 2026';

export interface FestivalStep {
  step: string;
  title: string;
  body: string;
  icon: LucideIcon;
}

export interface FestivalTierCard {
  /** Wire value posted to the backend — must be a FestivalTier enum constant. */
  value: 'GIFTING' | 'FEATURED' | 'TITLE';
  name: string;
  price: string;
  priceNote: string;
  /** The one line that decides whether this tier is the right one. */
  pitch: string;
  reach: string;
  features: string[];
  /** Exactly one tier carries this; it is the tier the page steers toward. */
  highlighted?: boolean;
}

// ============================================================================
// BRAND SIDE
// ============================================================================

/** Slide 3 — the status quo the offer displaces. Framed as the reader's experience, not ours. */
export const BRAND_PROBLEMS: { title: string; body: string }[] = [
  {
    title: "You can't track sales",
    body: "Gifts go out. Nothing ties them back to revenue — you're left guessing.",
  },
  {
    title: 'You chase every creator',
    body: 'DMs, follow-ups, flaky posts — all land on your team, for weeks.',
  },
  {
    title: "You don't own the content",
    body: 'A few reels appear, then vanish. Nothing to re-run or license.',
  },
  {
    title: 'One brand pays full cost',
    body: 'Venue, creators, production, styling — one budget carries it all.',
  },
];

/** Slide 4 — the three-beat answer. Deliberately three: it is the shape people repeat back. */
export const BRAND_FIX: FestivalStep[] = [
  {
    step: '01',
    title: 'You ship one box',
    body: "One box reaches our Mumbai hub. That's the whole of your logistics.",
    icon: Truck,
  },
  {
    step: '02',
    title: 'We stage the event',
    body: 'Ten styled creators, one festive room, a full shoot day we run.',
    icon: Camera,
  },
  {
    step: '03',
    title: 'You get the outcomes',
    body: 'Rights-cleared content, a reach report, sales tracked to each creator.',
    icon: LineChart,
  },
];

/** Slide 5 — the five acts of the event itself. Feeds the page's HowTo schema. */
export const BRAND_FLOW: FestivalStep[] = [
  {
    step: '01',
    title: 'The Match',
    body: 'Creators pick pieces and angles that suit them; no two cover your product the same way.',
    icon: Handshake,
  },
  {
    step: '02',
    title: 'The Styled Room',
    body: 'All ten wear every brand in the room — candid content, shot properly, all day.',
    icon: Sparkles,
  },
  {
    step: '03',
    title: 'The Games',
    body: 'Playful challenges surface a real product feature on camera — no script.',
    icon: Gamepad2,
  },
  {
    step: '04',
    title: 'Life & Talk',
    body: 'Honest demos over food and drinks — reads as experience, not an ad.',
    icon: Utensils,
  },
  {
    step: '05',
    title: 'The Drop',
    body: 'The finale hour streams live — coupon codes on screen, the page pinned in the link.',
    icon: Radio,
  },
];

/** Slide 6 — how one product gets two different treatments in one room. */
export const QUEEN_BEE = {
  headline: 'The Queen Bee model',
  standfirst:
    'One creator leads, the rest amplify. Every creator gets a spotlight, and no two make the same video.',
  queen: {
    title: 'The Queen — the hero angle',
    body: 'Shows and names the product in full — the piece that creates desire.',
  },
  bees: {
    title: 'The Bees — the detail angle',
    body: 'Colour, feature, styling, demo — the pieces that create proof.',
  },
  closing: 'Same product, two angles, one shoot — desire and proof, together.',
  roster: [
    { label: 'Queen Bee', band: '70K+', count: '×1' },
    { label: 'Strong bees', band: '50–70K', count: '×2' },
    { label: 'Worker bees', band: '25–50K', count: '×3' },
    { label: 'Micro bees', band: '15–25K', count: '×4' },
  ],
};

/** Slide 8 — the tracking chain. This is the part gifting cannot answer, so it gets its own act. */
export const TRACKING_CHAIN: { title: string; body: string; icon: LucideIcon }[] = [
  {
    title: 'Creator reel',
    body: 'Links to the Festival Box page with your coupon.',
    icon: Clapperboard,
  },
  {
    title: 'Festival Box page',
    // NOT "clicks counted per brand". Per-sponsor click counting needs a UtmCampaign row per
    // sponsor, and none are created yet — utm_campaigns only became able to hold a page-level link
    // in V20260905180000, and nothing provisions them. Coupon COPIES are counted (that endpoint is
    // live, see MEASUREMENT below); clicks are not. Do not restore the stronger wording until
    // tracking links are actually being minted.
    body: 'Every sponsor, one page — your code, ready to copy.',
    icon: Link2,
  },
  {
    title: 'Your checkout',
    body: 'Opens your store, code applied — you keep the sale.',
    icon: CircleDollarSign,
  },
  {
    title: 'Sale reported',
    body: 'Connect Shopify or WooCommerce once — orders report themselves, no spreadsheets.',
    icon: LineChart,
  },
];

/**
 * The two-code model, explained for a marketer rather than an engineer.
 *
 * WHY THIS EXISTS AS ITS OWN BLOCK: attribution is the single hardest thing to explain on this page
 * and the single most important thing a sponsor is buying. The tracking chain above is linear; this
 * is not — two different codes do two different jobs and converge on one report. Trying to fold it
 * into the chain made both harder to read.
 *
 * Deliberately says nothing about webhooks, HMAC or click endpoints. The brand does not care, and
 * naming the machinery makes a reliable thing sound fragile.
 */
export const TWO_CODE_MODEL = {
  headline: 'Two codes, two jobs',
  standfirst:
    'Every sponsor gets a set of codes. Which code a shopper uses is what tells us who earned the sale.',
  creatorCode: {
    label: 'A code per creator',
    example: 'PRIYA15',
    where: 'In each creator’s own reel and caption',
    answers: 'Which creator drove this sale',
    body: 'Ten creators, ten codes. Priya’s code means the sale is Priya’s.',
  },
  pageCode: {
    label: 'One code for the page',
    example: 'FESTIVE10',
    where: 'On your listing on the Festival Box page',
    answers: 'Sales from the page itself',
    body: 'For visitors without a creator’s code — exclusive to this edition, only from here.',
  },
  closing:
    // CORRECTED 2026-09-05 — this previously said "created in your own store", which is backwards.
    // CouponCodeService generates the code SERVER-SIDE (generateCreatorCoupon / generateBrandCoupon);
    // the caller supplies only the discount terms. The brand then creates that exact code in their
    // own store, which is what makes it work at checkout. Stating it the other way round would have
    // a sponsor waiting for us to accept a code we never asked for — and a code that exists on our
    // page but not in their store is dead the moment a shopper tries it.
    'We issue the codes; you add them to your store with the discount you want. That is what makes them work at your checkout — and lets you expire them whenever you like.',
};

/**
 * What the brand actually has to do. Four items, stated on the page BEFORE the price.
 *
 * WHY IT IS ON THE PAGE AT ALL: two of these (connect your store, supply your codes) are hard
 * requirements for the sales tracking this page sells, and neither appeared anywhere in the deck or
 * on this page. A sponsor who first hears "connect your store" on the kickoff call has been sold
 * something on terms they did not see. Putting the ask before the tier prices is deliberate: a
 * brand should be able to disqualify itself for free.
 */
export const BRAND_REQUIREMENTS: { title: string; body: string; icon: LucideIcon }[] = [
  {
    title: 'Your product',
    body: "Enough pieces for ten creators, sized after The Match. That's the only stock you commit.",
    icon: Gift,
  },
  {
    title: 'One shipment',
    body: 'One box to our Mumbai hub by Day 7, any courier. Your logistics end there.',
    icon: Truck,
  },
  {
    title: 'Your coupon codes',
    body: 'We issue one code per creator plus one for the page. You add them to your store at the discount you choose.',
    icon: Ticket,
  },
  {
    title: 'A store connection',
    body: 'Connect Shopify or WooCommerce at kickoff so orders report themselves. Selling elsewhere? See the note below.',
    icon: PlugZap,
  },
];

/**
 * The Amazon caveat, stated plainly and NOT buried.
 *
 * The deck (slide 7) says "Buy on your store or Amazon" and this page repeated it, which implied
 * Amazon sales appear in the report. They cannot: Amazon does not call out to anyone when an order
 * is placed, so there is no mechanism — not one we have not built, one that does not exist. A
 * sponsor selling primarily on Amazon would otherwise discover this at the Day-20 report, which is
 * the worst possible moment.
 */
export const TRACKING_CAVEAT = {
  title: 'If you sell on Amazon or a marketplace',
  body: "Marketplaces don't report orders back, so those sales can't be counted automatically. Your codes still work and reach is unaffected — we reconcile marketplace redemptions from the figures you share at wave's end. Everything on a connected Shopify or WooCommerce store is automatic.",
};

/**
 * What a sponsor can measure, beyond the sale itself.
 *
 * EVERY CLAIM HERE IS BUILT AND VERIFIED — that is the entire point of this block, and the bar for
 * adding to it. This page has already had to walk back one overpromise (a "live scoreboard" that
 * did not exist), and `wiki/decisions/FESTIVAL-BOX-CMO-ASSESSMENT.md` names overpromising the
 * platform as this product's single biggest risk. Verified when written, 2026-09-05:
 *
 *   - Coupon copies: `POST /festival/coupon-copied` is live and permitAll'd in SecurityConfig,
 *     rate-limited in AuthRateLimitFilter, counted into a per-sponsor daily bucket, and fired by
 *     the Festival Box page's copy handler. Admin reads it at /admin/festival-metrics.
 *   - Sponsor pixel: `workspaces.meta_pixel_id` (V20260905150000), written from the admin brand
 *     screen, fired ONLY after the visitor accepts the on-page consent bar, with
 *     connect.facebook.net allowed by a CSP rule scoped to /festival-box/* alone.
 *
 * DELIBERATELY NOT CLAIMED: per-creator or per-brand CLICK counts on this page. utm_campaigns only
 * became able to hold a page-level link in V20260905180000 and nothing mints those links yet, so
 * there is no click number to report. Add it here when links are actually being created — not
 * before.
 */
export const MEASUREMENT: { title: string; body: string; icon: LucideIcon }[] = [
  {
    title: 'Sales, attributed',
    body: 'Connect your store once. Every order using one of your codes reports itself — and a creator code tells you which creator earned it.',
    icon: LineChart,
  },
  {
    title: 'Coupon copies',
    body: 'How many shoppers took your code off the page. A demand signal, not a sale — and the only number a marketplace seller can get.',
    icon: Ticket,
  },
  {
    title: 'Your own pixel',
    body: 'Add your Meta pixel and retarget everyone who visited the page — an audience that watched a creator and came to shop. Fires only if the visitor consents.',
    icon: Radio,
  },
];

/** Slide 9/10 — tiers. Prices are the deck's Edition-01 rates. */
export const BRAND_TIERS: FestivalTierCard[] = [
  {
    value: 'GIFTING',
    name: 'Gifting Partner',
    price: 'Product only',
    priceNote: 'approx. ₹20,000 in product value',
    pitch: 'Test the format with product instead of budget.',
    reach: 'Est. 40–70K reach',
    features: [
      'Worn by all 10 creators at the event',
      'Tagged in the group reel (Influora Collab)',
      '1 dedicated reel + story from a micro creator (15–25K)',
      '2 rights-cleared UGC pieces handed over',
      'Listed on the Festival Box page with your coupon',
      'Post-event report: clicks → redemptions → sales',
    ],
  },
  {
    value: 'FEATURED',
    name: 'Featured Brand',
    price: '₹25,000 – ₹40,000',
    priceNote: 'plus your product',
    pitch: 'The tier most brands pick — a named collab plus a proof piece.',
    reach: 'Est. 90–140K reach',
    highlighted: true,
    features: [
      'Everything in Gifting Partner',
      '1 dedicated reel + story as a branded Collab, from a mid creator (25–70K)',
      '1 additional detail-angle piece',
      '5 rights-cleared UGC pieces handed over',
      'Logo and clip placement in the recap film',
    ],
  },
  {
    value: 'TITLE',
    name: 'Title Sponsor',
    price: '₹50,000 – ₹75,000',
    priceNote: 'plus your product · one per edition',
    pitch: 'Own the edition: the hero campaign and the top of the page.',
    reach: 'Est. 250–400K reach',
    features: [
      'Everything in Featured Brand',
      'The full Queen Bee campaign — hero piece plus the swarm',
      'Queen exclusive: no competing brand in her hero piece',
      '10 rights-cleared UGC pieces handed over',
      'Featured at the top of the Festival Box page',
      'Named / co-branded across the recap film',
    ],
  },
];

/** Slide 11 — what working together actually looks like, day by day. Feeds the HowTo schema. */
export const BRAND_TIMELINE: FestivalStep[] = [
  {
    step: 'Day 0',
    title: 'Lock your slot',
    body: 'Pick a tier, e-sign on Influora, pay the 50% advance. Slots go in payment order.',
    icon: FileSignature,
  },
  {
    step: 'Day 1–2',
    title: 'Kickoff call',
    body: 'One 30-minute call covers products, sizes, brief, and your coupon code.',
    icon: Handshake,
  },
  {
    step: 'Day 3–7',
    title: 'Ship your box',
    body: 'One box to our Mumbai hub — we send the packing list and label.',
    icon: Boxes,
  },
  {
    step: 'Day 12',
    title: 'We stage the event',
    body: 'Styling, event, shoot. Your brief is locked — nothing needed from you.',
    icon: Camera,
  },
  {
    step: 'Day 13–16',
    title: 'The wave',
    body: 'Content goes live in sequence, and tracking starts with the first post.',
    icon: Radio,
  },
  {
    step: 'By Day 20',
    title: 'Handover',
    body: 'Files, links, report and invoices — handed over in one place, yours to keep.',
    icon: PackageCheck,
  },
];

/** Slide 15 — what persists after the wave ends. The answer to "and then what?". */
export const BRAND_DELIVERABLES: { title: string; body: string; icon: LucideIcon }[] = [
  {
    title: 'Content library',
    body: 'Every rights-cleared file, full resolution, in your brand folder — ready to download.',
    icon: Clapperboard,
  },
  {
    title: 'Live links sheet',
    body: 'Every reel, story and tag URL, listed per creator — ready to repost.',
    icon: Link2,
  },
  {
    title: 'Sales tracking',
    body: 'Clicks, redemptions and rupee sales, broken out per creator — reported through the wave.',
    icon: LineChart,
  },
  {
    title: 'Final report',
    body: 'The Day-20 PDF: reach, sales and a content index — proof for your next budget.',
    icon: BadgeCheck,
  },
];

/** Slide 21 — the objections, answered in the reader's own words. Feeds the FAQ schema. */
export const BRAND_FAQ: { question: string; answer: string }[] = [
  {
    question: 'I give away product and get nothing measurable back. How is this different?',
    answer:
      'Every reel points to your listing on the Festival Box page, which carries a coupon that exists nowhere else. Influora counts the clicks; you share the redemptions that code brings in; we reconcile the two and attribute each sale to the creator who drove it. Because the code is page-exclusive, the attribution is not guesswork. You also keep a library of rights-cleared content you own outright.',
  },
  {
    question: 'What if a creator takes the product and never posts?',
    answer:
      'Deliverables are contracted through an e-signed agreement on Influora, with dates in writing. If a creator misses a deliverable, we reshoot or replace them at no cost to you.',
  },
  {
    question: 'Will the content actually look good enough to run?',
    answer:
      'We style, light, shoot and curate every piece on the day. Content is delivered rights-cleared, and a paid-ads licence is available as an add-on if you want to run the pieces as Meta or Instagram ads.',
  },
  {
    question: 'Can an event with ten creators really drive sales?',
    answer:
      'The reach and the seeding build over the wave, and the finale hour streams live with coupon codes on screen — that is the part that converts in real time. Actual reach and sales are reported after the event, not estimated.',
  },
  {
    question: 'Do I have to handle payments, stock or returns?',
    answer:
      'No. The buyer checks out on your own store or Amazon, so you keep the customer, the payment and the margin. Influora never takes the payment. Creator payouts are our responsibility, not yours.',
  },
  {
    question: 'What exactly is my cost?',
    answer:
      'Your tier fee plus your product, and one shipment to our Mumbai hub. Venue, creators, styling, production, the page, the tracking and the reporting are all included in the tier. The only optional extra is the ads licence.',
  },
];

/** Slide 18 — the economics, stated plainly because it is the strongest single argument. */
export const BRAND_MATH = {
  alone: {
    label: 'On your own',
    amount: '₹1.5L',
    body: 'Venue, ten creators, production and styling — on your budget, your team runs it.',
  },
  shared: {
    label: 'Shared, with Influora',
    amount: '~₹50K',
    body: 'Your slice of a multi-brand event. Same room, same creators — we run it.',
  },
  punchline: 'You get the ₹1.5L production for roughly a third, because you share the room, not the spotlight.',
};

// ============================================================================
// CREATOR SIDE
// ============================================================================

/**
 * The creator half of the page.
 *
 * The deck has no creator narrative — in it, creators are the roster being recruited, not an
 * audience being sold to. So this side is NOT a translation of the brand pitch: it answers the
 * three things a creator actually decides on (what do I get, what do I owe, will I be paid), and it
 * promises nothing the brand-side contract does not already commit Influora to.
 */
export const CREATOR_BENEFITS: { title: string; body: string; icon: LucideIcon }[] = [
  {
    title: 'A full shoot day, styled for you',
    body: "Venue, styling, lighting, a crew — production quality you'd otherwise pay for, free.",
    icon: Camera,
  },
  {
    title: 'Paid deliverables, contracted',
    body: 'Your fee and deliverables, e-signed before the day, with usage rights spelled out.',
    icon: FileSignature,
  },
  {
    title: 'Payment protected end to end',
    body: 'The brand funds the deal up front — payout is our responsibility, not yours to chase.',
    icon: ShieldCheck,
  },
  {
    title: 'Products worth keeping',
    body: 'Every brand dresses all ten creators. Wear it, shoot it, keep it.',
    icon: Gift,
  },
  {
    title: 'A month of content in a day',
    body: 'Multiple looks, several brands, a live finale — enough to carry your grid for weeks.',
    icon: Clapperboard,
  },
  {
    title: 'Brands that come back',
    body: 'Clicks and sales reported per creator — proof you can show, and a reason to be rebooked.',
    icon: LineChart,
  },
];

/** What Influora asks in return. Stated up front — a roster call that hides the ask gets no-shows. */
export const CREATOR_COMMITMENTS: { title: string; body: string }[] = [
  {
    title: 'One full day, in person, in Mumbai',
    body: 'One shoot day. Travel in the city is on you; the venue is on us.',
  },
  {
    title: 'Wear every brand in the room',
    body: 'All ten wear all the brands. Your dedicated pieces are yours to shape.',
  },
  {
    title: 'Deliver on the dates you sign',
    body: 'Stories within 48 hours, dedicated pieces inside 10 days — dates in your e-signed agreement.',
  },
  {
    title: 'Label it honestly',
    body: "Every piece carries #ad or a paid-partnership label. ASCI disclosure isn't optional.",
  },
];

/** How the roster is picked. Published so the standard is a standard, not a favour. */
export const CREATOR_SELECTION: { title: string; body: string; icon: LucideIcon }[] = [
  {
    title: 'Apply with your handle',
    body: 'One short form. We look at your real account, not a media kit.',
    icon: Users,
  },
  {
    title: 'Metrics verified, not claimed',
    body: "Read straight from the platform, so a strong small account beats an inflated big one.",
    icon: BadgeCheck,
  },
  {
    title: 'Fit and brand safety',
    body: 'Category fit, a minimum follower band, and a brand-safety check — same bar for everyone.',
    icon: ShieldCheck,
  },
  {
    title: 'Slot offered and signed',
    body: "If you're in, band, fee and deliverables are in writing before you commit.",
    icon: FileSignature,
  },
];

export const CREATOR_FAQ: { question: string; answer: string }[] = [
  {
    question: 'Do I have to pay anything to join the roster?',
    answer:
      'No. Creators are never charged to be in an edition. Influora is paid by the sponsoring brands, and your fee is paid to you.',
  },
  {
    question: 'How many followers do I need?',
    answer:
      'The Mumbai edition roster runs from about 15K to 70K+, across four bands. A smaller account with genuine engagement is a real candidate — metrics are verified from the platform, so engagement counts as much as size.',
  },
  {
    question: 'When do I get paid?',
    answer:
      'Your fee and its release conditions are set out in the agreement you e-sign before the shoot. The brand funds the deal up front on Influora, so the money is secured before the day rather than invoiced for afterwards.',
  },
  {
    question: 'Who owns the content I shoot?',
    answer:
      'Usage rights, exclusivity and duration are written into the agreement. Brands receive rights-cleared pieces for the term they licensed; anything beyond that — running your content as a paid ad, or advertising from your handle — is a separate, explicitly agreed add-on.',
  },
];
