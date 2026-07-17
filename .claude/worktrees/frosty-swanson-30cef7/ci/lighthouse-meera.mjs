/**
 * CI gate: Lighthouse mobile for the Meera workspace (/brand/meera).
 * Closes the M2.5 DoD item "Lighthouse ≥85 mobile; no layout shift"
 * (FRONTEND-BUILD-SPEC-MEERA.md §9).
 *
 * WHY A SCRIPT AND NOT `npx lighthouse`:
 *   /brand/meera is behind ProtectedRoute (src/App.tsx:42-50), which only
 *   renders when localStorage.brand_token is truthy. The old `?demo=true`
 *   bypass is gated on `import.meta.env.DEV`, a Vite compile-time constant that
 *   is FALSE in a production (`vite preview`) build — so the branch is
 *   dead-stripped and a plain crawl of /brand/meera redirects to /brand/login,
 *   scoring the WRONG page. We drive Chrome with puppeteer-core, seed the
 *   brand_token into the page's localStorage FIRST, then hand the same Chrome
 *   instance to Lighthouse with disableStorageReset:true so the token survives
 *   its navigation. No source is modified — this measures the exact shipped
 *   production bundle.
 *
 * DEPENDENCIES: lighthouse + puppeteer-core, devDependencies only, approved by
 *   Priya (see wiki/tech/approved-deps.md). puppeteer-core downloads no
 *   Chromium — it drives an existing Chrome/Chromium resolved at runtime.
 *
 * ENV KNOBS:
 *   LH_ORIGIN         origin to hit                     (default http://localhost:4173)
 *   LH_PERF_MIN       min Performance score, 0-100      (default 85)
 *   LH_CLS_MAX        max Cumulative Layout Shift        (default 0.01 — "no layout shift")
 *   LH_CHROME_PATH    explicit Chrome/Chromium binary    (default: auto-detect per-OS)
 *   LH_MANAGE_SERVER  "1" => spawn+own `vite preview`    (default off: caller runs the server)
 *   LH_OUT_DIR        report output dir                  (default ./ci/.lighthouse)
 *
 * EXIT CODES: 0 = gate passed; 1 = gate failed or route unmeasurable.
 */
import puppeteer from 'puppeteer-core';
import lighthouse from 'lighthouse';
import { spawn } from 'node:child_process';
import { existsSync, mkdirSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

const ORIGIN = process.env.LH_ORIGIN || 'http://localhost:4173';
const TARGET = `${ORIGIN}/brand/meera`;
const PERF_MIN = Number(process.env.LH_PERF_MIN ?? 85);
const CLS_MAX = Number(process.env.LH_CLS_MAX ?? 0.01);
const MANAGE_SERVER = process.env.LH_MANAGE_SERVER === '1';
const OUT_DIR = resolve(process.env.LH_OUT_DIR || './ci/.lighthouse');
const PREVIEW_PORT = Number(new URL(ORIGIN).port || 4173);

/** Resolve a Chrome/Chromium binary without downloading one. */
function resolveChrome() {
  if (process.env.LH_CHROME_PATH) return process.env.LH_CHROME_PATH;
  const candidates = [
    // Windows
    'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
    'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
    // Linux (GitHub-hosted ubuntu runners ship google-chrome-stable / chromium)
    '/usr/bin/google-chrome',
    '/usr/bin/google-chrome-stable',
    '/usr/bin/chromium-browser',
    '/usr/bin/chromium',
    // macOS
    '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  ];
  const found = candidates.find((p) => existsSync(p));
  if (!found) {
    throw new Error(
      'No Chrome/Chromium found. Set LH_CHROME_PATH to a Chrome binary. Tried:\n  ' +
        candidates.join('\n  '),
    );
  }
  return found;
}

/** Poll the origin until it serves, or throw after `timeoutMs`. */
async function waitForServer(url, timeoutMs = 60_000) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    try {
      const res = await fetch(url, { redirect: 'manual' });
      // Any HTTP response (even a 3xx/4xx) means the port is live.
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

/** Optionally spawn `vite preview` and return a killer fn (no-op if not managed). */
async function maybeStartServer() {
  if (!MANAGE_SERVER) {
    await waitForServer(ORIGIN);
    return () => {};
  }
  const npmCmd = process.platform === 'win32' ? 'npm.cmd' : 'npm';
  const child = spawn(
    npmCmd,
    ['run', 'preview', '--', '--port', String(PREVIEW_PORT), '--strictPort'],
    { stdio: 'inherit', shell: process.platform === 'win32' },
  );
  try {
    await waitForServer(ORIGIN);
  } catch (e) {
    child.kill();
    throw e;
  }
  return () => {
    try {
      child.kill();
    } catch {
      /* already gone */
    }
  };
}

const CHROME = resolveChrome();
mkdirSync(OUT_DIR, { recursive: true });

let stopServer = () => {};
let browser;
try {
  stopServer = await maybeStartServer();

  browser = await puppeteer.launch({
    executablePath: CHROME,
    headless: true,
    args: ['--no-sandbox', '--disable-gpu', '--disable-dev-shm-usage'],
  });

  // 1) Seed the auth token ProtectedRoute reads, so /brand/meera renders.
  const seed = await browser.newPage();
  await seed.goto(ORIGIN, { waitUntil: 'domcontentloaded' });
  await seed.evaluate(() => localStorage.setItem('brand_token', 'lh-audit-token'));
  // The seed nav warmed the HTTP cache. Since Lighthouse runs with
  // disableStorageReset:true (to keep the token) it won't clear the cache
  // itself, so clear it here for a true cold first-visit measurement.
  // localStorage is untouched by clearBrowserCache — the token survives.
  const cdp = await seed.target().createCDPSession();
  await cdp.send('Network.clearBrowserCache');
  await cdp.detach();
  await seed.close();

  const port = Number(new URL(browser.wsEndpoint()).port);

  // 2) Run Lighthouse. Defaults ARE "mobile": formFactor mobile, Moto-G-class
  //    screen emulation, simulated Slow-4G / 4x-CPU throttling.
  const runnerResult = await lighthouse(TARGET, {
    port,
    output: ['json', 'html'],
    logLevel: 'error',
    disableStorageReset: true, // keep the seeded localStorage across LH's navigation
    formFactor: 'mobile',
    screenEmulation: { mobile: true, width: 412, height: 823, deviceScaleFactor: 1.75, disabled: false },
    throttlingMethod: 'simulate',
  });

  const [jsonReport, htmlReport] = runnerResult.report;
  writeFileSync(resolve(OUT_DIR, 'meera-mobile.report.json'), jsonReport);
  writeFileSync(resolve(OUT_DIR, 'meera-mobile.report.html'), htmlReport);

  const lhr = runnerResult.lhr;
  const finalUrl = lhr.finalDisplayedUrl || lhr.finalUrl || '';
  const perf = lhr.categories.performance?.score;
  const perfScore = perf == null ? null : Math.round(perf * 100);
  const clsAudit = lhr.audits['cumulative-layout-shift'];
  const cls = clsAudit?.numericValue ?? null;

  console.log('\n=== LIGHTHOUSE — /brand/meera (mobile, simulate) ===');
  console.log('LH version   :', lhr.lighthouseVersion);
  console.log('Requested URL:', lhr.requestedUrl);
  console.log('Final URL    :', finalUrl);
  for (const key of ['performance', 'accessibility', 'best-practices', 'seo']) {
    const c = lhr.categories[key];
    if (c) console.log(`  ${key.padEnd(16)}: ${c.score == null ? 'n/a' : Math.round(c.score * 100)}`);
  }
  console.log('Core metrics :');
  for (const id of [
    'first-contentful-paint',
    'largest-contentful-paint',
    'total-blocking-time',
    'cumulative-layout-shift',
    'speed-index',
  ]) {
    const a = lhr.audits[id];
    if (a) console.log(`  ${id.padEnd(26)}: ${a.displayValue}`);
  }

  // 3) Assert the DoD gates.
  const failures = [];

  if (/\/brand\/(login|register|forgot-password)\b/.test(finalUrl)) {
    failures.push(`Redirected to ${finalUrl} — auth seed failed; the score is for the WRONG page.`);
  }
  if (perfScore == null) {
    failures.push('Performance score is null (Lighthouse could not measure the page, e.g. NO_FCP).');
  } else if (perfScore < PERF_MIN) {
    failures.push(`Performance ${perfScore} < required ${PERF_MIN}.`);
  }
  if (cls == null) {
    failures.push('CLS could not be measured.');
  } else if (cls > CLS_MAX) {
    failures.push(`CLS ${cls.toFixed(4)} > allowed ${CLS_MAX} (DoD: "no layout shift").`);
  }

  console.log('\n=== DoD GATE ===');
  console.log(`Performance ≥ ${PERF_MIN} : ${perfScore == null ? 'n/a' : perfScore}  ->  ${perfScore != null && perfScore >= PERF_MIN ? 'PASS' : 'FAIL'}`);
  console.log(`CLS ≈ 0 (≤ ${CLS_MAX})    : ${cls == null ? 'n/a' : cls.toFixed(4)}  ->  ${cls != null && cls <= CLS_MAX ? 'PASS' : 'FAIL'}`);

  if (failures.length) {
    console.error('\n❌ GATE FAILED:');
    for (const f of failures) console.error('  - ' + f);
    console.error(`\nReports: ${OUT_DIR}`);
    process.exitCode = 1;
  } else {
    console.log('\n✅ GATE PASSED — Performance ≥ ' + PERF_MIN + ' and CLS ≈ 0.');
    console.log(`Reports: ${OUT_DIR}`);
  }
} finally {
  if (browser) await browser.close();
  stopServer();
}
