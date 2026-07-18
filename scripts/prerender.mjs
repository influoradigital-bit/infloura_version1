/**
 * Post-build prerender snapshot for GEO/AEO (GEO-TECHNICAL-AUDIT.md C1,
 * SHARED_CONTEXT.md "FROM Aditya -> Vikram | RR7 prerender").
 *
 * WHY THIS APPROACH AND NOT RR7 FRAMEWORK-MODE `prerender`:
 *   The audit assumed the repo was "already on RR7, no migration needed" for
 *   framework-mode `prerender` (react-router.config.ts + ssr:false). That's
 *   incorrect — `package.json` has `react-router-dom` (library mode) with
 *   `BrowserRouter`/`<Routes>` in src/App.tsx mounted via `createRoot` in
 *   src/main.tsx. There is no `@react-router/dev` dependency anywhere in the
 *   tree. Migrating ~60 routes (including protected /brand/*, /creator/*,
 *   /admin/* zones) to framework mode to get prerendering on 16 marketing
 *   routes is exactly the invasive, destabilizing move the task brief warned
 *   against. This script is the non-invasive alternative: it changes zero
 *   routing code. The SPA is untouched; only `dist/` gains extra static
 *   `index.html` files that the client-side bundle overwrites on hydration
 *   for real browsers, while non-JS crawlers (GPTBot, ClaudeBot,
 *   PerplexityBot, CCBot, Bingbot) get real rendered HTML.
 *
 * WHY puppeteer-core (NOT Playwright):
 *   The task brief assumed Playwright was already a dev dependency. It is
 *   not — `@playwright/test` is absent from package.json, and the only
 *   trace in node_modules is an orphaned partial install (empty
 *   `node_modules/@playwright/` with a stray temp dir; no `test/` package).
 *   `puppeteer-core` IS a real, installed devDependency (already approved by
 *   Priya, see wiki/tech/approved-deps.md) and this exact
 *   resolve-local-Chrome + puppeteer-core pattern is already established in
 *   `ci/lighthouse-meera.mjs`. Reusing it means zero new installs.
 *
 * WHAT THIS DOES:
 *   1. Serves the just-built `dist/` on a local port (spawns `vite preview`).
 *   2. Launches local Chrome/Chromium headless via puppeteer-core.
 *   3. Copies the pristine, pre-prerender `dist/index.html` to
 *      `dist/app-shell.html` — this becomes the SPA fallback target (see
 *      `public/_redirects`) for every route that ISN'T one of the 16
 *      marketing routes below: `/brand/*`, `/creator/*`, `/admin/*`, and
 *      the `/:handle` public-portfolio catch-all keep rendering purely
 *      client-side, exactly as before.
 *   4. Visits each marketing route, waits for the SPA to render (Seo
 *      component's <title>/<meta> are React-19-hoisted into <head> — wait
 *      for the title to move off the raw index.html default before
 *      snapshotting), then de-dupes any singleton `<head>` tag (title,
 *      description, canonical, OG/Twitter) that exists both statically in
 *      the raw HTML and as a React-rendered override.
 *   5. Writes the fully rendered, de-duped `<html>` to
 *      `dist/<route>/index.html` (root "/" overwrites `dist/index.html`
 *      directly — a real physical file always wins over the `_redirects`
 *      wildcard on Netlify/Vercel/Cloudflare Pages, so this is safe).
 *   6. Leaves every other file in `dist/` alone.
 *
 * ENV KNOBS (mirrors ci/lighthouse-meera.mjs conventions):
 *   PRERENDER_ORIGIN       origin to hit                (default http://localhost:4174)
 *   PRERENDER_CHROME_PATH  explicit Chrome/Chromium bin  (default: auto-detect per-OS)
 *   PRERENDER_MANAGE_SERVER "1" => spawn+own `vite preview` (default "1")
 *
 * EXIT CODES: 0 = all routes snapshotted; 1 = any route failed.
 */
import puppeteer from 'puppeteer-core';
import { JSDOM } from 'jsdom';
import { spawn } from 'node:child_process';
import { existsSync, mkdirSync, writeFileSync, readFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';

const DIST_DIR = resolve('dist');
const ORIGIN = process.env.PRERENDER_ORIGIN || 'http://localhost:4174';
const PREVIEW_PORT = Number(new URL(ORIGIN).port || 4174);
const MANAGE_SERVER = process.env.PRERENDER_MANAGE_SERVER !== '0';

// The 16 marketing routes from GEO-TECHNICAL-AUDIT.md / SHARED_CONTEXT.md.
// Everything NOT in this list (/brand/*, /creator/*, /admin/*, /:handle
// public portfolios, /terms /privacy /support placeholder shells — see
// GEO-TECHNICAL-AUDIT.md H3, tracked separately) stays pure client-rendered.
const MARKETING_ROUTES = [
  '/',
  '/pricing',
  '/about',
  '/contact',
  '/how-it-works/brands',
  '/how-it-works/creators',
  '/features/escrow',
  '/features/deal-room',
  '/features/hype',
  '/blog',
  '/blog/what-is-escrow-in-influencer-marketing',
  '/blog/how-to-pay-influencers-safely-india-2026',
  '/blog/micro-influencer-pricing-guide-india-2026',
  '/terms',
  '/privacy',
  '/support',
];

/** Resolve a Chrome/Chromium binary without downloading one (same list as ci/lighthouse-meera.mjs). */
function resolveChrome() {
  if (process.env.PRERENDER_CHROME_PATH) return process.env.PRERENDER_CHROME_PATH;
  const candidates = [
    'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
    'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
    'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
    '/usr/bin/google-chrome',
    '/usr/bin/google-chrome-stable',
    '/usr/bin/chromium-browser',
    '/usr/bin/chromium',
    '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  ];
  const found = candidates.find((p) => existsSync(p));
  if (!found) {
    throw new Error(
      'No Chrome/Chromium found. Set PRERENDER_CHROME_PATH to a Chrome binary. Tried:\n  ' +
        candidates.join('\n  '),
    );
  }
  return found;
}

async function waitForServer(url, timeoutMs = 60_000) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    try {
      const res = await fetch(url, { redirect: 'manual' });
      if (res.status > 0) return;
    } catch {
      /* not up yet */
    }
    if (Date.now() > deadline) {
      throw new Error(`Server at ${url} did not come up within ${timeoutMs}ms`);
    }
    await new Promise((r) => setTimeout(r, 500));
  }
}

async function maybeStartServer() {
  if (!MANAGE_SERVER) {
    await waitForServer(ORIGIN);
    return () => {};
  }
  if (!existsSync(DIST_DIR)) {
    throw new Error(`dist/ not found at ${DIST_DIR} — run "vite build" before prerender.mjs.`);
  }
  const npmCmd = process.platform === 'win32' ? 'npm.cmd' : 'npm';
  const child = spawn(
    npmCmd,
    ['run', 'preview', '--', '--port', String(PREVIEW_PORT), '--strictPort'],
    { stdio: 'pipe', shell: process.platform === 'win32' },
  );
  child.stdout?.on('data', () => {});
  child.stderr?.on('data', () => {});

  // On Windows, `npm.cmd` runs under a shell wrapper — killing the spawned
  // PID alone leaves the actual `vite preview` node process (its
  // grandchild) running and holding the port. `taskkill /T` kills the whole
  // process tree. On POSIX, plain child.kill() is sufficient.
  const killTree = () => {
    if (process.platform === 'win32' && child.pid) {
      spawn('taskkill', ['/pid', String(child.pid), '/T', '/F'], { stdio: 'ignore' });
    } else {
      try {
        child.kill();
      } catch {
        /* already gone */
      }
    }
  };

  try {
    await waitForServer(ORIGIN);
  } catch (e) {
    killTree();
    throw e;
  }
  return killTree;
}

/** "/how-it-works/brands" -> dist/how-it-works/brands/index.html; "/" -> dist/index.html */
function distPathFor(route) {
  if (route === '/') return resolve(DIST_DIR, 'index.html');
  const clean = route.replace(/^\/+/, '').replace(/\/+$/, '');
  return resolve(DIST_DIR, clean, 'index.html');
}

async function snapshotRoute(page, route, rawTitle) {
  const url = `${ORIGIN}${route}`;

  await page.goto(url, { waitUntil: 'networkidle0', timeout: 30_000 });

  // React 19 hoists <title>/<meta> from the Seo component into <head> once
  // the page component has rendered. Wait for the title to diverge from the
  // pre-render default (or just settle) so we don't snapshot a blank shell.
  await page
    .waitForFunction(
      (fallback) => document.title && document.title !== fallback,
      { timeout: 10_000 },
      rawTitle,
    )
    .catch(() => {
      // Some pages may legitimately keep a similar title — not fatal, the
      // body-content check below is the real signal.
    });

  // Make sure real body text landed (h1/p/etc, not just the empty root or
  // the static <noscript>-era fallback).
  await page.waitForFunction(
    () => {
      const root = document.getElementById('root');
      return !!root && root.textContent && root.textContent.trim().length > 40;
    },
    { timeout: 15_000 },
  );

  const html = await page.evaluate(() => '<!doctype html>\n' + document.documentElement.outerHTML);
  return html;
}

/**
 * Keys for the singleton `<head>` tags that exist in BOTH the static
 * `index.html` (unconditional, hard-coded) and every page's `<Seo>`
 * component (React-19-hoisted). React only manages elements it renders
 * itself — it never removes the static ones already sitting in the served
 * HTML — so a naive snapshot ends up with two `<title>` elements, two
 * `<meta name="description">`, two `og:title`/`canonical`/etc. Browsers are
 * forgiving (JS-driven `document.title` "wins" via live DOM state), but a
 * raw-HTML crawler parsing top-to-bottom has no such guarantee — and per the
 * WHATWG spec a document's title is defined as the FIRST `<title>` in tree
 * order, so a stray duplicate is a real correctness risk, not just noise.
 */
const DEDUPE_SELECTORS = [
  { selector: 'title', valueOf: (el) => el.textContent },
  { selector: 'meta[name="description"]', valueOf: (el) => el.getAttribute('content') },
  { selector: 'link[rel="canonical"]', valueOf: (el) => el.getAttribute('href') },
  { selector: 'meta[name="robots"]', valueOf: (el) => el.getAttribute('content') },
  { selector: 'meta[property="og:type"]', valueOf: (el) => el.getAttribute('content') },
  { selector: 'meta[property="og:site_name"]', valueOf: (el) => el.getAttribute('content') },
  { selector: 'meta[property="og:title"]', valueOf: (el) => el.getAttribute('content') },
  { selector: 'meta[property="og:description"]', valueOf: (el) => el.getAttribute('content') },
  { selector: 'meta[property="og:url"]', valueOf: (el) => el.getAttribute('content') },
  { selector: 'meta[property="og:image"]', valueOf: (el) => el.getAttribute('content') },
  { selector: 'meta[name="twitter:card"]', valueOf: (el) => el.getAttribute('content') },
  { selector: 'meta[name="twitter:title"]', valueOf: (el) => el.getAttribute('content') },
  { selector: 'meta[name="twitter:description"]', valueOf: (el) => el.getAttribute('content') },
  { selector: 'meta[name="twitter:image"]', valueOf: (el) => el.getAttribute('content') },
];

/**
 * Build a { selector -> staticValue } map from the pristine, unmodified
 * `dist/index.html` (before any snapshot has been written). This is the
 * "generic fallback" value each selector had before a page-specific `<Seo>`
 * rendered on top of it.
 */
function buildStaticDefaults(originalIndexHtml) {
  const dom = new JSDOM(originalIndexHtml);
  const defaults = new Map();
  for (const { selector, valueOf } of DEDUPE_SELECTORS) {
    const el = dom.window.document.querySelector(selector);
    if (el) defaults.set(selector, valueOf(el));
  }
  return defaults;
}

/**
 * For every dedupe-prone selector, if more than one matching element exists:
 * prefer the element(s) whose value differs from the known static default
 * (i.e. the page-specific one the `<Seo>` component rendered) and drop the
 * rest. If every match equals the static default (e.g. /terms, /privacy,
 * /support — no `<Seo>` component there, tracked separately per
 * GEO-TECHNICAL-AUDIT.md H3), collapse to a single instance so the output
 * is still valid HTML with exactly one of each singleton tag.
 */
function dedupeHead(html, staticDefaults) {
  const dom = new JSDOM(html);
  const { document } = dom.window;

  for (const { selector, valueOf } of DEDUPE_SELECTORS) {
    const matches = Array.from(document.querySelectorAll(selector));
    if (matches.length <= 1) continue;

    const staticValue = staticDefaults.get(selector);
    const pageSpecific = matches.filter((el) => valueOf(el) !== staticValue);

    const keep = pageSpecific.length > 0 ? pageSpecific[pageSpecific.length - 1] : matches[0];
    for (const el of matches) {
      if (el !== keep) el.remove();
    }
  }

  return '<!doctype html>\n' + dom.window.document.documentElement.outerHTML;
}

async function main() {
  const CHROME = resolveChrome();
  let stopServer = () => {};
  let browser;
  let staticDefaults = new Map();
  const failures = [];
  const captured = [];

  // IMPORTANT: capture every route's HTML into memory FIRST and only write
  // to disk in a second pass, after the whole browser session is done.
  // vite preview's SPA fallback serves dist/index.html verbatim for any
  // route without its own physical file. If we wrote dist/index.html (the
  // "/" route's output) mid-loop, every route snapshotted afterwards would
  // load that already-hydrated markup as its starting DOM instead of the
  // pristine build output — and because React 19 only cleans up <title>/
  // <meta> tags it created *in that render*, not ones already baked into
  // the served HTML from a prior snapshot, titles and OG/meta tags would
  // stack up across routes (verified: an early version of this script
  // produced 3 stacked <title> tags on /pricing). Keeping dist/ untouched
  // until every page.goto() has run avoids the whole class of bug.
  try {
    stopServer = await maybeStartServer();

    // Read once, before any snapshot writes happen — dist/index.html is
    // guaranteed untouched at this point (writes are deferred to the second
    // pass below), so a single read is both correct and avoids N redundant
    // re-reads of a file that never changes during the loop.
    const originalIndexHtml = readFileSync(resolve(DIST_DIR, 'index.html'), 'utf8');
    const rawTitleMatch = /<title>([^<]*)<\/title>/.exec(originalIndexHtml);
    const rawTitle = rawTitleMatch ? rawTitleMatch[1] : '';
    staticDefaults = buildStaticDefaults(originalIndexHtml);

    // Preserve the pristine, content-free bootstrap shell as the SPA
    // fallback target BEFORE dist/index.html gets overwritten with the "/"
    // route's prerendered content below. public/_redirects points its
    // wildcard rule at this file so private zones (/brand/*, /creator/*,
    // /admin/*) and the /:handle catch-all keep rendering purely
    // client-side instead of flashing prerendered marketing content.
    writeFileSync(resolve(DIST_DIR, 'app-shell.html'), originalIndexHtml, 'utf8');
    console.log('  wrote dist/app-shell.html (pristine SPA fallback for private zones)');

    browser = await puppeteer.launch({
      executablePath: CHROME,
      headless: true,
      args: ['--no-sandbox', '--disable-gpu', '--disable-dev-shm-usage'],
    });

    const page = await browser.newPage();
    await page.setUserAgent('Mozilla/5.0 (compatible; InfluoraPrerender/1.0)');

    for (const route of MARKETING_ROUTES) {
      try {
        const html = await snapshotRoute(page, route, rawTitle);
        captured.push({ route, html });
        console.log(`  captured ${route}`);
      } catch (err) {
        failures.push({ route, error: err instanceof Error ? err.message : String(err) });
        console.error(`  FAIL ${route.padEnd(45)} -> ${err instanceof Error ? err.message : err}`);
      }
    }
  } finally {
    if (browser) await browser.close();
    stopServer();
  }

  // Second pass: now that the server is stopped and every snapshot is safely
  // in memory, write them all to disk.
  const results = [];
  for (const { route, html } of captured) {
    const cleaned = dedupeHead(html, staticDefaults);
    const outPath = distPathFor(route);
    mkdirSync(dirname(outPath), { recursive: true });
    writeFileSync(outPath, cleaned, 'utf8');
    results.push({ route, outPath, bytes: Buffer.byteLength(cleaned) });
    console.log(`  ok   ${route.padEnd(45)} -> ${outPath.replace(DIST_DIR, 'dist')} (${Buffer.byteLength(cleaned)} bytes)`);
  }

  console.log(`\n=== PRERENDER: ${results.length}/${MARKETING_ROUTES.length} routes snapshotted ===`);
  if (failures.length) {
    console.error(`\n${failures.length} route(s) failed:`);
    for (const f of failures) console.error(`  - ${f.route}: ${f.error}`);
    process.exitCode = 1;
  } else {
    console.log('All marketing routes prerendered successfully.');
  }
}

main().catch((err) => {
  console.error('[prerender] fatal:', err);
  process.exitCode = 1;
});
