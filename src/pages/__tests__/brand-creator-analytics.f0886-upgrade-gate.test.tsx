/**
 * F-0886 (paywall-with-no-way-to-pay) — creator analytics.
 *
 * `GET /analytics/creators/:id/metrics` 402s `UPGRADE_REQUIRED` once a Free workspace exceeds
 * B39's per-creator monthly analytics limit. Before this fix `useCreatorMetrics` only turned that
 * into a nicer error STRING (`UPGRADE_PROMPT_MESSAGE`) — the page rendered it as plain text next
 * to metric tiles that were all "0" (a misleading, not-actually-empty state) with no way to act.
 * This pins:
 *   1. The hook now exposes `upgradeRequired: true` distinctly from `error`.
 *   2. The page renders the shared `UpgradeGate` in place of the metric tiles/trend chart when
 *      that flag is set, not a zeroed-out dashboard.
 *   3. A non-402 metrics error still renders the plain-text banner (no regression).
 *   4. The CTA is role-aware via `canManageBilling`.
 *
 * Falsification: each "renders UpgradeGate" assertion was checked red by reverting the page's
 * `metricsUpgradeRequired ? <UpgradeGate .../> : <>...</>` branch to always render the metric
 * tiles — see the run log in the task report; reverted back to this file afterward.
 *
 * Run: npx vitest run src/pages/__tests__/brand-creator-analytics.f0886-upgrade-gate.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import BrandCreatorAnalyticsPage from '../brand-creator-analytics';

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return { ...actual, isApiLive: () => true };
});

let mockMetrics: {
  data: unknown;
  loading: boolean;
  error: string | null;
  upgradeRequired: boolean;
  refresh: () => void;
} = { data: null, loading: false, error: null, upgradeRequired: false, refresh: vi.fn() };
let mockCanManageBilling = true;

vi.mock('@/hooks/analytics/useCreatorMetrics', () => ({
  useCreatorMetrics: () => mockMetrics,
}));
vi.mock('@/hooks/analytics/useCreatorScores', () => ({
  useCreatorScores: () => ({ data: null, loading: false, error: null, notFound: false, refresh: vi.fn() }),
}));
vi.mock('@/hooks/analytics/useContentPerformance', () => ({
  useContentPerformance: () => ({ data: null, loading: false, error: null, notImplemented: false, refresh: vi.fn() }),
}));
vi.mock('@/hooks/brand/useBrandBillingAccess', () => ({
  useBrandBillingAccess: () => ({ role: 'OWNER', canManage: mockCanManageBilling, isLoading: false }),
}));

vi.mock('@/components/analytics/CreatorMetricsCard', () => ({
  CreatorMetricsCard: ({ title, value }: { title: string; value?: number }) => (
    <div data-testid={`metric-${title}`}>{String(value ?? '')}</div>
  ),
}));
vi.mock('@/components/analytics/MetricsTrendChart', () => ({
  MetricsTrendChart: () => <div data-testid="trend-chart" />,
}));
vi.mock('@/components/analytics/EngagementRateGauge', () => ({
  EngagementRateGauge: () => <div data-testid="engagement-gauge" />,
}));
vi.mock('@/components/analytics/FakeFollowerIndicator', () => ({
  FakeFollowerIndicator: () => <div data-testid="fake-follower" />,
}));
vi.mock('@/components/analytics/QualityScoreDisplay', () => ({
  QualityScoreDisplay: () => <div data-testid="quality-score" />,
}));
vi.mock('@/components/analytics/BrandSafetyBadge', () => ({
  BrandSafetyBadge: () => <div data-testid="brand-safety" />,
}));
vi.mock('@/hooks/analytics/useCreatorDemographics', () => ({
  useCreatorDemographics: () => ({ data: null, loading: false, error: null, refresh: vi.fn() }),
}));
vi.mock('@/components/analytics/AudienceDemographicsPanel', () => ({
  AudienceDemographicsPanel: () => <div data-testid="demographics-panel" />,
}));
vi.mock('@/components/analytics/ContentPerformancePanel', () => ({
  ContentPerformancePanel: () => <div data-testid="content-performance" />,
}));

function renderPage(creatorId = 'cr_1') {
  return render(
    <MemoryRouter initialEntries={[`/brand/analytics/${creatorId}`]}>
      <Routes>
        <Route path="/brand/analytics/:creatorId" element={<BrandCreatorAnalyticsPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('BrandCreatorAnalyticsPage — F-0886 analytics upgrade gate', () => {
  beforeEach(() => {
    mockMetrics = { data: null, loading: false, error: null, upgradeRequired: false, refresh: vi.fn() };
    mockCanManageBilling = true;
  });

  it('F-0951: a brand sees the creator follower count from the metrics response', () => {
    mockMetrics = {
      data: {
        totalReach: 2000,
        totalImpressions: 3000,
        totalEngagements: 90,
        engagementRate: 4.5,
        followerGrowth: 150,
        avgViewsPerPost: 3000,
        trendData: [],
        followers: 42000,
      },
      loading: false,
      error: null,
      upgradeRequired: false,
      refresh: vi.fn(),
    };
    renderPage();
    expect(screen.getByTestId('metric-Followers').textContent).toBe('42000');
    // F-0953: the per-post averages are labelled as such, and the duplicate views card is gone.
    expect(screen.getByTestId('metric-Avg. reach per post').textContent).toBe('2000');
    expect(screen.getByTestId('metric-Avg. views per post').textContent).toBe('3000');
    // Exactly one views card (the old duplicate read the same field), and no "Total" card.
    const titles = screen
      .getAllByTestId(/^metric-/)
      .map((el) => el.getAttribute('data-testid')!.replace('metric-', ''));
    // Exact card set: avgViewsPerPost is filled from the same column as totalImpressions, so
    // no extra card may show it again under any label.
    expect(titles).toEqual(['Followers', 'Avg. reach per post', 'Avg. views per post', 'Follower Growth']);
    expect(titles.some((t) => /^Total /i.test(t))).toBe(false);
    // F-0953: the real demographics panel replaces the false "not built yet" placeholder.
    expect(screen.getByTestId('demographics-panel')).toBeInTheDocument();
    expect(screen.queryByText(/coming soon|hasn't been built|not built yet/i)).not.toBeInTheDocument();
  });

  it('renders the metric tiles as normal when the load succeeds', () => {
    renderPage();
    expect(screen.getByTestId('metric-Avg. reach per post')).toBeInTheDocument();
    expect(screen.getByTestId('trend-chart')).toBeInTheDocument();
    expect(screen.queryByTestId('upgrade-gate')).not.toBeInTheDocument();
  });

  it('renders the UpgradeGate instead of zeroed-out metric tiles when the plan limit 402s', () => {
    mockMetrics = {
      data: null,
      loading: false,
      error: "Upgrade to view more creator analytics — you've reached your plan's monthly limit.",
      upgradeRequired: true,
      refresh: vi.fn(),
    };
    renderPage();

    expect(screen.getByTestId('upgrade-gate')).toBeInTheDocument();
    expect(screen.queryByTestId('metric-Avg. reach per post')).not.toBeInTheDocument();
    expect(screen.queryByTestId('trend-chart')).not.toBeInTheDocument();
  });

  it('still shows the plain-text error banner for a non-402 metrics failure (no regression)', () => {
    mockMetrics = { data: null, loading: false, error: 'Network error', upgradeRequired: false, refresh: vi.fn() };
    renderPage();

    expect(screen.getByText(/Some data couldn't be loaded: Network error/)).toBeInTheDocument();
    expect(screen.queryByTestId('upgrade-gate')).not.toBeInTheDocument();
    expect(screen.getByTestId('metric-Avg. reach per post')).toBeInTheDocument();
  });

  it('the gate is role-aware: a MANAGER/MEMBER/VIEWER sees why they cannot upgrade', () => {
    mockMetrics = { data: null, loading: false, error: null, upgradeRequired: true, refresh: vi.fn() };
    mockCanManageBilling = false;
    renderPage();

    expect(screen.queryByRole('button', { name: /upgrade to pro/i })).not.toBeInTheDocument();
    expect(screen.getByText(/Only a workspace owner or admin can upgrade the plan/i)).toBeInTheDocument();
  });
});
