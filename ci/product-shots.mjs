/**
 * Capture REAL screenshots of the actual product UI, for use as marketing imagery.
 *
 * WHY THIS EXISTS:
 *   The Stitch designs shipped AI-GENERATED pictures of "our" product. Three had to be
 *   pulled on 2026-09-07: a fake dashboard whose nav read "Escrow Wallet" with a balance
 *   in USD and a teal logo that is not ours; a fake app screen listing live deals with
 *   Nykaa, Zara and Myntra using their real trademarked logos; and an invented UPI
 *   receipt with a named person and a reference number.
 *
 *   A generated picture of the product is a CLAIM. A screenshot of it is evidence, it is
 *   more persuasive, and it cannot drift from what we actually shipped. This script
 *   produces the second kind.
 *
 * HOW IT GETS PAST THE AUTH GATE — no backend required:
 *   In MOCK mode `useAuthGuardState` (src/App.tsx:123) is a synchronous localStorage
 *   read of `brand_token` / `creator_token` — no network call, no onboarding round-trip.
 *   So seeding those two keys before the app boots renders every protected screen,
 *   populated with the sample data `mockOr` substitutes.
 *
 *   We seed via `evaluateOnNewDocument` so the keys exist BEFORE React mounts; setting
 *   them after `goto` is too late and lands you on /brand/login.
 *
 * ⚠️ THIS SCRIPT STARTS ITS OWN DEV SERVER IN MOCK MODE. Do not point it at the normal
 *   dev server. `.env.local` in this repo sets `VITE_API_MODE=live` against a Spring
 *   backend on :8080. With that backend down, `useAuthGuardState` takes the async
 *   `api.auth.bootstrap` path, the fetch is refused, and every protected route redirects
 *   to /brand/login a beat AFTER first paint. The first version of this script captured
 *   that beat and wrote out five completely BLANK PNGs while reporting "OK" — the URL
 *   had not changed yet and the empty body contained no "sign in" text to trip the login
 *   check. Hence both guards below: we own the server (so the mode is known) and we
 *   assert rendered content (so a blank frame can never pass).
 *
 *   A shell `VITE_API_MODE=mock` does override `.env.local` — verified.
 *
 * ⚠️ HONESTY REQUIREMENT — READ BEFORE PUBLISHING ANYTHING THIS PRODUCES:
 *   These are real screens showing SAMPLE data, not a real customer's account. Any page
 *   that publishes one MUST caption it, exactly like the existing hero panels do:
 *       "Illustrative — figures shown are an example, not live data."
 *   The `DemoModeBanner` pill ("Demo data — not a live backend") is KEPT by default so a
 *   careless run produces the honest image. `SC_HIDE_BANNER=1` removes it — only use that
 *   when the consuming page carries the caption above.
 *
 * DEPENDENCIES: puppeteer-core only (already a devDependency, shared with
 *   ci/lighthouse-meera.mjs and ci/mobile-sweep.mjs). Downloads no Chromium.
 *
 * ENV KNOBS:
 *   SC_PORT         port for the mock-mode server it starts  (default 3100)
 *   SC_ORIGIN       attach to an EXISTING origin instead of starting one — you are then
 *                   responsible for it running in mock mode  (default: unset)
 *   SC_OUT          output directory                   (default ./public/product-shots)
 *   SC_SCALE        deviceScaleFactor, 1 or 2          (default 2 — retina)
 *   SC_WIDTH        viewport width                     (default 1440)
 *   SC_HEIGHT       viewport height                    (default 900)
 *   SC_FULLPAGE     "1" => full-page instead of viewport (default off)
 *   SC_HIDE_BANNER  "1" => hide the demo-data pill     (default off — see above)
 *   SC_MIN_TEXT     min rendered chars to accept a shot (default 200)
 *   SC_CHROME_PATH  explicit Chrome binary             (default: auto-detect per-OS)
 *   SC_ROUTES       comma-separated path list override
 *
 * EXIT CODES: 0 = every target captured; 1 = at least one route failed, redirected to a
 *   login screen, or rendered too little to be a real screenshot. Never ship a folder
 *   this script exited 1 on — check what it actually wrote.
 */
import puppeteer from 'puppeteer-core';
import { existsSync, mkdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { spawn } from 'node:child_process';

const PORT = Number(process.env.SC_PORT ?? 3100);
const OWN_SERVER = !process.env.SC_ORIGIN;
const ORIGIN = process.env.SC_ORIGIN || `http://localhost:${PORT}`;
const MIN_TEXT = Number(process.env.SC_MIN_TEXT ?? 200);
const OUT = resolve(process.env.SC_OUT || './public/product-shots');
const SCALE = Number(process.env.SC_SCALE ?? 2);
const WIDTH = Number(process.env.SC_WIDTH ?? 1440);
const HEIGHT = Number(process.env.SC_HEIGHT ?? 900);
const FULLPAGE = process.env.SC_FULLPAGE === '1';
const HIDE_BANNER = process.env.SC_HIDE_BANNER === '1';

/**
 * `clip` is an optional CSS selector. When set, the shot is cropped to that element
 * instead of the whole viewport — which is usually what a marketing page wants (the
 * deal-room panel, not the panel plus half the sidebar).
 */
const DEFAULT_TARGETS = [
  { path: '/brand/dashboard', name: 'brand-dashboard' },
  { path: '/brand/campaigns', name: 'brand-campaigns' },
  { path: '/brand/deals', name: 'brand-deals' },
  { path: '/brand/wallet', name: 'brand-wallet' },
  { path: '/brand/pipeline', name: 'brand-pipeline' },
  { path: '/creator/dashboard', name: 'creator-dashboard' },
  { path: '/creator/wallet', name: 'creator-wallet' },
  { path: '/creator/deals', name: 'creator-deals' },
];

/**
 * Normalise a route from SC_ROUTES.
 *
 * Git Bash on Windows (MSYS) rewrites a leading-slash argument into a Windows path, so
 * `SC_ROUTES=/brand/dashboard` arrives as `C:/Program Files/Git/brand/dashboard` and
 * Chrome rejects it as an invalid URL. Strip any drive/prefix junk back to the last
 * recognisable app segment, and tolerate routes written without a leading slash.
 */
function normaliseRoute(raw) {
  let p = raw.trim().replace(/\\/g, '/');
  const m = p.match(/\/((?:brand|creator|features|blog|admin)\/.*)$/);
  if (m) p = '/' + m[1];
  else if (!p.startsWith('/')) p = '/' + p;
  return p;
}

const TARGETS = process.env.SC_ROUTES
  ? process.env.SC_ROUTES.split(',')
      .map(normaliseRoute)
      .filter((p) => p !== '/')
      .map((p) => ({ path: p, name: p.replace(/^\//, '').replace(/\//g, '-') }))
  : DEFAULT_TARGETS;

function resolveChrome() {
  if (process.env.SC_CHROME_PATH) return process.env.SC_CHROME_PATH;
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
      'No Chrome/Chromium found. Set SC_CHROME_PATH to a Chrome binary. Tried:\n  ' +
        candidates.join('\n  '),
    );
  }
  return found;
}

mkdirSync(OUT, { recursive: true });

/** Start a dev server we KNOW is in mock mode, so no real backend is ever contacted. */
let server = null;
if (OWN_SERVER) {
  server = spawn('npx', ['vite', '--port', String(PORT)], {
    env: { ...process.env, VITE_API_MODE: 'mock' },
    stdio: 'ignore',
    shell: process.platform === 'win32',
  });
  const deadline = Date.now() + 60000;
  for (;;) {
    try {
      const r = await fetch(ORIGIN + '/');
      if (r.ok) break;
    } catch {
      /* not up yet */
    }
    if (Date.now() > deadline) {
      server.kill();
      throw new Error(`mock dev server did not come up on ${ORIGIN} within 60s`);
    }
    await new Promise((r) => setTimeout(r, 500));
  }
}
const shutdown = () => {
  if (server && !server.killed) server.kill();
};
process.on('exit', shutdown);
process.on('SIGINT', () => {
  shutdown();
  process.exit(130);
});

const browser = await puppeteer.launch({
  executablePath: resolveChrome(),
  headless: 'new',
  args: ['--no-sandbox', '--disable-dev-shm-usage'],
});

const page = await browser.newPage();
await page.setViewport({ width: WIDTH, height: HEIGHT, deviceScaleFactor: SCALE });

// Seed BEFORE the app boots. Setting these after goto() is too late — React has already
// read them, decided 'unauthenticated', and redirected to the login route.
await page.evaluateOnNewDocument(() => {
  try {
    localStorage.setItem('brand_token', 'product-shot-token');
    localStorage.setItem('creator_token', 'product-shot-token');
    localStorage.setItem('brand_onboarding_complete', 'true');
  } catch {
    /* private mode / storage disabled — the run will fail loudly at the login check */
  }
});

let fails = 0;
console.log(
  `Product shots — ${ORIGIN} at ${WIDTH}x${HEIGHT} @${SCALE}x` +
    `${FULLPAGE ? ' (full page)' : ''}${HIDE_BANNER ? ' (banner hidden)' : ''}\n` +
    `Output: ${OUT}\n`,
);

for (const t of TARGETS) {
  try {
    // `domcontentloaded`, NOT `networkidle2` — Vite's HMR websocket stays open, so
    // networkidle never fires against a dev server and every goto times out.
    //
    // The generous timeout is deliberate: on a COLD dev server a heavy route
    // (/brand/deals) can take longer than 30s just to fire domcontentloaded while Vite
    // optimises its chunk on demand.
    await page.goto(ORIGIN + t.path, { waitUntil: 'domcontentloaded', timeout: 90000 });

    // Wait for the demo pill rather than sleeping a fixed amount. It is mounted at the
    // app root, so its presence proves BOTH that React finished mounting and that we are
    // in mock mode. A fixed sleep raced the cold-start chunk and produced a half-rendered
    // page with no pill, which the mode check then correctly rejected — but for the wrong
    // reason, hiding a simple timing problem behind a scary-looking "not in mock mode".
    await page
      .waitForSelector('[aria-label="Demo data mode"]', { timeout: 60000 })
      .catch(() => null); // fall through to the explicit checks below for a clear message
    await new Promise((r) => setTimeout(r, 1500)); // entrance animations

    const landed = await page.evaluate(() => {
      const main = document.querySelector('main');
      const text = (main?.innerText || document.body.innerText || '').trim();
      return {
        url: location.pathname,
        textLen: text.length,
        looksLikeLogin: /sign in|log in|welcome back/i.test(text.slice(0, 900)),
        demoPill: !!document.querySelector('[aria-label="Demo data mode"]'),
      };
    });

    // Refuse a login page dressed up as a product shot.
    if (landed.looksLikeLogin || /login/.test(landed.url)) {
      fails++;
      console.log(`FAIL  ${t.path.padEnd(24)} redirected to ${landed.url} — auth seed not honoured`);
      continue;
    }
    // Refuse a blank frame. This is the check that would have caught the five empty
    // PNGs the first version happily reported as "OK".
    if (landed.textLen < MIN_TEXT) {
      fails++;
      console.log(
        `FAIL  ${t.path.padEnd(24)} only ${landed.textLen} chars rendered (min ${MIN_TEXT}) — blank or still loading`,
      );
      continue;
    }
    // The pill proves we are on mock data. If it is missing we may be pointed at a live
    // backend, and these would be screenshots of somebody's real account.
    if (!landed.demoPill) {
      fails++;
      console.log(
        `FAIL  ${t.path.padEnd(24)} no demo-data pill — server may not be in mock mode; refusing to capture real account data`,
      );
      continue;
    }

    if (HIDE_BANNER) {
      await page.evaluate(() => {
        const el = document.querySelector('[aria-label="Demo data mode"]');
        if (el) el.remove();
      });
    }

    const file = `${OUT}/${t.name}.png`;
    if (t.clip) {
      const el = await page.$(t.clip);
      if (!el) throw new Error(`clip selector not found: ${t.clip}`);
      await el.screenshot({ path: file });
    } else {
      await page.screenshot({ path: file, fullPage: FULLPAGE });
    }

    const dims = await page.evaluate(() => ({
      w: document.documentElement.clientWidth,
      h: document.body.scrollHeight,
    }));
    console.log(
      `OK    ${t.path.padEnd(24)} ${dims.w}x${FULLPAGE ? dims.h : HEIGHT} @${SCALE}x  ->  ${t.name}.png`,
    );
  } catch (e) {
    fails++;
    console.log(`ERROR ${t.path.padEnd(24)} ${String(e).slice(0, 120)}`);
  }
}

await browser.close();
const captured = TARGETS.length - fails;
console.log(`\n${captured}/${TARGETS.length} captured`);
if (!HIDE_BANNER && captured > 0) {
  console.log('Note: the "Demo data" pill is present. Re-run with SC_HIDE_BANNER=1 once the');
  console.log('consuming page carries an "Illustrative — not live data" caption.');
}
process.exit(fails ? 1 : 0);
