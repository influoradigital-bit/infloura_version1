/**
 * F-0419 (mocked-surface) — brand-analytics.tsx.
 *
 * There is no brand-wide aggregate endpoint (AnalyticsController only exposes per-creator
 * /metrics, /scores, /demographics). The old overview derived the roster from every deal
 * returned by GET /deals?role=brand, then silently defaulted the metric cards to whichever
 * creator landed first in that list — a brand reading "Total Reach" would be reading one
 * creator's number as their whole account's, with no indication only one creator was shown.
 *
 * This suite renders the real page in live mode with TWO distinct creators returned by
 * GET /deals?role=brand and per-creator metrics that differ between them, then asserts the
 * default overview shows the real SUM across both creators — not just the first one's number
 * — proving the page now performs a genuine client-side aggregation instead of fabricating a
 * single-creator number as the account total.
 *
 * Run: npx vitest run src/pages/__tests__/brand-analytics.aggregate.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import BrandAnalyticsPage from '../brand-analytics';

const dealsListMock = vi.fn();
const getCreatorMetricsMock = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => true,
    api: {
      ...actual.api,
      deals: {
        ...actual.api.deals,
        list: (...a: unknown[]) => dealsListMock(...a),
      },
      analytics: {
        ...actual.api.analytics,
        getCreatorMetrics: (...a: unknown[]) => getCreatorMetricsMock(...a),
      },
    },
  };
});

// Stub the presentational cards/chart — CountUp/Framer need IntersectionObserver, and this
// suite only cares about the numeric `value` each card is handed, not its animation.
vi.mock('@/components/analytics/CreatorMetricsCard', () => ({
  CreatorMetricsCard: ({ title, value }: { title: string; value: number }) => (
    <div data-testid={`metric-card-${title}`}>{`${title}:${value}`}</div>
  ),
}));

vi.mock('@/components/analytics/MetricsTrendChart', () => ({
  MetricsTrendChart: ({ title }: { title: string }) => (
    <div data-testid="metrics-trend-chart">{title}</div>
  ),
}));

function emptyMetrics(totalReach: number) {
  return {
    totalReach,
    totalImpressions: 0,
    totalEngagements: 0,
    engagementRate: null,
    followerGrowth: 0,
    avgViewsPerPost: null,
    trendData: [],
  };
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/brand/analytics']}>
      <BrandAnalyticsPage />
    </MemoryRouter>,
  );
}

describe('F-0419 — brand analytics overview aggregates across every roster creator', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // Two distinct creators come back from the brand's real deals — mirrors the shape
    // deriveRosterFromDeals reads (counterpartyId/counterpartyName), nothing else.
    dealsListMock.mockResolvedValue([
      { counterpartyId: 'cr_first', counterpartyName: 'First Creator' },
      { counterpartyId: 'cr_second', counterpartyName: 'Second Creator' },
    ]);
    getCreatorMetricsMock.mockImplementation((creatorId: string) => {
      if (creatorId === 'cr_first') return Promise.resolve(emptyMetrics(1000));
      if (creatorId === 'cr_second') return Promise.resolve(emptyMetrics(2000));
      return Promise.reject(new Error(`unexpected creatorId ${creatorId}`));
    });
  });

  it('shows the SUM of every creator\'s Total Reach by default, not just the first creator\'s', async () => {
    renderPage();

    await waitFor(() => {
      expect(getCreatorMetricsMock).toHaveBeenCalledTimes(2);
    });

    // The old behaviour rendered "Total Reach:1000" (cr_first alone). The fix must render the
    // real combined total, 1000 + 2000 = 3000.
    await waitFor(() => {
      expect(screen.getByTestId('metric-card-Total Reach').textContent).toBe('Total Reach:3000');
    });

    // Both creators were actually queried — not just the first one picked and presented as
    // the account's number.
    expect(getCreatorMetricsMock).toHaveBeenCalledWith('cr_first', expect.anything(), expect.anything());
    expect(getCreatorMetricsMock).toHaveBeenCalledWith('cr_second', expect.anything(), expect.anything());
  });

  it('discloses the combined scope in the subtitle rather than presenting it as unlabeled roster data', async () => {
    renderPage();

    await waitFor(() => {
      expect(screen.getByTestId('metric-card-Total Reach').textContent).toBe('Total Reach:3000');
    });

    expect(screen.getByTestId('analytics-scope-subtitle').textContent).toMatch(/combined/i);
    expect(screen.getByTestId('analytics-scope-subtitle').textContent).toMatch(/2/);
  });
});
