/**
 * Per-edition data for the public Festival Box page at `/festival-box/:edition`
 * (T-FESTIVALBOX-0905 phase 3).
 *
 * ---------------------------------------------------------------------------------------------
 * WHY THIS IS A CONTENT MODULE AND NOT AN API CALL
 *
 * There is no edition or sponsor-listing model in the backend — none. Provisioning
 * (`FestivalSponsorProvisioningService`) creates a Workspace and a Campaign for a won sponsor, but
 * nothing anywhere records "this brand is in the Mumbai 2026 edition, showing this product, with
 * this coupon". Building that model is real work and, per
 * `wiki/decisions/FESTIVAL-BOX-CMO-ASSESSMENT.md` §5 (Tejas, 2026-09-02), Edition 01 is
 * deliberately run BY HAND — the automated version is deferred until the pilot proves out, with a
 * cap of 3-5 brands.
 *
 * So this file is the Edition-01 source of truth, in the same spirit as
 * `src/content/how-it-works-steps.ts`: the page renders it AND derives its JSON-LD from it, so the
 * structured data an AI answer engine quotes is always the copy actually on the page.
 *
 * The shape below is deliberately the shape an API would return. When the edition model ships,
 * `getEdition()` becomes a fetch and nothing else in the page changes.
 * ---------------------------------------------------------------------------------------------
 *
 * NO FABRICATED SPONSORS. `sponsors: []` is not a stub — it is the truthful state. Edition 01 is
 * still being sold; no brand has signed. Inventing plausible-looking brands, products and coupon
 * codes here would put fake commercial listings on a public, indexable page, and the first person
 * to try one of those codes would find it dead. The page renders a real pre-launch state instead
 * and routes interest back to `/festival-box`. Populate this array only with sponsors who have
 * actually signed.
 */

/** Matches the backend's `FestivalTier` enum. Decides ordering and prominence on the page. */
export type FestivalSponsorTier = 'TITLE' | 'FEATURED' | 'GIFTING';

export interface FestivalSponsor {
  /** Stable id, used as a React key and in the click-tracking path. */
  slug: string;
  /** The brand's name as it should appear to a shopper. */
  brand: string;
  /** The specific product featured in the room — "Kurta Set", "The Bag". */
  product: string;
  /** One line a shopper reads before deciding to tap. Not marketing filler — say what it is. */
  blurb: string;
  tier: FestivalSponsorTier;
  /**
   * The page-exclusive coupon code, shown tap-to-copy.
   *
   * Edition 01 stores the literal string because coupons are minted by hand. NOTE for whoever
   * automates this: `CouponCode` requires a non-null `creator_id`, so a single brand-wide code has
   * no home in that model today — that fork (per-creator codes vs. one brand code) is an open
   * decision, and it is why this is a plain string rather than a coupon id.
   */
  coupon: string;
  /** Human-readable value of the code — "15% off". Shown next to it so the code means something. */
  couponValue: string;
  /** Where the Shop button sends the visitor: the brand's OWN store or Amazon listing. */
  shopUrl: string;
  /**
   * Optional Influora click-tracking URL (`/track/click/{utmCampaignId}`) which 302s to `shopUrl`.
   * When absent the button links straight to `shopUrl` — the visitor still reaches the right place,
   * the click just is not counted. Never block a sale on tracking being wired.
   */
  trackingUrl?: string;
  /** Product image. Absent renders a typographic tile rather than a broken image. */
  imageUrl?: string;
  /**
   * This sponsor's Meta Pixel id, if they gave Influora one to measure page visits with
   * (T-FESTIVALBOX-0905 phase 6). Absent by default — no sponsor has provided one yet, so nothing
   * fires today. When present, the page inits and fires this pixel ONLY after the visitor accepts
   * the on-page consent bar (see PageConsentBar.tsx / usePageConsent) — never on page load, and
   * never in module scope or index.html, since consent must gate the tag firing at all
   * (DPDP Act 2023 / GDPR), not just what it's allowed to collect.
   */
  metaPixelId?: string;
}

export interface FestivalCreatorCredit {
  handle: string;
  /** Follower band as published on the roster — "70K+", "25-50K". Never a precise fake number. */
  band: string;
}

export type FestivalEditionStatus = 'UPCOMING' | 'LIVE' | 'ARCHIVED';

export interface FestivalEdition {
  /** URL segment: `/festival-box/mumbai-2026`. */
  slug: string;
  /** Must match the backend `edition` value so enquiries from this page attribute correctly. */
  editionKey: string;
  name: string;
  city: string;
  /** Human date label — "Festive 2026". Deliberately not a Date; nothing computes on it. */
  dateLabel: string;
  status: FestivalEditionStatus;
  /** The one line a shopper reads first. */
  heroLine: string;
  /** Second line: what this page IS, for someone who arrived from a reel with no context. */
  standfirst: string;
  sponsors: FestivalSponsor[];
  creators: FestivalCreatorCredit[];
}

/**
 * Every edition, keyed by URL slug.
 *
 * Adding an edition is adding a key here. The page 404s on an unknown slug rather than rendering
 * an empty shell, so a mistyped or retired URL is honestly a not-found and never a page that looks
 * live but sells nothing.
 */
export const FESTIVAL_EDITIONS: Record<string, FestivalEdition> = {
  'mumbai-2026': {
    slug: 'mumbai-2026',
    editionKey: 'MUMBAI_FESTIVE_2026',
    name: 'The Festival Box — Mumbai',
    city: 'Mumbai',
    dateLabel: 'Festive Edition 2026',
    status: 'UPCOMING',
    heroLine: 'Everything from the room, in one place.',
    standfirst:
      'Ten creators, one festive shoot day in Mumbai, and every brand they wore. Each piece ships from its own brand — tap a code, then buy where you normally would.',
    // Truthfully empty: Edition 01 is still being sold. See this file's header — do not seed this
    // with example brands to "show the layout", and do not leave a verification fixture behind.
    // (A temp fixture WAS left here once when a session ended mid-task; it put a fake brand and a
    // dead coupon code on a public indexable page. If you add one to test, revert it in the same
    // change, not "before shipping".)
    sponsors: [],
    creators: [],
  },
};

/**
 * Looks up one edition by URL slug.
 *
 * Returns `null` for an unknown slug so the page can render a real 404 instead of an empty shell.
 * Kept as a function rather than exposing the record directly because this is the exact seam that
 * becomes an API call once the edition model exists — the page awaits a lookup either way.
 */
export function getEdition(slug: string | undefined): FestivalEdition | null {
  if (!slug) return null;
  return FESTIVAL_EDITIONS[slug.toLowerCase()] ?? null;
}

/**
 * Sponsors in display order: Title first, then Featured, then Gifting, alphabetical within a tier.
 *
 * Tier order is the thing sponsors actually pay for — slide 9 sells "featured top of the page" as
 * the Title tier's differentiator — so it must be enforced here rather than left to whatever order
 * someone happened to type the array in. Alphabetical within a tier keeps it defensible: no brand
 * can claim a better slot than another on the same tier.
 */
export function sortedSponsors(sponsors: FestivalSponsor[]): FestivalSponsor[] {
  const rank: Record<FestivalSponsorTier, number> = { TITLE: 0, FEATURED: 1, GIFTING: 2 };
  return [...sponsors].sort(
    (a, b) => rank[a.tier] - rank[b.tier] || a.brand.localeCompare(b.brand),
  );
}
