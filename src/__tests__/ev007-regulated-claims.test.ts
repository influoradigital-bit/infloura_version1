/**
 * EV-007 / EV-031 / XR-ROHAN-01 — regulated and false money claims must not come back.
 *
 * Owner facts this gate encodes (2026-09-19):
 *   - Influora holds NO RBI Payment Aggregator / escrow licence and runs no regulated escrow.
 *   - Brand funds sit as a reserved balance on Influora's own wallet ledger. Razorpay only
 *     processes the card/UPI/net-banking top-ups. No regulator or licensed PA holds brand funds,
 *     and Influora's wallet balances are pooled in its own account, so "never pools" is false.
 *   - Payment is not guaranteed: a dispute can return reserved funds to the brand.
 *   - TDS is not computed anywhere on the automated rail and invoices carry no TDS line
 *     (MoneyDtos: "TDS and GST are unimplemented platform-wide"; Payout.tdsAmount is only an
 *     operator-entered figure on the manual payout rail). No Form 16A is generated.
 *
 * What is scanned: every customer-facing text source — src/** (pages, components, content,
 * legal and blog markdown, remotion scripts), index.html, public/*.txt, the OG-card source,
 * influora-api/src/main/resources/** and the Java classes that hold email/notification copy.
 * Test files are not scanned (they legitimately quote banned text in negative assertions), and
 * comments are blanked before matching so an explanation of WHY a phrase is banned does not trip
 * the gate — while line numbers are preserved for the report.
 *
 * Anti-vacuity: the gate asserts it scanned a minimum number of files, that each high-risk
 * surface is in the scanned set, that every pattern matches its historical offending sentence
 * (positive control), and that the honest rewrites do not match (negative control).
 */
import { describe, expect, it } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = fileURLToPath(import.meta.url);
const REPO_ROOT = path.resolve(path.dirname(HERE), '..', '..');
// EV007_SCAN_ROOT lets falsification point the gate at an extracted copy of the tree
// (e.g. `git archive de249ed`), so proving the gate never rewrites the working checkout.
const ROOT = process.env.EV007_SCAN_ROOT ? path.resolve(process.env.EV007_SCAN_ROOT) : REPO_ROOT;
const SELF = path.relative(REPO_ROOT, HERE).split(path.sep).join('/');

type Rule = { id: string; re: RegExp; why: string };

// Each rule is paired with the historical sentence it was written against (POSITIVE_CONTROL).
export const RULES: Rule[] = [
  {
    id: 'rbi-regulated',
    re: /\bRBI[\s-]*(?:authori[sz]ed|licen[sc]ed|regulated|approved)\b/i,
    why: 'Influora holds no RBI licence and no RBI-authorised entity holds brand funds',
  },
  {
    id: 'licensed-payment-holder',
    re: /\blicen[sc]ed\b[^.\n]{0,40}?\b(?:payment|gateway|aggregator|escrow|banking)\b/i,
    why: 'implies a licensed partner holds brand funds; Razorpay only processes top-ups',
  },
  {
    id: 'aggregator-holds-funds',
    re: /\bpayment aggregator\b[^.\n]{0,80}?\b(?:holds?|held)\b|\b(?:holds?|held)\b[^.\n]{0,80}?\bpayment aggregator\b/i,
    why: 'no Payment Aggregator holds brand funds',
  },
  {
    id: 'never-pools',
    re: /\b(?:never|does not|doesn't)\s+pools?\b/i,
    why: 'wallet balances sit in Influora’s own account',
  },
  {
    id: 'neutral-third-party',
    re: /\bneutral\s+third[\s-]party\b/i,
    why: 'escrow/trust framing; Influora is a party and not a regulated escrow',
  },
  {
    id: 'guaranteed-payment',
    re: /\bguaranteed?\s+(?:payment|payout|pay)\b|\b(?:payment|payout|pay)\s+is\s+guaranteed\b|\bguarantees?\s+payment\b|\bguaranteed\s+the\s+rate\b/i,
    why: 'a dispute can return reserved funds to the brand',
  },
  {
    id: 'tds-recorded-shown',
    re: /\b(?:any\s+)?recorded\s+TDS\b|\bTDS\s+(?:is\s+)?(?:shown|recorded|reflected)\b|\bTDS\s+on\s+(?:creator\s+)?(?:payouts|invoices)\b/i,
    why: 'invoices carry no TDS line; TDS is not computed on the automated rail',
  },
  {
    id: 'tds-automation',
    re: /\bTDS[\s-]+calculated\b|\bcalculates?\s+and\s+deducts?\s+TDS\b|\bTDS\s+calculation\b|\brequired\s+to\s+deduct\s+TDS\b|\bhandle\s+the\s+tax\s+complexity\b/i,
    why: 'no TDS engine exists',
  },
  {
    id: 'tds-handled-bundle',
    re: /\bTDS\s+(?:and\s+invoices\s+handled|invoice\b|included\b)|\bcontracts\s+and\s+TDS\b/i,
    why: 'markets TDS handling that does not exist',
  },
  {
    id: 'tds-rate-deducted',
    re: /\bTDS\s*(?:@|\()\s*1\s*%|\bdeducted\s+at\s+source\s+as\s+per\b/i,
    why: 'asserts a TDS rate is being deducted; nothing deducts it',
  },
  {
    id: 'form-16a-issued',
    re: /\bdownload\s+form\s*16A\s+quarterly\b|\bwe\s+issue\s+a\s+TDS\s+certificate\b|\bform\s*16A\s+certificates\s+are\s+issued\b/i,
    why: 'no Form 16A is generated',
  },
  {
    id: 'tds-deducted-by-platform',
    re: /\bapplicable\s+TDS\b|\bTDS\s+at\s+the\s+applicable\s+rate\b|\bdisclose\b[^.\n]{0,40}\bTDS\b|\bis\s+deducted\s+at\s+source\s+\(TDS\)\s+from\b/i,
    why: 'states the platform deducts TDS on every payout; nothing computes it',
  },
  {
    id: 'escrow-prose',
    re: /\b(?:through|via|into|in|from)\s+escrow\b/i,
    why: '"escrow" is banned in brand/creator copy and Influora runs no regulated escrow',
  },
];

// Historical offending sentences, one per rule (assembled from the pre-EV-007 copy).
const POSITIVE_CONTROL: Record<string, string> = {
  'rbi-regulated': 'deposited with a licensed, RBI-authorized Payment Aggregator.',
  'licensed-payment-holder': 'Payments held by a licensed gateway',
  'aggregator-holds-funds': 'It is held in payment protection by a Payment Aggregator',
  'never-pools': 'Influora never pools Campaign funds in its own bank account.',
  'neutral-third-party': 'your money is held by a neutral third party — Influora —',
  'guaranteed-payment': 'The funds are already locked and protected before you start work, so payment is guaranteed.',
  'tds-recorded-shown': 'Contracts, payment protection, TDS shown on invoices. Free to start.',
  'tds-automation': 'with auto-generated contracts, TDS-calculated payouts, and UPI transfers',
  'tds-handled-bundle': 'TDS and invoices handled',
  'tds-rate-deducted': 'TDS (1%) is deducted at source as per IT Act.',
  'form-16a-issued': 'Download Form 16A quarterly for filing.',
  'tds-deducted-by-platform': 'the payment releases the Payout to the creator, minus the Platform fee and applicable TDS.',
  'escrow-prose': 'Join Influora to see the opportunity, get paid through escrow, and manage the deal.',
};

// The honest replacements shipped with EV-007 — none of these may trip a rule.
const NEGATIVE_CONTROL = [
  'Payments processed securely by Razorpay',
  "Influora reserves the brand's payment in their Influora wallet from the moment the contract is signed",
  'The reserved amount is released to your Influora wallet, minus the creator commission.',
  'Influora does not calculate, deduct or file TDS for you.',
  'An invoice is generated for the Payout. It does not show a TDS line.',
  'TDS: not recorded on this payout · tax stays with your CA',
  'Where Indian tax law requires tax to be deducted at source (TDS) from a creator Payout, it is handled as that law requires.',
  'Paying an advance with no guarantee the content ever gets posted',
  'Brands receive rights-cleared pieces for the term they licensed; anything beyond that',
  'Invoices on every payout + dispute resolution',
];

const TEXT_EXT = new Set(['.ts', '.tsx', '.js', '.jsx', '.mjs', '.cjs', '.md', '.mdx', '.html', '.json', '.txt', '.yml', '.yaml', '.properties', '.ftl', '.mustache']);
const CODE_EXT = new Set(['.ts', '.tsx', '.js', '.jsx', '.mjs', '.cjs', '.java']);
const SKIP_DIRS = new Set(['node_modules', 'dist', '.git', '__tests__', 'test', '__snapshots__']);

function isTestFile(rel: string): boolean {
  return /\.(test|spec)\.[cm]?[jt]sx?$/.test(rel);
}

function walk(dir: string, out: string[]): void {
  if (!fs.existsSync(dir)) return;
  for (const ent of fs.readdirSync(dir, { withFileTypes: true })) {
    if (ent.isDirectory()) {
      if (!SKIP_DIRS.has(ent.name)) walk(path.join(dir, ent.name), out);
      continue;
    }
    const full = path.join(dir, ent.name);
    const rel = path.relative(ROOT, full).split(path.sep).join('/');
    if (!TEXT_EXT.has(path.extname(ent.name)) || isTestFile(rel)) continue;
    out.push(rel);
  }
}

/** Customer-facing Java: the email template registry and the notification copy. */
const JAVA_COPY_FILES = [
  'influora-api/src/main/java/com/influora/integration/msg91/EmailTemplateRegistry.java',
  'influora-api/src/main/java/com/influora/service/notification/NotificationListener.java',
];

export function collectFiles(): string[] {
  const files: string[] = [];
  walk(path.join(ROOT, 'src'), files);
  walk(path.join(ROOT, 'influora-api', 'src', 'main', 'resources'), files);
  for (const f of ['index.html', 'public/llms.txt', 'public/robots.txt', 'scripts/generate-og-image.mjs', ...JAVA_COPY_FILES]) {
    if (fs.existsSync(path.join(ROOT, f))) files.push(f);
  }
  return files.filter((f) => f !== SELF && !f.endsWith('.sql'));
}

/** Blank comments while keeping every newline, so reported line numbers stay true. */
export function maskComments(text: string, ext: string): string[] {
  const blank = (s: string) => s.replace(/[^\n]/g, ' ');
  if (!CODE_EXT.has(ext)) {
    let t = text.replace(/<!--[\s\S]*?-->/g, blank);
    if (ext === '.yml' || ext === '.yaml' || ext === '.properties') t = t.replace(/^\s*#.*$/gm, blank);
    return t.split('\n');
  }
  const out: string[] = [];
  let inBlock = false;
  for (const line of text.split('\n')) {
    if (inBlock) {
      const end = line.indexOf('*/');
      if (end === -1) {
        out.push('');
        continue;
      }
      inBlock = false;
      out.push(' '.repeat(end + 2) + line.slice(end + 2));
      continue;
    }
    const t = line.trimStart();
    if (t.startsWith('//') || t.startsWith('*')) {
      out.push('');
      continue;
    }
    if (t.startsWith('/*') || t.startsWith('{/*')) {
      const end = line.indexOf('*/');
      if (end === -1) {
        inBlock = true;
        out.push('');
      } else {
        out.push(' '.repeat(end + 2) + line.slice(end + 2));
      }
      continue;
    }
    out.push(
      line
        .replace(/\{\/\*.*?\*\/\}/g, (m) => ' '.repeat(m.length))
        .replace(/\/\*.*?\*\//g, (m) => ' '.repeat(m.length))
        .replace(/\s\/\/\s.*$/, ''),
    );
  }
  return out;
}

export function findViolations(files: string[]): string[] {
  const hits: string[] = [];
  for (const rel of files) {
    const text = fs.readFileSync(path.join(ROOT, rel), 'utf8').replace(/\r\n/g, '\n');
    const lines = maskComments(text, path.extname(rel));
    lines.forEach((line, i) => {
      for (const rule of RULES) {
        const m = rule.re.exec(line);
        if (m) hits.push(`${rel}:${i + 1} [${rule.id}] "${m[0]}" — ${rule.why}`);
      }
    });
  }
  return hits;
}

describe('EV-007: no regulated or false money claims in customer-facing copy', () => {
  const files = collectFiles();

  it('scans the real surfaces (anti-vacuity)', () => {
    expect(files.length).toBeGreaterThan(300);
    for (const must of [
      'index.html',
      'public/llms.txt',
      'src/pages/features/secure-payments.tsx',
      'src/pages/pricing.tsx',
      'src/pages/landing.tsx',
      'src/components/site/trust-items.ts',
      'src/content/legal/escrow-and-refund-policy.md',
      'src/content/legal/tds-policy.md',
      'src/content/legal/terms-of-service.md',
      'src/content/legal/privacy-policy.md',
      'src/content/blog/how-to-pay-influencers-safely-india-2026.md',
      'src/pages/creator-wallet.tsx',
      'src/pages/brand-wallet.tsx',
      ...JAVA_COPY_FILES,
    ]) {
      expect(files, `${must} must be scanned`).toContain(must);
    }
    expect(files).not.toContain(SELF);
  });

  it('every rule catches the sentence it was written against (positive control)', () => {
    for (const rule of RULES) {
      const sample = POSITIVE_CONTROL[rule.id];
      expect(sample, `rule ${rule.id} has a positive control`).toBeTruthy();
      expect(rule.re.test(sample), `rule ${rule.id} must match: ${sample}`).toBe(true);
    }
  });

  it('the honest rewrites pass every rule (negative control)', () => {
    for (const s of NEGATIVE_CONTROL) {
      for (const rule of RULES) expect(rule.re.test(s), `${rule.id} wrongly matched: ${s}`).toBe(false);
    }
  });

  it('comment masking keeps code copy and hides only comments', () => {
    const masked = maskComments(
      ["/* RBI-authorized */", "const a = 'TDS and invoices handled'; // RBI-authorized", "  * never pools"].join('\n'),
      '.ts',
    );
    expect(masked[0].trim()).toBe('');
    expect(masked[1]).toContain('TDS and invoices handled');
    expect(masked[1]).not.toContain('RBI');
    expect(masked[2].trim()).toBe('');
  });

  it('no banned claim appears in any scanned file', () => {
    expect(findViolations(files)).toEqual([]);
  });
});
