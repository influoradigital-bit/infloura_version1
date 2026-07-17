/**
 * Reusable per-page SEO component.
 *
 * Renders `<title>` / `<meta>` / `<link>` tags directly inside a page component.
 * React 19 hoists these into `<head>` automatically — no react-helmet or other
 * dependency required. Use this on every new marketing/blog page.
 *
 * Usage:
 *   <Seo title="Pricing" description="..." canonical="/pricing" />
 */
import type { ReactElement } from 'react';
import { DEFAULT_OG_IMAGE_URL, SITE_NAME, SITE_URL } from './schema';

export interface SeoProps {
  /** Page-specific title. `" | Influora"` is appended automatically unless already present. */
  title: string;
  /** Under 160 chars, CTA-driven per keywords.md section 9.2. */
  description: string;
  /** Site-relative path (e.g. "/pricing") or an absolute URL. Every page must pass one. */
  canonical: string;
  /** Absolute or site-relative image URL. Defaults to the site-wide OG image. */
  ogImage?: string;
  /** Set true for v0/legal/utility pages that should not be indexed yet. */
  noindex?: boolean;
}

const TITLE_SUFFIX = ` | ${SITE_NAME}`;
const RECOMMENDED_MAX_TITLE_LENGTH = 60;
const RECOMMENDED_MAX_DESCRIPTION_LENGTH = 160;

function resolveUrl(pathOrUrl: string): string {
  if (pathOrUrl.startsWith('http://') || pathOrUrl.startsWith('https://')) {
    return pathOrUrl;
  }
  return `${SITE_URL}${pathOrUrl.startsWith('/') ? '' : '/'}${pathOrUrl}`;
}

export function Seo({
  title,
  description,
  canonical,
  ogImage = DEFAULT_OG_IMAGE_URL,
  noindex = false,
}: SeoProps): ReactElement {
  const fullTitle = title.includes(SITE_NAME) ? title : `${title}${TITLE_SUFFIX}`;
  const canonicalUrl = resolveUrl(canonical);
  const imageUrl = resolveUrl(ogImage);

  if (import.meta.env.DEV) {
    if (fullTitle.length > RECOMMENDED_MAX_TITLE_LENGTH) {
      console.warn(
        `[Seo] "${fullTitle}" is ${fullTitle.length} chars — keep titles under ${RECOMMENDED_MAX_TITLE_LENGTH}.`,
      );
    }
    if (description.length > RECOMMENDED_MAX_DESCRIPTION_LENGTH) {
      console.warn(
        `[Seo] description for "${canonical}" is ${description.length} chars — keep under ${RECOMMENDED_MAX_DESCRIPTION_LENGTH}.`,
      );
    }
  }

  return (
    <>
      <title>{fullTitle}</title>
      <meta name="description" content={description} />
      <link rel="canonical" href={canonicalUrl} />
      <meta name="robots" content={noindex ? 'noindex, nofollow' : 'index, follow'} />

      <meta property="og:type" content="website" />
      <meta property="og:site_name" content={SITE_NAME} />
      <meta property="og:title" content={fullTitle} />
      <meta property="og:description" content={description} />
      <meta property="og:url" content={canonicalUrl} />
      <meta property="og:image" content={imageUrl} />

      <meta name="twitter:card" content="summary_large_image" />
      <meta name="twitter:title" content={fullTitle} />
      <meta name="twitter:description" content={description} />
      <meta name="twitter:image" content={imageUrl} />
    </>
  );
}
