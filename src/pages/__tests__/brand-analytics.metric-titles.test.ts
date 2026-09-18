/**
 * F-0953 — /brand/analytics labelled per-post averages as totals ("Total Reach",
 * "Total Engagements"). Both views are covered here: the single-creator view is behind a Radix
 * Select that jsdom cannot drive reliably, so the titles live in one exported helper the page uses.
 *
 * Run: npx vitest run src/pages/__tests__/brand-analytics.metric-titles.test.ts
 */
import { describe, expect, it } from 'vitest';

import { brandMetricTitles } from '../brand-analytics';

describe('brandMetricTitles (F-0953)', () => {
  it('single creator: per-post averages, never totals', () => {
    const t = brandMetricTitles(false);
    expect(t).toEqual({ reach: 'Avg. reach per post', engagements: 'Avg. engagements per post' });
  });

  it('combined roster: says the figures are sums of per-creator averages', () => {
    const t = brandMetricTitles(true);
    expect(t.reach).toBe("Sum of creators' avg. reach per post");
    expect(t.engagements).toBe("Sum of creators' avg. engagements per post");
  });

  it('no title in either view claims a total', () => {
    for (const view of [true, false]) {
      for (const title of Object.values(brandMetricTitles(view))) {
        expect(title).not.toMatch(/total/i);
      }
    }
  });
});
