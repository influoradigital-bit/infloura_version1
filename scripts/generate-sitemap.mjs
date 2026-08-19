/**
 * Build-time sitemap + robots generator.
 *
 * WHY THIS REPLACED THE HAND-MAINTAINED public/sitemap.xml
 * -------------------------------------------------------
 * The previous sitemap was a static file that someone had to remember to edit.
 * By the time it was replaced it had drifted in three separate ways at once:
 *
 *   - every `<lastmod>` still said 2026-07-13, months stale, which teaches a
 *     crawler that nothing on the site ever changes and slows re-crawl of pages
 *     that genuinely did;
 *   - it listed 3 of the 6 blog posts, so half the blog was never submitted;
 *   - it listed /features/escrow, a URL that no longer exists.
 *
 * This is exactly the failure F-0308 recorded against scripts/prerender.mjs: a
 * hand-written list of routes compared against itself can never notice what it
 * is missing. The fix is the same one — derive the routes from the same sources
 * the application itself reads, so a page that exists cannot be absent here.
 *
 * SOURCES OF TRUTH
 *   - static marketing routes: INDEXABLE_ROUTES, imported from
 *     scripts/marketing-routes.mjs. scripts/prerender.mjs imports the same
 *     module, so the sitemap can no longer advertise a URL that has no
 *     prerendered HTML behind it.
 *   - blog routes: read from src/content/blog/*.md, the same directory
 *     src/lib/blog/posts.ts globs.
 *   - `lastmod`: the post's own `updatedAt` frontmatter for blog URLs, and the
 *     build date for the static marketing pages.
 *
 * WHAT IS DELIBERATELY EXCLUDED
 *   - /terms, /privacy, /refund-policy, /disputes, /grievance, /kyc, /tds,
 *     /disclosure — all rendered `noindex` (v0 legal drafts pending counsel
 *     review). A URL that is noindex must not be in the sitemap: submitting a
 *     page you then tell the crawler not to index is a contradictory signal and
 *     is reported as an error in Search Console.
 *   - /support — a "coming soon" stub with no content. It was in the old
 *     sitemap at priority 0.5.
 *   - /brand/*, /creator/*, /admin/* — authenticated app zones.
 *   - /:handle public creator portfolios — user-generated, and there is no
 *     build-time list of them. They are discoverable via internal links; add a
 *     dedicated generated sitemap here if that becomes insufficient.
 *
 * Writes dist/sitemap.xml. Run from `postbuild`, before the prerender step.
 */
import { existsSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { INDEXABLE_ROUTES, PRERENDER_ONLY_ROUTES } from './marketing-routes.mjs';

const SITE_URL = 'https://influora.in';
const DIST_DIR = resolve('dist');
const BLOG_CONTENT_DIR = resolve('src/content/blog');

/** Build date, YYYY-MM-DD, used as `lastmod` for the non-blog marketing pages. */
const BUILD_DATE = new Date().toISOString().slice(0, 10);

function readFrontmatterField(raw, field) {
  const match = new RegExp(`^${field}:\\s*"([^"]*)"`, 'm').exec(raw);
  return match ? match[1] : null;
}

function discoverBlogUrls() {
  if (!existsSync(BLOG_CONTENT_DIR)) {
    throw new Error(
      `[sitemap] blog content dir missing: ${BLOG_CONTENT_DIR}. Refusing to emit a ` +
        `sitemap that silently claims the site has no blog.`,
    );
  }

  const files = readdirSync(BLOG_CONTENT_DIR).filter((f) => f.endsWith('.md'));
  if (files.length === 0) {
    throw new Error(`[sitemap] no .md posts found in ${BLOG_CONTENT_DIR}`);
  }

  return files
    .map((file) => {
      const raw = readFileSync(resolve(BLOG_CONTENT_DIR, file), 'utf8');
      const slug = readFrontmatterField(raw, 'slug');
      const fileSlug = file.replace(/\.md$/, '');

      // The route is built from the FILE name (that is what src/lib/blog/posts.ts
      // and scripts/prerender.mjs both key off), so a frontmatter `slug` that
      // disagrees with it would put a URL in the sitemap that 404s. Fail loudly.
      if (slug && slug !== fileSlug) {
        throw new Error(
          `[sitemap] ${file}: frontmatter slug "${slug}" does not match the filename ` +
            `"${fileSlug}". The route is derived from the filename, so this would ` +
            `publish a URL that does not resolve. Rename the file or fix the slug.`,
        );
      }

      return {
        path: `/blog/${fileSlug}`,
        changefreq: 'monthly',
        priority: '0.7',
        lastmod:
          readFrontmatterField(raw, 'updatedAt') ||
          readFrontmatterField(raw, 'publishedAt') ||
          BUILD_DATE,
      };
    })
    .sort((a, b) => a.path.localeCompare(b.path));
}

function toUrlEntry({ path, changefreq, priority, lastmod }) {
  return [
    '  <url>',
    `    <loc>${SITE_URL}${path}</loc>`,
    `    <lastmod>${lastmod || BUILD_DATE}</lastmod>`,
    `    <changefreq>${changefreq}</changefreq>`,
    `    <priority>${priority}</priority>`,
    '  </url>',
  ].join('\n');
}

function main() {
  if (!existsSync(DIST_DIR)) {
    throw new Error(`[sitemap] ${DIST_DIR} does not exist — run the build first.`);
  }

  const urls = [...INDEXABLE_ROUTES, ...discoverBlogUrls()];

  const seen = new Set();
  for (const { path } of urls) {
    if (seen.has(path)) {
      throw new Error(`[sitemap] duplicate URL ${path} — a duplicated entry is a crawl error.`);
    }
    seen.add(path);
  }

  const xml = [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">',
    ...urls.map(toUrlEntry),
    '</urlset>',
    '',
  ].join('\n');

  writeFileSync(resolve(DIST_DIR, 'sitemap.xml'), xml, 'utf8');

  console.log(`[sitemap] wrote ${urls.length} URLs to dist/sitemap.xml`);
  for (const { path, reason } of PRERENDER_ONLY_ROUTES) {
    console.log(`[sitemap] excluded ${path} — ${reason}`);
  }
  console.log(
    '[sitemap] NOT LISTED: /brand/*, /creator/*, /admin/* (authenticated) and ' +
      '/:handle creator portfolios (user-generated, no build-time list).',
  );
}

main();
