/**
 * T-TSOFF-0920 — no surface may promise trend-derived content without asking the server first.
 *
 * SIBLING OF, NOT AN EXTENSION OF, `ev007-regulated-claims.test.ts`. That gate bans a fixed list
 * of sentences outright: "RBI-authorised", "TDS calculated" and friends are false in every
 * environment, forever, so a pure text blocklist is the right shape for it. The claims here are
 * different in kind — "your first idea lands by tomorrow morning" is perfectly TRUE when trend
 * ingest is running, and false only while it is switched off. Banning the words would forbid the
 * feature from ever describing itself; the thing that actually has to hold is STRUCTURAL: copy
 * like that may only render behind the one server switch. So this file checks wiring, not
 * vocabulary, and the two gates are kept apart rather than mixing a conditional rule into an
 * unconditional blocklist. Both must stay green.
 *
 * THREE RULES
 *   A. Static, pre-login surfaces (marketing HTML, llms.txt, the OG card, site components, legal
 *      and blog content, server-side email/notification copy) cannot read a runtime flag at all,
 *      so they may not mention trend suggestions or daily content ideas in any form.
 *   B. Any in-app source file whose CODE promises trend-derived content must either read the flag
 *      itself, or be listed in GATED_BY with the flag-aware parent that renders it — and that
 *      parent is verified to (1) read the flag and (2) actually import the child.
 *   C. `CopilotPreviewCard` — the single component that renders a hand-written example idea the
 *      model did not produce — must have exactly one call site, and that call site must be
 *      flag-aware.
 *
 * Comments are blanked before matching (line numbers preserved), so an explanation of WHY a
 * phrase is gated does not itself trip the gate.
 *
 * Run: npx vitest run src/__tests__/trends-off-copy.test.ts
 */
import { describe, expect, it } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = fileURLToPath(import.meta.url);
const REPO_ROOT = path.resolve(path.dirname(HERE), '..', '..');
/** Lets falsification point the gate at an extracted tree (`git archive <sha>`) — see ev007. */
const ROOT = process.env.TSOFF_SCAN_ROOT ? path.resolve(process.env.TSOFF_SCAN_ROOT) : REPO_ROOT;

/** Promises that are only true while trend ingest is running. */
const PROMISE_RULES: { id: string; re: RegExp }[] = [
  { id: 'daily-idea', re: /\bdaily\s+(?:content\s+)?idea/i },
  { id: 'todays-content-idea', re: /\btoday(?:’|')?s\s+content\s+idea/i },
  { id: 'content-idea', re: /\bcontent\s+ideas?\b/i },
  { id: 'first-idea', re: /\bfirst\s+idea\b/i },
  { id: 'trendspark', re: /\btrend[\s-]?spark\b/i },
  { id: 'whats-trending', re: /\bwhat(?:’|')?s\s+trending\b/i },
  { id: 'trending-window', re: /\btrending\s+(?:now|today|this\s+week|right\s+now|topics?)\b/i },
];

/** A file is "flag-aware" if it reads the ONE server switch, directly or through the hook that does. */
const FLAG_IMPORT = /useTrendsEnabled|useDailySuggestion/;

/**
 * Files that legitimately carry promise copy but do not read the flag themselves, each mapped to
 * the flag-aware parent that decides whether they render. The parent is verified below, so this
 * is a stated claim the gate checks, not an exemption it takes on trust.
 */
const GATED_BY: Record<string, string> = {
  'src/components/creator/copilot/IGConnectPrompt.tsx':
    'src/components/creator/copilot/DailySuggestionSection.tsx',
  'src/components/creator/copilot/SuggestionEmptyState.tsx':
    'src/components/creator/copilot/DailySuggestionSection.tsx',
  // NOTE: CopilotPreviewCard is deliberately NOT listed here. Its fabricated example idea does
  // not use any of the PROMISE_RULES phrasings, so rule B would never see it; rule C covers it
  // by call-site instead, which is the stronger check for a component whose whole risk is WHERE
  // it renders rather than what it says.
  // The mock nudge payload (MOCK_TRENDSPARK_NUDGE) lives here; its only consumer is the
  // flag-aware card, which is what keeps it off a real user's screen.
  'src/lib/api.ts': 'src/components/trendspark/TrendSparkNudgeCard.tsx',
};

/** Surfaces with no runtime flag available. Directories are scanned recursively. */
const STATIC_SURFACES = [
  'index.html',
  'public/llms.txt',
  'public/robots.txt',
  'scripts/generate-og-image.mjs',
  'src/components/site',
  'src/content',
  'influora-api/src/main/java/com/influora/integration/msg91',
  'influora-api/src/main/java/com/influora/service/notification',
];

const TEXT_EXT = new Set(['.ts', '.tsx', '.js', '.jsx', '.mjs', '.html', '.txt', '.md', '.java']);

function isTestFile(rel: string): boolean {
  return /\.(test|spec)\.[tj]sx?$/.test(rel) || rel.includes('__tests__/');
}

function walk(abs: string, out: string[]): void {
  if (!fs.existsSync(abs)) return;
  const st = fs.statSync(abs);
  if (st.isFile()) {
    if (TEXT_EXT.has(path.extname(abs))) out.push(abs);
    return;
  }
  for (const entry of fs.readdirSync(abs)) {
    if (entry === 'node_modules' || entry === '.git') continue;
    walk(path.join(abs, entry), out);
  }
}

/**
 * Blank comment bodies AND import specifiers, preserving newlines so reported line numbers stay
 * true. Imports are blanked because a module PATH is not copy: `import { TrendSparkNudgeCard }
 * from '@/components/trendspark/TrendSparkNudgeCard'` says nothing to a user, and treating it as
 * a promise would force every file that merely mounts a self-gating component to re-declare a
 * flag it does not need.
 */
function blankComments(src: string): string {
  const blank = (m: string) => m.replace(/[^\n]/g, ' ');
  return src
    .replace(/\/\*[\s\S]*?\*\//g, blank)
    .replace(/(^|[^:])\/\/[^\n]*/g, (m, p1) => p1 + ' '.repeat(m.length - p1.length))
    .replace(/<!--[\s\S]*?-->/g, blank)
    .replace(/^[ \t]*import[\s\S]*?from\s+['"][^'"]*['"];?/gm, blank)
    .replace(/^[ \t]*vi\.mock\(\s*['"][^'"]*['"]/gm, blank);
}

function relOf(abs: string): string {
  return path.relative(ROOT, abs).split(path.sep).join('/');
}

function read(relPath: string): string {
  return fs.readFileSync(path.join(ROOT, relPath), 'utf8');
}

function hits(code: string): { rule: string; line: number; text: string }[] {
  const found: { rule: string; line: number; text: string }[] = [];
  code.split('\n').forEach((line, i) => {
    for (const rule of PROMISE_RULES) {
      if (rule.re.test(line)) {
        found.push({ rule: rule.id, line: i + 1, text: line.trim().slice(0, 120) });
      }
    }
  });
  return found;
}

// --- the in-app source set (rule B) -----------------------------------------------------------
const APP_FILES: string[] = [];
walk(path.join(ROOT, 'src'), APP_FILES);
const APP_SOURCES = APP_FILES.map(relOf).filter((r) => !isTestFile(r) && /\.tsx?$/.test(r));

describe('T-TSOFF-0920 — trend promises are gated, not hardcoded', () => {
  it('scanned a plausible number of files (anti-vacuity)', () => {
    expect(APP_SOURCES.length).toBeGreaterThan(200);
    // The surfaces this gate exists for must actually be in the scanned set.
    expect(APP_SOURCES).toContain('src/components/creator/copilot/DailySuggestionSection.tsx');
    expect(APP_SOURCES).toContain('src/components/trendspark/TrendSparkNudgeCard.tsx');
    expect(APP_SOURCES).toContain('src/pages/creator-deals.tsx');
  });

  // --- Rule A ---------------------------------------------------------------------------------
  it('A: no static, pre-login surface mentions trend suggestions or daily content ideas', () => {
    const files: string[] = [];
    for (const s of STATIC_SURFACES) walk(path.join(ROOT, s), files);
    expect(files.length).toBeGreaterThan(5);

    const offenders: string[] = [];
    for (const abs of files) {
      const r = relOf(abs);
      if (isTestFile(r)) continue;
      for (const h of hits(blankComments(fs.readFileSync(abs, 'utf8')))) {
        offenders.push(r + ':' + h.line + ' [' + h.rule + '] ' + h.text);
      }
    }
    expect(offenders).toEqual([]);
  });

  // --- Rule B ---------------------------------------------------------------------------------
  it('B: every in-app file promising trend content is flag-aware or has a flag-aware parent', () => {
    const ungated: string[] = [];
    for (const r of APP_SOURCES) {
      const code = blankComments(read(r));
      const found = hits(code);
      if (found.length === 0) continue;
      if (FLAG_IMPORT.test(code)) continue; // reads the switch itself
      if (GATED_BY[r]) continue; // claimed parent — verified in the next test
      ungated.push(r + ':' + found[0].line + ' [' + found[0].rule + '] ' + found[0].text);
    }
    expect(ungated).toEqual([]);
  });

  it('B: every claimed parent really reads the flag AND really renders its child', () => {
    for (const [child, parent] of Object.entries(GATED_BY)) {
      // RAW source here, not `blankComments`: the relationship being verified IS the import, so
      // the import lines that `blankComments` strips for copy-matching are exactly the evidence.
      const parentCode = read(parent);
      expect(FLAG_IMPORT.test(parentCode), parent + ' must read the trend flag').toBe(true);

      const childModule = child.replace(/^src\//, '@/').replace(/\.tsx?$/, '');
      const childSymbol = path.basename(child).replace(/\.tsx?$/, '');
      const references =
        parentCode.includes(childModule) ||
        new RegExp('\\b' + childSymbol + '\\b').test(parentCode);
      expect(references, parent + ' must actually import/render ' + child).toBe(true);
    }
  });

  it('B: the GATED_BY list has no stale entries', () => {
    for (const child of Object.keys(GATED_BY)) {
      expect(fs.existsSync(path.join(ROOT, child)), child + ' is listed but missing').toBe(true);
      // An entry that no longer carries promise copy should be deleted, not left to rot.
      expect(
        hits(blankComments(read(child))).length,
        child + ' no longer needs a GATED_BY entry',
      ).toBeGreaterThan(0);
    }
  });

  // --- Rule C ---------------------------------------------------------------------------------
  it('C: the hand-written example idea has exactly one, flag-aware, call site', () => {
    // RAW source, not `blankComments`: an `import { CopilotPreviewCard } from ...` IS a call
    // site for this rule's purposes, and blanking imports here would let a second mount point
    // be added without the gate noticing (verified — the blanked version silently passed).
    // Comments are stripped only so the reference in THIS rule's own explanation elsewhere in
    // the tree cannot register as a call site.
    const callSites = APP_SOURCES.filter(
      (r) =>
        r !== 'src/components/creator/copilot/CopilotPreviewCard.tsx' &&
        read(r)
          .replace(/\/\*[\s\S]*?\*\//g, '')
          .replace(/(^|[^:])\/\/[^\n]*/g, '$1')
          .includes('CopilotPreviewCard'),
    );
    expect(callSites).toEqual(['src/pages/creator-copilot.tsx']);

    const page = blankComments(read('src/pages/creator-copilot.tsx'));
    expect(FLAG_IMPORT.test(page)).toBe(true);
    // It renders only for the 'idle' status, which `useDailySuggestion` never returns while the
    // feature is off (it returns 'disabled' first) — so the sample idea cannot appear then.
    expect(page).toMatch(/showPreview = status === 'idle'/);
    expect(page).toMatch(/\{showPreview && <CopilotPreviewCard/);
  });

  // --- positive controls: the rules can actually fire ------------------------------------------
  it('positive control: each rule matches the sentence it was written against', () => {
    const SAMPLES: Record<string, string> = {
      'daily-idea': 'Get your first daily idea',
      'todays-content-idea': 'Get today’s content idea from Co-pilot',
      'content-idea': 'A fresh content idea every morning',
      'first-idea': 'your first idea lands by tomorrow morning',
      trendspark: 'Powered by Trend-Spark AI',
      'whats-trending': 'See what’s trending for your niche',
      'trending-window': 'Monsoon cravings are trending this week',
    };
    for (const rule of PROMISE_RULES) {
      expect(rule.re.test(SAMPLES[rule.id]), rule.id + ' must match its sample').toBe(true);
    }
  });

  it('negative control: ordinary analytics and deliverable copy is not flagged', () => {
    const BENIGN = [
      'Reach & Engagement Trend — All creators combined',
      'High-quality reel with trending audio',
      'Follower & Reach Trend (30d)',
      'Meera is Influora’s built-in AI campaign co-pilot',
    ];
    for (const line of BENIGN) {
      for (const rule of PROMISE_RULES) {
        expect(rule.re.test(line), rule.id + ' must not match: ' + line).toBe(false);
      }
    }
  });
});
