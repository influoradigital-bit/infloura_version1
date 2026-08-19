/**
 * JSON-LD structured-data helpers for Influora.
 *
 * Each `get*Schema` function returns a plain object shaped per schema.org,
 * ready to `JSON.stringify` into a `<script type="application/ld+json">` tag.
 * Use the exported `<JsonLd data={...} />` component to render it — React 19
 * hoists the resulting `<script>` correctly even when rendered deep in the tree.
 *
 * No new dependencies. Written in plain TS (no JSX) so this stays a `.ts` file;
 * `JsonLd` uses `React.createElement` directly.
 */
import { createElement, type ReactElement } from 'react';
import { COMPANY } from '@/lib/company';

export const SITE_URL = 'https://influora.in';
export const SITE_NAME = 'Influora';
export const DEFAULT_LOGO_URL = `${SITE_URL}/icon.svg`;
export const DEFAULT_OG_IMAGE_URL = `${SITE_URL}/og-image.png`;
export const DEFAULT_AUTHOR_NAME = 'Influora Team';

/** A JSON-serializable value — the shape every schema.org JSON-LD payload is limited to. */
export type JsonLdValue =
  | string
  | number
  | boolean
  | null
  | JsonLdValue[]
  | { [key: string]: JsonLdValue };

/** Base shape of any JSON-LD object: schema.org context + a declared type, plus arbitrary props. */
export type JsonLdObject = {
  '@context': 'https://schema.org';
  '@type': string;
} & Record<string, JsonLdValue>;

export interface OrganizationSchemaOptions {
  name?: string;
  /** Registered legal entity name (distinct from the consumer-facing brand name). */
  legalName?: string;
  url?: string;
  logoUrl?: string;
  description?: string;
  /** Social profile URLs (LinkedIn, X/Twitter, Instagram, etc.) — placeholder until socials are live. */
  sameAs?: string[];
}

/**
 * Organization schema — used on the homepage and /about.
 *
 * Defaults are sourced from `COMPANY` (src/lib/company.ts, the single source
 * of truth for legal/contact details) so this schema stays correct without a
 * per-call-site edit whenever the company record is filled in further (e.g.
 * once `registeredAddress` lands, `address.streetAddress` appears here too).
 */
export function getOrganizationSchema(options: OrganizationSchemaOptions = {}): JsonLdObject {
  const {
    name = SITE_NAME,
    legalName = COMPANY.legalName,
    url = SITE_URL,
    logoUrl = DEFAULT_LOGO_URL,
    description = 'Influencer marketing platform for India with built-in payment protection, connecting brands with verified creators.',
    sameAs = ([COMPANY.socials.instagram, COMPANY.socials.linkedin] as string[]).filter(
      (value) => value.length > 0,
    ),
  } = options;

  // Built imperatively (not via ternary spread) so the optional streetAddress
  // key doesn't widen the inferred literal type to include `undefined`, which
  // isn't a valid JsonLdValue.
  const address: Record<string, JsonLdValue> = {
    '@type': 'PostalAddress',
    addressRegion: COMPANY.state,
    addressCountry: 'IN',
  };
  if (COMPANY.registeredAddress) {
    address.streetAddress = COMPANY.registeredAddress;
  }

  const contactPoint: JsonLdValue = {
    '@type': 'ContactPoint',
    contactType: 'customer support',
    email: COMPANY.email,
    telephone: COMPANY.phone,
    areaServed: 'IN',
  };

  return {
    '@context': 'https://schema.org',
    '@type': 'Organization',
    name,
    legalName,
    url,
    logo: logoUrl,
    description,
    address,
    contactPoint,
    // CIN/GSTIN as schema.org `identifier` PropertyValue entries — no
    // dedicated schema.org property for Indian company registration numbers.
    identifier: [
      { '@type': 'PropertyValue', propertyID: 'CIN', value: COMPANY.cin },
      { '@type': 'PropertyValue', propertyID: 'GSTIN', value: COMPANY.gstin },
    ],
    ...(sameAs.length > 0 ? { sameAs } : {}),
  };
}

export interface WebsiteSchemaOptions {
  name?: string;
  url?: string;
  /** Set true once a working on-site search route exists; adds a SearchAction. */
  includeSearchAction?: boolean;
  /** e.g. "https://influora.in/search?q={search_term_string}" */
  searchUrlTemplate?: string;
}

/** WebSite schema — used on the homepage. SearchAction is a placeholder until site search ships. */
export function getWebsiteSchema(options: WebsiteSchemaOptions = {}): JsonLdObject {
  const {
    name = SITE_NAME,
    url = SITE_URL,
    includeSearchAction = false,
    searchUrlTemplate = `${SITE_URL}/search?q={search_term_string}`,
  } = options;

  const base: JsonLdObject = {
    '@context': 'https://schema.org',
    '@type': 'WebSite',
    name,
    url,
  };

  if (!includeSearchAction) {
    return base;
  }

  return {
    ...base,
    potentialAction: {
      '@type': 'SearchAction',
      target: `${searchUrlTemplate}`,
      'query-input': 'required name=search_term_string',
    },
  };
}

export interface ArticleSchemaOptions {
  title: string;
  /** Flat blog slug, e.g. "how-to-pay-influencers-safely-in-india" (no /category/ segment — see CEO-DECISIONS.md). */
  slug: string;
  publishedAt: string;
  /** Defaults to publishedAt if the post has not been updated. */
  modifiedAt?: string;
  /** Defaults to "Influora Team" per CEO decision (no personal-brand attribution). */
  author?: string;
  description?: string;
  image?: string;
}

/** Article schema — used on each /blog/:slug post. */
export function getArticleSchema(options: ArticleSchemaOptions): JsonLdObject {
  const {
    title,
    slug,
    publishedAt,
    modifiedAt = publishedAt,
    author = DEFAULT_AUTHOR_NAME,
    description,
    image = DEFAULT_OG_IMAGE_URL,
  } = options;

  const url = `${SITE_URL}/blog/${slug}`;

  return {
    '@context': 'https://schema.org',
    '@type': 'Article',
    headline: title,
    url,
    mainEntityOfPage: {
      '@type': 'WebPage',
      '@id': url,
    },
    datePublished: publishedAt,
    dateModified: modifiedAt,
    author: {
      '@type': 'Organization',
      name: author,
    },
    publisher: {
      '@type': 'Organization',
      name: SITE_NAME,
      logo: {
        '@type': 'ImageObject',
        url: DEFAULT_LOGO_URL,
      },
    },
    image,
    ...(description ? { description } : {}),
  };
}

export interface FaqItem {
  question: string;
  answer: string;
}

/** FAQPage schema — used on GEO problem/question pages (escrow, KYC, TDS, dispute resolution, etc.). */
export function getFaqPageSchema(items: FaqItem[]): JsonLdObject {
  return {
    '@context': 'https://schema.org',
    '@type': 'FAQPage',
    mainEntity: items.map(({ question, answer }) => ({
      '@type': 'Question',
      name: question,
      acceptedAnswer: {
        '@type': 'Answer',
        text: answer,
      },
    })),
  };
}

export interface BreadcrumbItem {
  name: string;
  /** Absolute or site-relative URL; relative paths are resolved against SITE_URL. */
  url: string;
}

/** BreadcrumbList schema — used on any nested page (features/*, blog/:slug, guidelines/*, etc.). */
export function getBreadcrumbListSchema(items: BreadcrumbItem[]): JsonLdObject {
  return {
    '@context': 'https://schema.org',
    '@type': 'BreadcrumbList',
    itemListElement: items.map(({ name, url }, index) => ({
      '@type': 'ListItem',
      position: index + 1,
      name,
      item: url.startsWith('http') ? url : `${SITE_URL}${url.startsWith('/') ? '' : '/'}${url}`,
    })),
  };
}

export interface JsonLdProps {
  data: JsonLdObject | JsonLdObject[];
}

/**
 * Renders a `<script type="application/ld+json">` tag from a schema object (or array of them).
 * Plain `React.createElement` call (not JSX) so this file can stay a `.ts` module.
 */
export function JsonLd({ data }: JsonLdProps): ReactElement {
  // Escape "<" so a malicious/unexpected value (e.g. a blog title) can never break out of the
  // script tag via "</script>" — safe even though our current inputs are all internally authored.
  const json = JSON.stringify(data).replace(/</g, '\\u003c');

  return createElement('script', {
    type: 'application/ld+json',
    dangerouslySetInnerHTML: { __html: json },
  });
}

// ---------------------------------------------------------------------------
// AEO / GEO schema helpers
//
// Added for the SEO/AEO/GEO pass (T-SEOCRO-0819). The four types below are the
// ones that actually change what an answer engine does with a page:
//
//   SoftwareApplication + Offer  -> price eligibility in AI Overviews and the
//                                  "what does it cost" answer, which is the
//                                  single most-asked commercial query.
//   HowTo                        -> step extraction for "how do I ..." prompts.
//                                  ChatGPT/Perplexity quote HowTo steps nearly
//                                  verbatim.
//   QAPage                       -> ONE canonical question per page, which is
//                                  what a retrieval engine matches against.
//   speakable                    -> marks the passages a voice/AI surface should
//                                  read back, so the engine quotes the sentence
//                                  we wrote instead of one it assembles itself.
//
// All of them take their copy from the SAME constants the page renders (never a
// second hand-written string), so schema and visible text cannot drift — that
// drift is what gets a page demoted for structured-data mismatch.
// ---------------------------------------------------------------------------

export interface OfferOption {
  /** Plan name exactly as shown on /pricing. */
  name: string;
  /** Numeric price in INR. Use 0 for free tiers. */
  price: number;
  /** UN/CEFACT unit code, e.g. "MON" for monthly. Omit for one-off/free. */
  billingPeriod?: string;
  description?: string;
}

export interface SoftwareApplicationSchemaOptions {
  name?: string;
  description: string;
  url?: string;
  /** e.g. "BusinessApplication" / "WebApplication". */
  applicationCategory?: string;
  offers?: OfferOption[];
  /** Feature bullets — schema.org featureList. */
  featureList?: string[];
}

/**
 * SoftwareApplication schema — used on the homepage and /pricing.
 *
 * `offers` renders as an AggregateOffer when more than one plan is passed, which
 * is what lets an answer engine state a price *range* ("free, or ₹4,999/mo")
 * instead of picking one plan arbitrarily and misquoting the cost.
 */
export function getSoftwareApplicationSchema(
  options: SoftwareApplicationSchemaOptions,
): JsonLdObject {
  const {
    name = SITE_NAME,
    description,
    url = SITE_URL,
    applicationCategory = 'BusinessApplication',
    offers = [],
    featureList = [],
  } = options;

  const base: JsonLdObject = {
    '@context': 'https://schema.org',
    '@type': 'SoftwareApplication',
    name,
    description,
    url,
    applicationCategory,
    operatingSystem: 'Web',
    provider: {
      '@type': 'Organization',
      name: SITE_NAME,
      url: SITE_URL,
    },
  };

  if (featureList.length > 0) {
    base.featureList = featureList;
  }

  if (offers.length === 0) {
    return base;
  }

  const offerNodes: JsonLdValue[] = offers.map((offer) => {
    const node: Record<string, JsonLdValue> = {
      '@type': 'Offer',
      name: offer.name,
      price: offer.price,
      priceCurrency: 'INR',
      availability: 'https://schema.org/InStock',
      url: `${SITE_URL}/pricing`,
    };
    if (offer.description) {
      node.description = offer.description;
    }
    if (offer.billingPeriod) {
      node.priceSpecification = {
        '@type': 'UnitPriceSpecification',
        price: offer.price,
        priceCurrency: 'INR',
        billingDuration: 1,
        billingIncrement: 1,
        unitCode: offer.billingPeriod,
      };
    }
    return node;
  });

  const prices = offers.map((offer) => offer.price);

  base.offers = {
    '@type': 'AggregateOffer',
    priceCurrency: 'INR',
    lowPrice: Math.min(...prices),
    highPrice: Math.max(...prices),
    offerCount: offers.length,
    offers: offerNodes,
  };

  return base;
}

export interface HowToStep {
  name: string;
  text: string;
}

export interface HowToSchemaOptions {
  name: string;
  description: string;
  steps: HowToStep[];
  /** Site-relative or absolute canonical for the page the HowTo lives on. */
  url: string;
  /** ISO-8601 duration, e.g. "PT10M". */
  totalTime?: string;
}

/**
 * HowTo schema — used on /how-it-works/brands and /how-it-works/creators.
 *
 * Answer engines lift these steps almost verbatim for "how do I run an
 * influencer campaign in India" prompts, so each `text` must be a complete,
 * standalone sentence: it gets quoted without the surrounding page.
 */
export function getHowToSchema(options: HowToSchemaOptions): JsonLdObject {
  const { name, description, steps, url, totalTime } = options;
  const absoluteUrl = url.startsWith('http')
    ? url
    : `${SITE_URL}${url.startsWith('/') ? '' : '/'}${url}`;

  const base: JsonLdObject = {
    '@context': 'https://schema.org',
    '@type': 'HowTo',
    name,
    description,
    step: steps.map((step, index) => ({
      '@type': 'HowToStep',
      position: index + 1,
      name: step.name,
      text: step.text,
      url: `${absoluteUrl}#step-${index + 1}`,
    })),
  };

  if (totalTime) {
    base.totalTime = totalTime;
  }

  return base;
}

export interface QaPageSchemaOptions {
  /** The single canonical question this page answers. */
  question: string;
  /** A complete, standalone answer — 40-60 words is the citation sweet spot. */
  answer: string;
  url: string;
}

/**
 * QAPage schema — one canonical question per page.
 *
 * Distinct from FAQPage (many questions of equal weight): a retrieval engine
 * scores a page against a *single* dominant intent, and declaring it explicitly
 * beats making the engine infer it from six sibling FAQ entries.
 */
export function getQaPageSchema(options: QaPageSchemaOptions): JsonLdObject {
  const { question, answer, url } = options;
  const absoluteUrl = url.startsWith('http')
    ? url
    : `${SITE_URL}${url.startsWith('/') ? '' : '/'}${url}`;

  return {
    '@context': 'https://schema.org',
    '@type': 'QAPage',
    mainEntity: {
      '@type': 'Question',
      name: question,
      text: question,
      url: absoluteUrl,
      answerCount: 1,
      acceptedAnswer: {
        '@type': 'Answer',
        text: answer,
        url: absoluteUrl,
      },
    },
  };
}

export interface WebPageSchemaOptions {
  name: string;
  description: string;
  url: string;
  /** CSS selectors whose text a voice/AI surface should read back. */
  speakableSelectors?: string[];
  /** ISO date the page content was last substantively reviewed. */
  dateModified?: string;
}

/**
 * WebPage schema with `speakable` — used on every marketing page.
 *
 * `speakable` is how we choose which sentence gets quoted. Without it the engine
 * picks its own extract, which in practice is whatever sits nearest the H1 —
 * frequently a nav label or a badge, not the answer. Pages mark their intended
 * pull-quote with `data-speakable`.
 */
export function getWebPageSchema(options: WebPageSchemaOptions): JsonLdObject {
  const {
    name,
    description,
    url,
    speakableSelectors = ['h1', '[data-speakable]'],
    dateModified,
  } = options;

  const absoluteUrl = url.startsWith('http')
    ? url
    : `${SITE_URL}${url.startsWith('/') ? '' : '/'}${url}`;

  const base: JsonLdObject = {
    '@context': 'https://schema.org',
    '@type': 'WebPage',
    name,
    description,
    url: absoluteUrl,
    inLanguage: 'en-IN',
    isPartOf: {
      '@type': 'WebSite',
      name: SITE_NAME,
      url: SITE_URL,
    },
    speakable: {
      '@type': 'SpeakableSpecification',
      cssSelector: speakableSelectors,
    },
  };

  if (dateModified) {
    base.dateModified = dateModified;
  }

  return base;
}
