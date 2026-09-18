/**
 * Backend notification links ↔ SPA route table.
 * ----------------------------------------------------------------------------
 * The notifications page and the bell popover call `navigate(notification.link)` with whatever
 * string the backend stored. Nothing checked that those strings are routes: every brand
 * deal-lifecycle notification pointed at a page that never existed (/brand/proposals/{id},
 * /brand/collaborations/{id}, /brand/deliverables/{id}, /brand/shipments/{id}, /brand/billing,
 * ...) and opened the 404 page.
 *
 * This reads the Java sources that mint in-app links, extracts every "/brand/..." and
 * "/creator/..." string literal, and matches its pathname against the routes App.tsx registers.
 * Same idea as src/lib/__tests__/api-contract.test.ts, pointed at links instead of REST paths.
 */
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { matchPath } from 'react-router-dom';

const REPO_ROOT = resolve(__dirname, '../..');
const JAVA_ROOT = resolve(REPO_ROOT, 'influora-api/src/main/java/com/influora');

/** Java sources that write a Notification.link or a dashboard action link. */
const LINK_SOURCES = [
  'service/notification/NotificationListener.java',
  'service/DashboardService.java',
];

/**
 * Creator links that are still dead (audit 2026-09-17, creator side). The brand half was fixed
 * first; these are fixed in the creator pass. This set may only SHRINK: the last test below fails
 * if an entry here starts resolving or is no longer emitted, so it cannot rot into an allow-list.
 */
const KNOWN_DEAD_LINKS = new Set<string>([
  '/creator/messages/:id',
  '/creator/proposals/:id',
  '/creator/collaborations/:id',
  '/creator/shipments/:id',
  '/creator/contracts/:id',
  '/creator/profile/kyc',
]);

function registeredRoutes(): string[] {
  const app = readFileSync(resolve(REPO_ROOT, 'src/App.tsx'), 'utf8');
  const routes = [...app.matchAll(/<Route\s[^>]*?path="([^"]+)"/g)].map((m) => m[1]);
  // The "*" not-found route matches everything; the point is to land on a REAL page.
  return routes.filter((r) => r !== '*');
}

/** "/brand/chat?deal=" -> "/brand/chat";  "/creator/messages/" (+ id) -> "/creator/messages/:id" */
function toPathPattern(literal: string): string {
  const pathname = literal.split('?')[0];
  return pathname.endsWith('/') ? `${pathname}:id` : pathname;
}

function emittedLinks(): Array<{ source: string; line: number; literal: string; pattern: string }> {
  const found: Array<{ source: string; line: number; literal: string; pattern: string }> = [];
  for (const source of LINK_SOURCES) {
    const lines = readFileSync(resolve(JAVA_ROOT, source), 'utf8').split(/\r?\n/);
    lines.forEach((text, i) => {
      const code = text.trim();
      // Javadoc / line comments describe links (including the old dead ones); only code emits them.
      if (code.startsWith('//') || code.startsWith('*') || code.startsWith('/*')) return;
      for (const m of code.matchAll(/"(\/(?:brand|creator)\/[^"]*)"/g)) {
        found.push({ source, line: i + 1, literal: m[1], pattern: toPathPattern(m[1]) });
      }
    });
  }
  return found;
}

function resolves(pattern: string, routes: string[]): boolean {
  const concrete = pattern.replace(':id', '01HXXXXXXXXXXXXXXXXXXXXXXX');
  return routes.some((route) => matchPath({ path: route, end: true }, concrete) !== null);
}

describe('backend notification links resolve to a registered SPA route', () => {
  const routes = registeredRoutes();
  const links = emittedLinks();

  it('actually found the route table and the links (guards against a vacuous pass)', () => {
    expect(routes).toContain('/brand/chat');
    expect(routes).toContain('/brand/settings/billing');
    expect(links.length).toBeGreaterThanOrEqual(15);
    const patterns = links.map((l) => l.pattern);
    expect(patterns).toContain('/brand/chat');
    expect(patterns).toContain('/brand/contracts');
    expect(patterns).toContain('/brand/wallet');
    expect(patterns).toContain('/brand/settings/billing');
  });

  it('every emitted /brand and /creator link lands on a real page', () => {
    const dead = links
      .filter((l) => !KNOWN_DEAD_LINKS.has(l.pattern))
      .filter((l) => !resolves(l.pattern, routes))
      .map((l) => `${l.source}:${l.line}  "${l.literal}"  ->  no route matches ${l.pattern}`);
    expect(dead).toEqual([]);
  });

  it('the known-dead baseline only shrinks', () => {
    const emitted = new Set(links.map((l) => l.pattern));
    const stale = [...KNOWN_DEAD_LINKS].filter((p) => !emitted.has(p) || resolves(p, routes));
    expect(stale, 'remove these from KNOWN_DEAD_LINKS — they are fixed or no longer emitted').toEqual([]);
  });
});
