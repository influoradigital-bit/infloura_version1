/**
 * application-status.ts — CEO ruling 2026-08-18 (.proof-os/tasks/T-RULING-0818/SWAPNIL-RULING.md)
 * regressions: F-0327/Decision 2 (TERMS_AGREED badge), Decision 5 (TERMS_AGREED bucket), and
 * Decision 1 (the decline-wording variant switch).
 *
 * Run: npx vitest run src/lib/application-status.test.ts
 */

import { describe, it, expect, vi } from 'vitest';
import {
  APPLICATION_BUCKETS,
  DECLINE_WORDING,
  DECLINE_WORDING_VARIANT,
  bucketOf,
  getApplicationStatusBadgeProps,
  getApplicationStatusLabel,
  getDeclineWording,
} from './application-status';

describe('application-status — F-0327/Decision 2: TERMS_AGREED card badge', () => {
  it('labels TERMS_AGREED "Accepted", not "In negotiation"', () => {
    expect(getApplicationStatusLabel('TERMS_AGREED')).toBe('Accepted');
  });

  it('leaves the genuinely-still-negotiating IN_NEGOTIATION status untouched', () => {
    expect(getApplicationStatusLabel('IN_NEGOTIATION')).toBe('In negotiation');
  });

  it('gives TERMS_AGREED the success (green) badge styling, same as Active/Completed', () => {
    const badge = getApplicationStatusBadgeProps('TERMS_AGREED');
    expect(badge.label).toBe('Accepted');
    expect(badge.className).toBe('border-transparent bg-success text-success-foreground');
  });
});

describe('application-status — Decision 5: TERMS_AGREED filter bucket', () => {
  it('buckets TERMS_AGREED under "active", not "in_negotiation"', () => {
    expect(bucketOf('TERMS_AGREED')).toBe('active');
  });

  it('keeps CONTRACT_PENDING (equally pre-signed) in "active" too — the boundary is "said yes", not "contract exists"', () => {
    expect(bucketOf('CONTRACT_PENDING')).toBe('active');
  });

  it('does not add a 7th tab or reorder/relabel the existing six', () => {
    expect(APPLICATION_BUCKETS.map((b) => b.id)).toEqual([
      'applied',
      'shortlisted',
      'in_negotiation',
      'active',
      'completed',
      'closed',
    ]);
    // "In negotiation" the TAB still exists and still says that — only which status lands in
    // it changed (IN_NEGOTIATION still does; TERMS_AGREED no longer does).
    expect(APPLICATION_BUCKETS.find((b) => b.id === 'in_negotiation')?.label).toBe('In negotiation');
  });
});

describe('application-status — Decision 1: decline-wording variant switch', () => {
  it('ships with "arbitration" as the default variant', () => {
    expect(DECLINE_WORDING_VARIANT).toBe('arbitration');
  });

  it('the "arbitration" variant is pinned to "Closed" wording and a neutral icon, regardless of which variant ships', () => {
    expect(DECLINE_WORDING.arbitration).toEqual({
      statusLabel: 'Closed',
      eventLabel: 'Closed',
      icon: 'neutral',
    });
  });

  it('the "spec" variant is pinned to explicit "Rejected" wording and an explicit icon, regardless of which variant ships', () => {
    expect(DECLINE_WORDING.spec).toEqual({
      statusLabel: 'Rejected',
      eventLabel: 'Application Rejected',
      icon: 'explicit',
    });
  });

  it('getDeclineWording() resolves to whichever variant DECLINE_WORDING_VARIANT names', () => {
    expect(getDeclineWording()).toBe(DECLINE_WORDING[DECLINE_WORDING_VARIANT]);
  });

  it('a CANCELLED application\'s status label is driven by the live variant, not a separate copy', () => {
    expect(getApplicationStatusLabel('CANCELLED')).toBe(getDeclineWording().statusLabel);
    // Pinned against the live default too, so this test fails loudly (not silently) if someone
    // flips DECLINE_WORDING_VARIANT without updating this file's expectations.
    //
    // WEAKNESS (review condition C1, 2026-08-18): while the shipped default is 'arbitration',
    // both sides of the first assertion above are 'Closed' regardless of whether
    // getApplicationStatusLabel genuinely reads DECLINE_WORDING or has been hardcoded — the
    // assertion can't tell the difference. The test below this one is the one that actually
    // catches that; keep both, but don't mistake this one for a strong guard on its own.
    expect(getApplicationStatusLabel('CANCELLED')).toBe('Closed');
  });
});

describe('application-status — C1 fix: getApplicationStatusLabel genuinely depends on the live variant', () => {
  /**
   * Review condition C1 (2026-08-18): a reviewer hardcoded getApplicationStatusLabel's CANCELLED
   * branch to always return 'Closed' — re-creating F-0303's label/badge split on the badge side —
   * and every existing test stayed green. Two causes: the test above compares the mutated
   * function against itself (tautological while the default is 'arbitration'), and the
   * ApplicationHistoryTimeline component test mocks getApplicationStatusLabel directly, so it
   * structurally cannot observe the real function's body at all.
   *
   * This test avoids both: it mocks ONLY decline-wording-variant.ts (the constant module, not
   * application-status.ts or any of its functions), forces DECLINE_WORDING_VARIANT to 'spec' via
   * vi.doMock + vi.resetModules, then dynamically imports the REAL, unmocked
   * getApplicationStatusLabel from application-status.ts and asserts its actual return value. A
   * hardcoded CANCELLED branch fails this immediately and unconditionally — it does not depend on
   * which variant happens to ship as the default.
   */
  it('resolves CANCELLED to "Rejected" via the real function when the variant module reports "spec"', async () => {
    vi.resetModules();
    vi.doMock('./decline-wording-variant', async () => {
      const actual = await vi.importActual<typeof import('./decline-wording-variant')>('./decline-wording-variant');
      return { ...actual, DECLINE_WORDING_VARIANT: 'spec' as const };
    });

    try {
      const fresh = await import('./application-status');
      expect(fresh.getDeclineWording().statusLabel).toBe('Rejected');
      // The real, unmocked function — not a re-implementation of its CANCELLED branch.
      expect(fresh.getApplicationStatusLabel('CANCELLED')).toBe('Rejected');
    } finally {
      vi.doUnmock('./decline-wording-variant');
      vi.resetModules();
    }
  });
});
