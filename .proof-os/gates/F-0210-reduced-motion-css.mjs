// gates/F-0210-reduced-motion-css.mjs — origin: F-0210 (reduced-motion-visual-regression).
// Headless CSS-semantics gate: loads the REAL compiled dist CSS, reproduces each
// cssVars conversion's class+var pairing, and reads computed styles under forced
// reduced motion. Catches the invalid-at-computed-value-time class of bug (a value
// that lints, typechecks, and builds but computes to 'none' in the browser).
// LAW: tool-cannot-run (no node_modules, no Chrome, no dist) => exit 2. exit 1 ONLY
// for a computed value that contradicts the intended render.
// Run: node .proof-os/gates/F-0210-reduced-motion-css.mjs  (requires a prior npm run build)
import { createRequire } from 'node:module';
import { readdirSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const ROOT = 'C:/Users/Sage world/Downloads/New Influora Ai/New Influora';
const require = createRequire(resolve(ROOT, 'package.json'));
const puppeteer = require('puppeteer-core');
const cssFile = readdirSync(resolve(ROOT, 'dist/assets')).find(f => f.endsWith('.css'));
const css = readFileSync(resolve(ROOT, 'dist/assets', cssFile), 'utf8');

// The exact current source values:
const src = readFileSync(resolve(ROOT, 'src/components/feature/meera/MeeraOrb.tsx'), 'utf8');
const rmMatch = src.match(/const REDUCED_MOTION_BG =\s*((?:'[^']*'\s*\+?\s*)+)/);
const REDUCED_MOTION_BG = rmMatch[1].split('+').map(s => s.trim().replace(/^'|'$/g, '')).join('');
const CORE_BG = src.match(/const CORE_BG = '([^']+)'/)[1];

const html = `<!doctype html><html><head><style>${css}</style></head><body>
  <div id="orb"  class="relative shrink-0 overflow-hidden rounded-full bg-[#0B0F1A] bg-[image:var(--orb-bg)] w-[var(--orb-size)]! h-[var(--orb-size)]!"></div>
  <div id="core" class="bg-[image:var(--orb-core-bg)]"></div>
  <div id="credit" class="h-full rounded-full bg-primary w-[var(--credit-progress-w)]"></div>
  <div id="aura" class="[transition:var(--pulse-aura-transition)]"></div>
  <svg viewBox="0 0 120 120" width="120" height="120"><path id="shackle" d="M40 52V40a20 20 0 0 1 40 0v12" class="[transform-origin:60px_52px] [transform:var(--lock-shackle-transform)]"/></svg>
  <!-- transform probed WITHOUT the transition class: with it, the 420ms transition is
       mid-flight at read time and the computed matrix is the start value (identity),
       which is timing, not a resolution failure. The transition-var slot is the aura probe. -->
</body></html>`;

const browser = await puppeteer.launch({
  executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe',
  headless: 'new',
});
const page = await browser.newPage();
await page.emulateMediaFeatures([{ name: 'prefers-reduced-motion', value: 'reduce' }]);
await page.setContent(html, { waitUntil: 'load' });
const results = await page.evaluate((RM_BG, CORE) => {
  const set = (id, vars) => {
    const el = document.getElementById(id);
    for (const [k, v] of Object.entries(vars)) el.style.setProperty(k, v);
    return el;
  };
  const cs = el => getComputedStyle(el);
  const orb = set('orb', { '--orb-bg': RM_BG, '--orb-size': '96px' });
  const core = set('core', { '--orb-core-bg': CORE });
  const credit = set('credit', { '--credit-progress-w': '47%' });
  const aura = set('aura', { '--pulse-aura-transition': 'opacity 350ms ease-out' });
  const sh = set('shackle', {
    '--lock-shackle-transform': 'translateY(-6px)',
    '--lock-shackle-transition': 'transform 420ms cubic-bezier(0.23,1,0.32,1), stroke 300ms ease-out',
  });
  document.body.offsetHeight;
  return {
    reducedMotionActive: matchMedia('(prefers-reduced-motion: reduce)').matches,
    orb: { backgroundImage: cs(orb).backgroundImage.slice(0, 60), backgroundColor: cs(orb).backgroundColor, width: cs(orb).width },
    core: { backgroundImage: cs(core).backgroundImage.slice(0, 60) },
    credit: { widthStyleResolved: cs(credit).width !== 'auto' && cs(credit).width !== '0px' ? cs(credit).width : 'FAIL:' + cs(credit).width },
    aura: { transition: cs(aura).transitionProperty + ' ' + cs(aura).transitionDuration },
    shackle: { transform: cs(sh).transform, transition: cs(sh).transitionProperty },
  };
}, REDUCED_MOTION_BG, CORE_BG);
await browser.close();

let fail = 0;
const assert = (name, ok, detail) => { console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}  ${detail}`); if (!ok) fail = 1; };
assert('reduced-motion emulated', results.reducedMotionActive, '');
assert('orb backgroundImage != none (F-0210 fix)', results.orb.backgroundImage !== 'none', results.orb.backgroundImage);
assert('orb base color', results.orb.backgroundColor === 'rgb(11, 15, 26)', results.orb.backgroundColor);
assert('orb size var honored', results.orb.width === '96px', results.orb.width);
assert('core gradient resolves', results.core.backgroundImage !== 'none', results.core.backgroundImage);
assert('credit width var resolves', !String(results.credit.widthStyleResolved).startsWith('FAIL'), results.credit.widthStyleResolved);
assert('aura transition var resolves', results.aura.transition.includes('opacity') && results.aura.transition.includes('0.35s'), results.aura.transition);
assert('shackle transform var resolves to translateY(-6px)', results.shackle.transform === 'matrix(1, 0, 0, 1, 0, -6)', results.shackle.transform);
console.log('NOT CHECKED: full-page rendering with React mounted (this is value-in-slot semantics, not a screenshot); reduced-motion branches added after this gate was written unless their sites are added above');
process.exit(fail);
