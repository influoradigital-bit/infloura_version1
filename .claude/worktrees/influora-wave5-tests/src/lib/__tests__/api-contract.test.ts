/**
 * FE ↔ Backend contract guardrail.
 * ----------------------------------------------------------------------------
 * This test is the seam check that the 2026-07 audit found missing. It exists
 * because `vite build` does NOT typecheck, mock mode returns fake data for every
 * endpoint, and nothing verified that `src/lib/api.ts` calls paths the Java
 * backend actually exposes — so ~10 fabricated money/onboarding contracts and 6
 * entire missing client layers shipped "green".
 *
 * Two guarantees:
 *   A. Facade shape — every namespace on `api` is present and its members are
 *      callable. Prevents the "api.storeIntegrations is undefined" runtime crash
 *      class (a whole feature's client layer silently missing).
 *   B. Path existence — every REST path api.ts calls resolves to a real
 *      @RequestMapping in influora-api. New unmatched paths fail the build; the
 *      KNOWN_PHANTOM_PATHS baseline documents the pre-existing broken contracts
 *      (P0 #3) that are deferred, so the set can only shrink, never grow.
 */

import { describe, it, expect } from 'vitest';
import { readdirSync, readFileSync, existsSync } from 'node:fs';
import { join, resolve } from 'node:path';
import api from '@/lib/api';

// ---------------------------------------------------------------------------
// A. Facade shape
// ---------------------------------------------------------------------------

describe('api facade shape', () => {
  const EXPECTED_NAMESPACES = [
    'auth', 'workspaces', 'onboarding', 'campaigns', 'creators', 'deals',
    'messages', 'contracts', 'deliverables', 'wallet', 'payments', 'dashboard',
    'notifications', 'uploads', 'portfolio', 'analytics', 'creatorAnalytics',
    'contentPerformance', 'campaignTracking', 'storeIntegrations', 'creatorReviews',
    'brandReviews', 'metaOAuth', 'creatorCoupons', 'affiliateEarnings',
    'creatorCampaigns', 'creatorDeliverables', 'creatorDisputes', 'brandDisputes',
  ] as const;

  it('exposes every expected namespace', () => {
    for (const ns of EXPECTED_NAMESPACES) {
      expect(api, `api.${ns} missing`).toHaveProperty(ns);
    }
  });

  it('every namespace member is a callable function', () => {
    for (const ns of EXPECTED_NAMESPACES) {
      const group = (api as Record<string, unknown>)[ns] as Record<string, unknown>;
      expect(typeof group, `api.${ns} is not an object`).toBe('object');
      for (const [name, member] of Object.entries(group)) {
        expect(typeof member, `api.${ns}.${name} is not a function`).toBe('function');
      }
    }
  });
});

// ---------------------------------------------------------------------------
// B. Path existence vs real Java controllers
// ---------------------------------------------------------------------------

/**
 * Pre-existing broken FE→BE contracts (audit P0 #3). Each calls a path no Java
 * controller exposes. Deferred (money-flow + onboarding rework). This baseline
 * lets the guardrail pass today while FAILING on any NEW fabricated path — the
 * set may only shrink. Remove entries here as the real contracts are wired.
 */
const KNOWN_PHANTOM_PATHS = new Set<string>([
  '/onboarding/creator/socials',
  '/onboarding/creator/profile',
  '/onboarding/creator/complete',
  '/onboarding/creator/kyc',
  '/onboarding/creator/payout',
  '/uploads',
  '/wallet/recharge',
  '/notifications/read-all',
  '/deliverables/{}/approve',
  '/deliverables/{}/revise',
]);

const API_TS = resolve(__dirname, '../api.ts');
const ADMIN_API_TS = resolve(__dirname, '../../admin/services/api-contracts.ts');
const CONTROLLERS_DIR = resolve(
  __dirname,
  '../../../influora-api/src/main/java/com/influora/web',
);

/** `${x}` and `{param}` collapse to a single `{}` wildcard segment. */
function normalize(path: string): string {
  return path
    .replace(/\$\{[^}]*\}/g, '{}')
    .replace(/\{[^}]*\}/g, '{}')
    .replace(/\/+$/, '') || '/';
}

function extractFePaths(src: string): string[] {
  const paths = new Set<string>();
  // http.request / http.requestWithMeta → path is the 2nd arg (after the method).
  const reqRe = /http\.(?:request|requestWithMeta)(?:<[^>]*>)?\(\s*'(?:GET|POST|PUT|PATCH|DELETE)'\s*,\s*[`']([^`']+)[`']/g;
  // http.upload → path is the 1st arg.
  const upRe = /http\.upload(?:<[^>]*>)?\(\s*[`']([^`']+)[`']/g;
  for (const re of [reqRe, upRe]) {
    let m: RegExpExecArray | null;
    while ((m = re.exec(src)) !== null) paths.add(normalize(m[1]));
  }
  return [...paths];
}

/**
 * src/admin/services/api-contracts.ts uses a different call shape than lib/api.ts:
 * `apiRequest<T>('/path', options)` where the path is the 1st arg (not preceded by an
 * HTTP-method string arg like `http.request`) and paths are relative to `API_BASE =
 * '/api/v1/admin'` (declared in that file). Query strings (`?...`) are stripped — Java
 * @RequestMapping paths never include them — and the admin controllers' @RequestMapping
 * bases only declare the `/admin/...` segment (not `/api/v1`), matching the convention
 * `extractControllerPaths` already relies on for the non-admin controllers, so paths here
 * are prefixed with `/admin` (not the full `API_BASE`) before normalizing.
 */
function extractAdminPaths(src: string): string[] {
  const paths = new Set<string>();
  // Lazy content + a lookahead requiring the closing backtick/quote to be immediately
  // followed by `,` or `)` (not just "next backtick/quote") — a few call sites (e.g.
  // moderationApi.getSuspensions) nest a second template literal inside the `${...}`
  // substitution of the first, which would otherwise terminate the capture early on
  // the inner literal's own backtick.
  const re = /apiRequest(?:<[\s\S]*?>)?\(\s*[`']([\s\S]*?)[`'](?=\s*[,)])/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(src)) !== null) {
    let pathOnly = m[1];
    // A couple of call sites (moderationApi.getContentFlags/getSuspensions/
    // getPendingApprovals) build the query string as a *nested* template literal
    // inside the outer one's `${...}` (e.g. `` `/x${cond ? `?y=${z}` : ''}` ``).
    // A bare backtick in the captured content means we've hit one of those —
    // keep only the static prefix before the substitution starts.
    if (pathOnly.includes('`')) pathOnly = pathOnly.split('${')[0];
    pathOnly = pathOnly.split('?')[0];
    paths.add(normalize(`/admin${pathOnly}`));
  }
  return [...paths];
}

function javaFiles(dir: string): string[] {
  const out: string[] = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) out.push(...javaFiles(full));
    else if (entry.name.endsWith('.java')) out.push(full);
  }
  return out;
}

function extractControllerPaths(): Set<string> {
  const mappings = new Set<string>();
  for (const file of javaFiles(CONTROLLERS_DIR)) {
    const src = readFileSync(file, 'utf8');
    const baseMatch = src.match(/@RequestMapping\(\s*"([^"]*)"/);
    const base = baseMatch ? baseMatch[1] : '';
    const methodRe = /@(?:Get|Post|Put|Patch|Delete)Mapping\(\s*(?:value\s*=\s*)?"([^"]*)"/g;
    let m: RegExpExecArray | null;
    let hadValue = false;
    while ((m = methodRe.exec(src)) !== null) {
      hadValue = true;
      mappings.add(normalize(base + m[1]));
    }
    // A bare @GetMapping with no value maps to the base path itself.
    if (/@(?:Get|Post|Put|Patch|Delete)Mapping(\s*\(\s*\)|\s*[^("])/.test(src) || !hadValue) {
      if (base) mappings.add(normalize(base));
    }
  }
  return mappings;
}

function segMatch(fe: string, java: string): boolean {
  const a = fe.split('/');
  const b = java.split('/');
  if (a.length !== b.length) return false;
  return a.every((seg, i) => seg === b[i] || seg === '{}' || b[i] === '{}');
}

describe('api.ts REST paths resolve to real Java controllers', () => {
  const run = existsSync(CONTROLLERS_DIR) ? it : it.skip;

  run('no NEW fabricated FE→BE contract (unmatched paths ⊆ known baseline)', () => {
    const fePaths = extractFePaths(readFileSync(API_TS, 'utf8'));
    expect(fePaths.length).toBeGreaterThan(20); // sanity: extraction worked

    const adminPaths = extractAdminPaths(readFileSync(ADMIN_API_TS, 'utf8'));
    expect(adminPaths.length).toBeGreaterThan(20); // sanity: extraction worked

    const controllerPaths = extractControllerPaths();
    const allFePaths = [...fePaths, ...adminPaths];
    const unmatched = allFePaths.filter(
      (p) => ![...controllerPaths].some((c) => segMatch(p, c)),
    );
    const newlyBroken = unmatched.filter((p) => !KNOWN_PHANTOM_PATHS.has(p));

    expect(
      newlyBroken,
      `New FE paths with no matching Java controller (fabricated contract). ` +
        `Add a real endpoint or, if intentionally deferred, add to KNOWN_PHANTOM_PATHS:\n` +
        newlyBroken.join('\n'),
    ).toEqual([]);
  });
});
