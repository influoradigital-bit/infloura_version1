/**
 * `countAtOrBeyond` — the cumulative rule behind the brand first-run checklist.
 *
 * The defect this pins: an exact-bucket count makes the checklist go BACKWARDS. A deal that
 * moves from `Negotiating` to `Contracted` empties the Negotiating bucket, so "Agree the terms
 * in the Deal Room" would un-tick at the exact moment the user succeeded at it.
 *
 * Run: npx vitest run src/lib/__tests__/brand-pipeline-progress.test.ts
 */

import { describe, it, expect } from 'vitest';
import { countAtOrBeyond, PIPELINE_STAGE_ORDER } from '@/lib/brand-pipeline-progress';

/** One deal that has reached the terminal stage and nothing anywhere else. */
const ONE_SETTLED = [
  { stage: 'Outreach', count: 0 },
  { stage: 'Negotiating', count: 0 },
  { stage: 'Contracted', count: 0 },
  { stage: 'In Progress', count: 0 },
  { stage: 'Review', count: 0 },
  { stage: 'Settled', count: 1 },
];

describe('countAtOrBeyond', () => {
  it('counts a deal at a later stage toward every earlier one — the checklist never goes backwards', () => {
    for (const stage of PIPELINE_STAGE_ORDER) {
      expect(countAtOrBeyond(ONE_SETTLED, stage)).toBe(1);
    }
  });

  it('does not count a deal toward a stage it has not reached', () => {
    const oneNegotiating = [
      { stage: 'Outreach', count: 0 },
      { stage: 'Negotiating', count: 1 },
    ];
    expect(countAtOrBeyond(oneNegotiating, 'Outreach')).toBe(1);
    expect(countAtOrBeyond(oneNegotiating, 'Negotiating')).toBe(1);
    expect(countAtOrBeyond(oneNegotiating, 'Contracted')).toBe(0);
    expect(countAtOrBeyond(oneNegotiating, 'Settled')).toBe(0);
  });

  it('sums across every qualifying bucket rather than reading only the first match', () => {
    const spread = [
      { stage: 'Outreach', count: 3 },
      { stage: 'Negotiating', count: 2 },
      { stage: 'Contracted', count: 4 },
    ];
    expect(countAtOrBeyond(spread, 'Outreach')).toBe(9);
    expect(countAtOrBeyond(spread, 'Negotiating')).toBe(6);
    expect(countAtOrBeyond(spread, 'Contracted')).toBe(4);
  });

  it('is zero for a genuinely empty pipeline, so no step ticks on a brand-new account', () => {
    expect(countAtOrBeyond([], 'Outreach')).toBe(0);
    const allZero = PIPELINE_STAGE_ORDER.map((stage) => ({ stage, count: 0 }));
    for (const stage of PIPELINE_STAGE_ORDER) {
      expect(countAtOrBeyond(allZero, stage)).toBe(0);
    }
  });

  it('ignores an unrecognised stage label instead of throwing — a new backend bucket must not blank the dashboard', () => {
    const withUnknown = [
      { stage: 'Outreach', count: 1 },
      { stage: 'SomeNewBucket', count: 99 },
    ];
    expect(countAtOrBeyond(withUnknown, 'Outreach')).toBe(1);
  });

  it('does not double-count the dead pre-PL-2 `Completed` label alongside `Settled`', () => {
    // `bucketFor` emits `Settled`; `Completed` survives only as a defensive fallback elsewhere.
    // If both were in the order, an account carrying the legacy label would count twice.
    expect(PIPELINE_STAGE_ORDER).not.toContain('Completed');
    expect(countAtOrBeyond([{ stage: 'Completed', count: 5 }], 'Settled')).toBe(0);
  });
});
