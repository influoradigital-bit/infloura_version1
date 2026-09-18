/**
 * F-0953 — the single-creator view of /brand/analytics. The titles live in
 * brandMetricTitles (tested on its own), but only a page render proves the page actually uses
 * them when one creator is picked. The creator picker is a Radix Select, which jsdom cannot drive
 * reliably, so it is swapped for a native <select> with the same value/onValueChange contract.
 *
 * Run: npx vitest run src/pages/__tests__/brand-analytics.single-view.test.tsx
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import type { ReactNode } from 'react';
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
      deals: { ...actual.api.deals, list: (...a: unknown[]) => dealsListMock(...a) },
      analytics: {
        ...actual.api.analytics,
        getCreatorMetrics: (...a: unknown[]) => getCreatorMetricsMock(...a),
      },
    },
  };
});

vi.mock('@/components/ui/select', () => ({
  Select: ({
    value,
    onValueChange,
    children,
  }: {
    value: string;
    onValueChange: (v: string) => void;
    children: ReactNode;
  }) => (
    <select data-testid="native-select" value={value} onChange={(e) => onValueChange(e.target.value)}>
      {children}
    </select>
  ),
  SelectTrigger: () => null,
  SelectValue: () => null,
  SelectContent: ({ children }: { children: ReactNode }) => <>{children}</>,
  SelectItem: ({ value, children }: { value: string; children: ReactNode }) => (
    <option value={value}>{children}</option>
  ),
}));

vi.mock('@/components/analytics/CreatorMetricsCard', () => ({
  CreatorMetricsCard: ({ title, value }: { title: string; value: number }) => (
    <div data-testid={`metric-card-${title}`}>{`${title}:${value}`}</div>
  ),
}));

vi.mock('@/components/analytics/MetricsTrendChart', () => ({
  MetricsTrendChart: ({ title }: { title: string }) => <div data-testid="metrics-trend-chart">{title}</div>,
}));

function metrics(totalReach: number, totalEngagements: number) {
  return {
    totalReach,
    totalImpressions: 0,
    totalEngagements,
    engagementRate: null,
    followerGrowth: 0,
    avgViewsPerPost: null,
    trendData: [],
    followers: 0,
  };
}

describe('brand analytics — single-creator view (F-0953)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    dealsListMock.mockResolvedValue([
      { counterpartyId: 'usr_first', counterpartyProfileId: 'cr_first', counterpartyName: 'First Creator' },
      { counterpartyId: 'usr_second', counterpartyProfileId: 'cr_second', counterpartyName: 'Second Creator' },
    ]);
    getCreatorMetricsMock.mockImplementation((creatorId: string) =>
      creatorId === 'cr_first' ? Promise.resolve(metrics(1000, 90)) : Promise.resolve(metrics(2000, 60)),
    );
  });

  it('picking one creator shows per-post averages for that creator, never "Total ..."', async () => {
    render(
      <MemoryRouter initialEntries={['/brand/analytics']}>
        <BrandAnalyticsPage />
      </MemoryRouter>,
    );

    // The creator picker is the select that offers the combined option.
    const picker = await waitFor(() => {
      const found = screen
        .getAllByTestId('native-select')
        .find((s) => within(s).queryByText('All creators (combined)'));
      if (!found) throw new Error('creator picker not rendered yet');
      return found;
    });
    await waitFor(() => expect(within(picker).getByText('First Creator only')).toBeInTheDocument());
    fireEvent.change(picker, { target: { value: 'cr_first' } });

    await waitFor(() => {
      expect(screen.getByTestId('metric-card-Avg. reach per post').textContent).toBe(
        'Avg. reach per post:1000',
      );
    });
    expect(screen.getByTestId('metric-card-Avg. engagements per post').textContent).toBe(
      'Avg. engagements per post:90',
    );
    const titles = screen
      .getAllByTestId(/^metric-card-/)
      .map((el) => el.getAttribute('data-testid')!.replace('metric-card-', ''));
    expect(titles.some((t) => /total/i.test(t))).toBe(false);
    expect(screen.getByTestId('analytics-window-note').textContent).toMatch(/most recent posts/i);
  });
});
