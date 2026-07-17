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
  CreatorMetricsCard: ({ title }: { title: string }) => (
    <div data-testid={`metric-card-${title}`}>{title}</div>
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
      screen.getByText(/Track your reach, engagement, and audience growth/i),
    ).toBeInTheDocument();
    expect(screen.getByText(/Demo data — connect a live API/i)).toBeInTheDocument();
  });

  it('shows mock metric card titles after load', async () => {
    renderPage();

    await waitFor(() => {
      expect(screen.getByText('Total Reach')).toBeInTheDocument();
    });
    expect(screen.getByText('Total Impressions')).toBeInTheDocument();
    expect(screen.getByText('Avg. Views Per Post')).toBeInTheDocument();
    expect(screen.getByText('Follower Growth')).toBeInTheDocument();
    expect(screen.getByTestId('metrics-trend-chart')).toBeInTheDocument();
  });
});
