/**
 * Generates public/og-image.png — the 1200x630 social/AI preview card.
 *
 * WHY THIS EXISTS
 * ---------------
 * index.html and every page rendered through src/lib/seo/Seo.tsx have always
 * pointed `og:image` and `twitter:image` at https://influora.in/og-image.png.
 * That file did not exist. public/og-image-placeholder.txt recorded the gap as a
 * TODO and it stayed open, so in the meantime EVERY share of the site — WhatsApp,
 * LinkedIn, Slack, X, and the preview cards AI search surfaces render beside a
 * citation — resolved a 404 and fell back to a bare grey link.
 *
 * That is a conversion problem disguised as a missing asset: a link with a card
 * is dramatically more clickable than a naked URL, and WhatsApp is the single
 * biggest sharing surface for this audience.
 *
 * WHY GENERATED RATHER THAN DESIGNED
 * ----------------------------------
 * A designed card from Zara is still the better artifact and should replace this
 * one when it lands. This exists because "no image at all" is strictly worse than
 * "an on-brand typographic card", and the TODO had already been open long enough
 * to ship. Everything here is brand tokens from src/app/globals.css and the
 * approved lockup at public/brand/logo-lockup-transparent.png — nothing invented,
 * and no logo geometry is redrawn in this file.
 *
 * WHY puppeteer-core
 * ------------------
 * Same reasoning, same helper shape, as scripts/prerender.mjs and
 * ci/lighthouse-meera.mjs: puppeteer-core is an approved, already-installed
 * devDependency (wiki/tech/approved-deps.md) and we deliberately do not bundle a
 * Chromium download. The resolver below mirrors prerender.mjs's.
 *
 * This is NOT wired into the build. The PNG is committed, so a normal build
 * needs no browser. Re-run by hand only when the card design changes:
 *
 *     node scripts/generate-og-image.mjs
 *
 * ENV: OG_CHROME_PATH / PUPPETEER_EXECUTABLE_PATH / CHROME_PATH override the
 * browser binary, exactly as in prerender.mjs.
 */
import puppeteer from 'puppeteer-core';
import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

const OUT = resolve('public/og-image.png');
const WIDTH = 1200;
const HEIGHT = 630;

/**
 * The brand lockup, inlined as a data URI.
 *
 * page.setContent() gives the document an `about:blank` base URL, so a relative
 * or file:// <img src> is not reliably resolvable from the inline HTML — and a
 * silently-broken <img> would ship a card with no logo on it at all. Reading the
 * bytes here and embedding them makes the template self-contained and makes a
 * missing asset a loud failure at startup instead of a blank rectangle in the
 * PNG.
 *
 * public/brand/logo-lockup-transparent.png is the approved 451x83 lockup
 * (mark + wordmark, transparent background). Do not regenerate it here.
 */
const LOCKUP_PATH = resolve('public/brand/logo-lockup-transparent.png');
const LOCKUP_W = 451;
const LOCKUP_H = 83;

function lockupDataUri() {
  if (!existsSync(LOCKUP_PATH)) {
    throw new Error(
      `Brand lockup not found at ${LOCKUP_PATH}. The OG card cannot be generated without it.`,
    );
  }
  return `data:image/png;base64,${readFileSync(LOCKUP_PATH).toString('base64')}`;
}

function resolveChrome() {
  const envPath =
    process.env.OG_CHROME_PATH ||
    process.env.PUPPETEER_EXECUTABLE_PATH ||
    process.env.CHROME_PATH;
  if (envPath) {
    if (existsSync(envPath)) return envPath;
    throw new Error(`Chrome path "${envPath}" is set but no file exists there.`);
  }

  const PF = process.env['ProgramFiles'] || 'C:\\Program Files';
  const PFx86 = process.env['ProgramFiles(x86)'] || 'C:\\Program Files (x86)';
  const LOCAL = process.env['LOCALAPPDATA'] || '';
  const winCandidates = [
    `${PF}\\Google\\Chrome\\Application\\chrome.exe`,
    `${PFx86}\\Google\\Chrome\\Application\\chrome.exe`,
    LOCAL ? `${LOCAL}\\Google\\Chrome\\Application\\chrome.exe` : '',
    `${PF}\\Microsoft\\Edge\\Application\\msedge.exe`,
    `${PFx86}\\Microsoft\\Edge\\Application\\msedge.exe`,
  ];
  const nixCandidates = [
    '/usr/bin/google-chrome',
    '/usr/bin/google-chrome-stable',
    '/usr/bin/chromium-browser',
    '/usr/bin/chromium',
    '/snap/bin/chromium',
    '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    '/Applications/Chromium.app/Contents/MacOS/Chromium',
  ];
  const candidates = (process.platform === 'win32' ? winCandidates : nixCandidates).filter(Boolean);
  const found = candidates.find((p) => existsSync(p));
  if (found) return found;

  try {
    if (process.platform === 'win32') {
      for (const exe of ['chrome.exe', 'msedge.exe']) {
        const out = execFileSync('where', [exe], { encoding: 'utf8' }).split(/\r?\n/)[0]?.trim();
        if (out && existsSync(out)) return out;
      }
    } else {
      for (const exe of ['google-chrome', 'chromium', 'chromium-browser']) {
        const out = execFileSync('command', ['-v', exe], { encoding: 'utf8', shell: true }).trim();
        if (out && existsSync(out)) return out;
      }
    }
  } catch {
    /* fall through */
  }

  throw new Error(
    'No Chrome/Chromium/Edge binary found. Set OG_CHROME_PATH to one. Tried:\n  ' +
      candidates.join('\n  '),
  );
}

// Brand tokens copied from src/app/globals.css (:root). The logo is the
// approved lockup asset, embedded as a data URI (see lockupDataUri above) —
// nothing about the mark is redrawn or re-coloured here.
const buildHtml = (lockupSrc) => `<!doctype html>
<html>
<head><meta charset="utf-8" />
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  body {
    width: ${WIDTH}px; height: ${HEIGHT}px;
    background: #faf9fd;
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
    color: #221e35;
    position: relative;
    overflow: hidden;
  }
  /* Brand-purple wash, bottom-right, so the card reads as Influora at thumbnail size. */
  .glow {
    position: absolute; right: -160px; bottom: -220px;
    width: 720px; height: 720px; border-radius: 50%;
    background: radial-gradient(circle, rgba(109,90,230,0.22) 0%, rgba(109,90,230,0) 70%);
  }
  .rule { position: absolute; left: 0; top: 0; width: 100%; height: 10px; background: #6d5ae6; }
  .wrap { position: relative; padding: 72px 80px; height: 100%; display: flex; flex-direction: column; }
  /* The lockup already contains the wordmark, so there is no separate
     "Influora" text next to it — that would be a double wordmark, and the
     text version would be a system-font approximation sitting beside the
     real one. Rendered at its exact 451:83 intrinsic ratio; no dark plate
     behind it, because the mark is orchid on transparent and reads fine on
     this light ground. */
  .brand { display: flex; align-items: center; }
  .brand img { display: block; width: ${LOCKUP_W * 0.86}px; height: ${LOCKUP_H * 0.86}px; }
  h1 { margin-top: auto; font-size: 68px; line-height: 1.08; font-weight: 700; letter-spacing: -0.03em; max-width: 940px; }
  p.sub { margin-top: 24px; font-size: 29px; line-height: 1.4; color: #67617d; max-width: 900px; }
  ul { margin-top: auto; padding-top: 44px; display: flex; gap: 40px; list-style: none; }
  li { font-size: 22px; font-weight: 600; color: #4c3bc2; display: flex; align-items: center; gap: 10px; }
  .dot { width: 9px; height: 9px; border-radius: 50%; background: #6d5ae6; }
</style>
</head>
<body>
  <div class="rule"></div>
  <div class="glow"></div>
  <div class="wrap">
    <div class="brand">
      <img src="${lockupSrc}" alt="Influora" />
    </div>

    <h1>Influencer marketing for India, without the payment risk</h1>
    <p class="sub">Hire verified creators, agree terms in one Deal Room, and pay only after you approve the work.</p>

    <ul>
      <li><span class="dot"></span>Verified creators</li>
      <li><span class="dot"></span>Contracts built in</li>
      <li><span class="dot"></span>Paid on approval</li>
      <!-- Wording matches the meta description shipped in index.html and
           src/pages/landing.tsx verbatim: the card and that description are
           rendered side by side in a share preview, so they must not disagree.
           Per Swapnil's ruling this claim is replaced with what is true, not
           stripped — the platform records TDS on the invoice, it does not
           file or remit it. -->
      <li><span class="dot"></span>TDS shown on invoices</li>
    </ul>
  </div>
</body>
</html>`;

async function main() {
  const lockupSrc = lockupDataUri();
  const executablePath = resolveChrome();
  const browser = await puppeteer.launch({
    executablePath,
    headless: true,
    args: ['--no-sandbox', '--disable-dev-shm-usage', '--font-render-hinting=none'],
  });

  try {
    const page = await browser.newPage();
    await page.setViewport({ width: WIDTH, height: HEIGHT, deviceScaleFactor: 1 });
    await page.setContent(buildHtml(lockupSrc), { waitUntil: 'load' });
    // Let webfont fallback metrics settle before the shot.
    await page.evaluate(() => document.fonts.ready);
    // Do not screenshot a half-decoded logo. 'load' should already cover the
    // data URI, but decode() is cheap and a logo-less card is expensive.
    await page.evaluate(() =>
      Promise.all(Array.from(document.images, (img) => img.decode().catch(() => {}))),
    );
    // Fail loudly rather than shipping a card with a broken image box on it.
    const logoOk = await page.evaluate(() => {
      const img = document.querySelector('.brand img');
      return Boolean(img && img.complete && img.naturalWidth > 0);
    });
    if (!logoOk) throw new Error('Brand lockup failed to render in the page — refusing to write a logo-less card.');
    const buffer = await page.screenshot({ type: 'png' });
    writeFileSync(OUT, buffer);
    console.log(`[og-image] wrote ${OUT} (${WIDTH}x${HEIGHT}, ${buffer.length} bytes)`);
  } finally {
    await browser.close();
  }
}

main().catch((err) => {
  console.error('[og-image] FAILED:', err.message);
  process.exit(1);
});
