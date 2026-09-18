/**
 * Creator Analytics page — Kv3b (Kavya)
 * Smoke: header, demo banner, mock metric cards (G-Kv3-A6 / §22).
 *
 * Run: npx vitest run src/pages/creator-analytics.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import CreatorAnalyticsPage from './creator-analytics';

vi.mock('@/components/creator/creator-layout', () => ({
  CreatorLayout: ({ children }: { children: React.ReactNode }) => (
    <div data-testid="creator-layout">{children}</div>
  ),
}));

// CountUp / Framer use IntersectionObserver — stub cards for jsdom smoke.
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

vi.mock('@/components/analytics/AudienceDemographicsPanel', () => ({
  AudienceDemographicsPanel: () => <div data-testid="demographics" />,
}));

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/creator/analytics']}>
      <CreatorAnalyticsPage />
    </MemoryRouter>,
  );
}

describe('CreatorAnalyticsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders analytics header and demo-data banner in mock mode', async () => {
    renderPage();

    expect(screen.getByTestId('creator-layout')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Analytics' })).toBeInTheDocument();
    expect(
      screen.getByText(/Followers, reach, views and engagement are from your latest sync/i),
    ).toBeInTheDocument();
    expect(screen.getByText(/Demo data — connect a live API/i)).toBeInTheDocument();
  });

  it('shows mock metric card titles after load', async () => {
    renderPage();

    await waitFor(() => {
      expect(screen.getByTestId('metric-card-Avg. reach per post')).toBeInTheDocument();
    });
    expect(screen.getByTestId('metric-card-Avg. views per post')).toBeInTheDocument();
    expect(screen.getByTestId('metric-card-Follower Growth')).toBeInTheDocument();
    expect(screen.getByTestId('metrics-trend-chart')).toBeInTheDocument();
  });

  it('F-0953: does not claim every figure covers the picked dates', async () => {
    renderPage();

    const note = screen.getByTestId('analytics-scope-note').textContent ?? '';
    expect(note).toMatch(/most recent posts/i);
    expect(note).toMatch(/growth and the trend chart cover the dates/i);
    expect(screen.queryByText(/over the last \d+ days/i)).not.toBeInTheDocument();
  });

  it('F-0951: shows the creator follower count from the metrics response', async () => {
    renderPage();

    // mockMetrics.followers in src/lib/api.ts is 18640.
    await waitFor(() => {
      expect(screen.getByTestId('metric-card-Followers').textContent).toBe('Followers:18640');
    });
  });

  it('F-0953: no card calls a per-post average a total, and no two cards show the same figure', async () => {
    renderPage();

    await waitFor(() => {
      expect(screen.getByTestId('metric-card-Avg. reach per post')).toBeInTheDocument();
    });
    const cards = screen.getAllByTestId(/^metric-card-/);
    const titles = cards.map((c) => c.getAttribute('data-testid')!.replace('metric-card-', ''));
    expect(titles.some((t) => /^Total /i.test(t))).toBe(false);
    // Exact card set: the old "Avg. Views Per Post" card read avgViewsPerPost, which the
    // backend fills from the SAME column as totalImpressions. No card may show it again,
    // under any label (mockMetrics.avgViewsPerPost is 22400).
    expect(titles).toEqual(['Followers', 'Avg. reach per post', 'Avg. views per post', 'Follower Growth']);
    expect(cards.map((c) => c.textContent).some((t) => t?.endsWith(':22400'))).toBe(false);
    // mockMetrics: totalReach 284000 (avg reach/post), totalImpressions 412000 (avg views/post).
    expect(screen.getByTestId('metric-card-Avg. reach per post').textContent).toBe('Avg. reach per post:284000');
    expect(screen.getByTestId('metric-card-Avg. views per post').textContent).toBe(
      'Avg. views per post:412000',
    );
  });
});
