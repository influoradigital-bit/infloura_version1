/**
 * CI gate: no horizontal overflow on any public route at a 375px viewport.
 *
 * WHY THIS GATE EXISTS (F-0907):
 *   The landing hero rendered its subhead, both CTAs and the entire stat row
 *   OFF-SCREEN on every phone, and shipped that way. Nothing caught it:
 *   `documentElement.scrollWidth` stayed exactly 375, because the offending
 *   column was clipped by an ancestor's `overflow-hidden` rather than
 *   overflowing the document. No scrollbar, no page-level signal, and tsc /
 *   eslint / vite build / a desktop screenshot / code review all pass clean.
 *
 *   The only thing that sees it is a PER-ELEMENT sweep: walk `body *` and flag
 *   any box wider than the viewport, skipping boxes inside a legitimate
 *   `overflow-x: auto` scroller (wide comparison tables are allowed to scroll).
 *   On the pre-fix homepage that returns 39 elements while scrollWidth is 375.
 *
 * ROOT CAUSE IT GUARDS AGAINST:
 *   A grid/flex item defaults to `min-width: auto`, so its min-content sizes
 *   the track. One wide child (the Deal Room card, min-content ~612px) blows
 *   out a single-column grid and drags its sibling column off-screen with it.
 *   The fix is `min-w-0` ON THE GRID ITEM — fixing the deepest overflowing
 *   descendant does nothing, which is how the first fix attempt failed.
 *   `overflow-x-auto` alone never shrinks a box; it only scrolls one already
 *   allowed to be narrower than its content.
 *
 * LAZY IMAGES — do not "simplify" the scroll loop:
 *   Scrolling straight to document.body.scrollHeight in one jump leaves every
 *   `loading="lazy"` image un-requested, so `img.complete` is false and this
 *   gate reports "broken image" on pages that serve fine. It must step through
 *   the page and await in-flight images before measuring. That false positive
 *   fired on 6 routes the first time this ran.
 *
 * DEPENDENCIES: puppeteer-core only (already a devDependency for
 *   ci/lighthouse-meera.mjs). Downloads no Chromium — drives an existing
 *   Chrome/Chromium resolved at runtime, same resolver as the Lighthouse gate.
 *
 * ENV KNOBS:
 *   MS_ORIGIN       origin to hit                    (default http://localhost:3000)
 *   MS_WIDTH        viewport width in px             (default 375)
 *   MS_HEIGHT       viewport height in px            (default 812)
 *   MS_CHROME_PATH  explicit Chrome/Chromium binary  (default: auto-detect per-OS)
 *   MS_ROUTES       comma-separated route override   (default: the list below)
 *
 * EXIT CODES: 0 = every route clean; 1 = at least one route overflows,
 *   has a genuinely broken image, or failed to load.
 */
import puppeteer from 'puppeteer-core';
import { existsSync } from 'node:fs';

const ORIGIN = process.env.MS_ORIGIN || 'http://localhost:3000';
const WIDTH = Number(process.env.MS_WIDTH ?? 375);
const HEIGHT = Number(process.env.MS_HEIGHT ?? 812);

/** Public, logged-out routes. App routes behind auth are out of scope for this gate. */
const DEFAULT_ROUTES = [
  '/',
  '/how-it-works/brands',
  '/how-it-works/creators',
  '/pricing',
  '/features/deal-room',
  '/features/hype',
  '/features/secure-payments',
  '/meera-for-creators',
  '/festival-box',
  '/about',
  '/blog',
  '/contact',
  '/support',
  '/terms',
  '/privacy',
  '/disputes',
];

const ROUTES = process.env.MS_ROUTES
  ? process.env.MS_ROUTES.split(',').map((r) => r.trim()).filter(Boolean)
  : DEFAULT_ROUTES;

function resolveChrome() {
  if (process.env.MS_CHROME_PATH) return process.env.MS_CHROME_PATH;
  const candidates = [
    'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
    'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
    '/usr/bin/google-chrome',
    '/usr/bin/google-chrome-stable',
    '/usr/bin/chromium-browser',
    '/usr/bin/chromium',
    '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  ];
  const found = candidates.find((p) => existsSync(p));
  if (!found) {
    throw new Error(
      'No Chrome/Chromium found. Set MS_CHROME_PATH to a Chrome binary. Tried:\n  ' +
        candidates.join('\n  '),
    );
  }
  return found;
}

const browser = await puppeteer.launch({
  executablePath: resolveChrome(),
  headless: 'new',
  args: ['--no-sandbox', '--disable-dev-shm-usage'],
});

const page = await browser.newPage();
await page.setViewport({
  width: WIDTH,
  height: HEIGHT,
  deviceScaleFactor: 2,
  isMobile: true,
  hasTouch: true,
});

let fails = 0;
console.log(`Mobile sweep — ${ORIGIN} at ${WIDTH}x${HEIGHT}, ${ROUTES.length} routes\n`);

for (const route of ROUTES) {
  try {
    await page.goto(ORIGIN + route, { waitUntil: 'networkidle2', timeout: 45000 });

    // Step through the page so `loading="lazy"` images actually get requested.
    // See the LAZY IMAGES note above before changing this.
    await page.evaluate(async () => {
      const step = window.innerHeight * 0.8;
      for (let y = 0; y < document.body.scrollHeight; y += step) {
        window.scrollTo(0, y);
        await new Promise((r) => setTimeout(r, 220));
      }
      window.scrollTo(0, 0);
    });
    await page.evaluate(() =>
      Promise.all(
        [...document.images]
          .filter((i) => !i.complete)
          .map(
            (i) =>
              new Promise((res) => {
                i.onload = i.onerror = res;
                setTimeout(res, 4000);
              }),
          ),
      ),
    );
    await new Promise((r) => setTimeout(r, 400));

    const res = await page.evaluate(() => {
      const de = document.documentElement;
      const cw = de.clientWidth;
      const inScroller = (el) => {
        let p = el.parentElement;
        while (p && p !== document.body) {
          const ox = getComputedStyle(p).overflowX;
          if (ox === 'auto' || ox === 'scroll') return true;
          p = p.parentElement;
        }
        return false;
      };
      const bad = [...document.querySelectorAll('body *')]
        .filter((e) => {
          const r = e.getBoundingClientRect();
          return r.width > cw + 1 && r.height > 0 && !inScroller(e);
        })
        .map((e) => ({
          tag: e.tagName,
          cls: (e.className?.baseVal ?? e.className ?? '').toString().slice(0, 64),
          w: Math.round(e.getBoundingClientRect().width),
        }));
      const seen = new Set();
      const offenders = bad
        .sort((a, b) => b.w - a.w)
        .filter((x) => {
          const k = x.tag + x.cls + x.w;
          if (seen.has(k)) return false;
          seen.add(k);
          return true;
        });
      return {
        clientW: cw,
        scrollW: de.scrollWidth,
        offenders: offenders.slice(0, 5),
        offenderCount: offenders.length,
        imgs: document.images.length,
        brokenImgs: [...document.images]
          .filter((i) => !i.complete || i.naturalWidth === 0)
          .map((i) => i.getAttribute('src')),
      };
    });

    const ok = res.offenderCount === 0 && res.brokenImgs.length === 0;
    if (!ok) fails++;
    console.log(
      `${ok ? 'PASS' : 'FAIL'}  ${route.padEnd(30)} ` +
        `scrollW=${res.scrollW} clientW=${res.clientW} overflow=${res.offenderCount} ` +
        `imgs=${res.imgs} brokenImgs=${res.brokenImgs.length}`,
    );
    for (const o of res.offenders) console.log(`        ${o.tag} w=${o.w} .${o.cls}`);
    for (const b of res.brokenImgs) console.log(`        BROKEN IMG ${b}`);
  } catch (e) {
    fails++;
    console.log(`ERROR ${route}: ${String(e).slice(0, 140)}`);
  }
}

await browser.close();
console.log(`\n${ROUTES.length - fails}/${ROUTES.length} routes clean at ${WIDTH}px`);
process.exit(fails ? 1 : 0);
