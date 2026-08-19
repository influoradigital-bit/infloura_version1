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
 * to ship. Everything here is brand tokens from src/app/globals.css and the mark
 * from public/icon.svg — nothing invented.
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
import { existsSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

const OUT = resolve('public/og-image.png');
const WIDTH = 1200;
const HEIGHT = 630;

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

// Brand tokens copied from src/app/globals.css (:root). The logo path data is
// lifted verbatim from public/icon.svg.
const HTML = `<!doctype html>
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
  .brand { display: flex; align-items: center; gap: 18px; }
  .mark { width: 60px; height: 60px; border-radius: 14px; background: #221e35; display: flex; align-items: center; justify-content: center; }
  .wordmark { font-size: 34px; font-weight: 700; letter-spacing: -0.02em; }
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
      <div class="mark">
        <svg width="40" height="40" viewBox="0 0 180 180" xmlns="http://www.w3.org/2000/svg">
          <g style="transform: scale(95%); transform-origin: center">
            <path fill="#ffffff" d="M101.141 53H136.632C151.023 53 162.689 64.6662 162.689 79.0573V112.904H148.112V79.0573C148.112 78.7105 148.098 78.3662 148.072 78.0251L112.581 112.898C112.701 112.902 112.821 112.904 112.941 112.904H148.112V126.672H112.941C98.5504 126.672 86.5638 114.891 86.5638 100.5V66.7434H101.141V100.5C101.141 101.15 101.191 101.792 101.289 102.422L137.56 66.7816C137.255 66.7563 136.945 66.7434 136.632 66.7434H101.141V53Z" />
            <path fill="#ffffff" d="M65.2926 124.136L14 66.7372H34.6355L64.7495 100.436V66.7372H80.1365V118.47C80.1365 126.278 70.4953 129.958 65.2926 124.136Z" />
          </g>
        </svg>
      </div>
      <span class="wordmark">Influora</span>
    </div>

    <h1>Influencer marketing for India, without the payment risk</h1>
    <p class="sub">Hire verified creators, agree terms in one Deal Room, and pay only after you approve the work.</p>

    <ul>
      <li><span class="dot"></span>Verified creators</li>
      <li><span class="dot"></span>Contracts built in</li>
      <li><span class="dot"></span>Paid on approval</li>
      <li><span class="dot"></span>TDS handled</li>
    </ul>
  </div>
</body>
</html>`;

async function main() {
  const executablePath = resolveChrome();
  const browser = await puppeteer.launch({
    executablePath,
    headless: true,
    args: ['--no-sandbox', '--disable-dev-shm-usage', '--font-render-hinting=none'],
  });

  try {
    const page = await browser.newPage();
    await page.setViewport({ width: WIDTH, height: HEIGHT, deviceScaleFactor: 1 });
    await page.setContent(HTML, { waitUntil: 'load' });
    // Let webfont fallback metrics settle before the shot.
    await page.evaluate(() => document.fonts.ready);
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
