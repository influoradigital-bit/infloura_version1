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
    description = 'Escrow-protected influencer marketing platform connecting Indian brands with verified creators.',
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
