/**
 * The single build-time list of public marketing routes.
 *
 * WHY IT EXISTS: scripts/prerender.mjs and scripts/generate-sitemap.mjs both
 * need to know which URLs are public marketing pages, and they need to agree.
 * When each kept its own copy, they drifted — the sitemap advertised
 * /features/escrow after it had been renamed, while prerender had no entry for
 * the page that replaced it. A URL in the sitemap with no prerendered HTML is
 * submitted to crawlers as a real page and then served an empty SPA shell.
 *
 * Both scripts import from here, so that divergence is no longer possible.
 *
 * Blog routes are NOT listed: they are discovered from src/content/blog/*.md by
 * each script, for the reason recorded in F-0308 — a hand-written list of posts
 * cannot notice a post that was added after it was written.
 */

/**
 * Indexable marketing pages. `changefreq`/`priority` are sitemap crawl hints;
 * they are ignored by the prerenderer, which only reads `path`.
 */
export const INDEXABLE_ROUTES = [
  { path: '/', changefreq: 'weekly', priority: '1.0' },
  { path: '/pricing', changefreq: 'monthly', priority: '0.9' },
  { path: '/how-it-works/brands', changefreq: 'monthly', priority: '0.9' },
  { path: '/how-it-works/creators', changefreq: 'monthly', priority: '0.9' },
  { path: '/features/secure-payments', changefreq: 'monthly', priority: '0.8' },
  { path: '/features/deal-room', changefreq: 'monthly', priority: '0.8' },
  { path: '/features/hype', changefreq: 'monthly', priority: '0.8' },
  { path: '/blog', changefreq: 'weekly', priority: '0.7' },
  { path: '/about', changefreq: 'monthly', priority: '0.6' },
  { path: '/contact', changefreq: 'monthly', priority: '0.5' },
];

/**
 * Public pages that are prerendered (so a non-JS crawler and a shared link both
 * get real HTML) but deliberately kept OUT of the sitemap.
 *
 * Submitting a URL in a sitemap while also serving it `noindex` is a
 * contradictory instruction and is reported as an error in Search Console — so
 * these two lists must stay separate rather than being one list with a flag
 * everyone forgets to check.
 */
export const PRERENDER_ONLY_ROUTES = [
  { path: '/terms', reason: 'noindex — v0 legal draft pending counsel review' },
  { path: '/privacy', reason: 'noindex — v0 legal draft pending counsel review' },
  { path: '/support', reason: 'placeholder stub, no content yet' },
];

/** Every route the prerenderer should snapshot (blog posts are added separately). */
export const PRERENDER_ROUTES = [
  ...INDEXABLE_ROUTES.map((r) => r.path),
  ...PRERENDER_ONLY_ROUTES.map((r) => r.path),
];

/**
 * Retired URLs that must keep resolving.
 *
 * These are live in the wild — crawled, linked from old posts, sitting in the
 * index — so letting them 404 would discard the ranking signal they carry
 * instead of forwarding it. The edge 301s live in public/_redirects; the
 * client-side twins live in src/App.tsx. This list is what the redirect gate
 * checks both of those against.
 */
export const PERMANENT_REDIRECTS = [
  { from: '/features/escrow', to: '/features/secure-payments' },
  {
    from: '/blog/what-is-escrow-in-influencer-marketing',
    to: '/blog/what-is-payment-protection-in-influencer-marketing',
  },
];
